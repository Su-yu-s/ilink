package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgDocEntity;
import cn.ilink.entity.KgEntity;
import cn.ilink.mapper.KgDocEntityMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.service.kg.KgScopeService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private SearchEntitiesTool searchTool;
    private ExpandNeighborsTool expandTool;
    private FilterEntitiesTool filterTool;
    private GetDocumentTool documentTool;
    private QueryTemporalTool temporalTool;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        scopeService = mock(KgScopeService.class);
        entityMapper = mock(KgEntityMapper.class);
        docEntityMapper = mock(KgDocEntityMapper.class);
        // 所有默认可见
        when(scopeService.canSee(any(), any())).thenReturn(true);
        when(scopeService.visibleTeamIds(any())).thenReturn(List.of());

        searchTool = new SearchEntitiesTool(scopeService, entityMapper, mock(cn.ilink.service.kg.KgEmbeddingService.class));
        expandTool = new ExpandNeighborsTool(scopeService, entityMapper, mock(cn.ilink.mapper.KgRelationMapper.class));
        filterTool = new FilterEntitiesTool(scopeService, entityMapper);
        documentTool = new GetDocumentTool(scopeService, entityMapper, docEntityMapper);
        temporalTool = new QueryTemporalTool(scopeService, entityMapper, mock(cn.ilink.mapper.KgRelationMapper.class));
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
        Map<String, Object> params = new HashMap<>();
        params.put("entity_id", 1L);

        Object result = documentTool.execute(params, 1L);

        assertTrue(result.toString().contains("POST:77"));
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
    void temporalToolToleratesBadDates() {
        when(entityMapper.selectById(1L)).thenReturn(entity(1L, "实体", "SKILL"));
        Map<String, Object> range = new HashMap<>();
        range.put("start", "不是日期");
        Map<String, Object> params = new HashMap<>();
        params.put("entity_id", 1L);
        params.put("time_range", range);
        // 非法日期被忽略（视作无窗口），不抛异常
        Object result = temporalTool.execute(params, 1L);
        assertTrue(result instanceof Map || result.toString().contains("NO_RESULTS")
            || result.toString().contains("count"));
    }

    @Test
    void temporalToolRejectsMissingEntityId() {
        assertTrue(temporalTool.execute(new HashMap<>(), 1L).toString().contains("INVALID_PARAMS"));
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
