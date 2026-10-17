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
import java.util.Set;

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

    @Autowired
    public KgRelationService(KgRelationMapper relationMapper, KgEntityMapper entityMapper) {
        this.relationMapper = relationMapper;
        this.entityMapper = entityMapper;
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
            result.addAll(entityMapper.selectBatchIds(batch));
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

    /**
     * 从种子实体出发扩展 2 跳子图。
     * 只取仍然有效的关系（valid_to IS NULL），最多返回 {@link #MAX_EXPAND_EDGES} 条边。
     *
     * @param seedIds 种子实体 ID（通常来自向量检索 top-k）
     */
    public SubGraph expandTwoHops(Collection<Long> seedIds) {
        SubGraph graph = new SubGraph();
        if (seedIds == null || seedIds.isEmpty()) {
            return graph;
        }
        List<Long> seeds = new ArrayList<>(new LinkedHashSet<>(seedIds));
        if (seeds.size() > MAX_IN_CLAUSE) {
            seeds = seeds.subList(0, MAX_IN_CLAUSE);
        }
        for (Long id : seeds) {
            graph.entityIds.add(id);
        }

        // 第 1 跳：源是种子
        List<KgRelation> firstHop = relationMapper.selectList(
            new QueryWrapper<KgRelation>()
                .in("source_id", seeds)
                .isNull("valid_to"));
        for (KgRelation edge : firstHop) {
            graph.addEdge(edge);
        }

        // 第 2 跳：源是第 1 跳的目标（排除种子本身，避免大量回边挤占配额）
        Set<Long> secondHopSources = new LinkedHashSet<>();
        for (KgRelation edge : firstHop) {
            Long target = edge.getTargetId();
            if (target != null && !seeds.contains(target)) {
                secondHopSources.add(target);
            }
        }
        if (secondHopSources.isEmpty() || graph.edges.size() >= MAX_EXPAND_EDGES) {
            return graph;
        }
        List<Long> sources = new ArrayList<>(secondHopSources);
        for (int from = 0; from < sources.size() && graph.edges.size() < MAX_EXPAND_EDGES; from += MAX_IN_CLAUSE) {
            List<Long> batch = sources.subList(from, Math.min(from + MAX_IN_CLAUSE, sources.size()));
            List<KgRelation> secondHop = relationMapper.selectList(
                new QueryWrapper<KgRelation>()
                    .in("source_id", batch)
                    .isNull("valid_to"));
            for (KgRelation edge : secondHop) {
                if (graph.edges.size() >= MAX_EXPAND_EDGES) {
                    break;
                }
                graph.addEdge(edge);
            }
        }
        return graph;
    }
}
