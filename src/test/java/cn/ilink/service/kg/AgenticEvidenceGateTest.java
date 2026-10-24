package cn.ilink.service.kg;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgAgentTraceMapper;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.kg.tool.GraphTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgenticEvidenceGateTest {
    private AiClient ai;
    private StubTool search;
    private StubTool neighbors;
    private StubTool temporal;
    private StubTool document;
    private EvidenceLedgerFactory factory;
    private AgenticGraphRagService service;
    private KgEntityMapper entityMapper;
    private KgRelation edge;

    @BeforeEach
    void setUp() {
        ai = mock(AiClient.class);
        entityMapper = mock(KgEntityMapper.class);
        KgRelationMapper relationMapper = mock(KgRelationMapper.class);
        KgDocumentChunkMapper chunkMapper = mock(KgDocumentChunkMapper.class);
        KgScopeService scopes = mock(KgScopeService.class);
        when(scopes.canSee(any(KgEntity.class), any())).thenReturn(true);
        when(scopes.canSee(any(), any(), any(), any())).thenReturn(true);
        when(scopes.canSeeChunk(any(KgDocumentChunk.class), any())).thenReturn(true);
        KgEntity post = entity(42, "LitePT 论文", "POST");
        KgEntity topic = entity(43, "点云", "SKILL");
        when(entityMapper.selectById(42L)).thenReturn(post);
        when(entityMapper.selectById(43L)).thenReturn(topic);
        edge = new KgRelation();
        edge.setId(7L);
        edge.setSourceId(42L);
        edge.setTargetId(43L);
        edge.setRelationType(KgRelation.MENTIONS);
        edge.setScope(KgEntity.SCOPE_GLOBAL);
        edge.setValidFrom(day("2026-02-10"));
        edge.setValidTo(day("2026-03-01"));
        when(relationMapper.selectById(7L)).thenReturn(edge);
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(99L);
        chunk.setDocType("POST");
        chunk.setDocId(6L);
        chunk.setTitle("技术分享");
        chunk.setContent("LitePT 使用 PointROPE 处理点云。");
        chunk.setScope(KgEntity.SCOPE_GLOBAL);
        when(chunkMapper.selectById(99L)).thenReturn(chunk);
        factory = new EvidenceLedgerFactory(
            entityMapper, relationMapper, chunkMapper, scopes);
        search = new StubTool("search_entities", Map.of("entities", List.of(
            Map.of("entityId", 42L, "name", "LitePT 论文"))));
        Map<String, Object> relation = Map.of("relationId", 7L, "sourceId", 42L,
            "targetId", 43L, "sourceName", "LitePT 论文", "targetName", "点云",
            "relationType", "MENTIONS", "entityId", 43L, "name", "点云");
        neighbors = new StubTool("expand_neighbors", Map.of("neighbors", List.of(relation)));
        temporal = new StubTool("query_temporal", Map.of("relations", List.of(relation)));
        document = new StubTool("get_document", Map.of("chunks", List.of(
            Map.of("chunkId", 99L, "docType", "POST", "docId", 6L,
                "title", "技术分享", "text", "LitePT 使用 PointROPE 处理点云。"))));
        service = new AgenticGraphRagService(mock(GraphRagService.class),
            mock(KgAgentTraceMapper.class), ai,
            List.of(search, neighbors, temporal, document), factory);
    }

    @Test
    void relationEvidenceIsFetchedBeforeFirstFinalAnswer() {
        when(ai.chat(anyList(), anyInt())).thenAnswer(stagedAnswer(
            "Thought: 已知\nFinal Answer: LitePT 论文 #42 与点云 #43 是 MENTIONS 关系。r#7"));

        AgenticGraphRagService.AgenticAnswer answer = service.query("LitePT 与点云有什么关系？", 8L);

        assertEquals(1, search.calls.size());
        assertEquals(1, neighbors.calls.size());
        assertEquals(2, answer.toolCalls);
        assertFalse(answer.fallback);
        assertTrue(answer.citations.stream().anyMatch(row -> Long.valueOf(7L).equals(row.get("relationId"))));
    }

    @Test
    void datedQuestionUsesTemporalToolWithParsedWindow() {
        when(ai.chat(anyList(), anyInt())).thenAnswer(stagedAnswer(
            "Thought: 已知\nFinal Answer: 2026 年 2 月，LitePT 论文 #42 提到点云 #43。r#7"));

        AgenticGraphRagService.AgenticAnswer answer = service.query("2026 年 2 月 LitePT 论文关联什么？", 8L);

        assertEquals(1, temporal.calls.size());
        @SuppressWarnings("unchecked")
        Map<String, String> range = (Map<String, String>) temporal.calls.get(0).get("time_range");
        assertEquals("2026-02-01", range.get("start"));
        assertEquals("2026-02-28", range.get("end"));
        assertEquals(0, neighbors.calls.size());
        assertTrue(answer.answer.contains("LitePT"));
    }

    @Test
    void detailQuestionRequiresRealSourceText() {
        when(ai.chat(anyList(), anyInt())).thenAnswer(stagedAnswer(
            "Thought: 原文足够\nFinal Answer: 原文写明使用 PointROPE 处理点云。c#99"));

        AgenticGraphRagService.AgenticAnswer answer = service.query("LitePT 论文的技术细节是什么？", 8L);

        assertEquals(1, document.calls.size());
        assertTrue(answer.citations.stream().anyMatch(row -> Long.valueOf(99L).equals(row.get("chunkId"))));
    }

    @Test
    void sourceQuestionCanUseDirectDocumentSearchWhenEntitySearchMisses() {
        StubTool noEntity = new StubTool("search_entities", "{\"error\":\"NO_RESULTS\"}");
        StubTool direct = new StubTool("search_documents", Map.of("chunks", List.of(
            Map.of("chunkId", 99L, "docType", "POST", "docId", 6L,
                "title", "技术分享", "text", "LitePT 使用 PointROPE 处理点云。"))));
        service = new AgenticGraphRagService(mock(GraphRagService.class),
            mock(KgAgentTraceMapper.class), ai, List.of(noEntity, direct), factory);
        when(ai.chat(anyList(), anyInt())).thenAnswer(stagedAnswer(
            "Thought: 原文足够\nFinal Answer: 原文写明使用 PointROPE 处理点云。c#99"));

        AgenticGraphRagService.AgenticAnswer answer = service.query("LitePT 论文的技术细节是什么？", 8L);

        assertEquals(1, direct.calls.size());
        assertEquals(0, answer.citations.stream().filter(row -> row.containsKey("entityId")).count());
        assertTrue(answer.citations.stream().anyMatch(row -> Long.valueOf(99L).equals(row.get("chunkId"))));
    }

    @Test
    void uncitedSourceAnswerCanRecoverARealRetrievedChunk() {
        StubTool direct = new StubTool("search_documents", Map.of("chunks", List.of(
            Map.of("chunkId", 99L, "docType", "POST", "docId", 6L,
                "title", "技术分享", "text", "LitePT 使用 PointROPE 处理点云。"))));
        service = new AgenticGraphRagService(mock(GraphRagService.class),
            mock(KgAgentTraceMapper.class), ai, List.of(direct), factory);
        when(ai.chat(anyList(), anyInt())).thenAnswer(stagedAnswer(
            "Thought: 原文足够\nFinal Answer: LitePT 使用 PointROPE 处理点云。"));

        AgenticGraphRagService.AgenticAnswer answer = service.query(
            "LitePT 论文的技术细节是什么？", 8L);

        assertTrue(answer.answer.contains("c#99"));
        assertTrue(answer.citations.stream().anyMatch(row -> Long.valueOf(99L).equals(row.get("chunkId"))));
    }

    @Test
    void unfinishedThoughtIsNeverReturnedAsAnswer() {
        StubTool direct = new StubTool("search_documents", Map.of("chunks", List.of(
            Map.of("chunkId", 99L, "docType", "POST", "docId", 6L,
                "title", "技术分享", "text", "LitePT 使用 PointROPE 处理点云。"))));
        service = new AgenticGraphRagService(mock(GraphRagService.class),
            mock(KgAgentTraceMapper.class), ai, List.of(direct), factory);
        when(ai.chat(anyList(), anyInt())).thenReturn(chat(
            "Thought: 我还要继续查询资料，因此目前不能给出最终答案。"));

        AgenticGraphRagService.AgenticAnswer answer = service.query(
            "LitePT 论文的技术细节是什么？", 8L);

        assertFalse(answer.answer.contains("Thought:"));
        assertTrue(answer.citations.isEmpty());
    }

    @Test
    void uncitedRecommendationDoesNotInventAvailablePlatformContests() {
        // The recommendation preflight rejects the draft claim before any model call.
        when(ai.chat(anyList(), anyInt())).thenReturn(chat(
            "Thought: 建议\nFinal Answer: 平台已收录区块链大赛，可直接报名。"));

        AgenticGraphRagService.AgenticAnswer answer = service.query(
            "有没有区块链方向的竞赛可以参加？", 8L);

        // The unsupported platform claim is replaced by non-targeted screening advice
        // that still names the user's own domain instead of inventing an available contest.
        assertTrue(answer.answer.contains("区块链"));
        assertTrue(answer.answer.contains("参赛资格") || answer.answer.contains("专业限制"));
        assertFalse(answer.answer.contains("已收录"));
        assertFalse(answer.answer.contains("可直接报名"));
        assertTrue(answer.answer.length() >= 80);
        assertTrue(answer.citations.isEmpty());
        assertTrue(answer.trace.stream()
            .anyMatch(row -> "GENERAL_SCREENING_ADVICE".equals(row.get("answerMode"))));
    }

    @Test
    void inventedEntityIsRewrittenOnceThenRejected() {
        when(ai.chat(anyList(), anyInt()))
            .thenReturn(chat("Thought: 直接回答\nFinal Answer: 编造的竞赛 #1001 正在举办。"))
            .thenReturn(chat("编造的竞赛 #1001 正在举办。"));

        AgenticGraphRagService.AgenticAnswer answer = service.query("LitePT 与点云有什么关系？", 8L);

        assertEquals(2, answer.toolCalls);
        assertEquals(0, answer.citations.size());
        assertFalse(answer.answer.contains("1001"));
        assertFalse(answer.answer.contains("编造"));
    }

    @Test
    void namedSubjectIsExpandedBeforeHigherRankedSemanticDistractors() {
        when(entityMapper.selectById(51L)).thenReturn(entity(51, "无关竞赛甲", "COMPETITION"));
        when(entityMapper.selectById(52L)).thenReturn(entity(52, "无关竞赛乙", "COMPETITION"));
        when(entityMapper.selectById(53L)).thenReturn(entity(53, "无关竞赛丙", "COMPETITION"));
        search = new StubTool("search_entities", Map.of("entities", List.of(
            Map.of("entityId", 51L, "name", "无关竞赛甲"),
            Map.of("entityId", 52L, "name", "无关竞赛乙"),
            Map.of("entityId", 53L, "name", "无关竞赛丙"),
            Map.of("entityId", 42L, "name", "LitePT 论文"))));
        service = new AgenticGraphRagService(mock(GraphRagService.class),
            mock(KgAgentTraceMapper.class), ai,
            List.of(search, neighbors, document), factory);
        when(ai.chat(anyList(), anyInt()))
            .thenReturn(chat("Thought: 已知\nFinal Answer: LitePT 论文 #42 与点云 #43 是 MENTIONS 关系。r#7"))
            .thenReturn(chat("{\"verdict\":\"SUPPORTED\"}"));

        service.query("LitePT 论文与点云有什么关系？", 8L);

        assertEquals(42L, ((Number) neighbors.calls.get(0).get("entity_id")).longValue());
    }

    @Test
    void exactSubjectFilterRecoversAnchorMissingFromSemanticSearch() {
        when(entityMapper.selectById(51L)).thenReturn(entity(51, "无关竞赛甲", "COMPETITION"));
        search = new StubTool("search_entities", Map.of("entities", List.of(
            Map.of("entityId", 51L, "name", "无关竞赛甲"))));
        StubTool filter = new StubTool("filter_entities", Map.of("entities", List.of(
            Map.of("entityId", 42L, "name", "LitePT 论文"))));
        service = new AgenticGraphRagService(mock(GraphRagService.class),
            mock(KgAgentTraceMapper.class), ai,
            List.of(search, filter, neighbors, document), factory);
        when(ai.chat(anyList(), anyInt()))
            .thenReturn(chat("Thought: 已知\nFinal Answer: LitePT 论文 #42 与点云 #43 是 MENTIONS 关系。r#7"))
            .thenReturn(chat("{\"verdict\":\"SUPPORTED\"}"));

        service.query("「LitePT 论文」在图谱里关联了什么？", 8L);

        assertEquals("LitePT 论文", filter.calls.get(0).get("keyword"));
        assertEquals(42L, ((Number) neighbors.calls.get(0).get("entity_id")).longValue());
    }

    @Test
    void uncitedListUsesVerifiedDirectRelations() {
        edge.setValidFrom(null);
        edge.setValidTo(null);
        Map<String, Object> coverage = Map.of(
            "scope", Map.of("entityId", 42L),
            "visible", 1,
            "returned", 1,
            "countsComplete", true,
            "complete", true,
            "truncated", false,
            "offset", 0,
            "resultFingerprint", "test-single-page");
        Map<String, Object> relation = Map.of(
            "relationId", 7L, "sourceId", 42L, "targetId", 43L,
            "sourceName", "LitePT 论文", "targetName", "点云",
            "relationType", "MENTIONS", "entityId", 43L, "name", "点云");
        neighbors = new StubTool("expand_neighbors",
            Map.of("neighbors", List.of(relation), "coverage", coverage));
        service = new AgenticGraphRagService(mock(GraphRagService.class),
            mock(KgAgentTraceMapper.class), ai,
            List.of(search, neighbors, temporal, document), factory);
        when(ai.chat(anyList(), anyInt())).thenAnswer(invocation -> {
            Object payload = invocation.getArgument(0);
            String request = String.valueOf(payload);
            if (request.contains("决定是否还需补充证据"))
                return chat("Thought: 证据已足够\nFinal Answer: READY");
            if (request.contains("你是事实核验器"))
                return chat("{\"facts\":\"SUPPORTED\",\"coverage\":\"COMPLETE\","
                    + "\"unsupported\":[],\"missing\":[]}");
            return chat("Thought: 已知\nFinal Answer: 它关联了点云。r#7");
        });

        AgenticGraphRagService.AgenticAnswer answer = service.query(
            "LitePT 论文关联了哪些技能实体？", 8L);

        assertTrue(answer.answer.contains("点云"));
        assertTrue(answer.answer.contains("r#7"));
        assertTrue(answer.citations.stream().anyMatch(row -> Long.valueOf(7L).equals(row.get("relationId"))));
    }

    @Test
    void typedRelationSummaryReturnsBeforeAnyModelCall() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        List<EvidenceLedger.EntityFact> entities = new ArrayList<>();
        entities.add(new EvidenceLedger.EntityFact(1713L, "ilink", "POST", ""));
        List<EvidenceLedger.RelationFact> relations = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            long relationId = 945L + i;
            long entityId = 1714L + i;
            entities.add(new EvidenceLedger.EntityFact(entityId, "Skill " + (i + 1), "SKILL", ""));
            relations.add(new EvidenceLedger.RelationFact(relationId, 1713L, entityId,
                "MENTIONS", null, null, null, null, ""));
        }
        entities.add(new EvidenceLedger.EntityFact(956L, "蓝桥杯", "COMPETITION", ""));
        relations.add(new EvidenceLedger.RelationFact(956L, 1713L, 956L,
            "MENTIONS", null, null, null, null, ""));
        when(ledger.entities()).thenReturn(entities);
        when(ledger.relations()).thenReturn(relations);
        for (EvidenceLedger.EntityFact entity : entities) {
            when(ledger.entity(entity.id())).thenReturn(java.util.Optional.of(entity));
        }
        for (EvidenceLedger.RelationFact relation : relations) {
            when(ledger.relation(relation.id())).thenReturn(java.util.Optional.of(relation));
        }
        when(ledger.completeRelations("expand_neighbors", 1713L)).thenReturn(true);

        EvidenceLedgerFactory ledgerFactory = mock(EvidenceLedgerFactory.class);
        when(ledgerFactory.create()).thenReturn(ledger);
        StubTool search = new StubTool("search_entities", Map.of(
            "entities", List.of(Map.of("entityId", 1713L, "name", "ilink"))));
        StubTool expand = new StubTool("expand_neighbors", Map.of("neighbors", List.of()));
        AgenticGraphRagService summaryService = new AgenticGraphRagService(
            mock(GraphRagService.class), mock(KgAgentTraceMapper.class), ai,
            List.of(search, expand), ledgerFactory);

        AgenticGraphRagService.AgenticAnswer answer = summaryService.query(
            "知识图谱里 iLink 平台本身（ilink 实体）直接关联了哪些竞赛？", 8L);

        assertTrue(answer.answer.contains("蓝桥杯"),
            answer.answer + "\n" + answer.trace);
        assertTrue(answer.answer.contains("r#956"), answer.answer);
        assertFalse(answer.answer.contains("关系记录数为 0"), answer.answer);
        assertTrue(answer.trace.stream().anyMatch(row ->
            "STRUCTURED_RELATION_SUMMARY".equals(row.get("answerMode"))
                && "COMPETITION".equals(row.get("targetType"))
                && Integer.valueOf(1).equals(row.get("targetRelationCount"))
                && Integer.valueOf(11).equals(row.get("nonTargetRelationCount"))));
        verifyNoInteractions(ai);
    }

    @Test
    void zeroTargetRelationsAreClarifiedInsteadOfPublishedAsACompleteNegative() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        List<EvidenceLedger.EntityFact> entities = new ArrayList<>();
        entities.add(new EvidenceLedger.EntityFact(1713L, "ilink", "POST", ""));
        List<EvidenceLedger.RelationFact> relations = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            long relationId = 945L + i;
            long entityId = 1714L + i;
            entities.add(new EvidenceLedger.EntityFact(entityId, "Skill " + (i + 1), "SKILL", ""));
            relations.add(new EvidenceLedger.RelationFact(relationId, 1713L, entityId,
                "MENTIONS", null, null, null, null, ""));
        }
        when(ledger.entities()).thenReturn(entities);
        when(ledger.relations()).thenReturn(relations);
        for (EvidenceLedger.EntityFact entity : entities) {
            when(ledger.entity(entity.id())).thenReturn(java.util.Optional.of(entity));
        }
        for (EvidenceLedger.RelationFact relation : relations) {
            when(ledger.relation(relation.id())).thenReturn(java.util.Optional.of(relation));
        }
        when(ledger.completeRelations("expand_neighbors", 1713L)).thenReturn(true);
        when(ledger.chunks()).thenReturn(List.of());

        EvidenceLedgerFactory ledgerFactory = mock(EvidenceLedgerFactory.class);
        when(ledgerFactory.create()).thenReturn(ledger);
        StubTool search = new StubTool("search_entities", Map.of(
            "entities", List.of(Map.of("entityId", 1713L, "name", "ilink"))));
        StubTool expand = new StubTool("expand_neighbors", Map.of("neighbors", List.of()));
        AgenticGraphRagService summaryService = new AgenticGraphRagService(
            mock(GraphRagService.class), mock(KgAgentTraceMapper.class), ai,
            List.of(search, expand), ledgerFactory);

        AgenticGraphRagService.AgenticAnswer answer = summaryService.query(
            "知识图谱里 iLink 平台本身（ilink 实体）直接关联了哪些竞赛？", 8L);

        assertFalse(answer.answer.contains("关系记录数为 0"), answer.answer);
        assertFalse(answer.answer.contains("均非目标类型"), answer.answer);
        assertTrue(answer.answer.contains("主体名称可能指另一个同名或近似名称的对象"),
            answer.answer);
        assertTrue(answer.trace.stream().anyMatch(row ->
            "CLARIFICATION".equals(row.get("answerMode"))
                && "SUBJECT_DISAMBIGUATION_REQUIRED".equals(row.get("coverageReason"))));
        verifyNoInteractions(ai);
    }

    @Test
    void zeroTargetRelationsDoNotHideAnAuthorizedSourceThatAnswersTheRequest() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        EvidenceLedger.ChunkFact source = new EvidenceLedger.ChunkFact(380L, "POST", 1L,
            "iLink-平台介绍", "参与“互联网+”创新创业大赛、挑战杯、数学建模竞赛。", 101, 200);
        when(ledger.chunk(380L)).thenReturn(java.util.Optional.of(source));
        when(ledger.containsChunk(380L)).thenReturn(true);
        List<EvidenceLedger.EntityFact> entities = new ArrayList<>();
        entities.add(new EvidenceLedger.EntityFact(1713L, "ilink", "POST", ""));
        List<EvidenceLedger.RelationFact> relations = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            long relationId = 945L + i;
            long entityId = 1714L + i;
            entities.add(new EvidenceLedger.EntityFact(entityId, "Skill " + (i + 1), "SKILL", ""));
            relations.add(new EvidenceLedger.RelationFact(relationId, 1713L, entityId,
                "MENTIONS", null, null, null, null, ""));
        }
        when(ledger.entities()).thenReturn(entities);
        when(ledger.relations()).thenReturn(relations);
        for (EvidenceLedger.EntityFact entity : entities) {
            when(ledger.entity(entity.id())).thenReturn(java.util.Optional.of(entity));
        }
        for (EvidenceLedger.RelationFact relation : relations) {
            when(ledger.relation(relation.id())).thenReturn(java.util.Optional.of(relation));
        }
        when(ledger.completeRelations("expand_neighbors", 1713L)).thenReturn(true);
        when(ledger.chunks()).thenReturn(List.of(source));

        EvidenceLedgerFactory ledgerFactory = mock(EvidenceLedgerFactory.class);
        when(ledgerFactory.create()).thenReturn(ledger);
        StubTool search = new StubTool("search_entities", Map.of(
            "entities", List.of(Map.of("entityId", 1713L, "name", "ilink"))));
        StubTool expand = new StubTool("expand_neighbors", Map.of("neighbors", List.of()));
        AgenticGraphRagService summaryService = new AgenticGraphRagService(
            mock(GraphRagService.class), mock(KgAgentTraceMapper.class), ai,
            List.of(search, expand), ledgerFactory);
        when(ai.chat(anyList(), anyInt())).thenAnswer(stagedAnswer(
            "原文提到 iLink 参与“互联网+”创新创业大赛、挑战杯、数学建模竞赛。c#380"));

        AgenticGraphRagService.AgenticAnswer answer = summaryService.query(
            "知识图谱里 iLink 平台本身（ilink 实体）直接关联了哪些竞赛？", 8L);

        assertTrue(answer.answer.contains("挑战杯"), answer.answer);
        assertTrue(answer.answer.contains("互联网+"), answer.answer);
        assertTrue(answer.answer.contains("数学建模"), answer.answer);
        assertFalse(answer.answer.contains("主体名称可能指另一个同名或近似名称的对象"),
            answer.answer);
        assertTrue(answer.trace.stream().anyMatch(row ->
            "SOURCE_SCOPED_RECORD_ANSWER".equals(row.get("answerMode"))
                && "SOURCE_SCOPED".equals(row.get("answerCoverage"))
                && "PARTIAL".equals(row.get("graphCoverage"))), answer.trace.toString());
        assertTrue(answer.trace.stream().anyMatch(row -> {
            Object disposition = row.get("zeroTargetRelationDisposition");
            return disposition instanceof Map<?, ?> map
                && "CONTINUE_WITH_AUTHORIZED_SOURCE".equals(map.get("decision"));
        }), answer.trace.toString());
    }

    @Test
    void classificationCrosschecksDocumentEvidence() {
        StubTool direct = new StubTool("search_documents", Map.of("chunks", List.of(
            Map.of("chunkId", 99L, "docType", "POST", "docId", 6L,
                "title", "技术分享", "text", "LitePT 使用 PointROPE 处理点云。"))));
        service = new AgenticGraphRagService(mock(GraphRagService.class),
            mock(KgAgentTraceMapper.class), ai,
            List.of(search, neighbors, document, direct), factory);
        when(ai.chat(anyList(), anyInt()))
            .thenReturn(chat("Thought: 已知\nFinal Answer: LitePT 论文 #42 与点云 #43 是 MENTIONS 关系。r#7"))
            .thenReturn(chat("{\"verdict\":\"SUPPORTED\"}"));

        service.query("LitePT 论文关联的技能中，哪些不属于 Java 生态？", 8L);

        assertEquals(1, direct.calls.size());
        assertTrue(String.valueOf(direct.calls.get(0).get("query")).contains("LitePT"));
    }

    private static AiClient.AiChatResult chat(String text) {
        return new AiClient.AiChatResult(text, 1, 1);
    }

    /**
     * The production loop reuses one chat entry point for three stages: the ReAct evidence
     * decision, the answer draft and the semantic verdict. A stub that answers by call order
     * hands the draft to the decision stage as soon as the flow adds or reorders a call, so
     * these tests dispatch on the stage system prompt instead.
     */
    private static Answer<AiClient.AiChatResult> stagedAnswer(String draft) {
        return stagedAnswer(draft, "{\"facts\":\"SUPPORTED\",\"coverage\":\"COMPLETE\","
            + "\"unsupported\":[],\"missing\":[]}");
    }

    private static Answer<AiClient.AiChatResult> stagedAnswer(String draft, String verdict) {
        return invocation -> {
            // Bind to Object first: plain String.valueOf(getArgument(0)) infers char[] and
            // inserts a failing checkcast against the message list.
            Object payload = invocation.getArgument(0);
            String request = String.valueOf(payload);
            if (request.contains("你是事实核验器")) return chat(verdict);
            if (request.contains("决定是否还需补充证据"))
                return chat("Thought: 证据已足够\nFinal Answer: READY");
            return chat(draft);
        };
    }

    private static KgEntity entity(long id, String name, String type) {
        KgEntity row = new KgEntity();
        row.setId(id);
        row.setDisplayName(name);
        row.setEntityType(type);
        row.setScope(KgEntity.SCOPE_GLOBAL);
        row.setStatus(KgEntity.STATUS_ACTIVE);
        return row;
    }

    private static Date day(String date) {
        return Date.from(LocalDate.parse(date).atStartOfDay(KgTimeRange.ZONE).toInstant());
    }

    private static class StubTool implements GraphTool {
        final String name;
        final Object response;
        final List<Map<String, Object>> calls = new ArrayList<>();

        StubTool(String name, Object response) {
            this.name = name;
            this.response = response;
        }

        public String getName() { return name; }
        public String getDescription() { return name; }
        public String getParametersSchema() { return "{}"; }
        public Object execute(Map<String, Object> params, Long userId) {
            calls.add(params);
            return response;
        }
    }
}
