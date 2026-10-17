package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.service.kg.KgDocumentChunkService;
import cn.ilink.service.kg.KgScopeService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** B3 source-text search independent of whether an entity was extracted. */
@Component
public class SearchDocumentsTool extends AbstractGraphTool {
    private final KgDocumentChunkService chunks;

    public SearchDocumentsTool(KgScopeService scopeService, KgDocumentChunkService chunks) {
        super(scopeService);
        this.chunks = chunks;
    }

    @Override
    public String getName() {
        return "search_documents";
    }

    @Override
    public String getDescription() {
        return "直接检索可见的原始文档片段，适合原因、代码、数字和细节问题。";
    }

    @Override
    public String getParametersSchema() {
        return "{\"type\":\"object\",\"properties\":{"
            + "\"query\":{\"type\":\"string\"},"
            + "\"top_k\":{\"type\":\"integer\",\"default\":8}"
            + "},\"required\":[\"query\"]}";
    }

    @Override
    public Object execute(Map<String, Object> params, Long userId) {
        String query = paramString(params, "query");
        if (query == null || query.isBlank()) {
            return errorJson("INVALID_PARAMS", "query 不能为空");
        }
        int topK = Math.max(1, Math.min(20, paramInt(params, "top_k", 8)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KgDocumentChunk chunk : chunks.retrieve(query, userId, topK)) {
            if (!scopeService.canSee(chunk.getScope(), chunk.getTeamId(), chunk.getUserId(), userId)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkId", chunk.getId());
            row.put("docType", chunk.getDocType());
            row.put("docId", chunk.getDocId());
            row.put("title", chunk.getTitle());
            row.put("startOffset", chunk.getStartOffset());
            row.put("endOffset", chunk.getEndOffset());
            row.put("text", chunk.getContent().length() <= 800
                ? chunk.getContent() : chunk.getContent().substring(0, 800));
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return errorJson("NO_RESULTS", "未找到可见原文片段");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", rows.size());
        result.put("chunks", rows);
        return result;
    }
}
