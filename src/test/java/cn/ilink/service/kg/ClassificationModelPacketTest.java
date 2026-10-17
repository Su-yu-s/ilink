package cn.ilink.service.kg;

import cn.ilink.service.ai.AiClient;
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

/** D2 packet and production verification gates; synthetic mocks only. */
class ClassificationModelPacketTest {
    @Test void typedBindingsAvoidReintroducingEveryNumericRelationIntoTheModelText() {
        var f = GraphClassificationFlowTest.fixture(); var packet = packet(f, List.of("p1", "p2"));
        assertEquals(2, packet.relationCount());
        assertFalse(packet.text().contains("r#10")); assertFalse(packet.text().contains("r#11"));
        assertTrue(packet.text().contains("1:\"前端组件甲\""));
        assertTrue(packet.text().contains("2:\"存储服务乙\""));
        assertTrue(packet.text().contains("MENTIONS"));
        assertEquals(ClassificationModelPacket.POLICY, packet.packingPolicy());
    }

    @Test void completeInventorySurvivesCitedSourceSubset() {
        var f = GraphClassificationFlowTest.fixture(); var packet = packet(f, List.of("p1"));
        assertTrue(packet.text().contains("2:\"存储服务乙\""));
        assertTrue(packet.text().contains(GraphClassificationFlowTest.PARTITION));
        assertFalse(packet.text().contains("存储服务乙是独立存储服务。"));
    }

    @Test void generationAndVerificationUseExactlyTheSameSourceRepresentation() {
        var f = GraphClassificationFlowTest.fixture(); var ids = List.of("p1", "p2");
        var source = ClassificationModelContext.sources(f.proofs(), ids);
        var packet = packet(f, ids);
        assertTrue(packet.text().endsWith(source.text()));
        assertEquals(source.sourceSpanCount(), packet.packedSourceSpanCount());
        assertEquals(1, packet.packedSourceSpanCount());
        assertEquals(EvidenceContext.tokens(packet.text()), packet.tokenCount());
    }

    @Test void unknownOrRevokedProofCannotEnterTheNewPacket() {
        var f = GraphClassificationFlowTest.fixture();
        assertThrows(IllegalArgumentException.class, () -> packet(f, List.of("p999")));
        when(f.ledger().chunk(20L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> packet(f, List.of("p1")));
    }

    @Test void forgedProjectionIsRejectedBeforePacking() {
        var f = GraphClassificationFlowTest.fixture(); var p = ClassificationModelContext.project(f.catalog());
        var forged = new ClassificationModelContext.Projection(p.text().replace("存储服务乙", "错误对象"),
            p.tokenCount(), p.shownCount(), p.complete(), p.typedCatalogSha256());
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelPacket.pack(f.ledger(), f.plan(), checked(),
            f.catalog(), forged, GraphClassificationFlowTest.PARTITION, f.proofs(), List.of("p1")));
    }

    @Test void projectedNamesStillBindToTheActualLedgerEntities() {
        var f = GraphClassificationFlowTest.fixture();
        when(f.ledger().entity(2L)).thenReturn(Optional.of(new EvidenceLedger.EntityFact(2L, "别的组件", "SKILL", "")));
        assertThrows(IllegalArgumentException.class, () -> packet(f, List.of("p1", "p2")));
    }

    @Test void omittedNumericLabelsDoNotBypassDirectedEndpointChecks() {
        var f = GraphClassificationFlowTest.fixture();
        when(f.ledger().relation(10L)).thenReturn(Optional.of(new EvidenceLedger.RelationFact(10L,
            2L, 1L, "MENTIONS", null, null, "POST:1", 20L, "前端组件甲用于前端界面。")));
        assertThrows(IllegalArgumentException.class, () -> packet(f, List.of("p1")));
    }

    @Test void checkedRelationOutsideTypedCatalogCannotDisappearSilently() {
        var f = GraphClassificationFlowTest.fixture();
        var outside = new AnswerEvidenceVerifier.VerificationResult(true, List.of(), Set.of(), Set.of(999L), Set.of());
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelPacket.pack(f.ledger(), f.plan(), outside,
            f.catalog(), ClassificationModelContext.project(f.catalog()), GraphClassificationFlowTest.PARTITION,
            f.proofs(), List.of("p1")));
    }

    @Test void temporalQuestionsRemainOutsideClassificationPacketPacking() {
        var f = GraphClassificationFlowTest.fixture(); var temporal = QueryEvidencePlan.fromQuestion("2026年3月应用设计关联哪些竞赛？");
        assertTrue(temporal.requiresTemporal());
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelPacket.pack(f.ledger(), temporal,
            checked(), f.catalog(), ClassificationModelContext.project(f.catalog()), GraphClassificationFlowTest.PARTITION,
            f.proofs(), List.of("p1")));
    }

    @Test void actualPacketRatherThanHistoricalExpandedComparisonControlsTheBudget() {
        var f = GraphClassificationFlowTest.fixture(); var old = f.catalog();
        var raw = new GraphClassificationContract.Catalog(true, "", old.candidates(), old.text() + "原始数字绑定。".repeat(2000),
            old.metadata(), old.queryComplete(), old.inventoryComplete(), old.focusText());
        var changed = new GraphClassificationFlowTest.Fixture(f.ledger(), f.plan(), raw, f.proofs());
        var ai = mock(AiClient.class); var packets = new ArrayList<Map<String, Object>>();
        when(ai.chat(anyList(), anyInt())).thenReturn(GraphClassificationFlowTest.response(GraphClassificationFlowTest.SUPPORTED, "stop"));
        var verified = verifyProposal(changed, GraphClassificationFlowTest.PARTITION, Set.of("p1", "p2"), ai, packets);
        assertTrue(verified.supported()); assertEquals(1, packets.size());
        assertTrue((Integer) packets.get(0).get("tokenCount") <= EvidenceContext.MAX_BUDGET);
        assertTrue((Integer) packets.get(0).get("expandedTokenCount") > EvidenceContext.MAX_BUDGET);
        verify(ai, times(1)).chat(anyList(), eq(AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET));
    }

    @Test void actualOversizeSourcePacketStopsBeforePaidVerificationWithoutDeletingCandidates() {
        var f = oversizedFixture(); var ai = mock(AiClient.class); var packets = new ArrayList<Map<String, Object>>();
        var result = verifyProposal(f, GraphClassificationFlowTest.PARTITION, Set.of("p1", "p2"), ai, packets);
        assertFalse(result.supported());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("EVIDENCE_BUDGET")));
        assertTrue((Integer) packets.get(0).get("tokenCount") > EvidenceContext.MAX_BUDGET);
        assertTrue(packets.get(0).get("text").toString().contains("2:\"存储服务乙\""));
        verifyNoInteractions(ai);
    }

    @Test void actuallyUnsentProofIsStillRejectedBeforeTheNewSemanticVerifier() {
        var f = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        var result = verifyProposal(f, GraphClassificationFlowTest.PARTITION, Set.of("p1"), ai, new ArrayList<>());
        assertFalse(result.supported()); assertTrue(result.reasons().contains("CLASSIFICATION_PROOF_NOT_SENT:p2"));
        verifyNoInteractions(ai);
    }

    @Test void semanticUncertaintyRemainsARejectionAfterLocalCoveragePasses() {
        var f = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt())).thenReturn(GraphClassificationFlowTest.response(
            "{\"facts\":\"UNCERTAIN\",\"coverage\":\"COMPLETE\",\"unsupported\":[],\"missing\":[]}", "stop"));
        var result = verifyProposal(f, GraphClassificationFlowTest.PARTITION, Set.of("p1", "p2"), ai, new ArrayList<>());
        assertFalse(result.supported());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.startsWith("SEMANTIC_UNCERTAIN:FACTS:")));
    }

    @Test void verificationReceivesOnlyProposalCitedSourcesWhileInventoryRemainsComplete() {
        var f = GraphClassificationFlowTest.fixture(); var ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt())).thenReturn(GraphClassificationFlowTest.response(GraphClassificationFlowTest.SUPPORTED, "stop"));
        String oneCited = "{\"groups\":[{\"s\":\"I\",\"reason\":\"MATCH\",\"ids\":\"1\",\"proofs\":[\"p1\"]},"
            + "{\"s\":\"U\",\"reason\":\"INSUFFICIENT\",\"ids\":\"2\",\"proofs\":[]}]}";
        assertTrue(verifyProposal(f, oneCited, Set.of("p1", "p2"), ai, new ArrayList<>()).supported());
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Map<String, String>>> captured = ArgumentCaptor.forClass(List.class);
        verify(ai, times(1)).chat(captured.capture(), eq(AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET));
        String input = captured.getValue().get(1).get("content");
        assertTrue(input.contains("2:\"存储服务乙\""));
        assertFalse(input.contains("存储服务乙是独立存储服务。"));
    }

    private static VerificationEvidenceContext.Packet packet(GraphClassificationFlowTest.Fixture f, List<String> ids) {
        return ClassificationModelPacket.pack(f.ledger(), f.plan(), checked(), f.catalog(),
            ClassificationModelContext.project(f.catalog()), GraphClassificationFlowTest.PARTITION, f.proofs(), ids);
    }

    private static AnswerEvidenceVerifier.VerificationResult checked() {
        return new AnswerEvidenceVerifier.VerificationResult(true, List.of(), Set.of(1L, 2L, 3L), Set.of(10L, 11L), Set.of(20L));
    }

    private static AnswerEvidenceVerifier.VerificationResult verifyProposal(GraphClassificationFlowTest.Fixture f,
            String proposal, Set<String> sent, AiClient ai, List<Map<String, Object>> packets) {
        return new ClassificationEvidenceVerifier().verifyModelContext(GraphClassificationFlowTest.QUESTION, f.plan(), f.ledger(),
            f.catalog(), GraphClassificationContract.parseDecisions(proposal, f.catalog()), f.proofs(), sent,
            ClassificationModelContext.project(f.catalog()), ai, packets::add, ignored -> { });
    }

    private static GraphClassificationFlowTest.Fixture oversizedFixture() {
        var f = GraphClassificationFlowTest.fixture();
        String first = "前端组件甲用于前端界面。" + "说明内容 ".repeat(6000);
        String second = "存储服务乙是独立存储服务。"; String body = first + second;
        var chunk = new EvidenceLedger.ChunkFact(20L, "POST", 1L, "应用设计", body, 0, body.length());
        when(f.ledger().chunk(20L)).thenReturn(Optional.of(chunk)); when(f.ledger().chunks()).thenReturn(List.of(chunk));
        var spans = List.of(new ClassificationProofCatalog.ProofSpan("p1", 20L, "POST", 1L, "应用设计", 0,
                first.length(), first, Set.of(10L)),
            new ClassificationProofCatalog.ProofSpan("p2", 20L, "POST", 1L, "应用设计", first.length(), body.length(), second, Set.of(11L)));
        return new GraphClassificationFlowTest.Fixture(f.ledger(), f.plan(), f.catalog(),
            ClassificationProofCatalog.fromAuthorizedSpans(spans, Map.of()));
    }
}
