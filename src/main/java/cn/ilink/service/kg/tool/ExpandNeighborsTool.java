package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.kg.KgScopeService;
import cn.ilink.service.kg.KgTimeRange;
import cn.ilink.service.kg.GraphRelationFingerprint;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Date;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Comparator;

/** 图扩展：从指定实体出发查看邻居（1 跳），只返回对当前用户可见的邻居 */
@Component
public class ExpandNeighborsTool extends AbstractGraphTool {
    private static final int SCAN_LIMIT_PER_DIRECTION = 200;

    private final KgEntityMapper entityMapper;
    private final KgRelationMapper relationMapper;

    @Autowired
    public ExpandNeighborsTool(KgScopeService scopeService, KgEntityMapper entityMapper,
                               KgRelationMapper relationMapper) {
        super(scopeService);
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
    }

    @Override
    public String getName() {
        return "expand_neighbors";
    }

    @Override
    public String getDescription() {
        return "图扩展：给定 entityId，返回它直接相连的邻居实体与关系类型（1 跳）。"
            + "用于从种子实体出发继续探索图谱。"
            + "按用户可见关系排序分页，下一页使用 coverage.nextOffset。"
            + "单页不代表完整集合，遇到扫描上限不继续分页，不能断言全部邻居数量或不存在其他关系。";
    }

    @Override
    public String getParametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
            + "\"entity_id\":{\"type\":\"integer\",\"description\":\"种子实体ID\"},"
            + "\"limit\":{\"type\":\"integer\",\"default\":20,\"description\":\"最多返回关系数(1-50)\"},"
            + "\"offset\":{\"type\":\"integer\",\"default\":0,\"description\":\"已授权结果中的分页偏移\"}"
            + "},\"required\":[\"entity_id\"]}";
    }

    @Override
    public Object execute(Map<String, Object> params, Long userId) {
        Long entityId = paramLong(params, "entity_id");
        int limit = Math.max(1, Math.min(50, paramInt(params, "limit", 20)));
        int offset = paramInt(params, "offset", 0);
        if (offset < 0 || offset > SCAN_LIMIT_PER_DIRECTION * 2)
            return errorJson("INVALID_PARAMS", "offset 超出有界分页范围");
        if (entityId == null || entityId <= 0) {
            return errorJson("INVALID_PARAMS", "entity_id 必须是正整数");
        }
        KgEntity seed = entityMapper.selectById(entityId);
        if (seed == null || !KgEntity.STATUS_ACTIVE.equals(seed.getStatus())) {
            return errorJson("NOT_FOUND", "实体 #" + entityId + " 不存在");
        }
        if (!scopeService.canSee(seed, userId)) {
            return errorJson("PERMISSION_DENIED", "无权访问实体 #" + entityId);
        }

        Date now = KgTimeRange.now();
        List<KgRelation> outEdges = relationMapper.selectList(currentEdges("source_id", entityId, userId, now));
        List<KgRelation> inEdges = relationMapper.selectList(currentEdges("target_id", entityId, userId, now));

        List<Map<String, Object>> neighbors = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        Map<Long, KgEntity> endpointCache = new HashMap<>();
        endpointCache.put(entityId, seed);
        int[] counts = new int[3]; // permission-checked scanned, eligible candidates, distinct visible relations
        boolean outScanLimited = collectNeighbors(outEdges, neighbors, seen, endpointCache,
            counts, seed, "out", userId, now);
        boolean inScanLimited = collectNeighbors(inEdges, neighbors, seen, endpointCache,
            counts, seed, "in", userId, now);
        boolean scanLimited = outScanLimited || inScanLimited;
        neighbors.sort(Comparator.comparingLong(row -> ((Number) row.get("relationId")).longValue()));
        List<GraphRelationFingerprint.Row> fingerprintRows = neighbors.stream().map(row ->
            new GraphRelationFingerprint.Row(((Number) row.get("relationId")).longValue(),
                ((Number) row.get("sourceId")).longValue(), ((Number) row.get("targetId")).longValue(),
                String.valueOf(row.get("relationType")), (Long) row.get("validFromMillis"),
                (Long) row.get("validToMillis"), String.valueOf(row.get("sourceName")),
                String.valueOf(row.get("targetName")))).toList();
        int first = Math.min(offset, neighbors.size());
        int end = Math.min(first + limit, neighbors.size());
        List<Map<String, Object>> page = new ArrayList<>(neighbors.subList(first, end));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entityId", entityId);
        result.put("name", seed.getDisplayName());
        result.put("neighborCount", page.size());
        result.put("neighbors", page);
        Map<String, Object> queryScope = new LinkedHashMap<>();
        queryScope.put("entityId", entityId);
        queryScope.put("direction", "BOTH");
        queryScope.put("visibility", "CURRENT_USER");
        queryScope.put("match", "CURRENT");
        queryScope.put("currentAt", now.toInstant().toString());
        queryScope.put("scanLimitPerDirection", SCAN_LIMIT_PER_DIRECTION);
        Map<String, Object> coverage = GraphQueryCoverage.of(queryScope,
            SCAN_LIMIT_PER_DIRECTION * 2, scanLimited, counts[0], counts[1], counts[2], page.size(), limit);
        boolean moreInVisibleResult = end < neighbors.size();
        coverage.put("offset", offset);
        coverage.put("resultFingerprint", GraphRelationFingerprint.of(fingerprintRows));
        coverage.put("hasMore", moreInVisibleResult);
        coverage.put("hasMoreKnown", moreInVisibleResult || !scanLimited);
        coverage.put("nextOffset", !scanLimited && moreInVisibleResult ? end : null);
        coverage.put("complete", !scanLimited && offset == 0 && !moreInVisibleResult);
        coverage.put("truncated", scanLimited || offset > 0 || moreInVisibleResult);
        List<String> reasons = new ArrayList<>();
        if (scanLimited) reasons.add("SCAN_LIMIT");
        if (moreInVisibleResult) reasons.add("RETURN_LIMIT");
        if (offset > 0) reasons.add("PAGED_RESULT");
        coverage.put("truncationReasons", reasons);
        result.put("coverage", coverage);
        if (page.isEmpty()) {
            // A successful empty query must retain its coverage in the evidence ledger.
            result.put("code", "NO_RESULTS");
            coverage.put("emptyResultMeaning", offset > 0 ? "NO_VISIBLE_MATCH_IN_REQUESTED_PAGE"
                : scanLimited ? "NO_VISIBLE_MATCH_IN_SCANNED_PREFIX" : "NO_VISIBLE_MATCH_IN_QUERY_SCOPE");
            result.put("message", offset > 0 ? "本页没有可见结果，不表示该实体没有邻居关系"
                : scanLimited ? "本次有界扫描未找到可见的邻居关系，不能确认完整结果为空"
                : "该实体在本次查询的当前有效、用户可见范围内没有邻居关系");
        }
        return result;
    }

    private QueryWrapper<KgRelation> currentEdges(String endpointColumn, Long entityId, Long userId, Date now) {
        QueryWrapper<KgRelation> query = new QueryWrapper<KgRelation>()
            .eq(endpointColumn, entityId)
            .and(w -> w.isNull("valid_from").or().le("valid_from", now))
            .and(w -> w.isNull("valid_to").or().gt("valid_to", now));
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
        return query.orderByAsc("id").last("LIMIT " + (SCAN_LIMIT_PER_DIRECTION + 1));
    }

    private boolean collectNeighbors(List<KgRelation> edges, List<Map<String, Object>> neighbors,
                                      Set<Long> seen, Map<Long, KgEntity> endpointCache, int[] counts,
                                      KgEntity seed, String direction, Long userId, Date now) {
        if (edges == null) {
            return false;
        }
        for (KgRelation edge : edges.subList(0, Math.min(SCAN_LIMIT_PER_DIRECTION, edges.size()))) {
            if (edge == null || edge.getId() == null || edge.getSourceId() == null || edge.getTargetId() == null
                || !scopeService.canSee(edge.getScope(), edge.getTeamId(), edge.getUserId(), userId)) {
                continue;
            }
            Long otherId = "out".equals(direction) ? edge.getTargetId() : edge.getSourceId();
            KgEntity other = endpointCache.computeIfAbsent(otherId, entityMapper::selectById);
            if (other == null || !KgEntity.STATUS_ACTIVE.equals(other.getStatus())
                || !scopeService.canSee(other, userId)) {
                continue;
            }
            // Never expose the number of relations blocked by relation or endpoint permission checks.
            counts[0]++;
            if (edge.getValidFrom() != null && edge.getValidFrom().after(now)
                || edge.getValidTo() != null && !edge.getValidTo().after(now)) {
                continue;
            }
            counts[1]++;
            if (!seen.add(edge.getId())) {
                continue;
            }
            counts[2]++;
            // Apply pagination only after permission checks, deduplication and ordering.
            neighbors.add(neighborRow(seed, edge, other, direction));
        }
        return edges.size() > SCAN_LIMIT_PER_DIRECTION;
    }

    private Map<String, Object> neighborRow(KgEntity seed, KgRelation edge, KgEntity other, String direction) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entityId", other.getId());
        row.put("name", other.getDisplayName());
        row.put("entityType", other.getEntityType());
        row.put("relationId", edge.getId());
        row.put("sourceId", edge.getSourceId());
        row.put("sourceName", "out".equals(direction) ? seed.getDisplayName() : other.getDisplayName());
        row.put("targetId", edge.getTargetId());
        row.put("targetName", "out".equals(direction) ? other.getDisplayName() : seed.getDisplayName());
        row.put("relation", edge.getRelationType());
        row.put("relationType", edge.getRelationType());
        row.put("validFrom", formatDate(edge.getValidFrom()));
        row.put("validTo", formatDate(edge.getValidTo()));
        row.put("validFromMillis", edge.getValidFrom() == null ? null : edge.getValidFrom().getTime());
        row.put("validToMillis", edge.getValidTo() == null ? null : edge.getValidTo().getTime());
        row.put("sourceDocId", edge.getSourceDocId());
        row.put("direction", direction);
        return row;
    }

    private static String formatDate(Date date) {
        return date == null ? null : java.time.Instant.ofEpochMilli(date.getTime())
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
    }
}
