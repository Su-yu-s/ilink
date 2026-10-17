package cn.ilink.service.kg;

import cn.ilink.mapper.KgAgentTraceMapper;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.ai.ChatRequestBudget;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises dispatch order and production gates with isolated ledger/provider dependencies. */
class GraphClassificationFlowTest {
    static final String QUESTION = "《应用设计》在图谱里关联的技术栈中，哪些属于前端生态？";
    static final String PARTITION = "{\"groups\":[{\"s\":\"I\",\"reason\":\"MATCH\",\"ids\":\"1\",\"proofs\":[\"p1\"]},"
        + "{\"s\":\"E\",\"reason\":\"OUTSIDE_ATTRIBUTE\",\"ids\":\"2\",\"proofs\":[\"p2\"]}]}";
    static final String UNCERTAIN = "{\"groups\":[{\"s\":\"U\",\"reason\":\"INSUFFICIENT\",\"ids\":\"1-2\",\"proofs\":[]}]}";
    static final String SUPPORTED = "{\"facts\":\"SUPPORTED\",\"coverage\":\"COMPLETE\",\"unsupported\":[],\"missing\":[]}";

    @Test
    void completePartitionIsVerifiedOnceAndRenderedFromLedgerWithBothKindsOfCitations() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        when(ai.chat(anyList(), anyInt())).thenReturn(response(PARTITION, "stop"), response(SUPPORTED, "stop"));
        var answer = run(fixture, ai, trace);
        assertTrue(answer.supported()); assertEquals("COMPLETE", answer.coverage());
        assertTrue(answer.answer().contains("前端组件甲 [r#10]"));
        assertTrue(answer.answer().contains("存储服务乙 [r#11]"));
        assertTrue(answer.answer().contains("分类依据：c#20"));
        assertEquals(Set.of(10L, 11L), answer.verification().usedRelationIds());
        assertEquals(List.of(1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET), limits(ai, 2));
        assertTrue(trace.stream().anyMatch(step -> "VERIFY".equals(step.get("verificationStage"))));
        assertTrue(trace.stream().anyMatch(step -> "C-stage-2".equals(step.get("strategyCandidate"))));
        Map<?, ?> coverage = trace.stream().filter(step -> step.containsKey("classificationEvidenceCoverage"))
            .map(step -> (Map<?, ?>) step.get("classificationEvidenceCoverage")).reduce((a, b) -> b).orElseThrow();
        assertEquals(List.of(1, 2), coverage.get("sentRelevantCandidateIds"));
        assertEquals(Boolean.TRUE, coverage.get("semanticVerdictAccepted"));
        Map<?, ?> responseAudit = trace.stream().filter(step -> step.containsKey("classificationVerificationResult"))
            .map(step -> (Map<?, ?>) step.get("classificationVerificationResult")).findFirst().orElseThrow();
        assertEquals("ACCEPTED", responseAudit.get("status"));
        assertEquals(SUPPORTED, responseAudit.get("rawVerdict"));
        assertTrue(trace.stream().anyMatch(step -> step.containsKey("verificationEvidence")
            && !step.containsKey("classificationVerificationResult")));
    }

    @Test
    void failedVerifierResponseRemainsAuditableWhenRewriteIntroducesAnOmission() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        String omitted = "{\"groups\":[{\"s\":\"I\",\"reason\":\"MATCH\",\"ids\":\"1\",\"proofs\":[\"p1\"]}]}";
        when(ai.chat(anyList(), anyInt())).thenReturn(response(PARTITION, "stop"),
            response("{\"facts\":", "length"), response(omitted, "stop"));
        var result = run(fixture, ai, trace);
        assertFalse(result.supported()); assertEquals("", result.answer());
        assertEquals(List.of(1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET, 1200), limits(ai, 3));
        List<Map<String, Object>> audits = trace.stream()
            .filter(step -> step.containsKey("classificationVerificationResult")).toList();
        assertEquals(1, audits.size()); assertEquals("GENERATE", audits.get(0).get("proposalStage"));
        Map<?, ?> audit = (Map<?, ?>) audits.get(0).get("classificationVerificationResult");
        assertEquals("NON_STOP_FINISH", audit.get("status")); assertEquals("length", audit.get("finishReason"));
        assertEquals("{\"facts\":", audit.get("rawVerdict")); assertEquals(Boolean.FALSE, audit.get("semanticParsed"));
        assertTrue(trace.toString().contains("CLASSIFICATION_OMITTED_CANDIDATE:2"));
        assertTrue(trace.stream().noneMatch(step -> "EXTRA".equals(step.get("verificationStage"))));
    }

    @Test
    void crossMemberProofReuseIsRejectedBeforeSemanticDispatchInBothProposalSlots() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        String incorrect = PARTITION.replace("\"p2\"", "\"p1\"");
        when(ai.chat(anyList(), anyInt())).thenReturn(response(incorrect, "stop"), response(incorrect, "stop"));
        var result = run(fixture, ai, trace);
        assertFalse(result.supported()); assertEquals("", result.answer());
        assertEquals(List.of(1200, 1200), limits(ai, 2));
        assertTrue(trace.toString().contains("CLASSIFICATION_MEMBER_EVIDENCE_MISSING:2"));
        assertTrue(trace.stream().noneMatch(step -> step.containsKey("classificationVerificationResult")));
        assertTrue(trace.stream().noneMatch(step -> step.containsKey("verificationEvidence")));
        assertTrue(trace.stream().noneMatch(step -> "STRUCTURED_GRAPH_CLASSIFICATION".equals(step.get("answerMode"))));
    }

    @Test
    void memberRepairUsesOnlyActuallySentEligibleIdsAndPreservesSingleRewriteSlot() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        String incorrect = PARTITION.replace("\"p2\"", "\"p1\"");
        when(ai.chat(anyList(), anyInt())).thenReturn(response(incorrect, "stop"),
            response(PARTITION, "stop"), response(SUPPORTED, "stop"));
        assertTrue(run(fixture, ai, trace).supported());
        assertEquals(List.of(1200, 1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET), limits(ai, 3));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messages = ArgumentCaptor.forClass(List.class);
        verify(ai, times(3)).chat(messages.capture(), anyInt());
        String revision = messages.getAllValues().get(1).get(1).get("content");
        assertTrue(revision.contains("candidateId=2")); assertTrue(revision.contains("status=SENT_NOT_CITED"));
        assertTrue(revision.contains("sentRelevantProofIds=[p2]"));
        assertFalse(revision.contains("availableRelevantProofIds="));
        assertTrue(trace.stream().noneMatch(step -> "VERIFY".equals(step.get("verificationStage"))));
        assertTrue(trace.stream().anyMatch(step -> "EXTRA".equals(step.get("verificationStage"))));
    }

    @Test
    void unparsedPrefixDoesNotProduceInventedMemberRepairHints() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        when(ai.chat(anyList(), anyInt())).thenReturn(response("{\"groups\":[", "length"),
            response(PARTITION, "stop"), response(SUPPORTED, "stop"));
        assertTrue(run(fixture, ai, trace).supported());
        Object hints = trace.stream().filter(step -> step.containsKey("classificationRepairHints"))
            .map(step -> step.get("classificationRepairHints")).findFirst().orElseThrow();
        assertEquals(List.of(), hints);
        assertEquals(List.of(1200, 1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET), limits(ai, 3));
    }

    @Test
    void acceptedUncertainPartitionRetainsSemanticVisibilityLimitationAndPartialCoverage() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        when(ai.chat(anyList(), anyInt())).thenReturn(response(UNCERTAIN, "stop"), response(SUPPORTED, "stop"));
        var answer = run(fixture, ai, trace);
        assertEquals("PARTIAL", answer.coverage());
        Map<?, ?> members = trace.stream().filter(step -> step.containsKey("classificationMemberEvidence"))
            .map(step -> (Map<?, ?>) step.get("classificationMemberEvidence")).findFirst().orElseThrow();
        assertEquals("PROPOSAL_CITED_PROOF_POOL_ONLY", members.get("semanticSourceScope"));
        assertEquals(Boolean.FALSE, members.get("semanticVisibilityCertified"));
        assertEquals(List.of(1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET), limits(ai, 2));
    }

    @Test
    void validJsonWithLengthFinishStillConsumesSingleRewriteAndNeverFirstVerify() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        when(ai.chat(anyList(), anyInt())).thenReturn(response(PARTITION, "length"),
            response(PARTITION, "stop"), response(SUPPORTED, "stop"));
        assertTrue(run(fixture, ai, trace).supported());
        assertEquals(List.of(1200, 1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET), limits(ai, 3));
        assertEquals(1, trace.stream().filter(step -> step.containsKey("rewriteResult")).count());
        assertTrue(trace.stream().noneMatch(step -> "VERIFY".equals(step.get("verificationStage"))));
        assertTrue(trace.stream().anyMatch(step -> "EXTRA".equals(step.get("verificationStage"))));
    }

    @Test
    void omissionIsRejectedBeforeSemanticRequestAndRewriteMustCoverTheWholeCatalog() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        String omitted = "{\"groups\":[{\"s\":\"I\",\"reason\":\"MATCH\",\"ids\":\"1\",\"proofs\":[\"p1\"]}]}";
        when(ai.chat(anyList(), anyInt())).thenReturn(response(omitted, "stop"),
            response(PARTITION, "stop"), response(SUPPORTED, "stop"));
        assertTrue(run(fixture, ai, trace).supported());
        assertEquals(List.of(1200, 1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET), limits(ai, 3));
        assertTrue(trace.toString().contains("CLASSIFICATION_OMITTED_CANDIDATE:2"));
    }

    @Test
    void secondProtocolFailureCannotStartAnotherGenerationOrReleaseDraft() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        when(ai.chat(anyList(), anyInt())).thenReturn(response("{\"groups\":[", "length"),
            response(PARTITION, "length"));
        var answer = run(fixture, ai, trace);
        assertFalse(answer.supported()); assertEquals("", answer.answer());
        assertEquals(List.of(1200, 1200), limits(ai, 2));
        assertTrue(trace.stream().noneMatch(step -> step.containsKey("verificationEvidence")));
    }

    @Test
    void unsupportedSemanticClassificationCanUseOnlyExistingRewriteAndExtraSlots() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        when(ai.chat(anyList(), anyInt())).thenReturn(response(PARTITION, "stop"),
            response("{\"facts\":\"UNSUPPORTED\",\"coverage\":\"COMPLETE\",\"unsupported\":[\"分类需修正\"],\"missing\":[]}", "stop"),
            response(PARTITION, "stop"), response(SUPPORTED, "stop"));
        assertTrue(run(fixture, ai, trace).supported());
        assertEquals(List.of(1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET, 1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET), limits(ai, 4));
    }

    @Test
    void allUncertainIsAnAuditablePartialPartitionWithRealAssociationCitations() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        when(ai.chat(anyList(), anyInt())).thenReturn(response(UNCERTAIN, "stop"), response(SUPPORTED, "stop"));
        var answer = run(fixture, ai, trace);
        assertTrue(answer.supported()); assertEquals("PARTIAL", answer.coverage());
        assertTrue(answer.answer().contains("部分分类结果；尚不能完整回答"));
        assertEquals("CLASSIFICATION_UNCERTAIN_CANDIDATES", answer.coverageReason());
        assertEquals(Set.of(10L, 11L), answer.verification().usedRelationIds());
    }

    @Test
    void unavailableUsageBudgetStopPropagatesWithoutDispatchingRewrite() {
        var fixture = fixture(); var ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt())).thenThrow(new ChatRequestBudget.BudgetStopException("USAGE_UNKNOWN"));
        assertThrows(ChatRequestBudget.BudgetStopException.class, () -> run(fixture, ai, trace()));
        verify(ai, times(1)).chat(anyList(), eq(1200));
    }

    @Test
    void providerDispatchUsesTheBoundGenerateAndVerifyStageLimits() {
        var fixture = fixture(); var ai = mock(AiClient.class); var budget = new ChatRequestBudget();
        var responses = List.of(response(PARTITION, "stop"), response(SUPPORTED, "stop"));
        var position = new java.util.concurrent.atomic.AtomicInteger();
        when(ai.chat(anyList(), anyInt())).thenAnswer(call -> {
            assertEquals((int) call.getArgument(1), budget.completionLimit(9999));
            return responses.get(position.getAndIncrement());
        });
        try (ChatRequestBudget.Scope ignored = budget.bind()) { assertTrue(run(fixture, ai, trace()).supported()); }
        assertEquals(2, position.get()); assertNull(ChatRequestBudget.current());
    }

    @Test
    void inventoryTruncationStopsBeforeAnyChatInsteadOfUsingFreeClassification() {
        var fixture = fixture(); var source = fixture.catalog(); var ai = mock(AiClient.class);
        var partial = new GraphClassificationContract.Catalog(true, "", source.candidates(), source.text(),
            source.metadata(), true, false, source.focusText());
        var result = new GraphClassificationFlow().run(QUESTION, fixture.plan(), fixture.ledger(), partial, ai, trace());
        assertFalse(result.supported()); verifyNoInteractions(ai);
    }

    @Test
    void publicAgenticEntryUsesStructuredProtocolAndKeepsToolAndRoundCounters() {
        var fixture = fixture(); var ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt())).thenReturn(response(PARTITION, "stop"), response(SUPPORTED, "stop"));
        EvidenceLedgerFactory factory = mock(EvidenceLedgerFactory.class);
        when(factory.create()).thenReturn(fixture.ledger());
        var service = new AgenticGraphRagService(mock(GraphRagService.class), mock(KgAgentTraceMapper.class), ai, List.of(), factory);
        var answer = service.query(QUESTION, 8L);
        assertTrue(answer.answer.contains("前端组件甲"));
        assertTrue(answer.trace.stream().anyMatch(step -> "STRUCTURED_GRAPH_CLASSIFICATION".equals(step.get("answerMode"))));
        assertEquals(List.of(1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET), limits(ai, 2));
        assertTrue(answer.toolCalls <= 8); assertNull(ChatRequestBudget.current());
    }

    @Test
    void legacyFreeLabelResponseIsRejectedBeforePaidVerificationAndGetsOneClosedDecisionRewrite() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        String question = "《应用设计》在图谱里关联的技术栈中，哪些不属于 Orion 生态？";
        String legacy = "{\"groups\":[{\"s\":\"I\",\"role\":\"Orion生态组件\",\"ids\":\"1-2\","
            + "\"proofs\":[\"p1\",\"p2\"],\"why\":\"属于该生态\"}]}";
        when(ai.chat(anyList(), anyInt())).thenReturn(response(legacy, "stop"),
            response(UNCERTAIN, "stop"), response(SUPPORTED, "stop"));
        var result = new GraphClassificationFlow().run(question, QueryEvidencePlan.fromQuestion(question),
            fixture.ledger(), fixture.catalog(), ai, trace);
        assertTrue(result.supported()); assertEquals("PARTIAL", result.coverage());
        assertEquals(List.of(1200, 1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET), limits(ai, 3));
        assertTrue(trace.toString().contains("CLOSED_DECISION_FIELDS_REQUIRED"));
        assertFalse(result.answer().contains("Orion生态组件"));
        assertTrue(trace.stream().noneMatch(step -> "VERIFY".equals(step.get("verificationStage"))));
        assertTrue(trace.stream().anyMatch(step -> "EXTRA".equals(step.get("verificationStage"))));
        assertTrue(trace.stream().filter(step -> step.containsKey("classificationDisposition"))
            .map(step -> (Map<?, ?>) step.get("classificationDisposition"))
            .anyMatch(state -> Boolean.TRUE.equals(state.get("semanticVerdictAccepted"))));
        assertTrue(trace.stream().filter(step -> step.containsKey("classificationDisposition"))
            .map(step -> (Map<?, ?>) step.get("classificationDisposition"))
            .allMatch(state -> Boolean.FALSE.equals(state.get("uncertaintySemanticallyVerified"))));
    }

    @Test
    void rewriteReceivesSpecificReasonsAndDuplicateOwnersWithoutProgramReassignment() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        String invalid = PARTITION.replace("\"ids\":\"2\"", "\"ids\":\"1-2\"")
            .replace("\"p1\"", "\"p1:说明\"");
        when(ai.chat(anyList(), anyInt())).thenReturn(response(invalid, "stop"), response(invalid, "stop"));
        assertFalse(run(fixture, ai, trace).supported());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messages = ArgumentCaptor.forClass(List.class);
        verify(ai, times(2)).chat(messages.capture(), eq(1200));
        String rewrite = messages.getAllValues().get(1).get(1).get("content");
        assertTrue(rewrite.contains("CLASSIFICATION_GROUP:1:PROOFS:1:FORMAT_EXPECTED_SINGLE_P_ID"));
        assertTrue(rewrite.contains("candidateId=1"));
        assertTrue(rewrite.contains("groupIndexes=[1, 2]"));
        assertTrue(rewrite.contains("不得由程序代选归属"));
        assertTrue(trace.stream().noneMatch(step -> step.containsKey("verificationEvidence")));
        assertTrue(trace.stream().filter(step -> step.containsKey("classificationDisposition"))
            .map(step -> (Map<?, ?>) step.get("classificationDisposition"))
            .allMatch(state -> Boolean.FALSE.equals(state.get("uncertaintySemanticallyVerified"))));
    }

    @Test
    void generationAndSemanticVerificationShareTheSameQueryDerivedCriteria() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        when(ai.chat(anyList(), anyInt())).thenReturn(response(PARTITION, "stop"), response(SUPPORTED, "stop"));
        assertTrue(run(fixture, ai, trace).supported());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messages = ArgumentCaptor.forClass(List.class);
        verify(ai, times(2)).chat(messages.capture(), anyInt());
        String criteria = ClassificationCriteria.fromQuestion(QUESTION).instruction();
        assertTrue(messages.getAllValues().get(0).get(0).get("content").contains(criteria));
        assertTrue(messages.getAllValues().get(1).get(1).get("content").contains(criteria));
        String generation = messages.getAllValues().get(0).get(0).get("content");
        assertTrue(generation.contains("MATCH"));
        assertTrue(generation.contains("OUTSIDE_ATTRIBUTE"));
        assertTrue(generation.contains("OUTSIDE_TYPE"));
        assertTrue(generation.contains("INSUFFICIENT"));
        assertTrue(generation.contains("禁止role/why/解释/名称"));
        assertTrue(trace.stream().anyMatch(step -> step.containsKey("classificationCriteria")));
    }

    @Test
    void rewriteOmissionsAreNotReportedAsDiagnosticsKnownBeforeTheRewrite() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        String omitted = "{\"groups\":[{\"s\":\"I\",\"reason\":\"MATCH\",\"ids\":\"1\",\"proofs\":[\"p1\"]}]}";
        when(ai.chat(anyList(), anyInt())).thenReturn(response("{\"groups\":[", "length"), response(omitted, "stop"));
        assertFalse(run(fixture, ai, trace).supported());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messages = ArgumentCaptor.forClass(List.class);
        verify(ai, times(2)).chat(messages.capture(), eq(1200));
        String rewriteInput = messages.getAllValues().get(1).get(1).get("content");
        assertTrue(rewriteInput.contains("CLASSIFICATION_INVALID_JSON"));
        assertFalse(rewriteInput.contains("CLASSIFICATION_OMITTED_CANDIDATE:2"));
        var attempts = trace.stream().filter(step -> step.containsKey("classificationDisposition"))
            .map(step -> (Map<?, ?>) step.get("classificationDisposition")).toList();
        assertEquals("GENERATE", attempts.get(0).get("proposalStage"));
        assertEquals("UNAVAILABLE", attempts.get(0).get("proposalParseStatus"));
        assertNull(attempts.get(0).get("criterionConsistent"));
        assertEquals("REWRITE", attempts.get(1).get("proposalStage"));
        assertTrue(attempts.get(1).get("failures").toString().contains("CLASSIFICATION_OMITTED_CANDIDATE:2"));
        assertTrue(trace.stream().noneMatch(step -> step.containsKey("verificationEvidence")));
    }

    @Test
    void shortFreeDescriptionFieldsAreRejectedWithoutPublishingEitherAttempt() {
        for (String description : List.of("\"role\":\"前端\"", "\"why\":\"原文支持\"")) {
            var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
            String extraField = PARTITION.replace("\"reason\":\"MATCH\"",
                "\"reason\":\"MATCH\"," + description);
            when(ai.chat(anyList(), anyInt())).thenReturn(response(extraField, "stop"), response(extraField, "stop"));
            var result = run(fixture, ai, trace);
            assertFalse(result.supported()); assertEquals("", result.answer());
            assertEquals(List.of(1200, 1200), limits(ai, 2));
            assertTrue(trace.toString().contains("CLASSIFICATION_GROUP:1:SCHEMA:CLOSED_DECISION_FIELDS_REQUIRED"));
            assertTrue(trace.stream().noneMatch(step -> step.containsKey("verificationEvidence")));
        }
    }

    @Test
    void reasonCodeCannotBeUsedWithAnIncompatibleStateEvenWithValidProofs() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        String incompatible = PARTITION.replace("\"reason\":\"MATCH\"", "\"reason\":\"OUTSIDE_ATTRIBUTE\"");
        when(ai.chat(anyList(), anyInt())).thenReturn(response(incompatible, "stop"), response(incompatible, "stop"));
        var result = run(fixture, ai, trace);
        assertFalse(result.supported()); assertEquals("", result.answer());
        assertEquals(List.of(1200, 1200), limits(ai, 2));
        assertTrue(trace.toString().contains("CLASSIFICATION_STATE_REASON_MISMATCH"));
        assertTrue(trace.toString().contains("CLASSIFICATION_GROUP:1:REASON:STATE_MISMATCH:I:OUTSIDE_ATTRIBUTE"));
        assertTrue(trace.stream().noneMatch(step -> step.containsKey("verificationEvidence")));
    }

    @Test
    void exhaustiveClosedDecisionsAreNotPublishedWhenBothSemanticChecksRejectTheirAssignments() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        String wrongDecisions = "{\"groups\":[{\"s\":\"I\",\"reason\":\"MATCH\",\"ids\":\"2\",\"proofs\":[\"p2\"]},"
            + "{\"s\":\"E\",\"reason\":\"OUTSIDE_ATTRIBUTE\",\"ids\":\"1\",\"proofs\":[\"p1\"]}]}";
        String rejected = "{\"facts\":\"UNSUPPORTED\",\"coverage\":\"COMPLETE\","
            + "\"unsupported\":[\"原文不支持这两个候选的分类决定\"],\"missing\":[]}";
        when(ai.chat(anyList(), anyInt())).thenReturn(response(wrongDecisions, "stop"), response(rejected, "stop"),
            response(wrongDecisions, "stop"), response(rejected, "stop"));
        var result = run(fixture, ai, trace);
        assertFalse(result.supported()); assertEquals("", result.answer());
        assertEquals(List.of(1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET, 1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET), limits(ai, 4));
        assertTrue(trace.stream().anyMatch(step -> "VERIFY".equals(step.get("verificationStage"))));
        assertTrue(trace.stream().anyMatch(step -> "EXTRA".equals(step.get("verificationStage"))));
        assertTrue(trace.stream().filter(step -> step.containsKey("classificationDisposition"))
            .map(step -> (Map<?, ?>) step.get("classificationDisposition"))
            .allMatch(state -> Boolean.FALSE.equals(state.get("semanticVerdictAccepted"))));
        assertTrue(trace.stream().noneMatch(step -> "STRUCTURED_GRAPH_CLASSIFICATION".equals(step.get("answerMode"))));
    }

    @Test
    void parsedOmissionProvidesSentSourceHintsWithoutAssigningOrPublishingTheMissingMember() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        String omitted = "{\"groups\":[{\"s\":\"I\",\"reason\":\"MATCH\",\"ids\":\"1\",\"proofs\":[\"p1\"]}]}";
        when(ai.chat(anyList(), anyInt())).thenReturn(response(omitted, "stop"), response(omitted, "stop"));
        var result = run(fixture, ai, trace);
        assertFalse(result.supported()); assertEquals("", result.answer());
        assertEquals(List.of(Map.of("candidateId", 2, "state", "UNASSIGNED", "status", "UNASSIGNED",
            "sentRelevantProofIds", List.of("p2"))), repairHints(trace));
        var generated = firstTraceMap(trace, "classificationDisposition");
        assertEquals(List.of(2), generated.get("missingCandidateIds"));
        assertEquals(Boolean.FALSE, generated.get("partitionComplete"));
        String rewrite = rewriteInput(ai);
        assertTrue(rewrite.contains("status=UNASSIGNED")); assertTrue(rewrite.contains("sentRelevantProofIds=[p2]"));
        assertTrue(rewrite.contains("不是类别真值")); assertTrue(rewrite.contains("程序不代选I/E/U或补证明"));
        assertTrue(result.verification().reasons().contains("CLASSIFICATION_OMITTED_CANDIDATE:2"));
        assertEquals(List.of(1200, 1200), limits(ai, 2));
        assertTrue(trace.stream().noneMatch(step -> step.containsKey("verificationEvidence")
            || "STRUCTURED_GRAPH_CLASSIFICATION".equals(step.get("answerMode"))));
    }

    @Test
    void parsedEmptyProofGroupsReceiveOnlyActuallySentHintsAndNeverAutomaticProofRepair() {
        for (String omittedProof : List.of("p1", "p2")) {
            var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
            String emptyProof = PARTITION.replace("[\"" + omittedProof + "\"]", "[]");
            when(ai.chat(anyList(), anyInt())).thenReturn(response(emptyProof, "stop"), response(emptyProof, "stop"));
            var result = run(fixture, ai, trace);
            int candidateId = "p1".equals(omittedProof) ? 1 : 2;
            assertFalse(result.supported()); assertEquals("", result.answer());
            assertEquals(List.of(Map.of("candidateId", candidateId,
                "state", candidateId == 1 ? "I" : "E", "status", "SENT_NOT_CITED",
                "sentRelevantProofIds", List.of(omittedProof))), repairHints(trace));
            assertTrue(rewriteInput(ai).contains("sentRelevantProofIds=[" + omittedProof + "]"));
            assertTrue(result.verification().reasons().contains("CLASSIFICATION_PROOF_REQUIRED"));
            assertEquals(List.of(1200, 1200), limits(ai, 2));
            assertTrue(trace.stream().noneMatch(step -> step.containsKey("verificationEvidence")
                || "STRUCTURED_GRAPH_CLASSIFICATION".equals(step.get("answerMode"))));
        }
    }

    @Test
    void duplicateMemberKeepsBothOwnersAndItsMissingSourceHintCannotChooseTheGroup() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        String duplicate = PARTITION.replace("\"ids\":\"2\"", "\"ids\":\"1-2\"");
        when(ai.chat(anyList(), anyInt())).thenReturn(response(duplicate, "stop"), response(duplicate, "stop"));
        var result = run(fixture, ai, trace);
        assertFalse(result.supported()); assertEquals("", result.answer());
        assertEquals(List.of(Map.of("candidateId", 1, "state", "E", "status", "SENT_NOT_CITED",
            "sentRelevantProofIds", List.of("p1"))), repairHints(trace));
        assertEquals(List.of(Map.of("candidateId", 1, "groupIndexes", List.of(1, 2),
            "states", List.of("I", "E"), "assignmentCount", 2)),
            firstTraceMap(trace, "classificationDisposition").get("duplicateCandidateOwnership"));
        List<?> memberRows = (List<?>) firstTraceMap(trace, "classificationMemberEvidence").get("members");
        var owners = memberRows.stream().map(row -> (Map<?, ?>) row)
            .filter(row -> Integer.valueOf(1).equals(row.get("candidateId"))).toList();
        assertEquals(List.of(1, 2), owners.stream().map(row -> row.get("groupIndex")).toList());
        assertTrue(owners.stream().allMatch(row -> Boolean.TRUE.equals(row.get("retrievalOnly"))
            && Boolean.TRUE.equals(row.get("semanticSupportNotEstablished"))));
        String rewrite = rewriteInput(ai);
        assertTrue(rewrite.contains("groupIndexes=[1, 2]")); assertTrue(rewrite.contains("不得由程序代选归属"));
        assertTrue(result.verification().reasons().contains("CLASSIFICATION_DUPLICATE_CANDIDATE:1"));
        assertEquals(List.of(1200, 1200), limits(ai, 2));
        assertTrue(trace.stream().noneMatch(step -> step.containsKey("verificationEvidence")
            || "STRUCTURED_GRAPH_CLASSIFICATION".equals(step.get("answerMode"))));
    }

    @Test
    void deletingARecognizedGenerationGroupDuringRewriteCannotEvadeItsProofFailure() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        String missingProof = PARTITION.replace("[\"p2\"]", "[]");
        String deletedGroup = "{\"groups\":[{\"s\":\"I\",\"reason\":\"MATCH\",\"ids\":\"1\",\"proofs\":[\"p1\"]}]}";
        when(ai.chat(anyList(), anyInt())).thenReturn(response(missingProof, "stop"), response(deletedGroup, "stop"));
        var result = run(fixture, ai, trace);
        assertFalse(result.supported()); assertEquals("", result.answer());
        assertEquals(List.of(Map.of("candidateId", 2, "state", "E", "status", "SENT_NOT_CITED",
            "sentRelevantProofIds", List.of("p2"))), repairHints(trace));
        var attempts = trace.stream().filter(step -> step.containsKey("classificationDisposition"))
            .map(step -> (Map<?, ?>) step.get("classificationDisposition")).toList();
        assertEquals(List.of("MATCH", "OUTSIDE_ATTRIBUTE"), attempts.get(0).get("reasonCodes"));
        assertEquals(List.of(), attempts.get(0).get("missingCandidateIds"));
        assertEquals(List.of("MATCH"), attempts.get(1).get("reasonCodes"));
        assertEquals(List.of(2), attempts.get(1).get("missingCandidateIds"));
        assertTrue(rewriteInput(ai).contains("不得删除一整组以逃避其proofs错误"));
        assertTrue(result.verification().reasons().contains("CLASSIFICATION_OMITTED_CANDIDATE:2"));
        assertEquals(1, trace.stream().filter(step -> step.containsKey("rewriteResult")).count());
        assertEquals(List.of(1200, 1200), limits(ai, 2));
        assertTrue(trace.stream().noneMatch(step -> step.containsKey("verificationEvidence")
            || "STRUCTURED_GRAPH_CLASSIFICATION".equals(step.get("answerMode"))));
    }

    @Test
    void invalidJsonContainingACompleteGroupStillHasNoInventedMemberRepairHints() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        String invalid = "{\"groups\":[{\"s\":\"I\",\"reason\":\"MATCH\",\"ids\":\"1\",\"proofs\":[\"p1\"]},";
        when(ai.chat(anyList(), anyInt())).thenReturn(response(invalid, "length"), response(invalid, "length"));
        var result = run(fixture, ai, trace);
        assertFalse(result.supported()); assertEquals("", result.answer());
        assertEquals(List.of(), repairHints(trace));
        assertEquals("UNAVAILABLE", firstTraceMap(trace, "classificationDisposition").get("proposalParseStatus"));
        String rewrite = rewriteInput(ai);
        assertTrue(rewrite.contains("逐成员证据诊断（仅当前已展示出处索引，不是类别真值）：[]"));
        assertFalse(rewrite.contains("status=UNASSIGNED"));
        assertTrue(result.verification().reasons().contains("CLASSIFICATION_INVALID_JSON"));
        assertEquals(List.of(1200, 1200), limits(ai, 2));
        assertTrue(trace.stream().noneMatch(step -> step.containsKey("verificationEvidence")));
    }

    @Test
    void stageC2KeepsTheDefaultSelectionSnapshotWhenExpansionIsUnnecessary() {
        var fixture = fixture(); var ai = mock(AiClient.class); var trace = trace();
        when(ai.chat(anyList(), anyInt())).thenReturn(response(PARTITION, "stop"), response(SUPPORTED, "stop"));
        assertTrue(run(fixture, ai, trace).supported());
        assertTrue(trace.stream().anyMatch(step -> "C-stage-2".equals(step.get("strategyCandidate"))
            && "C_MONOTONE_PACKING_AND_PARSED_REPAIR".equals(step.get("optimizationStage"))));
        var selection = firstTraceMap(trace, "classificationSelection");
        var baseline = (Map<?, ?>) selection.get("defaultSelection");
        var generation = firstTraceMap(trace, "generationEvidence");
        assertEquals(ClassificationCoverageProofs.POLICY_MONOTONE_COVERAGE, selection.get("packingPolicy"));
        assertEquals(ClassificationCoverageProofs.POLICY_MONOTONE_COVERAGE, generation.get("packingPolicy"));
        assertTrue(trace.stream().filter(step -> step.containsKey("classificationEvidenceCoverage"))
            .map(step -> (Map<?, ?>) step.get("classificationEvidenceCoverage"))
            .allMatch(coverage -> ClassificationCoverageProofs.POLICY_MONOTONE_COVERAGE.equals(coverage.get("packingPolicy"))));
        assertEquals("INITIAL", baseline.get("runMode")); assertEquals(EvidenceContext.DEFAULT_BUDGET, baseline.get("budget"));
        assertEquals(List.of("p1", "p2"), baseline.get("proofIds"));
        assertEquals(baseline.get("proofIds"), generation.get("proofIds"));
        assertEquals(baseline.get("tokenCount"), generation.get("tokenCount"));
        assertEquals(Boolean.TRUE, baseline.get("withinBudget"));
        assertEquals(Boolean.FALSE, selection.get("budgetExpansionAttempted"));
        assertEquals(Boolean.FALSE, selection.get("budgetExpansionAdopted"));
        assertEquals("NOT_NEEDED", selection.get("budgetExpansionReason"));
        assertFalse(selection.containsKey("expandedSelection"));
        assertEquals(selection, generation.get("selectionAudit"));
        assertEquals(List.of(1200, AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET), limits(ai, 2));
    }

    @Test
    void expandedSelectionSnapshotContinuesDefaultHistoryAndPreservesEveryMemberCoverageDimension() {
        var fixture = expandedFixture(); var ai = mock(AiClient.class); var trace = trace();
        when(ai.chat(anyList(), anyInt())).thenReturn(response("{\"groups\":[", "length"),
            response("{\"groups\":[", "length"));
        assertFalse(run(fixture, ai, trace).supported());
        var selection = firstTraceMap(trace, "classificationSelection");
        var baseline = (Map<?, ?>) selection.get("defaultSelection");
        var expanded = (Map<?, ?>) selection.get("expandedSelection");
        assertEquals(Boolean.TRUE, selection.get("budgetExpansionAttempted"));
        assertEquals(Boolean.TRUE, selection.get("budgetExpansionAdopted"));
        assertEquals("CONTINUATION_COVERAGE_PRESERVED", selection.get("budgetExpansionReason"));
        assertEquals("INITIAL", baseline.get("runMode")); assertEquals("CONTINUATION", expanded.get("runMode"));
        assertEquals(EvidenceContext.DEFAULT_BUDGET, baseline.get("budget"));
        assertEquals(EvidenceContext.MAX_BUDGET, expanded.get("budget"));
        assertEquals(List.of("p1"), baseline.get("proofIds"));
        assertEquals(List.of("p1", "p2"), expanded.get("proofIds"));
        assertEquals(baseline.get("proofIds"), expanded.get("seedProofIds"));
        assertEquals(baseline.get("tokenCount"), expanded.get("seedTokenCount"));
        assertEquals(baseline.get("selectionSteps"), expanded.get("seedSelectionSteps"));
        assertEquals(baseline.get("prefixSha256"), expanded.get("prefixSha256"));
        assertEquals(baseline.get("sourceCatalogSha256"), expanded.get("sourceCatalogSha256"));
        List<?> baselineHistory = (List<?>) baseline.get("selectionHistory");
        List<?> expandedHistory = (List<?>) expanded.get("selectionHistory");
        assertEquals(baselineHistory, expandedHistory.subList(0, baselineHistory.size()));
        for (String dimension : List.of("sentMentionCandidateIds", "sentRelevantCandidateIds",
                "sentContextCandidateIds", "sentBoundCandidateIds")) {
            assertEquals(baseline.get(dimension), expanded.get(dimension.replace("sent", "seed")), dimension);
            assertTrue(((List<?>) expanded.get(dimension)).containsAll((List<?>) baseline.get(dimension)), dimension);
        }
        assertEquals(List.of(1), baseline.get("sentRelevantCandidateIds"));
        assertEquals(List.of(1, 2), expanded.get("sentRelevantCandidateIds"));
        assertEquals(Boolean.TRUE, expanded.get("withinBudget"));
        assertTrue((Integer) baseline.get("tokenCount") <= EvidenceContext.DEFAULT_BUDGET);
        assertTrue((Integer) expanded.get("tokenCount") <= EvidenceContext.MAX_BUDGET);
        var generation = firstTraceMap(trace, "generationEvidence");
        assertEquals(expanded.get("proofIds"), generation.get("proofIds"));
        assertEquals(expanded.get("tokenCount"), generation.get("tokenCount"));
        assertEquals(List.of(1200, 1200), limits(ai, 2));
        assertTrue(trace.stream().noneMatch(step -> step.containsKey("verificationEvidence")));
    }

    private static Fixture expandedFixture() {
        var fixture = fixture(); var ledger = fixture.ledger();
        var first = ledger.relations().iterator().next();
        StringBuilder secondText = new StringBuilder("存储服务乙是独立存储服务，");
        // One indivisible synthetic sentence cannot fit DEFAULT, but fits the MAX continuation with p1.
        while (EvidenceContext.tokens(secondText.toString()) <= EvidenceContext.DEFAULT_BUDGET + 32)
            secondText.append("参考 ".repeat(32));
        String secondFact = secondText.append('。').toString();
        var relations = List.of(first, new EvidenceLedger.RelationFact(11L, 1L, 3L, "MENTIONS",
            null, null, "POST:1", 20L, secondFact));
        String body = first.factText() + secondFact;
        var chunk = new EvidenceLedger.ChunkFact(20L, "POST", 1L, "应用设计", body, 0, body.length());
        when(ledger.relations()).thenReturn(relations); when(ledger.chunks()).thenReturn(List.of(chunk));
        when(ledger.chunk(20L)).thenReturn(Optional.of(chunk));
        when(ledger.relation(any())).thenAnswer(call -> relations.stream()
            .filter(item -> item.id().equals(call.getArgument(0))).findFirst());
        return new Fixture(ledger, fixture.plan(), GraphClassificationContract.prepare(QUESTION, fixture.plan(), ledger),
            ClassificationProofCatalog.prepare(QUESTION, ledger, List.of(2L, 3L)));
    }

    private static List<Map<?, ?>> repairHints(List<Map<String, Object>> trace) {
        List<?> rows = (List<?>) trace.stream().filter(step -> step.containsKey("classificationRepairHints"))
            .map(step -> step.get("classificationRepairHints")).findFirst().orElseThrow();
        return rows.stream().<Map<?, ?>>map(row -> (Map<?, ?>) row).toList();
    }

    private static Map<?, ?> firstTraceMap(List<Map<String, Object>> trace, String key) {
        return trace.stream().filter(step -> step.containsKey(key)).map(step -> (Map<?, ?>) step.get(key))
            .findFirst().orElseThrow();
    }

    private static String rewriteInput(AiClient ai) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messages = ArgumentCaptor.forClass(List.class);
        verify(ai, times(2)).chat(messages.capture(), eq(1200));
        return messages.getAllValues().get(1).get(1).get("content");
    }

    static Fixture fixture() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        var entities = List.of(new EvidenceLedger.EntityFact(1L, "应用设计", "POST", ""),
            new EvidenceLedger.EntityFact(2L, "前端组件甲", "SKILL", ""), new EvidenceLedger.EntityFact(3L, "存储服务乙", "SKILL", ""));
        var relations = List.of(new EvidenceLedger.RelationFact(10L, 1L, 2L, "MENTIONS", null, null, "POST:1", 20L, "前端组件甲用于前端界面。"),
            new EvidenceLedger.RelationFact(11L, 1L, 3L, "MENTIONS", null, null, "POST:1", 20L, "存储服务乙是独立存储服务。"));
        var chunk = new EvidenceLedger.ChunkFact(20L, "POST", 1L, "应用设计", "前端组件甲用于前端界面。存储服务乙是独立存储服务。", 0, 32);
        when(ledger.entities()).thenReturn(entities); when(ledger.relations()).thenReturn(relations);
        when(ledger.chunks()).thenReturn(List.of(chunk)); when(ledger.chunk(20L)).thenReturn(Optional.of(chunk));
        when(ledger.containsChunk(20L)).thenReturn(true);
        when(ledger.entity(any())).thenAnswer(call -> entities.stream().filter(item -> item.id().equals(call.getArgument(0))).findFirst());
        when(ledger.relation(any())).thenAnswer(call -> relations.stream().filter(item -> item.id().equals(call.getArgument(0))).findFirst());
        when(ledger.containsEntity(anyLong())).thenAnswer(call -> entities.stream().anyMatch(item -> item.id().equals(call.getArgument(0))));
        when(ledger.completeRelations(anyString(), anyLong())).thenReturn(true);
        when(ledger.hasToolResult(anyString())).thenAnswer(call -> Set.of("expand_neighbors", "get_document").contains(call.getArgument(0)));
        var plan = QueryEvidencePlan.fromQuestion(QUESTION);
        return new Fixture(ledger, plan, GraphClassificationContract.prepare(QUESTION, plan, ledger),
            ClassificationProofCatalog.prepare(QUESTION, ledger, List.of(2L, 3L)));
    }

    record Fixture(EvidenceLedger ledger, QueryEvidencePlan plan, GraphClassificationContract.Catalog catalog,
                   ClassificationProofCatalog.ProofCatalog proofs) { }
    static AiClient.AiChatResult response(String value, String finish) { return new AiClient.AiChatResult(value, null, 10, 20, finish); }
    private static ArrayList<Map<String, Object>> trace() { return new ArrayList<>(); }
    private static GraphClassificationFlow.Outcome run(Fixture fixture, AiClient ai, List<Map<String, Object>> trace) {
        return new GraphClassificationFlow().run(QUESTION, fixture.plan(), fixture.ledger(), fixture.catalog(), ai, trace);
    }
    private static List<Integer> limits(AiClient ai, int expectedCalls) {
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(ai, times(expectedCalls)).chat(anyList(), limits.capture());
        return limits.getAllValues();
    }
}
