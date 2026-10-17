package cn.ilink.service.kg;

import cn.ilink.service.ai.AiClient;
import cn.ilink.service.ai.ChatRequestBudget.BudgetStopException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** D2 dispatch and audit checks with synthetic ledgers and mocked providers. */
class GraphClassificationModelFlowTest {
    @Test void supportedDecisionUsesOneGenerateOneVerifyAndProgramBoundFinalCitations() {
        var f = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class); var trace = trace();
        when(ai.chat(anyList(), anyInt())).thenReturn(response(GraphClassificationFlowTest.PARTITION, "stop"),
            response(GraphClassificationFlowTest.SUPPORTED, "stop"));
        var result = run(f, ai, trace);
        assertTrue(result.supported()); assertEquals("COMPLETE", result.coverage());
        assertTrue(result.answer().contains("前端组件甲 [r#10]"));
        assertTrue(result.answer().contains("存储服务乙 [r#11]"));
        assertEquals(List.of(1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET), limits(ai, 2));
        assertTrue(trace.stream().anyMatch(row -> "D-stage-2".equals(row.get("strategyCandidate"))));
        assertEquals(ClassificationModelPacket.POLICY, last(trace, "verificationEvidence").get("packingPolicy"));
    }

    @Test void actualD1SeedAndItsC2SeedRemainSeparatelyAuditable() {
        var trace = beforeChat(GraphClassificationFlowTest.fixture()); var audit = last(trace, "classificationSelection");
        var source = map(audit.get("sourcePacking")); var model = map(audit.get("modelContext"));
        assertEquals(source, model.get("seedSelection"));
        assertEquals(source.get("proofIds"), model.get("seedProofIds"));
        var c2 = map(audit.get(Boolean.TRUE.equals(audit.get("budgetExpansionAdopted"))
            ? "expandedSelection" : "defaultSelection"));
        assertEquals(c2, source.get("seedSelection"));
    }

    @Test void allD1ProofIdsAndAllFourCoverageDimensionsSurviveModelProjection() {
        var trace = beforeChat(GraphClassificationFlowTest.fixture()); var audit = last(trace, "classificationSelection");
        var model = map(audit.get("modelContext")); var seed = map(model.get("seedSelection"));
        assertTrue(((Collection<?>) model.get("selectedProofIds")).containsAll((Collection<?>) seed.get("proofIds")));
        for (String dimension : List.of("Mention", "Relevant", "Context", "Bound"))
            assertTrue(((Collection<?>) model.get("sent" + dimension + "CandidateIds"))
                .containsAll((Collection<?>) seed.get("sent" + dimension + "CandidateIds")), dimension);
    }

    @Test void actualGenerationTextContainsOneCanonicalProjectionAndNoOldCatalogAppend() {
        var f = GraphClassificationFlowTest.fixture(); var trace = beforeChat(f);
        var generation = last(trace, "generationEvidence"); var projection = ClassificationModelContext.project(f.catalog());
        String text = generation.get("text").toString();
        assertTrue(text.startsWith(projection.text()));
        assertEquals(projection.metadata(), generation.get("modelProjection"));
        assertFalse(text.contains(f.catalog().text()));
        assertEquals(EvidenceContext.tokens(text), generation.get("tokenCount"));
        assertEquals(ClassificationCoverageProofs.POLICY_MODEL_CONTEXT, generation.get("packingPolicy"));
    }

    @Test void modelGenerationProofPoolAndSelectionAreTheSameExactSourceIds() {
        var f = GraphClassificationFlowTest.fixture(); var trace = beforeChat(f);
        var generation = last(trace, "generationEvidence"); var selection = last(trace, "classificationSelection");
        assertEquals(selection.get("selectedProofIds"), generation.get("proofIds"));
        assertEquals(selection.get("tokenCount"), generation.get("tokenCount"));
        @SuppressWarnings("unchecked") var ids = (List<String>) generation.get("proofIds");
        var prepared = ClassificationCoverageProofs.prepare(GraphClassificationFlowTest.QUESTION, f.catalog(), f.ledger());
        assertTrue(prepared.proofs().validate(f.ledger(), ids).isEmpty());
        assertTrue(generation.get("text").toString().contains(ClassificationModelContext.sources(prepared.proofs(), ids).text()));
    }

    @Test void malformedGenerationAndRewriteNeverDispatchSemanticVerificationOrPublishPrefix() {
        var f = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class); var trace = trace();
        when(ai.chat(anyList(), anyInt())).thenReturn(response("{\"groups\":[", "length"), response("{\"groups\":[", "length"));
        var result = run(f, ai, trace);
        assertFalse(result.supported()); assertEquals("", result.answer());
        assertEquals(List.of(1200, 1200), limits(ai, 2));
        assertFalse(trace.stream().anyMatch(row -> row.containsKey("verificationEvidence")));
    }

    @Test void incompleteInventoryCannotBeReopenedByTheCompactProjection() {
        var f = GraphClassificationFlowTest.fixture(); var c = f.catalog(); var ai = mock(AiClient.class);
        var partial = new GraphClassificationContract.Catalog(c.requested(), c.skipReason(), c.candidates(), c.text(),
            c.metadata(), c.queryComplete(), false, c.focusText());
        var result = run(new GraphClassificationFlowTest.Fixture(f.ledger(), f.plan(), partial, f.proofs()), ai, trace());
        assertFalse(result.supported()); assertEquals("CLASSIFICATION_INVENTORY_INCOMPLETE", result.coverageReason());
        verifyNoInteractions(ai);
    }

    @Test void nonContiguousTypedCandidateNumbersStopBeforePaidGeneration() {
        var f = GraphClassificationFlowTest.fixture(); var c = f.catalog(); var member = c.candidates().get(0);
        var wrong = new GraphClassificationContract.Candidate(99, member.anchorId(), member.otherId(), member.anchorName(),
            member.name(), member.entityType(), member.relationType(), member.direction(), member.relationIds());
        var forged = new GraphClassificationContract.Catalog(c.requested(), c.skipReason(), List.of(wrong), c.text(),
            c.metadata(), c.queryComplete(), c.inventoryComplete(), c.focusText()); var ai = mock(AiClient.class);
        var result = run(new GraphClassificationFlowTest.Fixture(f.ledger(), f.plan(), forged, f.proofs()), ai, trace());
        assertFalse(result.supported()); assertTrue(result.coverageReason().contains("MODEL_PROJECTION_INVALID"));
        verifyNoInteractions(ai);
    }

    @Test void temporalQueriesExplicitlySkipTheNewClassificationPath() {
        var f = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        var temporal = QueryEvidencePlan.fromQuestion("2026年3月应用设计关联哪些竞赛？"); assertTrue(temporal.requiresTemporal());
        var result = new GraphClassificationFlow().runModelContext(GraphClassificationFlowTest.QUESTION, temporal,
            f.ledger(), f.catalog(), ai, trace());
        assertFalse(result.supported()); assertEquals("CLASSIFICATION_SKIPPED:TEMPORAL_QUERY", result.coverageReason());
        verifyNoInteractions(ai);
    }

    @Test void noAuthorizedSourceCannotBeReplacedByTheCompactCatalogAsEvidence() {
        var f = GraphClassificationFlowTest.fixture(); when(f.ledger().chunks()).thenReturn(List.of()); var ai = mock(AiClient.class);
        var result = run(f, ai, trace());
        assertFalse(result.supported()); assertEquals("CLASSIFICATION_NO_AUTHORIZED_PROOF", result.coverageReason());
        verifyNoInteractions(ai);
    }

    @Test void truncatedVerifierStillRequiresOneRewriteAndItsOwnExtraVerification() {
        var f = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class); var trace = trace();
        when(ai.chat(anyList(), anyInt())).thenReturn(response(GraphClassificationFlowTest.PARTITION, "stop"),
            response("{\"facts\":", "length"), response(GraphClassificationFlowTest.PARTITION, "stop"),
            response(GraphClassificationFlowTest.SUPPORTED, "stop"));
        assertTrue(run(f, ai, trace).supported()); assertEquals(List.of(1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET, 1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET), limits(ai, 4));
        assertTrue(trace.stream().anyMatch(row -> "EXTRA".equals(row.get("verificationStage"))));
    }

    @Test void semanticRejectionAfterBothProposalsKeepsUnverifiedClassificationUnpublished() {
        var f = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class); var trace = trace();
        String unsupported = "{\"facts\":\"UNSUPPORTED\",\"coverage\":\"COMPLETE\",\"unsupported\":[\"成员依据不足\"],\"missing\":[]}";
        when(ai.chat(anyList(), anyInt())).thenReturn(response(GraphClassificationFlowTest.PARTITION, "stop"),
            response(unsupported, "stop"), response(GraphClassificationFlowTest.PARTITION, "stop"), response(unsupported, "stop"));
        var result = run(f, ai, trace); assertFalse(result.supported()); assertEquals("", result.answer());
        assertEquals(List.of(1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET, 1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET), limits(ai, 4));
        assertFalse(trace.stream().anyMatch(row -> "STRUCTURED_GRAPH_CLASSIFICATION".equals(row.get("answerMode"))));
    }

    @Test void actualD2M7RewriteRangesLeaveExactlyTheObservedFiveGaps() {
        var candidates = java.util.stream.IntStream.rangeClosed(1, 88).mapToObj(number ->
            new GraphClassificationContract.Candidate(number, 1743L, 1700L + number, "智能学习助手", "c" + number,
                "SKILL", "MENTIONS", "OUT", List.of(1000L + number))).toList();
        var catalog = new GraphClassificationContract.Catalog(true, "", candidates, "", Map.of(), true, true, "");
        String d2Rewrite = "{\"groups\":[{\"s\":\"I\",\"reason\":\"MATCH\","
            + "\"ids\":\"4-6,8-12,13-20,21-28,33-49,50-68,69-71,73-88\",\"proofs\":[\"p7\"]},"
            + "{\"s\":\"E\",\"reason\":\"OUTSIDE_TYPE\",\"ids\":\"1,2,3,7\",\"proofs\":[\"p7\"]}]}";
        var parsed = GraphClassificationContract.parseDecisions(d2Rewrite, catalog);
        assertFalse(parsed.complete());
        assertEquals(List.of(29, 30, 31, 32, 72), parsed.diagnosticsMetadata().get("missingCandidateIds"));
    }

    private static List<Map<String, Object>> beforeChat(GraphClassificationFlowTest.Fixture f) {
        var ai = mock(AiClient.class); var trace = trace();
        when(ai.chat(anyList(), anyInt())).thenThrow(new BudgetStopException("SYNTHETIC_D2_PRE_CHAT_STOP"));
        assertThrows(BudgetStopException.class, () -> run(f, ai, trace));
        verify(ai, times(1)).chat(anyList(), eq(1200)); return trace;
    }
    private static GraphClassificationFlow.Outcome run(GraphClassificationFlowTest.Fixture f, AiClient ai, List<Map<String, Object>> trace) {
        return new GraphClassificationFlow().runModelContext(GraphClassificationFlowTest.QUESTION, f.plan(), f.ledger(), f.catalog(), ai, trace);
    }
    private static AiClient.AiChatResult response(String body, String finish) { return GraphClassificationFlowTest.response(body, finish); }
    private static ArrayList<Map<String, Object>> trace() { return new ArrayList<>(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private static Map<String, Object> last(List<Map<String, Object>> trace, String key) {
        return trace.stream().filter(row -> row.containsKey(key)).reduce((first, second) -> second).map(row -> map(row.get(key))).orElseThrow();
    }
    private static List<Integer> limits(AiClient ai, int count) {
        var limits = ArgumentCaptor.forClass(Integer.class); verify(ai, times(count)).chat(anyList(), limits.capture());
        return limits.getAllValues();
    }
}
