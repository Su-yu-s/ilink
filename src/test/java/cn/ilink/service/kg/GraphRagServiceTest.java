package cn.ilink.service.kg;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.entity.KgDocEntity;
import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.service.ai.AiClient;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Date;
import java.time.LocalDate;

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
    private KgScopeService scopeService;
    private cn.ilink.mapper.KgDocEntityMapper docEntityMapper;
    private KgDocumentChunkService chunkService;
    private GraphRagService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        embeddingService = mock(KgEmbeddingService.class);
        relationService = mock(KgRelationService.class);
        entityMapper = mock(KgEntityMapper.class);
        aiClient = mock(AiClient.class);
        scopeService = mock(KgScopeService.class);
        docEntityMapper = mock(cn.ilink.mapper.KgDocEntityMapper.class);
        chunkService = mock(KgDocumentChunkService.class);
        when(scopeService.canSee(any(KgEntity.class), any())).thenReturn(true);
        when(scopeService.canSee(any(), any(), any(), any())).thenReturn(true);
        service = new GraphRagService(embeddingService, relationService, entityMapper, aiClient,
            scopeService, docEntityMapper, chunkService);
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
        when(relationService.expandTwoHops(any(), any(), any())).thenReturn(graph);
        when(relationService.getEntitiesByIds(any())).thenReturn(List.of(
            entity(1L, "数学建模", "SKILL"), entity(2L, "国赛", "COMPETITION")));
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("答案内容 #1", 10, 20));

        GraphRagService.GraphRagAnswer answer = service.query("数学建模需要什么技能？", 7L);

        assertEquals("答案内容 #1", answer.answer);
        assertTrue(answer.vectorRetrieval);
        assertEquals(2, answer.seedCount);
        assertEquals(1, answer.edgeCount);
        assertEquals(1, answer.citations.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryFallsBackToKeywordWhenVectorUnavailable() {
        when(embeddingService.embedText(any())).thenThrow(new IllegalStateException("未配置"));
        when(entityMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(entity(5L, "数学建模", "SKILL")));

        KgRelationService.SubGraph graph = new KgRelationService.SubGraph();
        graph.entityIds.add(5L);
        when(relationService.expandTwoHops(any(), any(), any())).thenReturn(graph);
        when(relationService.getEntitiesByIds(any()))
            .thenReturn(List.of(entity(5L, "数学建模", "SKILL")));
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("关键词回答 #5", 10, 20));

        GraphRagService.GraphRagAnswer answer = service.query("数学建模", 7L);

        assertFalse(answer.vectorRetrieval);
        assertEquals(1, answer.seedCount);
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitMentionedEntityIsKeptWhenVectorSearchFindsAnUnrelatedSeed() {
        KgEntity unrelated = entity(1L, "随机实体", "SKILL");
        KgEntity mentioned = entity(2L, "智能学习助手", "PROJECT");
        when(embeddingService.embedText(any())).thenReturn(new float[]{1f});
        when(embeddingService.searchTopK(any(), anyInt()))
            .thenReturn(List.of(new KgEmbeddingService.ScoredEntity(1L, 0.9)));
        when(entityMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mentioned));
        when(relationService.getEntitiesByIds(any())).thenReturn(List.of(unrelated, mentioned));
        KgRelationService.SubGraph graph = new KgRelationService.SubGraph();
        graph.entityIds.add(2L);
        when(relationService.expandTwoHops(any(), any(), any())).thenReturn(graph);
        when(aiClient.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult("智能学习助手 #2", 1, 1));

        GraphRagService.GraphRagAnswer answer = service.query("《智能学习助手》是什么？", 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> seeds = ArgumentCaptor.forClass((Class) List.class);
        verify(relationService).expandTwoHops(seeds.capture(),
            org.mockito.ArgumentMatchers.eq(7L), any());
        assertEquals(2L, seeds.getValue().get(0));
        assertTrue(seeds.getValue().contains(1L));
        assertTrue(answer.vectorRetrieval);
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
        when(relationService.expandTwoHops(any(), any(), any())).thenReturn(new KgRelationService.SubGraph());
        when(relationService.getEntitiesByIds(any())).thenReturn(List.of());
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("通用回答", 1, 1));

        GraphRagService.GraphRagAnswer answer = service.query("一个图谱里没有的问题", 7L);

        org.mockito.Mockito.verify(aiClient, org.mockito.Mockito.never()).chat(anyList(), anyInt());
        assertEquals(0, answer.seedCount);
        assertTrue(answer.citations.isEmpty());
        assertFalse(answer.answer.contains("#"));
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
        when(relationService.expandTwoHops(any(), any(), any())).thenReturn(new KgRelationService.SubGraph());
        when(relationService.getEntitiesByIds(any())).thenReturn(List.of());
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("ok", 1, 1));
        service.query("另一个问题", 7L);
        org.mockito.Mockito.verify(aiClient, org.mockito.Mockito.never()).chat(anyList(), anyInt());
    }

    @Test
    void preservesRetrievedContentWhenExplainingAnAlgorithmStep() {
        String answer = "1. 记忆检索：查找相关历史。\n"
            + "2. 上下文构建：检索到的记忆被拼接到完整上下文，然后输入模型。\n"
            + "3. 记忆更新：决定是否写入新记忆。";
        assertEquals(answer, GraphRagService.stripInternalStateSentences(answer));
        assertEquals("检索到的项目文件用于初始化工作区。",
            GraphRagService.stripInternalStateSentences("检索到的项目文件用于初始化工作区。"));
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

    @Test
    @SuppressWarnings("unchecked")
    void queryRendersTemporalRelationAndLinkedSourceChunk() {
        when(embeddingService.embedText(any())).thenReturn(new float[]{1f});
        when(embeddingService.searchTopK(any(), anyInt()))
            .thenReturn(List.of(new KgEmbeddingService.ScoredEntity(1L, 0.9)));
        KgEntity source = entity(1L, "团队空间记录", "POST");
        KgEntity target = entity(2L, "数学建模竞赛", "COMPETITION");
        when(relationService.getEntitiesByIds(any())).thenReturn(List.of(source, target));
        KgRelation edge = new KgRelation();
        edge.setId(70L);
        edge.setSourceId(1L);
        edge.setTargetId(2L);
        edge.setRelationType(KgRelation.MENTIONS);
        edge.setScope(KgEntity.SCOPE_GLOBAL);
        edge.setSourceDocId("POST:7");
        edge.setValidFrom(Date.from(LocalDate.parse("2026-02-10")
            .atStartOfDay(KgTimeRange.ZONE).toInstant()));
        edge.setValidTo(Date.from(LocalDate.parse("2026-03-01")
            .atStartOfDay(KgTimeRange.ZONE).toInstant()));
        KgRelationService.SubGraph graph = new KgRelationService.SubGraph();
        graph.addEdge(edge);
        when(relationService.expandTwoHops(any(), any(), any())).thenReturn(graph);
        KgDocEntity mapping = new KgDocEntity();
        mapping.setDocType("POST");
        mapping.setDocId(7L);
        when(docEntityMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mapping));
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(42L);
        chunk.setDocType("POST");
        chunk.setDocId(7L);
        chunk.setTitle("团队空间开发记录");
        chunk.setContent("开发记录提到数学建模竞赛。");
        when(chunkService.listByDocument("POST", 7L, 7L)).thenReturn(List.of(chunk));
        when(aiClient.chat(anyList(), anyInt())).thenReturn(
            new AiClient.AiChatResult("团队空间记录提到数学建模竞赛。#1 r#70 c#42", 10, 20));

        GraphRagService.GraphRagAnswer answer = service.query("2026 年 2 月团队空间记录关联哪个竞赛？", 7L);

        ArgumentCaptor<KgTimeRange> range = ArgumentCaptor.forClass(KgTimeRange.class);
        verify(relationService).expandTwoHops(any(), org.mockito.ArgumentMatchers.eq(7L), range.capture());
        assertEquals(LocalDate.parse("2026-02-01"), range.getValue().startInclusive());
        ArgumentCaptor<List<java.util.Map<String, String>>> messages = ArgumentCaptor.forClass((Class) List.class);
        verify(aiClient).chat(messages.capture(), anyInt());
        String prompt = messages.getValue().get(1).get("content");
        assertTrue(prompt.contains("r#70"));
        assertTrue(prompt.contains("2026-02-10"));
        assertTrue(prompt.contains("2026-03-01"));
        assertTrue(prompt.contains("c#42"));
        assertTrue(prompt.contains("开发记录提到数学建模竞赛"));
        assertEquals(3, answer.citations.size());
    }

    @Test
    void fabricatedGraphCitationCannotReachUser() {
        when(embeddingService.embedText(any())).thenReturn(new float[]{1f});
        when(embeddingService.searchTopK(any(), anyInt()))
            .thenReturn(List.of(new KgEmbeddingService.ScoredEntity(1L, 0.9)));
        when(relationService.getEntitiesByIds(any())).thenReturn(List.of(entity(1L, "团队空间", "POST")));
        KgRelationService.SubGraph graph = new KgRelationService.SubGraph();
        graph.entityIds.add(1L);
        when(relationService.expandTwoHops(any(), any(), any())).thenReturn(graph);
        when(aiClient.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult("编造的竞赛 #999", 10, 20));

        GraphRagService.GraphRagAnswer answer = service.query("团队空间关联哪些竞赛？", 7L);

        verify(aiClient, org.mockito.Mockito.times(2)).chat(anyList(), anyInt());
        assertTrue(answer.citations.isEmpty());
        assertFalse(answer.answer.contains("编造"));
    }

    @Test
    void anEntityCitationAloneCannotSupportAClaimAboutItsRelations() {
        when(embeddingService.embedText(any())).thenReturn(new float[]{1f});
        when(embeddingService.searchTopK(any(), anyInt()))
            .thenReturn(List.of(new KgEmbeddingService.ScoredEntity(1L, 0.9)));
        when(relationService.getEntitiesByIds(any()))
            .thenReturn(List.of(entity(1L, "团队空间", "POST")));
        KgRelationService.SubGraph graph = new KgRelationService.SubGraph();
        graph.entityIds.add(1L);
        when(relationService.expandTwoHops(any(), any(), any())).thenReturn(graph);
        when(aiClient.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult("团队空间关联全国赛。#1", 1, 1));

        GraphRagService.GraphRagAnswer answer = service.query("团队空间关联哪些竞赛？", 7L);

        verify(aiClient, org.mockito.Mockito.times(2)).chat(anyList(), anyInt());
        assertTrue(answer.citations.isEmpty());
        assertFalse(answer.answer.contains("全国赛"));
    }

    @Test
    void unavailableGeneratorReturnsConservativeAnswer() {
        when(embeddingService.embedText(any())).thenReturn(new float[]{1f});
        when(embeddingService.searchTopK(any(), anyInt()))
            .thenReturn(List.of(new KgEmbeddingService.ScoredEntity(1L, 0.9)));
        when(relationService.getEntitiesByIds(any()))
            .thenReturn(List.of(entity(1L, "团队空间", "POST")));
        KgRelationService.SubGraph graph = new KgRelationService.SubGraph();
        graph.entityIds.add(1L);
        when(relationService.expandTwoHops(any(), any(), any())).thenReturn(graph);
        when(aiClient.chat(anyList(), anyInt()))
            .thenThrow(new AiClient.AiUnavailableException("offline"));

        GraphRagService.GraphRagAnswer answer = service.query("团队空间是什么？", 7L);

        assertTrue(answer.citations.isEmpty());
        assertFalse(answer.answer.contains("#"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void privateEndpointNeverAppearsInPromptOrCitations() {
        when(embeddingService.embedText(any())).thenReturn(new float[]{1f});
        when(embeddingService.searchTopK(any(), anyInt()))
            .thenReturn(List.of(new KgEmbeddingService.ScoredEntity(1L, 0.9)));
        KgEntity publicEntity = entity(1L, "公开记录", "POST");
        KgEntity privateEntity = entity(2L, "私人赛事秘密", "COMPETITION");
        privateEntity.setScope(KgEntity.SCOPE_PRIVATE);
        when(scopeService.canSee(any(KgEntity.class), any()))
            .thenAnswer(inv -> KgEntity.SCOPE_GLOBAL.equals(((KgEntity) inv.getArgument(0)).getScope()));
        when(relationService.getEntitiesByIds(any())).thenReturn(List.of(publicEntity, privateEntity));
        KgRelation edge = new KgRelation();
        edge.setId(71L);
        edge.setSourceId(1L);
        edge.setTargetId(2L);
        edge.setScope(KgEntity.SCOPE_GLOBAL);
        KgRelationService.SubGraph graph = new KgRelationService.SubGraph();
        graph.addEdge(edge);
        when(relationService.expandTwoHops(any(), any(), any())).thenReturn(graph);
        when(aiClient.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("公开记录 #1", 1, 1));

        GraphRagService.GraphRagAnswer answer = service.query("公开记录", 7L);

        ArgumentCaptor<List<java.util.Map<String, String>>> messages = ArgumentCaptor.forClass((Class) List.class);
        verify(aiClient).chat(messages.capture(), anyInt());
        String prompt = messages.getValue().get(1).get("content");
        assertFalse(prompt.contains("私人赛事秘密"));
        assertFalse(prompt.contains("r#71"));
        assertEquals(1L, answer.citations.get(0).get("entityId"));
    }
}
