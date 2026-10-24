package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgDocEntity;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgDocEntityMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.kg.KgScopeService;
import cn.ilink.service.kg.KgDocumentChunkService;
import cn.ilink.service.kg.KgEmbeddingService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 阶段 3/4：工具行为（参数校验、可见性、结构化错误） */
class GraphToolsTest {

    private KgScopeService scopeService;
    private KgEntityMapper entityMapper;
    private KgDocEntityMapper docEntityMapper;
    private KgDocumentChunkService chunkService;
    private KgRelationMapper relationMapper;
    private KgEmbeddingService embeddings;

    private SearchEntitiesTool searchTool;
    private ExpandNeighborsTool expandTool;
    private FilterEntitiesTool filterTool;
    private GetDocumentTool documentTool;
    private SearchDocumentsTool searchDocumentsTool;
    private QueryTemporalTool temporalTool;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        scopeService = mock(KgScopeService.class);
        entityMapper = mock(KgEntityMapper.class);
        docEntityMapper = mock(KgDocEntityMapper.class);
        chunkService = mock(KgDocumentChunkService.class);
        relationMapper = mock(KgRelationMapper.class);
        embeddings = mock(KgEmbeddingService.class);
        // 所有默认可见
        when(scopeService.canSee(any(), any())).thenReturn(true);
        when(scopeService.canSee(any(), any(), any(), any())).thenReturn(true);
        when(scopeService.canSeeChunk(any(KgDocumentChunk.class), any())).thenReturn(true);
        when(scopeService.visibleTeamIds(any())).thenReturn(List.of());

        searchTool = new SearchEntitiesTool(scopeService, entityMapper, embeddings);
        expandTool = new ExpandNeighborsTool(scopeService, entityMapper, relationMapper);
        filterTool = new FilterEntitiesTool(scopeService, entityMapper);
        documentTool = new GetDocumentTool(scopeService, entityMapper, docEntityMapper, chunkService);
        searchDocumentsTool = new SearchDocumentsTool(scopeService, chunkService);
        temporalTool = new QueryTemporalTool(scopeService, entityMapper, relationMapper);
    }

    private static KgEntity entity(long id, String name, String type) {
        KgEntity entity = new KgEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDisplayName(name);
        entity.setEntityType(type);
        entity.setScope(KgEntity.SCOPE_GLOBAL);
        entity.setStatus(KgEntity.STATUS_ACTIVE);
        return entity;
    }

    // ==================== SearchEntitiesTool ====================

    @Test
    void searchToolRejectsEmptyQuery() {
        Object result = searchTool.execute(new HashMap<>(), 1L);
        assertTrue(result.toString().contains("INVALID_PARAMS"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchToolFallsBackToKeywordWhenVectorFails() {
        Map<String, Object> params = new HashMap<>();
        params.put("query", "数学建模");
        when(entityMapper.selectList(any(Wrapper.class))).thenReturn(List.of(entity(1L, "数学建模", "SKILL")));

        Object result = searchTool.execute(params, 1L);

        assertTrue(result instanceof Map);
        assertEquals("keyword", ((Map<String, Object>) result).get("retrieval"));
        assertEquals(1, ((Map<String, Object>) result).get("count"));
    }

    @Test
    void searchToolClampsTopK() {
        Map<String, Object> params = new HashMap<>();
        params.put("query", "x");
        params.put("top_k", 999);
        // 不会抛异常：top_k 被夹到合法区间，且空结果返回结构化 NO_RESULTS
        assertTrue(searchTool.execute(params, 1L).toString().contains("NO_RESULTS"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchToolBackfillsPastInvisibleVectorHit() {
        KgEntity privateEntity = entity(1L, "私人赛事", "COMPETITION");
        privateEntity.setScope(KgEntity.SCOPE_PRIVATE);
        KgEntity publicEntity = entity(2L, "数学建模竞赛", "COMPETITION");
        when(entityMapper.selectById(1L)).thenReturn(privateEntity);
        when(entityMapper.selectById(2L)).thenReturn(publicEntity);
        when(scopeService.canSee(any(KgEntity.class), any()))
            .thenAnswer(inv -> KgEntity.SCOPE_GLOBAL.equals(((KgEntity) inv.getArgument(0)).getScope()));
        when(embeddings.embedText(any())).thenReturn(new float[]{1f});
        when(embeddings.searchTopK(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
            new KgEmbeddingService.ScoredEntity(1L, 0.99),
            new KgEmbeddingService.ScoredEntity(2L, 0.80)));
        when(entityMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        Map<String, Object> result = (Map<String, Object>) searchTool.execute(
            Map.of("query", "数学建模竞赛", "top_k", 1), 1L);

        assertEquals(1, result.get("count"));
        assertTrue(result.toString().contains("数学建模竞赛"));
        org.junit.jupiter.api.Assertions.assertFalse(result.toString().contains("私人赛事"));
    }

    // ==================== ExpandNeighborsTool ====================

    @Test
    void expandToolRejectsMissingEntityId() {
        assertTrue(expandTool.execute(new HashMap<>(), 1L).toString().contains("INVALID_PARAMS"));
    }

    @Test
    void expandToolReportsNotFound() {
        when(entityMapper.selectById(404L)).thenReturn(null);
        Map<String, Object> params = new HashMap<>();
        params.put("entity_id", 404L);
        assertTrue(expandTool.execute(params, 1L).toString().contains("NOT_FOUND"));
    }

    @Test
    void expandToolDeniesInvisibleSeed() {
        when(entityMapper.selectById(9L)).thenReturn(entity(9L, "私有实体", "POST"));
        when(scopeService.canSee(any(), any())).thenReturn(false);
        Map<String, Object> params = new HashMap<>();
        params.put("entity_id", 9L);
        assertTrue(expandTool.execute(params, 2L).toString().contains("PERMISSION_DENIED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void expandToolReturnsActualRelationIdAndNamedEndpoints() {
        when(entityMapper.selectById(1L)).thenReturn(entity(1L, "开发记录", "POST"));
        when(entityMapper.selectById(2L)).thenReturn(entity(2L, "蓝桥杯", "COMPETITION"));
        KgRelation edge = new KgRelation();
        edge.setId(17L);
        edge.setSourceId(1L);
        edge.setTargetId(2L);
        edge.setRelationType(KgRelation.MENTIONS);
        edge.setScope(KgEntity.SCOPE_GLOBAL);
        when(relationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(edge), List.of());

        Map<String, Object> result = (Map<String, Object>) expandTool.execute(Map.of("entity_id", 1L), 1L);

        assertTrue(result.toString().contains("relationId=17"));
        assertTrue(result.toString().contains("sourceName=开发记录"));
        assertTrue(result.toString().contains("targetName=蓝桥杯"));
    }

    // ==================== FilterEntitiesTool ====================

    @Test
    void filterToolRejectsBadType() {
        Map<String, Object> params = new HashMap<>();
        params.put("entity_type", "MOVIE");
        assertTrue(filterTool.execute(params, 1L).toString().contains("INVALID_PARAMS"));
    }

    @Test
    void filterToolRequiresTypeOrKeyword() {
        assertTrue(filterTool.execute(new HashMap<>(), 1L).toString().contains("INVALID_PARAMS"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void filterToolReturnsMatchingEntities() {
        when(entityMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(entity(1L, "数学建模", "SKILL"), entity(2L, "数学正赛", "COMPETITION")));
        Map<String, Object> params = new HashMap<>();
        params.put("keyword", "数学");
        params.put("limit", 5);

        Object result = filterTool.execute(params, 1L);

        assertEquals(2, ((Map<String, Object>) result).get("count"));
    }

    // ==================== GetDocumentTool ====================

    @Test
    void documentToolReturnsDocRefs() {
        when(entityMapper.selectById(1L)).thenReturn(entity(1L, "数学建模", "SKILL"));
        KgDocEntity mapping = new KgDocEntity();
        mapping.setDocType("POST");
        mapping.setDocId(77L);
        when(docEntityMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class)))
            .thenReturn(List.of(mapping));
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(99L);
        chunk.setDocType("POST");
        chunk.setDocId(77L);
        chunk.setScope(KgEntity.SCOPE_GLOBAL);
        chunk.setTitle("原始帖子");
        chunk.setContent("PointROPE 用于点云处理。");
        when(chunkService.listByDocument("POST", 77L, 1L)).thenReturn(List.of(chunk));
        Map<String, Object> params = new HashMap<>();
        params.put("entity_id", 1L);

        Object result = documentTool.execute(params, 1L);

        assertTrue(result.toString().contains("POST:77"));
        assertTrue(result.toString().contains("chunkId=99"));
        assertTrue(result.toString().contains("PointROPE"));
    }

    @Test
    void documentToolReportsNoDocuments() {
        when(entityMapper.selectById(1L)).thenReturn(entity(1L, "孤儿实体", "SKILL"));
        when(docEntityMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class)))
            .thenReturn(List.of());
        Map<String, Object> params = new HashMap<>();
        params.put("entity_id", 1L);
        assertTrue(documentTool.execute(params, 1L).toString().contains("NO_RESULTS"));
    }

    @Test
    void documentToolRejectsInvisibleSourceChunk() {
        when(entityMapper.selectById(1L)).thenReturn(entity(1L, "公开实体", "POST"));
        KgDocEntity mapping = new KgDocEntity();
        mapping.setDocType("POST");
        mapping.setDocId(77L);
        when(docEntityMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class)))
            .thenReturn(List.of(mapping));
        KgDocumentChunk privateChunk = new KgDocumentChunk();
        privateChunk.setId(100L);
        privateChunk.setDocType("POST");
        privateChunk.setDocId(77L);
        privateChunk.setScope(KgEntity.SCOPE_PRIVATE);
        privateChunk.setUserId(99L);
        privateChunk.setContent("私人秘密");
        when(chunkService.listByDocument("POST", 77L, 1L)).thenReturn(List.of(privateChunk));
        when(scopeService.canSeeChunk(privateChunk, 1L)).thenReturn(false);

        Object result = documentTool.execute(Map.of("entity_id", 1L), 1L);

        assertTrue(result.toString().contains("NO_RESULTS"));
        org.junit.jupiter.api.Assertions.assertFalse(result.toString().contains("私人秘密"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void directDocumentSearchReturnsOnlyVisibleSourceText() {
        KgDocumentChunk visible = new KgDocumentChunk();
        visible.setId(101L);
        visible.setDocType("POST");
        visible.setDocId(6L);
        visible.setScope(KgEntity.SCOPE_GLOBAL);
        visible.setContent("FileList 是实时对象，需 Array.from 快照。");
        KgDocumentChunk privateChunk = new KgDocumentChunk();
        privateChunk.setId(102L);
        privateChunk.setDocType("POST");
        privateChunk.setDocId(7L);
        privateChunk.setScope(KgEntity.SCOPE_PRIVATE);
        privateChunk.setUserId(99L);
        privateChunk.setContent("私人秘密");
        when(chunkService.retrieve("文件上传 bug", 1L, 8))
            .thenReturn(List.of(visible, privateChunk));
        when(scopeService.canSeeChunk(privateChunk, 1L)).thenReturn(false);

        Map<String, Object> result = (Map<String, Object>) searchDocumentsTool.execute(
            Map.of("query", "文件上传 bug"), 1L);

        assertEquals(1, result.get("count"));
        assertTrue(result.toString().contains("FileList"));
        org.junit.jupiter.api.Assertions.assertFalse(result.toString().contains("私人秘密"));
    }

    // ==================== QueryTemporalTool ====================

    @Test
    void temporalToolRejectsInvertedRange() {
        when(entityMapper.selectById(1L)).thenReturn(entity(1L, "实体", "SKILL"));
        Map<String, Object> range = new HashMap<>();
        range.put("start", "2026-05-01");
        range.put("end", "2026-01-01");
        Map<String, Object> params = new HashMap<>();
        params.put("entity_id", 1L);
        params.put("time_range", range);
        assertTrue(temporalTool.execute(params, 1L).toString().contains("INVALID_PARAMS"));
    }

    @Test
    void temporalToolRejectsBadDates() {
        when(entityMapper.selectById(1L)).thenReturn(entity(1L, "实体", "SKILL"));
        Map<String, Object> range = new HashMap<>();
        range.put("start", "不是日期");
        Map<String, Object> params = new HashMap<>();
        params.put("entity_id", 1L);
        params.put("time_range", range);
        Object result = temporalTool.execute(params, 1L);
        assertTrue(result.toString().contains("INVALID_PARAMS"));
    }

    @Test
    void temporalToolRejectsMissingEntityId() {
        assertTrue(temporalTool.execute(new HashMap<>(), 1L).toString().contains("INVALID_PARAMS"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void temporalToolCanSearchVisibleGlobalWindowWithoutAnEntitySeed() {
        KgRelation edge = new KgRelation();
        edge.setId(18L);
        edge.setSourceId(1L);
        edge.setTargetId(2L);
        edge.setRelationType(KgRelation.MENTIONS);
        edge.setScope(KgEntity.SCOPE_GLOBAL);
        edge.setValidFrom(Date.from(LocalDate.parse("2026-03-05")
            .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant()));
        when(relationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(edge));
        when(entityMapper.selectById(1L)).thenReturn(entity(1L, "开发记录", "POST"));
        when(entityMapper.selectById(2L)).thenReturn(entity(2L, "蓝桥杯", "COMPETITION"));

        Map<String, Object> result = (Map<String, Object>) temporalTool.execute(Map.of(
            "time_range", Map.of("start", "2026-03-01", "end", "2026-03-31")), 1L);

        assertEquals(1, result.get("count"));
        assertTrue(result.toString().contains("蓝桥杯"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void temporalToolReturnsNamedVisibleFebruaryEdgeOnlyInFebruary() {
        when(entityMapper.selectById(1L)).thenReturn(entity(1L, "开发记录", "POST"));
        when(entityMapper.selectById(2L)).thenReturn(entity(2L, "数学建模竞赛", "COMPETITION"));
        KgRelation edge = new KgRelation();
        edge.setId(8L);
        edge.setSourceId(1L);
        edge.setTargetId(2L);
        edge.setRelationType(KgRelation.MENTIONS);
        edge.setScope(KgEntity.SCOPE_GLOBAL);
        edge.setValidFrom(Date.from(LocalDate.parse("2026-02-10")
            .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant()));
        edge.setValidTo(Date.from(LocalDate.parse("2026-03-01")
            .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant()));
        when(relationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(edge));

        Map<String, Object> february = (Map<String, Object>) temporalTool.execute(Map.of(
            "entity_id", 1L, "time_range", Map.of("start", "2026-02-01", "end", "2026-02-28")), 1L);
        assertEquals(1, february.get("count"));
        assertTrue(february.toString().contains("数学建模竞赛"));
        assertTrue(february.toString().contains("relationId=8"));

        Map<String, Object> march = (Map<String, Object>) temporalTool.execute(Map.of(
            "entity_id", 1L, "time_range", Map.of("start", "2026-03-01", "end", "2026-03-31")), 1L);
        assertEquals(0, march.get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void temporalToolHidesPrivateEndpoint() {
        when(entityMapper.selectById(1L)).thenReturn(entity(1L, "开发记录", "POST"));
        KgEntity privateEntity = entity(2L, "私人赛事秘密", "COMPETITION");
        privateEntity.setScope(KgEntity.SCOPE_PRIVATE);
        when(entityMapper.selectById(2L)).thenReturn(privateEntity);
        when(scopeService.canSee(any(KgEntity.class), any()))
            .thenAnswer(inv -> KgEntity.SCOPE_GLOBAL.equals(((KgEntity) inv.getArgument(0)).getScope()));
        KgRelation edge = new KgRelation();
        edge.setId(9L);
        edge.setSourceId(1L);
        edge.setTargetId(2L);
        edge.setScope(KgEntity.SCOPE_GLOBAL);
        when(relationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(edge));

        Map<String, Object> result = (Map<String, Object>) temporalTool.execute(Map.of("entity_id", 1L), 1L);

        assertEquals(0, result.get("count"));
        org.junit.jupiter.api.Assertions.assertFalse(result.toString().contains("私人赛事秘密"));
    }

    // ==================== KgScopeService ====================

    @Test
    @SuppressWarnings("unchecked")
    void scopeServiceVisibilityRules() {
        cn.ilink.service.impl.TeamDemandServiceImpl teamDemandService =
            mock(cn.ilink.service.impl.TeamDemandServiceImpl.class);
        cn.ilink.service.impl.TeamApplicationServiceImpl teamApplicationService =
            mock(cn.ilink.service.impl.TeamApplicationServiceImpl.class);
        cn.ilink.service.TeamAccessService teamAccessService =
            mock(cn.ilink.service.TeamAccessService.class);
        when(teamDemandService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
            .thenReturn(List.of());
        when(teamApplicationService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
            .thenReturn(List.of());
        KgScopeService service = new KgScopeService(teamAccessService, teamDemandService, teamApplicationService);

        // GLOBAL 全员可见；null 实体不可见
        assertTrue(service.canSee(entity(1L, "全局", "SKILL"), null));
        org.junit.jupiter.api.Assertions.assertFalse(service.canSee(null, 1L));

        // PRIVATE：属主可见，他人不可见
        KgEntity privateEntity = entity(2L, "私有", "POST");
        privateEntity.setScope(KgEntity.SCOPE_PRIVATE);
        privateEntity.setUserId(7L);
        assertTrue(service.canSee(privateEntity, 7L));
        org.junit.jupiter.api.Assertions.assertFalse(service.canSee(privateEntity, 8L));

        // 匿名用户对非 GLOBAL 实体不可见
        org.junit.jupiter.api.Assertions.assertFalse(service.canSee(privateEntity, null));
    }
}
