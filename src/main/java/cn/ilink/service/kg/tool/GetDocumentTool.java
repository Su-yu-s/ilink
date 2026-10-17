package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgDocEntity;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.mapper.KgDocEntityMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.service.kg.KgScopeService;
import cn.ilink.service.kg.KgDocumentChunkService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 原文追溯：查看实体是从哪些文档抽取出来的（docType + docId 引用，便于回看原文） */
@Component
public class GetDocumentTool extends AbstractGraphTool {

    private final KgEntityMapper entityMapper;
    private final KgDocEntityMapper docEntityMapper;
    private final KgDocumentChunkService chunkService;

    @Autowired
    public GetDocumentTool(KgScopeService scopeService, KgEntityMapper entityMapper,
                           KgDocEntityMapper docEntityMapper, KgDocumentChunkService chunkService) {
        super(scopeService);
        this.entityMapper = entityMapper;
        this.docEntityMapper = docEntityMapper;
        this.chunkService = chunkService;
    }

    @Override
    public String getName() {
        return "get_document";
    }

    @Override
    public String getDescription() {
        return "原文追溯：给定 entityId，返回实际可见的来源文档片段（chunkId、标题、原文）。"
            + "用于核对细节、数字、代码和事实来源。";
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
        List<KgDocEntity> mappings = docEntityMapper.selectList(new QueryWrapper<KgDocEntity>()
            .eq("entity_id", entityId));
        if (mappings == null || mappings.isEmpty()) {
            return errorJson("NO_RESULTS", "实体 #" + entityId + " 没有关联文档");
        }
        List<Map<String, Object>> docs = new ArrayList<>();
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (KgDocEntity mapping : mappings) {
            if (chunks.size() >= 8) {
                break;
            }
            List<KgDocumentChunk> visible = chunkService.listByDocument(
                mapping.getDocType(), mapping.getDocId(), userId);
            if (visible == null || visible.isEmpty()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docType", mapping.getDocType());
            row.put("docId", mapping.getDocId());
            row.put("ref", mapping.getDocType() + ":" + mapping.getDocId());
            docs.add(row);
            for (KgDocumentChunk chunk : visible) {
                if (chunks.size() >= 8) {
                    break;
                }
                if (!scopeService.canSee(chunk.getScope(), chunk.getTeamId(),
                    chunk.getUserId(), userId)) {
                    continue;
                }
                Map<String, Object> text = new LinkedHashMap<>();
                text.put("chunkId", chunk.getId());
                text.put("docType", chunk.getDocType());
                text.put("docId", chunk.getDocId());
                text.put("ref", chunk.getDocType() + ":" + chunk.getDocId());
                text.put("title", chunk.getTitle());
                text.put("startOffset", chunk.getStartOffset());
                text.put("endOffset", chunk.getEndOffset());
                text.put("text", chunk.getContent().length() <= 800
                    ? chunk.getContent() : chunk.getContent().substring(0, 800));
                chunks.add(text);
            }
        }
        if (chunks.isEmpty()) {
            return errorJson("NO_RESULTS", "实体 #" + entityId + " 没有可见原文片段");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entityId", entityId);
        result.put("name", entity.getDisplayName());
        result.put("documents", docs);
        result.put("chunks", chunks);
        return result;
    }
}
