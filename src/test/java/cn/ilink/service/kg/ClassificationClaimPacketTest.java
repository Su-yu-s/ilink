package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ClassificationClaimPacketTest {
    @Test
    void uncitedCandidateDispositionsRemainIndependentOfTheProofSubset() {
        var fixture = GraphClassificationFlowTest.fixture();
        var packet = packet(fixture, List.of("p1"));
        assertTrue(packet.text().contains("2:存储服务乙 r#11"));
        assertTrue(packet.text().contains("\"s\":\"E\""));
        assertTrue(packet.text().contains("前端组件甲用于前端界面。"));
        assertFalse(packet.text().contains("存储服务乙是独立存储服务。"));
    }

    @Test
    void contiguousSameChunkProofsMergeWithAllIdsAndReconstructOriginalText() {
        var fixture = GraphClassificationFlowTest.fixture();
        var packet = packet(fixture, List.of("p1", "p2", "p1"));
        assertEquals(1, packet.packedSourceSpanCount());
        assertTrue(packet.text().contains("p1,p2"));
        assertTrue(packet.text().contains("前端组件甲用于前端界面。存储服务乙是独立存储服务。"));
        assertEquals("classification-claim-spans-v1", packet.packingPolicy());
    }

    @Test
    void unknownOrRevokedProofCannotBePackedIntoVerifierInput() {
        var fixture = GraphClassificationFlowTest.fixture();
        assertThrows(IllegalArgumentException.class, () -> packet(fixture, List.of("p999")));
        when(fixture.ledger().chunk(20L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> packet(fixture, List.of("p1")));
    }

    @Test
    void candidateBindingsAvoidRepeatedRelationFactTextWhileDispositionIsAlwaysPreserved() {
        var fixture = GraphClassificationFlowTest.fixture();
        var checked = new AnswerEvidenceVerifier.VerificationResult(true, List.of(), Set.of(), Set.of(10L, 11L), Set.of(20L));
        var packet = VerificationEvidenceContext.packClaims(fixture.ledger(), fixture.plan(), checked,
            fixture.catalog().text(), GraphClassificationFlowTest.PARTITION, fixture.proofs(), List.of("p1", "p2"));
        assertEquals(2, packet.relationCount());
        assertEquals(1, occurrences(packet.text(), "前端组件甲用于前端界面。"));
        assertTrue(packet.text().contains(GraphClassificationFlowTest.PARTITION));
        assertEquals(EvidenceContext.tokens(packet.text()), packet.tokenCount());
    }

    @Test
    void oversizedProofPreservesDispositionAndFailsBudgetRatherThanDroppingCandidates() {
        var fixture = GraphClassificationFlowTest.fixture();
        String fullInventory = fixture.catalog().text() + "完整授权范围。".repeat(1800);
        var packet = VerificationEvidenceContext.packClaims(fixture.ledger(), fixture.plan(), checked(),
            fullInventory, GraphClassificationFlowTest.PARTITION, fixture.proofs(), List.of("p1"));
        assertTrue(packet.tokenCount() > EvidenceContext.MAX_BUDGET);
        assertTrue(packet.text().contains("2:存储服务乙 r#11"));
        assertTrue(packet.text().contains(GraphClassificationFlowTest.PARTITION));
        assertEquals(false, packet.metadata().get("withinBudget"));
    }

    @Test
    void temporalFallbackCannotEnterClassificationProofPacking() {
        var fixture = GraphClassificationFlowTest.fixture();
        var plan = QueryEvidencePlan.fromQuestion("2026年3月，应用设计与哪些竞赛有关联？");
        assertTrue(plan.requiresTemporal());
        assertThrows(IllegalArgumentException.class, () -> VerificationEvidenceContext.packClaims(
            fixture.ledger(), plan, checked(), fixture.catalog().text(), GraphClassificationFlowTest.PARTITION,
            fixture.proofs(), List.of("p1")));
    }

    private static VerificationEvidenceContext.Packet packet(GraphClassificationFlowTest.Fixture fixture, List<String> proofs) {
        return VerificationEvidenceContext.packClaims(fixture.ledger(), fixture.plan(), checked(), fixture.catalog().text(),
            GraphClassificationFlowTest.PARTITION, fixture.proofs(), proofs);
    }
    private static AnswerEvidenceVerifier.VerificationResult checked() {
        return new AnswerEvidenceVerifier.VerificationResult(true, List.of(), Set.of(), Set.of(), Set.of());
    }
    private static int occurrences(String text, String substring) { return text.split(java.util.regex.Pattern.quote(substring), -1).length - 1; }
}
