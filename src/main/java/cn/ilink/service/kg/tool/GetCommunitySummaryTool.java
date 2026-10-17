package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgCommunity;
import cn.ilink.entity.KgEntity;
import cn.ilink.mapper.KgCommunityMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.service.kg.KgCommunityService;
import cn.ilink.service.kg.KgRelationService;
import cn.ilink.service.kg.KgScopeService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 社区摘要：查看某实体所在社区的聚类摘要（无摘要时按需惰性生成一次） */
@Component
public class GetCommunitySummaryTool extends AbstractGraphTool {

    private final KgEntityMapper entityMapper;
    private final KgCommunityMapper communityMapper;
    private final KgRelationService relationService;
    private final KgCommunityService communityService;

    @Autowired
    public GetCommunitySummaryTool(KgScopeService scopeService, KgEntityMapper entityMapper,
                                   KgCommunityMapper communityMapper, KgRelationService relationService,
                                   KgCommunityService communityService) {
        super(scopeService);
        this.entityMapper = entityMapper;
        this.communityMapper = communityMapper;
        this.relationService = relationService;
        this.communityService = communityService;
    }

    @Override
    public String getName() {
        return "get_community_summary";
    }

    @Override
    public String getDescription() {
        return "社区摘要：给定 entityId，返回该实体所在邻域社区的聚类摘要（成员构成与主题概括）。"
            + "适合回答\"这一片图谱主要在讲什么\"的归纳型问题。";
    }

    @Override
    public String getParametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
            + "\"entity_id\":{\"type\":\"integer\",\"description\":\"目标实体ID\"}"
            + "},\"required\":[\"entity_id\"]}";
    }

    @Override
    public Object execute(Map<String, Object> params, Long userId) {
        Long entityId = paramLong(params, "entity_id");
        if (entityId == null || entityId <= 0) {
            return errorJson("INVALID_PARAMS", "entity_id 必须是正整数");
        }
        KgEntity entity = entityMapper.selectById(entityId);
        if (entity == null || !KgEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
            return errorJson("NOT_FOUND", "实体 #" + entityId + " 不存在");
        }
        if (!scopeService.canSee(entity, userId)) {
            return errorJson("PERMISSION_DENIED", "无权访问实体 #" + entityId);
        }

        // 已有摘要直接返回（按创建时间倒序取最新）
        KgCommunity existing = communityMapper.selectOne(new QueryWrapper<KgCommunity>()
            .like("entity_ids_json", "\"" + entityId + "\"")
            .orderByDesc("id")
            .last("LIMIT 1"));
        if (existing == null) {
            // 惰性生成：以该实体为种子取 1 跳邻域，调 Agnes 概括一次
            existing = communityService.buildCommunityForEntity(entityId, userId);
        }
        if (existing == null) {
            return errorJson("NO_RESULTS", "实体 #" + entityId + " 邻域过小或摘要生成失败");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entityId", entityId);
        result.put("communityId", existing.getId());
        result.put("title", existing.getTitle());
        result.put("summary", existing.getSummary());
        result.put("entityCount", existing.getEntityCount());
        return result;
    }

    /** 邻域实体清单（供测试与内部复用） */
    List<Long> neighborIds(Long entityId, Long userId) {
        List<Long> ids = new ArrayList<>();
        KgRelationService.SubGraph graph = relationService.expandTwoHops(List.of(entityId));
        for (Long id : graph.entityIds) {
            KgEntity entity = entityMapper.selectById(id);
            if (entity != null && scopeService.canSee(entity, userId)) {
                ids.add(id);
            }
        }
        return ids;
    }
}
