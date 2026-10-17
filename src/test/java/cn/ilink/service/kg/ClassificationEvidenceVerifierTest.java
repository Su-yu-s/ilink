package cn.ilink.service.kg;

import cn.ilink.service.ai.AiClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClassificationEvidenceVerifierTest {
    @Test
    void proofExistingInCatalogButNotSentIsRejectedBeforePaidVerification() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        var result = verifyPartition(fixture, GraphClassificationFlowTest.PARTITION, Set.of("p1"), ai, new ArrayList<>());
        assertFalse(result.supported()); assertTrue(result.reasons().contains("CLASSIFICATION_PROOF_NOT_SENT:p2"));
        verifyNoInteractions(ai);
    }

    @Test
    void unknownProofCannotBeInventedOrLoaded() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        var result = verifyPartition(fixture, GraphClassificationFlowTest.PARTITION.replace("p2", "p999"),
            Set.of("p1", "p999"), ai, new ArrayList<>());
        assertFalse(result.supported()); assertTrue(result.reasons().contains("UNKNOWN_PROOF:p999")); verifyNoInteractions(ai);
    }

    @Test
    void removedChunkInvalidatesProofInsteadOfReadingUnapprovedData() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        when(fixture.ledger().chunk(20L)).thenReturn(Optional.empty());
        var result = verifyPartition(fixture, GraphClassificationFlowTest.PARTITION, Set.of("p1", "p2"), ai, new ArrayList<>());
        assertFalse(result.supported()); assertTrue(result.reasons().contains("UNAUTHORIZED_PROOF:p1")); verifyNoInteractions(ai);
    }

    @Test
    void validSourceForAnotherSubjectCannotSupportGroupMemberClassification() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        var foreign = new EvidenceLedger.ChunkFact(21L, "POST", 9L, "其他应用", "前端组件甲用于前端界面。存储服务乙是独立存储服务。", 0, 32);
        when(fixture.ledger().chunks()).thenReturn(List.of(foreign));
        when(fixture.ledger().chunk(21L)).thenReturn(Optional.of(foreign));
        var proofs = ClassificationProofCatalog.prepare(GraphClassificationFlowTest.QUESTION, fixture.ledger(), List.of(2L, 3L));
        var alternate = new GraphClassificationFlowTest.Fixture(fixture.ledger(), fixture.plan(), fixture.catalog(), proofs);
        var result = verifyPartition(alternate, GraphClassificationFlowTest.PARTITION, Set.of("p1", "p2"), ai, new ArrayList<>());
        assertFalse(result.supported()); assertTrue(result.reasons().contains("CLASSIFICATION_PROOF_SUBJECT_MISMATCH:1")); verifyNoInteractions(ai);
    }

    @Test
    void exactRelationEndpointsAndDirectionAreCheckedBeforeSemanticRequest() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        when(fixture.ledger().relation(10L)).thenReturn(Optional.of(new EvidenceLedger.RelationFact(10L,
            3L, 1L, "MENTIONS", null, null, "POST:1", 20L, "存储服务乙是独立存储服务。")));
        var result = verifyPartition(fixture, GraphClassificationFlowTest.PARTITION, Set.of("p1", "p2"), ai, new ArrayList<>());
        assertFalse(result.supported()); assertTrue(result.reasons().contains("CLASSIFICATION_BINDING_INVALID:1")); verifyNoInteractions(ai);
    }

    @Test
    void validPartitionStillNeedsBothSemanticVerdictsAndPreservesUncertainty() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt())).thenReturn(GraphClassificationFlowTest.response(
            "{\"facts\":\"UNCERTAIN\",\"coverage\":\"COMPLETE\",\"unsupported\":[],\"missing\":[]}", "stop"));
        var result = verifyPartition(fixture, GraphClassificationFlowTest.PARTITION, Set.of("p1", "p2"), ai, new ArrayList<>());
        assertFalse(result.supported()); assertTrue(result.reasons().stream().anyMatch(reason -> reason.startsWith("SEMANTIC_UNCERTAIN:FACTS:")));
    }

    @Test
    void oversizeClaimPacketStopsBeforeTheSemanticProviderRequest() {
        var fixture = GraphClassificationFlowTest.fixture(); var original = fixture.catalog(); var ai = mock(AiClient.class);
        var oversized = new GraphClassificationContract.Catalog(true, "", original.candidates(),
            original.text() + "完整授权范围。".repeat(1800), original.metadata(), true, true, original.focusText());
        var alternate = new GraphClassificationFlowTest.Fixture(fixture.ledger(), fixture.plan(), oversized, fixture.proofs());
        var packets = new ArrayList<Map<String, Object>>();
        var result = verifyPartition(alternate, GraphClassificationFlowTest.PARTITION, Set.of("p1", "p2"), ai, packets);
        assertFalse(result.supported()); assertTrue(result.reasons().stream().anyMatch(reason -> reason.startsWith("SEMANTIC_UNCERTAIN:EVIDENCE_BUDGET")));
        assertEquals(false, packets.get(0).get("withinBudget")); verifyNoInteractions(ai);
    }

    @Test
    void semanticContradictionAndMalformedResponseCannotBeMarkedSupported() {
        var fixture = GraphClassificationFlowTest.fixture();
        for (String verdict : List.of("{\"facts\":\"SUPPORTED\",\"coverage\":\"COMPLETE\",\"unsupported\":[\"错分类\"],\"missing\":[]}",
            "{\"facts\":\"SUPPORTED\",\"coverage\":\"COMPLETE\",\"unsupported\":[],\"missing\":null}",
            GraphClassificationFlowTest.SUPPORTED + " trailing")) {
            var ai = mock(AiClient.class);
            when(ai.chat(anyList(), anyInt())).thenReturn(GraphClassificationFlowTest.response(verdict, "stop"));
            assertFalse(verifyPartition(fixture, GraphClassificationFlowTest.PARTITION, Set.of("p1", "p2"), ai, new ArrayList<>()).supported());
        }
    }

    @Test
    void proofPacketContainsAllDispositionsAndOnlyActuallyUsedOriginalSpans() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt())).thenReturn(GraphClassificationFlowTest.response(GraphClassificationFlowTest.SUPPORTED, "stop"));
        var packets = new ArrayList<Map<String, Object>>();
        assertTrue(verifyPartition(fixture, GraphClassificationFlowTest.PARTITION, Set.of("p1", "p2"), ai, packets).supported());
        assertEquals(1, packets.size());
        assertTrue(packets.get(0).get("text").toString().contains("\"s\":\"E\""));
        assertTrue(packets.get(0).get("text").toString().contains("存储服务乙是独立存储服务。"));
        assertEquals("classification-claim-spans-v1", packets.get(0).get("packingPolicy"));
        assertEquals(true, packets.get(0).get("withinBudget"));
    }

    @Test
    void directProductionVerificationAlsoBlocksIncompatibleStateAndReason() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        String question = "《应用设计》在图谱里关联的技术栈中，哪些不属于 Orion 生态？";
        String raw = GraphClassificationFlowTest.PARTITION.replace("\"reason\":\"MATCH\"", "\"reason\":\"OUTSIDE_ATTRIBUTE\"");
        var parsed = GraphClassificationContract.parseDecisions(raw, fixture.catalog());
        assertFalse(parsed.complete());
        var result = new ClassificationEvidenceVerifier().verify(question, QueryEvidencePlan.fromQuestion(question),
            fixture.ledger(), fixture.catalog(), parsed, fixture.proofs(), Set.of("p1", "p2"), ai,
            packet -> fail("Code matrix failure must stop before semantic evidence dispatch"));
        assertFalse(result.supported());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.startsWith("CLASSIFICATION_STATE_REASON_MISMATCH")));
        verifyNoInteractions(ai);
    }

    @Test
    void validPartitionCannotBypassSemanticRejectionOfAnIncorrectlyAssignedMember() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt())).thenReturn(GraphClassificationFlowTest.response(
            "{\"facts\":\"UNSUPPORTED\",\"coverage\":\"INCOMPLETE\","
                + "\"unsupported\":[\"组1成员1类别与证明相反\"],\"missing\":[\"成员1应满足筛选条件\"]}", "stop"));
        var result = verifyPartition(fixture, GraphClassificationFlowTest.PARTITION, Set.of("p1", "p2"), ai, new ArrayList<>());
        assertFalse(result.supported());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.startsWith("SEMANTIC_UNSUPPORTED:FACTS:")));
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.startsWith("SEMANTIC_UNSUPPORTED:COVERAGE:")));
        org.mockito.Mockito.verify(ai, times(1)).chat(anyList(), eq(AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET));
    }

    @Test
    void forgedCompleteRecordWithUnknownMemberIsRejectedWithoutDereferencingIt() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        var forged = new GraphClassificationContract.DecisionParsed(List.of(
            new GraphClassificationContract.DecisionGroup(GraphClassificationContract.State.I,
                GraphClassificationContract.Reason.MATCH, List.of(1, 2, 99), List.of("p1", "p2"))), List.of(), true, 0);
        var result = new ClassificationEvidenceVerifier().verify(GraphClassificationFlowTest.QUESTION, fixture.plan(),
            fixture.ledger(), fixture.catalog(), forged, fixture.proofs(), Set.of("p1", "p2"), ai,
            packet -> fail("Invalid record must stop before evidence dispatch"));
        assertFalse(result.supported());
        assertTrue(result.reasons().contains("CLASSIFICATION_UNKNOWN_CANDIDATE:99")); verifyNoInteractions(ai);
    }

    @Test
    void forgedCompleteRecordCannotHideDuplicateMemberAssignments() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        var forged = new GraphClassificationContract.DecisionParsed(List.of(
            new GraphClassificationContract.DecisionGroup(GraphClassificationContract.State.I,
                GraphClassificationContract.Reason.MATCH, List.of(1, 2), List.of("p1", "p2")),
            new GraphClassificationContract.DecisionGroup(GraphClassificationContract.State.U,
                GraphClassificationContract.Reason.INSUFFICIENT, List.of(2), List.of())), List.of(), true, 1);
        var result = new ClassificationEvidenceVerifier().verify(GraphClassificationFlowTest.QUESTION, fixture.plan(),
            fixture.ledger(), fixture.catalog(), forged, fixture.proofs(), Set.of("p1", "p2"), ai,
            packet -> fail("Duplicate member must stop before evidence dispatch"));
        assertFalse(result.supported());
        assertTrue(result.reasons().contains("CLASSIFICATION_DUPLICATE_CANDIDATE:2")); verifyNoInteractions(ai);
    }

    @Test
    void closedCodesCannotReplaceTheUsersRequiredCategoryCoverage() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt())).thenReturn(GraphClassificationFlowTest.response(
            "{\"facts\":\"SUPPORTED\",\"coverage\":\"INCOMPLETE\",\"unsupported\":[],\"missing\":[\"所需类别未表达\"]}", "stop"));
        var result = verifyPartition(fixture, GraphClassificationFlowTest.PARTITION, Set.of("p1", "p2"), ai, new ArrayList<>());
        assertFalse(result.supported());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.startsWith("SEMANTIC_UNSUPPORTED:COVERAGE:")));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Map<String, String>>> request = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(ai).chat(request.capture(), eq(AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET));
        String prompt = request.getValue().get(1).get("content");
        assertTrue(prompt.contains("用户要求多种类别/属性"));
        assertTrue(prompt.contains("不能用全部编号已处置替代回答完整性"));
    }

    @Test
    void sameSubjectProofDoesNotSupportAnUnmentionedGroupMember() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        String raw = "{\"groups\":[{\"s\":\"I\",\"reason\":\"MATCH\",\"ids\":\"1-2\",\"proofs\":[\"p1\"]}]}";
        var result = verifyPartition(fixture, raw, Set.of("p1"), ai, new ArrayList<>());
        assertFalse(result.supported());
        assertTrue(result.reasons().contains("CLASSIFICATION_MEMBER_EVIDENCE_MISSING:2")); verifyNoInteractions(ai);
    }

    @Test
    void lengthFinishIsRecordedWithoutTreatingProviderTextAsAParsedVerdict() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt())).thenReturn(GraphClassificationFlowTest.response("{\"facts\":", "length"));
        var observations = new ArrayList<Map<String, Object>>();
        assertFalse(verifyObserved(fixture, ai, observations).supported());
        assertEquals(1, observations.size()); var observed = observations.get(0);
        assertEquals("NON_STOP_FINISH", observed.get("status")); assertEquals("length", observed.get("finishReason"));
        assertEquals(false, observed.get("semanticParsed")); assertEquals(false, observed.get("semanticAccepted"));
        assertEquals("{\"facts\":", observed.get("rawVerdict"));
        org.mockito.Mockito.verify(ai, times(1)).chat(anyList(), eq(AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET));
    }

    @Test
    void invalidJsonAndInvalidSchemaAreDifferentRecordedFailureBranches() {
        var fixture = GraphClassificationFlowTest.fixture();
        for (var sample : Map.of("{\"facts\":", "INVALID_JSON",
            "{\"facts\":\"SUPPORTED\",\"coverage\":\"COMPLETE\",\"unsupported\":null,\"missing\":[]}",
                "INVALID_VERDICT_SCHEMA").entrySet()) {
            var ai = mock(AiClient.class); var observations = new ArrayList<Map<String, Object>>();
            when(ai.chat(anyList(), anyInt())).thenReturn(GraphClassificationFlowTest.response(sample.getKey(), "stop"));
            var result = verifyObserved(fixture, ai, observations);
            assertFalse(result.supported()); assertEquals(sample.getValue(), observations.get(0).get("status"));
            assertTrue(result.reasons().contains("SEMANTIC_UNCERTAIN:VERIFIER_PROTOCOL"));
            assertEquals(false, observations.get(0).get("semanticParsed"));
        }
    }

    @Test
    void parsedSemanticRejectionIsDistinguishedFromProtocolFailure() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        String raw = "{\"facts\":\"UNSUPPORTED\",\"coverage\":\"INCOMPLETE\",\"unsupported\":[\"成员错误\"],\"missing\":[]}";
        when(ai.chat(anyList(), anyInt())).thenReturn(GraphClassificationFlowTest.response(raw, "stop"));
        var observations = new ArrayList<Map<String, Object>>();
        assertFalse(verifyObserved(fixture, ai, observations).supported()); var observed = observations.get(0);
        assertEquals("SEMANTIC_REJECTED", observed.get("status")); assertEquals(true, observed.get("semanticParsed"));
        assertEquals(false, observed.get("semanticAccepted")); assertEquals("UNSUPPORTED", observed.get("facts"));
        assertEquals(raw, observed.get("rawVerdict"));
    }

    @Test
    void nullResponseRecordsUnknownUsageInsteadOfInventingZeroTokens() {
        var fixture = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        var observations = new ArrayList<Map<String, Object>>();
        assertFalse(verifyObserved(fixture, ai, observations).supported()); var observed = observations.get(0);
        assertEquals("NULL_RESPONSE", observed.get("status")); assertEquals(false, observed.get("responsePresent"));
        assertNull(observed.get("promptTokens")); assertNull(observed.get("completionTokens"));
        assertEquals("UNKNOWN", observed.get("finishReason"));
    }

    @Test
    void auditTextIsBoundedAndDoesNotExposeReasoning() {
        var response = new AiClient.AiChatResult("x".repeat(8191) + "\uD83D\uDE00" + "tail",
            "private model reasoning", 10, 512, "length");
        var observed = ClassificationVerifierObservation.describe(response, "NON_STOP_FINISH", false, false, "", null);
        assertEquals(true, observed.get("rawVerdictTruncated"));
        assertEquals(8191, observed.get("rawVerdict").toString().length());
        assertEquals(64, observed.get("rawVerdictSha256").toString().length());
        assertEquals(false, observed.get("reasoningRecorded")); assertFalse(observed.toString().contains("private model reasoning"));
    }

    @Test
    void sentMemberSourceOmittedByItsGroupIsDistinguishedFromThePresentMemberSource() {
        var fixture = GraphClassificationFlowTest.fixture();
        var parsed = new GraphClassificationContract.DecisionParsed(List.of(
            new GraphClassificationContract.DecisionGroup(GraphClassificationContract.State.I,
                GraphClassificationContract.Reason.MATCH, List.of(1, 2), List.of("p1"))), List.of(), true, 0);
        var rows = ClassificationEvidenceVerifier.memberEvidenceDiagnostics(fixture.catalog(), parsed,
            fixture.proofs(), Set.of("p1", "p2"), fixture.ledger());
        assertEquals(2, rows.size());
        var present = rows.get(0); var missingCitation = rows.get(1);
        assertEquals("MEMBER_PROOF_PRESENT", present.get("status"));
        assertEquals(List.of("p1"), present.get("citedRelevantProofIds"));
        assertEquals(2, missingCitation.get("candidateId"));
        assertEquals("存储服务乙", missingCitation.get("name"));
        assertEquals("I", missingCitation.get("state")); assertEquals("MATCH", missingCitation.get("reason"));
        assertEquals("SENT_NOT_CITED", missingCitation.get("status")); assertEquals(1, missingCitation.get("groupIndex"));
        assertEquals("1-based-recognized-parsed-groups", missingCitation.get("groupIndexBasis"));
        assertEquals(List.of("p1"), missingCitation.get("proposedProofIds"));
        assertEquals(List.of("p2"), missingCitation.get("sentRelevantProofIds"));
        assertEquals(List.of(), missingCitation.get("citedRelevantProofIds"));
        assertEquals(List.of("p2"), missingCitation.get("availableRelevantProofIds"));
        assertEquals(true, missingCitation.get("retrievalOnly"));
        assertEquals(true, missingCitation.get("semanticSupportNotEstablished"));
        assertFalse(rows.toString().contains("存储服务乙是独立存储服务。"));
    }

    @Test
    void availableUnsentCitationCannotBecomeASentOrPresentMemberProof() {
        var fixture = GraphClassificationFlowTest.fixture();
        var parsed = GraphClassificationContract.parseDecisions(GraphClassificationFlowTest.PARTITION, fixture.catalog());
        var row = ClassificationEvidenceVerifier.memberEvidenceDiagnostics(fixture.catalog(), parsed,
            fixture.proofs(), Set.of("p1", "p999"), fixture.ledger()).get(1);
        assertEquals("AVAILABLE_NOT_SENT", row.get("status")); assertEquals(2, row.get("groupIndex"));
        assertEquals("E", row.get("state")); assertEquals("OUTSIDE_ATTRIBUTE", row.get("reason"));
        assertEquals(List.of("p2"), row.get("proposedProofIds"));
        assertEquals(List.of("p2"), row.get("availableRelevantProofIds"));
        assertEquals(List.of(), row.get("sentRelevantProofIds"));
        assertEquals(List.of(), row.get("citedRelevantProofIds"));
        assertEquals(List.of("p2"), parsed.groups().get(1).proofs());
        assertEquals(GraphClassificationContract.State.E, parsed.groups().get(1).state());
    }

    @Test
    void missingAvailableSourcePreservesTheExclusionAndNeverInventsProofOrUncertainty() {
        var fixture = GraphClassificationFlowTest.fixture();
        var parsed = GraphClassificationContract.parseDecisions(GraphClassificationFlowTest.PARTITION, fixture.catalog());
        var proofs = ClassificationProofCatalog.fromAuthorizedSpans(List.of(fixture.proofs().span("p1").orElseThrow()), Map.of());
        var row = ClassificationEvidenceVerifier.memberEvidenceDiagnostics(fixture.catalog(), parsed,
            proofs, Set.of("p1", "p2", "p999"), fixture.ledger()).get(1);
        assertEquals("NO_AVAILABLE_MEMBER_PROOF", row.get("status"));
        assertEquals("E", row.get("state")); assertEquals("OUTSIDE_ATTRIBUTE", row.get("reason"));
        assertEquals(List.of("p2"), row.get("proposedProofIds"));
        assertEquals(List.of(), row.get("availableRelevantProofIds"));
        assertEquals(List.of(), row.get("sentRelevantProofIds")); assertEquals(List.of(), row.get("citedRelevantProofIds"));
        assertEquals(GraphClassificationContract.State.E, parsed.groups().get(1).state());
        assertEquals(0, parsed.uncertainCount()); assertFalse(row.containsKey("supported"));
    }

    @Test
    void citationInAnotherGroupCannotHideTheMembersOwnMissingCitation() {
        var fixture = GraphClassificationFlowTest.fixture();
        var parsed = new GraphClassificationContract.DecisionParsed(List.of(
            new GraphClassificationContract.DecisionGroup(GraphClassificationContract.State.I,
                GraphClassificationContract.Reason.MATCH, List.of(1), List.of("p1", "p2")),
            new GraphClassificationContract.DecisionGroup(GraphClassificationContract.State.E,
                GraphClassificationContract.Reason.OUTSIDE_ATTRIBUTE, List.of(2), List.of("p1"))), List.of(), true, 0);
        var rows = ClassificationEvidenceVerifier.memberEvidenceDiagnostics(fixture.catalog(), parsed,
            fixture.proofs(), Set.of("p1", "p2"), fixture.ledger());
        assertEquals(List.of("p1"), rows.get(0).get("citedRelevantProofIds"));
        var row = rows.get(1);
        assertEquals("SENT_NOT_CITED", row.get("status")); assertEquals(2, row.get("groupIndex"));
        assertEquals(List.of("p2"), row.get("sentRelevantProofIds"));
        assertEquals(List.of(), row.get("citedRelevantProofIds"));
        assertEquals(List.of("p1"), row.get("proposedProofIds"));
    }

    @Test
    void sameTitleAndMemberNamesFromAnotherSubjectAreNeverEligibleSourceIds() {
        var fixture = GraphClassificationFlowTest.fixture();
        var foreign = new EvidenceLedger.ChunkFact(21L, "POST", 9L, "应用设计",
            "前端组件甲用于前端界面。存储服务乙是独立存储服务。", 0, 32);
        when(fixture.ledger().chunks()).thenReturn(List.of(foreign));
        when(fixture.ledger().chunk(21L)).thenReturn(Optional.of(foreign));
        when(fixture.ledger().entities()).thenReturn(List.of(
            new EvidenceLedger.EntityFact(1L, "应用设计", "POST", ""),
            new EvidenceLedger.EntityFact(9L, "应用设计", "POST", ""),
            new EvidenceLedger.EntityFact(2L, "前端组件甲", "SKILL", ""),
            new EvidenceLedger.EntityFact(3L, "存储服务乙", "SKILL", "")));
        var proofs = ClassificationProofCatalog.prepare(GraphClassificationFlowTest.QUESTION, fixture.ledger(), List.of(2L, 3L));
        var parsed = GraphClassificationContract.parseDecisions(GraphClassificationFlowTest.PARTITION, fixture.catalog());
        var rows = ClassificationEvidenceVerifier.memberEvidenceDiagnostics(fixture.catalog(), parsed,
            proofs, Set.of("p1", "p2"), fixture.ledger());
        assertEquals(2, rows.size());
        for (var row : rows) {
            assertEquals("NO_AVAILABLE_MEMBER_PROOF", row.get("status"));
            assertEquals(List.of(), row.get("availableRelevantProofIds"));
            assertEquals(List.of(), row.get("sentRelevantProofIds"));
            assertEquals(List.of(), row.get("citedRelevantProofIds"));
        }
        assertEquals(List.of("p1"), rows.get(0).get("proposedProofIds"));
        assertEquals(List.of("p2"), rows.get(1).get("proposedProofIds"));
    }

    @Test
    void emptyOrUnparseableProposalRemainsUnassignedWithoutAMemberEvidenceRejection() {
        var fixture = GraphClassificationFlowTest.fixture();
        var unavailable = new GraphClassificationContract.DecisionParsed(List.of(),
            List.of("CLASSIFICATION_INVALID_JSON"), false, 0);
        for (var parsed : new GraphClassificationContract.DecisionParsed[] {unavailable, null}) {
            var rows = ClassificationEvidenceVerifier.memberEvidenceDiagnostics(fixture.catalog(), parsed,
                fixture.proofs(), Set.of("p1", "p2"), fixture.ledger());
            assertEquals(2, rows.size());
            for (var row : rows) {
                assertEquals("UNASSIGNED", row.get("state")); assertEquals("UNASSIGNED", row.get("reason"));
                assertEquals("UNASSIGNED", row.get("status")); assertNull(row.get("groupIndex"));
                assertEquals(List.of(), row.get("proposedProofIds")); assertEquals(List.of(), row.get("citedRelevantProofIds"));
                assertEquals(true, row.get("retrievalOnly")); assertEquals(true, row.get("semanticSupportNotEstablished"));
            }
            assertEquals(List.of("p1"), rows.get(0).get("sentRelevantProofIds"));
            assertEquals(List.of("p2"), rows.get(1).get("sentRelevantProofIds"));
            assertFalse(rows.toString().contains("CLASSIFICATION_MEMBER_EVIDENCE_MISSING"));
        }
        assertFalse(unavailable.complete()); assertEquals(List.of(), unavailable.groups());
        assertEquals(List.of("CLASSIFICATION_INVALID_JSON"), unavailable.failures());
    }

    private static AnswerEvidenceVerifier.VerificationResult verifyObserved(GraphClassificationFlowTest.Fixture fixture,
            AiClient ai, List<Map<String, Object>> observations) {
        var parsed = GraphClassificationContract.parseDecisions(GraphClassificationFlowTest.PARTITION, fixture.catalog());
        return new ClassificationEvidenceVerifier().verify(GraphClassificationFlowTest.QUESTION, fixture.plan(),
            fixture.ledger(), fixture.catalog(), parsed, fixture.proofs(), Set.of("p1", "p2"), ai,
            ignored -> { }, observations::add);
    }

    private static AnswerEvidenceVerifier.VerificationResult verifyPartition(GraphClassificationFlowTest.Fixture fixture,
            String partition, Set<String> sent, AiClient ai, List<Map<String, Object>> observed) {
        var parsed = GraphClassificationContract.parseDecisions(partition, fixture.catalog());
        assertTrue(parsed.complete(), parsed.failures().toString());
        return new ClassificationEvidenceVerifier().verify(GraphClassificationFlowTest.QUESTION, fixture.plan(),
            fixture.ledger(), fixture.catalog(), parsed, fixture.proofs(), sent, ai, observed::add);
    }
}
