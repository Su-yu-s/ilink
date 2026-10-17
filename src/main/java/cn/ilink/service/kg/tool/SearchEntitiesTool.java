package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgEntity;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.service.kg.KgEmbeddingService;
import cn.ilink.service.kg.KgScopeService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

/** 入口检索：问题语义检索（向量优先，关键词兜底），返回 top-k 实体摘要 */
@Component
public class SearchEntitiesTool extends AbstractGraphTool {

    private final KgEntityMapper entityMapper;
    private final KgEmbeddingService embeddingService;

    @Autowired
    public SearchEntitiesTool(KgScopeService scopeService, KgEntityMapper entityMapper,
                              KgEmbeddingService embeddingService) {
        super(scopeService);
        this.entityMapper = entityMapper;
        this.embeddingService = embeddingService;
    }

    @Override
    public String getName() {
        return "search_entities";
    }

    @Override
    public String getDescription() {
        return "知识图谱入口检索：按语义或关键词查找实体，返回 entityId/名称/类型/描述。"
            + "任何探索都应先调用本工具定位种子实体。";
    }

    @Override
    public String getParametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
            + "\"query\":{\"type\":\"string\",\"description\":\"检索关键词或一句描述\"},"
            + "\"top_k\":{\"type\":\"integer\",\"default\":8,\"description\":\"返回数量(1-20)\"}"
            + "},\"required\":[\"query\"]}";
    }

    @Override
    public Object execute(Map<String, Object> params, Long userId) {
        String query = paramString(params, "query");
        int topK = Math.max(1, Math.min(20, paramInt(params, "top_k", 8)));
        if (query == null || query.isEmpty()) {
            return errorJson("INVALID_PARAMS", "query 不能为空");
        }

        List<KgEntity> entities = searchByVector(query, topK, userId);
        boolean vector = !entities.isEmpty();
        if (entities.size() < topK) {
            List<KgEntity> backfill = searchByKeyword(query, topK, userId);
            for (KgEntity entity : backfill) {
                if (entities.stream().noneMatch(row -> row.getId().equals(entity.getId()))) {
                    entities.add(entity);
                    if (entities.size() >= topK) {
                        break;
                    }
                }
            }
        }
        if (entities.isEmpty()) {
            return errorJson("NO_RESULTS", "图谱中没有找到与 \"" + query + "\" 相关的实体，可换关键词再试");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (KgEntity entity : entities) {
            rows.add(toRow(entity));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retrieval", vector ? "vector+keyword" : "keyword");
        result.put("count", rows.size());
        result.put("entities", rows);
        return result;
    }

    private List<KgEntity> searchByVector(String query, int topK, Long userId) {
        try {
            float[] vec = embeddingService.embedText(query);
            List<KgEmbeddingService.ScoredEntity> hits = embeddingService.searchTopK(vec,
                Math.max(100, topK * 10));
            List<KgEntity> result = new ArrayList<>();
            for (KgEmbeddingService.ScoredEntity hit : hits) {
                KgEntity entity = entityMapper.selectById(hit.entityId);
                if (entity != null && KgEntity.STATUS_ACTIVE.equals(entity.getStatus())
                        && scopeService.canSee(entity, userId)
                        && result.stream().noneMatch(row -> row.getId().equals(entity.getId()))) {
                    result.add(entity);
                    if (result.size() >= topK) {
                        break;
                    }
                }
            }
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<KgEntity> searchByKeyword(String query, int topK, Long userId) {
        String keyword = query.length() > 40 ? query.substring(0, 40) : query;
        QueryWrapper<KgEntity> wrapper = new QueryWrapper<KgEntity>()
            .eq("status", KgEntity.STATUS_ACTIVE)
            .and(w -> w.like("display_name", keyword).or().like("name", keyword))
            .orderByAsc("id")
            .last("LIMIT " + Math.max(100, topK * 10));
        applyVisibility(wrapper, userId);
        Map<Long, KgEntity> candidates = new LinkedHashMap<>();
        List<KgEntity> direct = entityMapper.selectList(wrapper);
        for (KgEntity row : direct == null ? List.<KgEntity>of() : direct) {
            if (visible(row, userId)) {
                candidates.putIfAbsent(row.getId(), row);
            }
        }
        if (candidates.size() < topK) {
            QueryWrapper<KgEntity> scan = new QueryWrapper<KgEntity>()
                .eq("status", KgEntity.STATUS_ACTIVE)
                .orderByAsc("id").last("LIMIT 2000");
            applyVisibility(scan, userId);
            List<KgEntity> rows = entityMapper.selectList(scan);
            for (KgEntity row : rows == null ? List.<KgEntity>of() : rows) {
                if (!visible(row, userId) || row.getDisplayName() == null) {
                    continue;
                }
                boolean named = query.contains(row.getDisplayName())
                    || row.getName() != null && query.toLowerCase().contains(row.getName().toLowerCase());
                boolean category = query.contains("竞赛") || query.contains("赛事")
                    ? "COMPETITION".equals(row.getEntityType())
                    : (query.contains("帖子") || query.contains("记录"))
                        && "POST".equals(row.getEntityType());
                if (named || category) {
                    candidates.putIfAbsent(row.getId(), row);
                }
            }
        }
        return candidates.values().stream().sorted(Comparator
            .comparingInt((KgEntity row) -> query.contains(row.getDisplayName())
                ? row.getDisplayName().length() : 0).reversed()
            .thenComparing(KgEntity::getId)).limit(topK).toList();
    }

    private boolean visible(KgEntity row, Long userId) {
        return row != null && row.getId() != null
            && KgEntity.STATUS_ACTIVE.equals(row.getStatus())
            && scopeService.canSee(row, userId);
    }

    static Map<String, Object> toRow(KgEntity entity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entityId", entity.getId());
        row.put("name", entity.getDisplayName());
        row.put("entityType", entity.getEntityType());
        return row;
    }
}
