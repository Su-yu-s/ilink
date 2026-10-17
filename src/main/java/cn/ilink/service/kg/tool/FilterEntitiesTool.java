package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgEntity;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.service.kg.KgIndexService;
import cn.ilink.service.kg.KgScopeService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 条件过滤：按实体类型 + 名称关键词组合筛选（如"所有 SKILL 类型含'建模'的实体"） */
@Component
public class FilterEntitiesTool extends AbstractGraphTool {

    private static final int MAX_LIMIT = 30;

    private final KgEntityMapper entityMapper;

    @Autowired
    public FilterEntitiesTool(KgScopeService scopeService, KgEntityMapper entityMapper) {
        super(scopeService);
        this.entityMapper = entityMapper;
    }

    @Override
    public String getName() {
        return "filter_entities";
    }

    @Override
    public String getDescription() {
        return "条件过滤：按实体类型（COMPETITION/SKILL/TEACHER/ORGANIZATION/POST）"
            + "与名称关键词组合筛选实体列表。用于\"有哪些…\"类的枚举型问题。";
    }

    @Override
    public String getParametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
            + "\"entity_type\":{\"type\":\"string\",\"description\":\"实体类型，可省略\"},"
            + "\"keyword\":{\"type\":\"string\",\"description\":\"名称包含的关键词，可省略\"},"
            + "\"limit\":{\"type\":\"integer\",\"default\":10,\"description\":\"返回数量(1-30)\"}"
            + "}}";
    }

    @Override
    public Object execute(Map<String, Object> params, Long userId) {
        String entityType = paramString(params, "entity_type");
        String keyword = paramString(params, "keyword");
        int limit = Math.max(1, Math.min(MAX_LIMIT, paramInt(params, "limit", 10)));
        if (entityType != null) {
            entityType = entityType.toUpperCase();
            if (!KgIndexService.ALLOWED_ENTITY_TYPES.contains(entityType)) {
                return errorJson("INVALID_PARAMS", "entity_type 只能取 COMPETITION/SKILL/TEACHER/ORGANIZATION/POST");
            }
        }
        if ((entityType == null || entityType.isEmpty()) && (keyword == null || keyword.isEmpty())) {
            return errorJson("INVALID_PARAMS", "entity_type 与 keyword 至少提供一个");
        }

        QueryWrapper<KgEntity> wrapper = new QueryWrapper<KgEntity>()
            .eq("status", KgEntity.STATUS_ACTIVE);
        if (entityType != null && !entityType.isEmpty()) {
            wrapper.eq("entity_type", entityType);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like("display_name", keyword).or().like("name", keyword));
        }
        wrapper.last("LIMIT " + limit);
        applyVisibility(wrapper, userId);

        List<KgEntity> entities = entityMapper.selectList(wrapper);
        if (entities.isEmpty()) {
            return errorJson("NO_RESULTS", "没有满足条件的实体");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KgEntity entity : entities) {
            rows.add(SearchEntitiesTool.toRow(entity));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", rows.size());
        result.put("entities", rows);
        return result;
    }
}
