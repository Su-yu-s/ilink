package cn.ilink.service.kg;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.ai.AiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;

class AnswerEvidenceVerifierTest {
    private EvidenceLedger ledger;
    private AnswerEvidenceVerifier verifier;

    @BeforeEach
    void setUp() {
        KgEntityMapper entities = mock(KgEntityMapper.class);
        KgRelationMapper relations = mock(KgRelationMapper.class);
        KgDocumentChunkMapper chunks = mock(KgDocumentChunkMapper.class);
        KgScopeService scopes = mock(KgScopeService.class);
        when(scopes.canSee(any(KgEntity.class), any())).thenReturn(true);
        when(scopes.canSee(any(), any(), any(), any())).thenReturn(true);
        when(scopes.canSeeChunk(any(KgDocumentChunk.class), any())).thenReturn(true);
        KgEntity post = entity(42, "LitePT 论文", "POST");
        KgEntity topic = entity(43, "点云", "SKILL");
        when(entities.selectById(42L)).thenReturn(post);
        when(entities.selectById(43L)).thenReturn(topic);
        KgRelation relation = new KgRelation();
        relation.setId(7L);
        relation.setSourceId(42L);
        relation.setTargetId(43L);
        relation.setRelationType(KgRelation.MENTIONS);
        relation.setScope(KgEntity.SCOPE_GLOBAL);
        relation.setValidFrom(day("2026-02-10"));
        relation.setValidTo(day("2026-03-01"));
        when(relations.selectById(7L)).thenReturn(relation);
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(99L);
        chunk.setDocType("POST");
        chunk.setDocId(6L);
        chunk.setTitle("论文分享");
        chunk.setContent("LitePT 使用 PointROPE 处理点云，界面宽度 300px。");
        chunk.setScope(KgEntity.SCOPE_GLOBAL);
        when(chunks.selectById(99L)).thenReturn(chunk);
        KgDocumentChunk viewport = new KgDocumentChunk();
        viewport.setId(100L);
        viewport.setDocType("POST");
        viewport.setDocId(7L);
        viewport.setTitle("响应式设计");
        viewport.setContent("桌面端(1440/1024)固定高度72px，移动端375px沿用原有响应式56px。");
        viewport.setScope(KgEntity.SCOPE_GLOBAL);
        when(chunks.selectById(100L)).thenReturn(viewport);
        ledger = new EvidenceLedger(entities, relations, chunks, scopes);
        ledger.addToolResult("search_entities", Map.of("entities", List.of(
            Map.of("entityId", 42L, "name", "LitePT 论文"))), 8L);
        ledger.addToolResult("expand_neighbors", Map.of("neighbors", List.of(
            Map.of("relationId", 7L, "sourceId", 42L, "targetId", 43L,
                "sourceName", "LitePT 论文", "targetName", "点云"))), 8L);
        ledger.addToolResult("get_document", Map.of("chunks", List.of(
            Map.of("chunkId", 99L, "docType", "POST", "docId", 6L))), 8L);
        ledger.addToolResult("get_document", Map.of("chunks", List.of(
            Map.of("chunkId", 100L, "docType", "POST", "docId", 7L))), 8L);
        verifier = new AnswerEvidenceVerifier();
    }

    @Test
    void realIdsNamesAndSourceFactsPass() {
        var result = verifier.verify("LitePT 论文 #42 提到了点云 #43，原文使用 PointROPE 和 300px。c#99 r#7",
            ledger, QueryEvidencePlan.fromQuestion("LitePT 论文提到什么？"));
        assertTrue(result.supported(), result.reasons().toString());
        assertTrue(result.usedEntityIds().contains(42L));
        assertTrue(result.usedChunkIds().contains(99L));
    }

    @Test
    void unknownIdAndWrongNameFail() {
        var unknown = verifier.verify("实体 #1001 支持该赛事", ledger,
            QueryEvidencePlan.fromQuestion("赛事"));
        assertFalse(unknown.supported());
        assertTrue(unknown.reasons().toString().contains("UNKNOWN_ENTITY"));

        var wrongName = verifier.verify("DataTrack 算法应用挑战赛 #42 正在举办", ledger,
            QueryEvidencePlan.fromQuestion("赛事"));
        assertFalse(wrongName.supported());
        assertTrue(wrongName.reasons().toString().contains("NAME_MISMATCH"));
    }

    @Test
    void subjectAndObjectReferencesDoNotAssertAnEntityName() {
        var result = verifier.verify("在主体 #42 的已核对关系中，对象 #43 通过 r#7 关联。", ledger,
            QueryEvidencePlan.fromQuestion("LitePT 论文与点云什么关系？"));
        assertTrue(result.supported(), result.reasons().toString());
        assertTrue(result.usedEntityIds().containsAll(List.of(42L, 43L)));
    }

    @Test
    void relationTupleRoleLabelsDoNotAssertProseNames() {
        var result = verifier.verify(
            "r#7（主体 #42 -> 候选 #43 MENTIONS）表明两者相关。",
            ledger, QueryEvidencePlan.fromQuestion("LitePT 论文与点云什么关系？"));
        assertTrue(result.supported(), result.reasons().toString());
        assertFalse(result.reasons().contains("NAME_MISMATCH:#42"));
        assertFalse(result.reasons().contains("NAME_MISMATCH:#43"));
    }

    @Test
    void inventedRelationAndWrongTimeWindowFail() {
        var invented = verifier.verify("LitePT 论文 #42 -[REQUIRES]-> 点云 #43。r#7", ledger,
            QueryEvidencePlan.fromQuestion("LitePT 论文与点云什么关系？"));
        assertFalse(invented.supported());
        assertTrue(invented.reasons().toString().contains("RELATION_MISMATCH"));

        var march = verifier.verify("2026 年 3 月，LitePT 论文 #42 提到了点云 #43。r#7", ledger,
            QueryEvidencePlan.fromQuestion("2026 年 3 月 LitePT 论文提到什么？"));
        assertFalse(march.supported());
        assertTrue(march.reasons().toString().contains("TIME_MISMATCH"));
    }

    @Test
    void relationClaimCannotUseOnlyAnEntityCitation() {
        var result = verifier.verify("LitePT 论文 #42 与点云有关。", ledger,
            QueryEvidencePlan.fromQuestion("LitePT 论文与点云有什么关系？"));
        assertFalse(result.supported());
        assertTrue(result.reasons().contains("MISSING_RELATION_EVIDENCE"));
    }

    @Test
    void nonexistentDocumentAndUnsupportedNumericFactFail() {
        var unknownDoc = verifier.verify("post_3892 分享了 LitePT", ledger,
            QueryEvidencePlan.fromQuestion("哪个帖子分享了 LitePT？"));
        assertFalse(unknownDoc.supported());
        assertTrue(unknownDoc.reasons().toString().contains("UNKNOWN_DOCUMENT"));

        var wrongNumber = verifier.verify("LitePT 论文 #42 使用了 500px 宽度。c#99", ledger,
            QueryEvidencePlan.fromQuestion("LitePT 的宽度是多少？"));
        assertFalse(wrongNumber.supported());
        assertTrue(wrongNumber.reasons().toString().contains("UNSUPPORTED_EXACT_FACT"));
    }

    @Test
    void viewportBareNumbersMayReceivePxOnlyInsideTheSameDimensionContext() {
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion("响应式页面的尺寸是多少？");
        var supported = verifier.verify(
            "桌面端视口为1440px / 1024px，固定高度72px；移动端375px，高度56px。c#100",
            ledger, plan);
        assertTrue(supported.supported(), supported.reasons().toString());

        for (String pair : List.of("1440×1024", "1440*1024", "1440 x 1024", "1440/1024 px")) {
            var result = verifier.verify("桌面端视口为" + pair + "，固定高度72px。c#100", ledger, plan);
            assertTrue(result.supported(), pair + " -> " + result.reasons());
        }
    }

    @Test
    void viewportUnitSupportDoesNotBecomeGeneralUnitNormalization() {
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion("响应式页面的尺寸是多少？");
        for (String answer : List.of(
            "桌面端视口为1440em。c#100",
            "桌面端视口为1440px / 999px。c#100",
            "桌面端视口为1441px / 1024px。c#100")) {
            var result = verifier.verify(answer, ledger, plan);
            assertFalse(result.supported(), answer);
            assertTrue(result.reasons().toString().contains("UNSUPPORTED_VIEWPORT"),
                answer + " -> " + result.reasons());
        }

        var bareWithoutDimensionContext = verifier.verify("页面展示了1440，c#100", ledger, plan);
        assertFalse(bareWithoutDimensionContext.supported(),
            "A bare number without dimension context must not acquire px semantics.");
    }

    @Test
    void genericAdviceNeedsNoPlatformCitation() {
        var result = verifier.verify("建议先核对报名要求，再安排练习计划。", ledger,
            QueryEvidencePlan.fromQuestion("如何备赛？"));
        assertTrue(result.supported());
        assertEquals(0, result.usedEntityIds().size());
    }

    @Test
    void exactQuoteQuestionRequiresVerbatimTextFromCitedSource() {
        String question = "论文分享里用哪句话说明方法？";
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion(question);
        // The fixture chunk was already loaded before this repeated, identical tool result was added;
        // the ledger intentionally stores one authoritative copy per chunk id.
        assertTrue(plan.requiresSourceText());
        ledger.addToolResult("get_document", Map.of("chunks", List.of(
            Map.of("chunkId", 99L, "docType", "POST", "docId", 6L,
                "title", "论文分享", "text", "LitePT 使用 PointROPE 处理点云"))), 8L);
        assertTrue(ledger.containsChunk(99L));
        var paraphrase = verifier.verify(
            question, "论文分享里用“方法把旋转位置编码用于点云”说明方法。c#99", ledger, plan);
        assertFalse(paraphrase.supported());
        assertTrue(paraphrase.reasons().contains("LITERAL_SOURCE_QUOTE_REQUIRED"),
            paraphrase.reasons().toString());

        var verbatim = verifier.verify(
            question, "论文分享里用“LitePT 使用 PointROPE 处理点云”说明方法。c#99", ledger, plan);
        assertTrue(verbatim.supported(), verbatim.reasons().toString());
    }

    @Test
    void quotationCheckIgnoresCodeAndUncitedChunks() {
        String question = "原文怎么表述？";
        var result = verifier.verify(
            question, "原文写“`unrelatedFunction()`”。c#99", ledger,
            QueryEvidencePlan.fromQuestion(question));
        assertTrue(result.reasons().contains("LITERAL_SOURCE_QUOTE_REQUIRED"));
    }

    @Test
    void semanticCheckRejectsUnsupportedAndUncertainParaphrases() {
        AiClient ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult("{\"verdict\":\"SUPPORTED\"}", 1, 1))
            .thenReturn(new AiClient.AiChatResult("{\"verdict\":\"UNSUPPORTED\"}", 1, 1))
            .thenReturn(new AiClient.AiChatResult("unclear", 1, 1));
        String answer = "LitePT 论文 #42 处理点云 #43。c#99 r#7";
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion("LitePT 论文提到什么？");

        assertTrue(verifier.verifySemantic(answer, ledger, plan, ai).supported());
        assertTrue(verifier.verifySemantic(answer, ledger, plan, ai).reasons().toString()
            .contains("SEMANTIC_UNSUPPORTED"));
        assertTrue(verifier.verifySemantic(answer, ledger, plan, ai).reasons().toString()
            .contains("SEMANTIC_UNCERTAIN"));
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
}
