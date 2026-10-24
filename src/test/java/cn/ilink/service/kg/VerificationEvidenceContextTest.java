package cn.ilink.service.kg;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.ai.AiClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerificationEvidenceContextTest {
    private static final String QUESTION = "《演示项目》在图谱里关联的技能有哪些？";
    private static final String ANSWER = LongStream.rangeClosed(701, 710)
        .mapToObj(id -> "r#" + id).collect(Collectors.joining(" "));

    @Test
    void sharedSourcePackingRetainsUncitedCandidatesAndReachesSemanticCheck() {
        String body = "同一授权来源保留完整事实和上下文。".repeat(90);
        EvidenceLedger ledger = fixture(body, false);
        AiClient ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult(
            "{\"facts\":\"SUPPORTED\",\"coverage\":\"COMPLETE\",\"unsupported\":[],\"missing\":[]}", 1, 1));
        AtomicReference<Map<String, Object>> observed = new AtomicReference<>();
        var result = new AnswerEvidenceVerifier().verifySemantic(QUESTION, ANSWER, ledger,
            QueryEvidencePlan.fromQuestion(QUESTION), ai, observed::set);
        Map<String, Object> proof = observed.get();
        assertNotNull(proof);
        assertTrue((int) proof.get("expandedTokenCount") > EvidenceContext.MAX_BUDGET);
        assertTrue((int) proof.get("tokenCount") <= EvidenceContext.MAX_BUDGET);
        String text = (String) proof.get("text");
        assertTrue(text.contains(body));
        for (long id = 701; id <= 711; id++) assertTrue(text.contains("r#" + id), "Lost relation " + id);
        assertEquals(10, proof.get("relationCount"));
        assertEquals(1, proof.get("packedSourceSpanCount"));
        assertFalse(result.reasons().stream().anyMatch(reason -> reason.contains("EVIDENCE_BUDGET")));
        // Incomplete fixture query coverage must still fail; packing never disables that gate.
        assertFalse(result.supported());
        assertTrue(result.coverageOnlyFailure(), result.reasons().toString());
        verify(ai, times(1)).chat(anyList(), eq(AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET));
    }

    @Test
    void matchingTextInAnotherDocumentDoesNotInventASourceChunkPointer() {
        String body = "同一授权来源保留完整事实和上下文。".repeat(90);
        EvidenceLedger ledger = fixture(body, true);
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion(QUESTION);
        var checked = new AnswerEvidenceVerifier().verify(ANSWER, ledger, plan);
        var packet = VerificationEvidenceContext.pack(ledger, plan, checked,
            GraphAnswerScope.describe(QUESTION, ledger), body.repeat(20));
        assertEquals("exact-source-spans-v1", packet.packingPolicy());
        assertTrue(packet.text().contains("r#701 -> #101 技能1 原文=c#99@0:"));
        assertTrue(packet.text().contains("r#702 -> #102 技能2 原文=原文段1"));
        assertTrue(packet.text().contains("原文段1 [来源=POST:7] " + body));
    }

    @Test
    void oversizeUniqueProofStillStopsBeforeAnyModelRequest() {
        EvidenceLedger ledger = fixture("不可压缩成预算内的独立事实。".repeat(2000), false);
        AiClient ai = mock(AiClient.class);
        var result = new AnswerEvidenceVerifier().verifySemantic(QUESTION, ANSWER, ledger,
            QueryEvidencePlan.fromQuestion(QUESTION), ai);
        assertFalse(result.supported());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("EVIDENCE_BUDGET")));
        verifyNoInteractions(ai);
    }

    private static EvidenceLedger fixture(String body, boolean alternatingDocuments) {
        KgEntityMapper entities = mock(KgEntityMapper.class);
        KgRelationMapper relations = mock(KgRelationMapper.class);
        KgDocumentChunkMapper chunks = mock(KgDocumentChunkMapper.class);
        KgScopeService scopes = mock(KgScopeService.class);
        when(scopes.canSee(any(KgEntity.class), any())).thenReturn(true);
        when(scopes.canSee(any(), any(), any(), any())).thenReturn(true);
        when(scopes.canSeeChunk(any(KgDocumentChunk.class), any())).thenReturn(true);
        when(entities.selectById(42L)).thenReturn(entity(42L, "演示项目", "POST"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long i = 1; i <= 11; i++) {
            when(entities.selectById(100L + i)).thenReturn(entity(100L + i, "技能" + i, "SKILL"));
            KgRelation relation = new KgRelation();
            relation.setId(700L + i);
            relation.setSourceId(42L);
            relation.setTargetId(100L + i);
            relation.setRelationType(KgRelation.MENTIONS);
            relation.setScope(KgEntity.SCOPE_GLOBAL);
            relation.setSourceDocId(alternatingDocuments && i % 2 == 0 ? "POST:7" : "POST:6");
            relation.setFactText(body);
            when(relations.selectById(700L + i)).thenReturn(relation);
            rows.add(Map.of("relationId", 700L + i, "sourceId", 42L, "targetId", 100L + i));
        }
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(99L); chunk.setDocType("POST"); chunk.setDocId(6L);
        chunk.setTitle("演示项目"); chunk.setContent(body); chunk.setScope(KgEntity.SCOPE_GLOBAL);
        when(chunks.selectById(99L)).thenReturn(chunk);
        EvidenceLedger ledger = new EvidenceLedger(entities, relations, chunks, scopes);
        ledger.addToolResult("expand_neighbors", Map.of("neighbors", rows), 8L);
        ledger.addToolResult("search_documents", Map.of("chunks", List.of(
            Map.of("chunkId", 99L, "docType", "POST", "docId", 6L))), 8L);
        return ledger;
    }

    private static KgEntity entity(long id, String name, String type) {
        KgEntity entity = new KgEntity();
        entity.setId(id); entity.setDisplayName(name); entity.setEntityType(type);
        entity.setScope(KgEntity.SCOPE_GLOBAL); entity.setStatus(KgEntity.STATUS_ACTIVE);
        return entity;
    }
}
