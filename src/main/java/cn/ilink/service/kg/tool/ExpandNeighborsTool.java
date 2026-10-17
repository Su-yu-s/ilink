package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.kg.KgScopeService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 图扩展：从指定实体出发查看邻居（1 跳），只返回对当前用户可见的邻居 */
@Component
public class ExpandNeighborsTool extends AbstractGraphTool {

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
            + "用于从种子实体出发继续探索图谱。";
    }

    @Override
    public String getParametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
            + "\"entity_id\":{\"type\":\"integer\",\"description\":\"种子实体ID\"},"
            + "\"limit\":{\"type\":\"integer\",\"default\":20,\"description\":\"最多返回邻居数(1-50)\"}"
            + "},\"required\":[\"entity_id\"]}";
    }

    @Override
    public Object execute(Map<String, Object> params, Long userId) {
        Long entityId = paramLong(params, "entity_id");
        int limit = Math.max(1, Math.min(50, paramInt(params, "limit", 20)));
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

        List<KgRelation> outEdges = relationMapper.selectList(new QueryWrapper<KgRelation>()
            .eq("source_id", entityId).isNull("valid_to"));
        List<KgRelation> inEdges = relationMapper.selectList(new QueryWrapper<KgRelation>()
            .eq("target_id", entityId).isNull("valid_to"));

        List<Map<String, Object>> neighbors = new ArrayList<>();
        for (KgRelation edge : outEdges) {
            addNeighbor(neighbors, edge.getTargetId(), edge.getRelationType(), "out", userId, limit);
        }
        for (KgRelation edge : inEdges) {
            addNeighbor(neighbors, edge.getSourceId(), edge.getRelationType(), "in", userId, limit);
        }
        if (neighbors.isEmpty()) {
            return errorJson("NO_RESULTS", "实体 #" + entityId + " 在图谱中没有可见的邻居关系");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entityId", entityId);
        result.put("name", seed.getDisplayName());
        result.put("neighborCount", neighbors.size());
        result.put("neighbors", neighbors);
        return result;
    }

    private void addNeighbor(List<Map<String, Object>> neighbors, Long otherId, String relationType,
                             String direction, Long userId, int limit) {
        if (neighbors.size() >= limit || otherId == null) {
            return;
        }
        KgEntity other = entityMapper.selectById(otherId);
        if (other == null || !KgEntity.STATUS_ACTIVE.equals(other.getStatus())
                || !scopeService.canSee(other, userId)) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entityId", other.getId());
        row.put("name", other.getDisplayName());
        row.put("entityType", other.getEntityType());
        row.put("relation", relationType);
        row.put("direction", direction);
        neighbors.add(row);
    }
}
