package cn.ilink.service.kg;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.service.ai.AiClient;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 阶段 2：固定 2 跳检索问答 */
class GraphRagServiceTest {

    private KgEmbeddingService embeddingService;
    private KgRelationService relationService;
    private KgEntityMapper entityMapper;
    private AiClient aiClient;
    private GraphRagService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        embeddingService = mock(KgEmbeddingService.class);
        relationService = mock(KgRelationService.class);
        entityMapper = mock(KgEntityMapper.class);
        aiClient = mock(AiClient.class);
        service = new GraphRagService(embeddingService, relationService, entityMapper, aiClient);
    }

    private static KgEntity entity(long id, String name, String type) {
        KgEntity entity = new KgEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDisplayName(name);
        entity.setEntityType(type);
        entity.setDescription("描述" + id);
        entity.setScope(KgEntity.SCOPE_GLOBAL);
        entity.setStatus(KgEntity.STATUS_ACTIVE);
        return entity;
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryHappyPathUsesVectorRetrievalAndReturnsCitations() {
        when(embeddingService.embedText(any())).thenReturn(new float[]{0.1f, 0.2f});
        when(embeddingService.searchTopK(any(), anyInt())).thenReturn(List.of(
            new KgEmbeddingService.ScoredEntity(1L, 0.9),
            new KgEmbeddingService.ScoredEntity(2L, 0.8)));

        KgRelationService.SubGraph graph = new KgRelationService.SubGraph();
        KgRelation edge = new KgRelation();
        edge.setSourceId(1L);
        edge.setTargetId(2L);
        edge.setRelationType(KgRelation.REQUIRES);
        graph.addEdge(edge);
        when(relationService.expandTwoHops(any())).thenReturn(graph);
        when(relationService.getEntitiesByIds(any())).thenReturn(List.of(
            entity(1L, "数学建模", "SKILL"), entity(2L, "国赛", "COMPETITION")));
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("答案内容", 10, 20));

        GraphRagService.GraphRagAnswer answer = service.query("数学建模需要什么技能？", 7L);

        assertEquals("答案内容", answer.answer);
        assertTrue(answer.vectorRetrieval);
        assertEquals(2, answer.seedCount);
        assertEquals(1, answer.edgeCount);
        assertEquals(2, answer.citations.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryFallsBackToKeywordWhenVectorUnavailable() {
        when(embeddingService.embedText(any())).thenThrow(new IllegalStateException("未配置"));
        when(entityMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(entity(5L, "数学建模", "SKILL")));

        KgRelationService.SubGraph graph = new KgRelationService.SubGraph();
        when(relationService.expandTwoHops(any())).thenReturn(graph);
        when(relationService.getEntitiesByIds(any()))
            .thenReturn(List.of(entity(5L, "数学建模", "SKILL")));
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("关键词回答", 10, 20));

        GraphRagService.GraphRagAnswer answer = service.query("数学建模", 7L);

        assertFalse(answer.vectorRetrieval);
        assertEquals(1, answer.seedCount);
    }

    @Test
    void searchByKeywordDeduplicatesResults() {
        KgEntity same = entity(5L, "数学建模", "SKILL");
        when(entityMapper.selectList(any(Wrapper.class))).thenReturn(List.of(same, entity(6L, "建模大赛", "COMPETITION")));

        List<Long> ids = service.searchByKeyword("数学建模 教程");

        // 整句 LIKE 与分词 LIKE 会重复召回 #5，但结果必须去重
        assertTrue(ids.contains(5L));
        assertEquals(ids.size(), ids.stream().distinct().count());
    }

    @Test
    void subgraphTextContainsEntitiesAndRelations() {
        KgRelation edge = new KgRelation();
        edge.setSourceId(1L);
        edge.setTargetId(2L);
        edge.setRelationType(KgRelation.HOSTED_BY);
        String text = service.buildSubgraphText(
            List.of(entity(1L, "国赛", "COMPETITION"), entity(2L, "交流中心", "ORGANIZATION")),
            List.of(edge));

        assertTrue(text.contains("[COMPETITION] 国赛"));
        assertTrue(text.contains("[ORGANIZATION] 交流中心"));
        assertTrue(text.contains("-[HOSTED_BY]-> #2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generationPromptCarriesSubgraphAndQuestion() {
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("好", 1, 1));

        service.generateAnswer("数学建模需要什么技能？", "【知识图谱检索到的实体】\n- #1 [SKILL] 数学建模", 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<java.util.Map<String, String>>> captor =
            ArgumentCaptor.forClass((Class) List.class);
        verify(aiClient).chat(captor.capture(), anyInt());
        String userPrompt = captor.getValue().get(1).get("content");
        assertTrue(userPrompt.contains("#1 [SKILL] 数学建模"));
        assertTrue(userPrompt.contains("数学建模需要什么技能？"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyGraphStillAnswersFromModelKnowledge() {
        when(embeddingService.embedText(any())).thenThrow(new IllegalStateException("未配置"));
        when(entityMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(relationService.expandTwoHops(any())).thenReturn(new KgRelationService.SubGraph());
        when(relationService.getEntitiesByIds(any())).thenReturn(List.of());
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("通用回答", 1, 1));

        GraphRagService.GraphRagAnswer answer = service.query("一个图谱里没有的问题", 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<java.util.Map<String, String>>> captor =
            ArgumentCaptor.forClass((Class) List.class);
        verify(aiClient).chat(captor.capture(), anyInt());
        String userPrompt = captor.getValue().get(1).get("content");
        // P0-1：零命中时 prompt 完全不注入资料块——模型无从向用户解释"未收录/没有相关记录"
        assertFalse(userPrompt.contains("内部资料"));
        assertFalse(userPrompt.contains("没有检索到"));
        // 无召回时 prompt 必须引导模型用自身知识作答而不是拒绝
        assertTrue(userPrompt.contains("请结合你的专业知识给出完整、可执行回答"));
        assertEquals(0, answer.seedCount);
        assertEquals("通用回答", answer.answer);
    }

    @Test
    void emptyGraphPromptCarriesNoInternalStateWords() {
        // 直接验证 buildSubgraphText 零实体产物也不该被拼进 prompt（query 已保证），
        // 这里补一条：buildSubgraphText 自身对空输入仍返回标题行，但 query 零命中不会调用它
        String empty = service.buildSubgraphText(List.of(), List.of());
        assertTrue(empty.contains("内部资料"));
        // 真正的防线在 query()：entities 为空 → generateAnswer 收到 null → 不拼块
        when(embeddingService.embedText(any())).thenThrow(new IllegalStateException("未配置"));
        when(entityMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(relationService.expandTwoHops(any())).thenReturn(new KgRelationService.SubGraph());
        when(relationService.getEntitiesByIds(any())).thenReturn(List.of());
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("ok", 1, 1));
        service.query("另一个问题", 7L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<java.util.Map<String, String>>> captor =
            ArgumentCaptor.forClass((Class) List.class);
        verify(aiClient, org.mockito.Mockito.times(1)).chat(captor.capture(), anyInt());
        assertFalse(captor.getValue().get(1).get("content").contains("内部资料"));
    }

    @Test
    void stripsInternalStateSentencesFromAnswer() {
        String dirty = "根据资料整理如下：\n\n"
            + "当前知识图谱中暂无相关记录，以下基于专业知识为您整理：\n\n"
            + "## 推荐\n\n"
            + "| 竞赛名称 | 时间 |\n|---|---|\n| 数学建模 | 9月 |\n\n"
            + "检索结果显示资料覆盖不足。综上，建议尽早组队备赛。";
        String clean = GraphRagService.stripInternalStateSentences(dirty);
        assertFalse(clean.contains("知识图谱"));
        assertFalse(clean.contains("检索结果"));
        assertTrue(clean.contains("## 推荐"));
        assertTrue(clean.contains("数学建模"));
        assertTrue(clean.contains("综上，建议尽早组队备赛。"));

        // 整段全是内部状态表述：回退原文（不输出空白）
        String allBad = "知识图谱中没有相关记录。";
        assertEquals(allBad, GraphRagService.stripInternalStateSentences(allBad));
    }
}
