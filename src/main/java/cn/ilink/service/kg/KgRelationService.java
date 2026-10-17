package cn.ilink.service.kg;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Comparator;

/**
 * KG 图查询服务：批量实体查询、2 跳子图扩展。
 * MySQL 5.7 不支持递归 CTE，2 跳扩展用两步查询（1 跳 → 目标集合 → 2 跳）实现，
 * 语义等价且可强制限定最大边数。
 */
@Service
@Slf4j
public class KgRelationService {

    /** 2 跳扩展的最大返回边数 */
    public static final int MAX_EXPAND_EDGES = 200;
    /** 单次 IN 查询的安全上限 */
    private static final int MAX_IN_CLAUSE = 500;

    private final KgRelationMapper relationMapper;
    private final KgEntityMapper entityMapper;
    private final KgScopeService scopeService;

    @Autowired
    public KgRelationService(KgRelationMapper relationMapper, KgEntityMapper entityMapper,
                             KgScopeService scopeService) {
        this.relationMapper = relationMapper;
        this.entityMapper = entityMapper;
        this.scopeService = scopeService;
    }

    /** 批量查询实体（自动去重、剔除 null/非法 ID，保持入参顺序） */
    public List<KgEntity> getEntitiesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> distinct = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                distinct.add(id);
            }
        }
        if (distinct.isEmpty()) {
            return Collections.emptyList();
        }
        List<KgEntity> result = new ArrayList<>(distinct.size());
        List<Long> idList = new ArrayList<>(distinct);
        // 分批 IN 查询，防止超长 SQL
        for (int from = 0; from < idList.size(); from += MAX_IN_CLAUSE) {
            List<Long> batch = idList.subList(from, Math.min(from + MAX_IN_CLAUSE, idList.size()));
            List<KgEntity> rows = entityMapper.selectBatchIds(batch);
            if (rows != null) {
                result.addAll(rows);
            }
        }
        // 保持入参顺序返回
        result.sort((a, b) -> {
            int ia = indexOf(idList, a.getId());
            int ib = indexOf(idList, b.getId());
            return Integer.compare(ia, ib);
        });
        return result;
    }

    private static int indexOf(List<Long> list, Long value) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equals(value)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** 2 跳扩展结果：边集合 + 涉及到的全部实体 ID（含种子） */
    public static class SubGraph {
        public final List<KgRelation> edges = new ArrayList<>();
        public final Set<Long> entityIds = new LinkedHashSet<>();

        void addEdge(KgRelation edge) {
            if (edges.size() >= MAX_EXPAND_EDGES) {
                return;
            }
            edges.add(edge);
            entityIds.add(edge.getSourceId());
            entityIds.add(edge.getTargetId());
        }
    }

    /** Legacy overload; callers with a user context should use the visibility-aware form. */
    public SubGraph expandTwoHops(Collection<Long> seedIds) {
        return expandTwoHops(seedIds, null, null);
    }

    /** Fixed bidirectional two-hop traversal with visibility and time-window filtering. */
    public SubGraph expandTwoHops(Collection<Long> seedIds, Long userId, KgTimeRange timeRange) {
        SubGraph graph = new SubGraph();
        if (seedIds == null || seedIds.isEmpty()) {
            return graph;
        }
        List<Long> seeds = seedIds.stream().filter(id -> id != null && id > 0)
            .distinct().limit(MAX_IN_CLAUSE).toList();
        if (seeds.isEmpty()) {
            return graph;
        }
        Set<Long> visibleSeeds = new LinkedHashSet<>();
        for (KgEntity entity : getEntitiesByIds(seeds)) {
            if (isVisibleActive(entity, userId)) {
                visibleSeeds.add(entity.getId());
            }
        }
        if (visibleSeeds.isEmpty()) {
            return graph;
        }
        graph.entityIds.addAll(visibleSeeds);
        Set<Long> seenEdges = new LinkedHashSet<>();
        Set<Long> frontier = visibleSeeds;
        for (int hop = 0; hop < 2 && !frontier.isEmpty()
            && graph.edges.size() < MAX_EXPAND_EDGES; hop++) {
            List<KgRelation> candidates = incidentEdges(frontier, userId, timeRange);
            candidates.sort(Comparator.comparing(KgRelation::getId,
                Comparator.nullsLast(Long::compareTo)));
            Set<Long> endpointIds = new LinkedHashSet<>();
            for (KgRelation edge : candidates) {
                endpointIds.add(edge.getSourceId());
                endpointIds.add(edge.getTargetId());
            }
            Map<Long, KgEntity> endpoints = new LinkedHashMap<>();
            for (KgEntity entity : getEntitiesByIds(endpointIds)) {
                endpoints.put(entity.getId(), entity);
            }
            Set<Long> nextFrontier = new LinkedHashSet<>();
            for (KgRelation edge : candidates) {
                if (graph.edges.size() >= MAX_EXPAND_EDGES) {
                    break;
                }
                if (edge.getId() == null || !seenEdges.add(edge.getId())
                    || !isVisibleRelation(edge, userId, timeRange)
                    || !isVisibleActive(endpoints.get(edge.getSourceId()), userId)
                    || !isVisibleActive(endpoints.get(edge.getTargetId()), userId)) {
                    continue;
                }
                graph.addEdge(edge);
                if (!visibleSeeds.contains(edge.getSourceId())) {
                    nextFrontier.add(edge.getSourceId());
                }
                if (!visibleSeeds.contains(edge.getTargetId())) {
                    nextFrontier.add(edge.getTargetId());
                }
            }
            frontier = nextFrontier;
        }
        return graph;
    }

    /** Bounded graph retrieval for questions covering every relation in a date window. */
    public SubGraph visibleTemporalGraph(Long userId, KgTimeRange timeRange) {
        SubGraph graph = new SubGraph();
        if (timeRange == null) {
            return graph;
        }
        QueryWrapper<KgRelation> query = new QueryWrapper<KgRelation>()
            .isNotNull("valid_from").lt("valid_from", timeRange.endDate())
            .and(w -> w.isNull("valid_to").or().gt("valid_to", timeRange.startDate()));
        query.and(w -> {
            w.eq("scope", KgEntity.SCOPE_GLOBAL);
            if (userId != null && userId > 0) {
                w.or().eq("scope", KgEntity.SCOPE_PRIVATE).eq("user_id", userId);
                List<Long> teams = scopeService.visibleTeamIds(userId);
                if (teams != null && !teams.isEmpty()) {
                    w.or().eq("scope", KgEntity.SCOPE_TEAM).in("team_id", teams);
                }
            }
        });
        query.orderByAsc("id").last("LIMIT 1000");
        List<KgRelation> candidates = relationMapper.selectList(query);
        if (candidates == null || candidates.isEmpty()) {
            return graph;
        }
        Set<Long> endpointIds = new LinkedHashSet<>();
        for (KgRelation edge : candidates) {
            endpointIds.add(edge.getSourceId());
            endpointIds.add(edge.getTargetId());
        }
        Map<Long, KgEntity> endpoints = new LinkedHashMap<>();
        for (KgEntity entity : getEntitiesByIds(endpointIds)) {
            endpoints.put(entity.getId(), entity);
        }
        for (KgRelation edge : candidates) {
            if (graph.edges.size() >= MAX_EXPAND_EDGES) {
                break;
            }
            if (isVisibleRelation(edge, userId, timeRange)
                && isVisibleActive(endpoints.get(edge.getSourceId()), userId)
                && isVisibleActive(endpoints.get(edge.getTargetId()), userId)) {
                graph.addEdge(edge);
            }
        }
        return graph;
    }

    private List<KgRelation> incidentEdges(Set<Long> frontier, Long userId, KgTimeRange timeRange) {
        List<Long> ids = new ArrayList<>(frontier);
        QueryWrapper<KgRelation> query = new QueryWrapper<KgRelation>()
            .and(w -> w.in("source_id", ids).or().in("target_id", ids));
        if (timeRange == null) {
            Date now = KgTimeRange.now();
            query.and(w -> w.isNull("valid_from").or().le("valid_from", now));
            query.and(w -> w.isNull("valid_to").or().gt("valid_to", now));
        } else {
            query.isNotNull("valid_from").lt("valid_from", timeRange.endDate());
            query.and(w -> w.isNull("valid_to").or().gt("valid_to", timeRange.startDate()));
        }
        query.and(w -> {
            w.eq("scope", KgEntity.SCOPE_GLOBAL);
            if (userId != null && userId > 0) {
                w.or().eq("scope", KgEntity.SCOPE_PRIVATE).eq("user_id", userId);
                List<Long> teams = scopeService.visibleTeamIds(userId);
                if (teams != null && !teams.isEmpty()) {
                    w.or().eq("scope", KgEntity.SCOPE_TEAM).in("team_id", teams);
                }
            }
        });
        query.orderByAsc("id").last("LIMIT 1000");
        List<KgRelation> rows = relationMapper.selectList(query);
        return rows == null ? new ArrayList<>() : new ArrayList<>(rows);
    }

    private boolean isVisibleActive(KgEntity entity, Long userId) {
        return entity != null && KgEntity.STATUS_ACTIVE.equals(entity.getStatus())
            && scopeService.canSee(entity.getScope(), entity.getTeamId(), entity.getUserId(), userId);
    }

    private boolean isVisibleRelation(KgRelation edge, Long userId, KgTimeRange timeRange) {
        if (!scopeService.canSee(edge.getScope(), edge.getTeamId(), edge.getUserId(), userId)) {
            return false;
        }
        if (timeRange != null) {
            return timeRange.overlaps(edge);
        }
        Date now = KgTimeRange.now();
        return (edge.getValidFrom() == null || !edge.getValidFrom().after(now))
            && (edge.getValidTo() == null || edge.getValidTo().after(now));
    }
}
