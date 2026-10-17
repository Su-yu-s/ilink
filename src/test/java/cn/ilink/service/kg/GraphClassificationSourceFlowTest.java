package cn.ilink.service.kg;

import cn.ilink.service.ai.AiClient;
import cn.ilink.service.ai.ChatRequestBudget.BudgetStopException;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic D production-flow checks stop at the mock generation boundary. */
class GraphClassificationSourceFlowTest {
    private static final String QUESTION = "「项目甲」在图谱中关联的技术栈，哪些不属于语言甲生态？";
    private record Fixture(GraphClassificationContract.Catalog catalog, EvidenceLedger ledger) { }

    @Test void productionSourceEntryRecordsDAndStopsBeforeUnverifiedPublication() {
        var trace = preChat(fixture());
        assertTrue(trace.stream().anyMatch(row -> "D-stage-1".equals(row.get("strategyCandidate"))));
        assertFalse(trace.stream().anyMatch(row -> row.containsKey("classificationDisposition")));
        assertEquals(ClassificationCoverageProofs.POLICY_SOURCE_PACKING, last(trace, "generationEvidence").get("packingPolicy"));
    }
    @Test void actualC2SeedIsSavedSeparatelyFromSourceBlockSelection() {
        var trace = preChat(fixture());
        var audit = last(trace, "classificationSelection");
        var blocks = map(audit.get("sourcePacking"));
        var seed = map(blocks.get("seedSelection"));
        var expected = map(audit.get(Boolean.TRUE.equals(audit.get("budgetExpansionAdopted"))
            ? "expandedSelection" : "defaultSelection"));
        assertEquals(expected, seed);
        assertEquals(seed.get("proofIds"), blocks.get("seedProofIds"));
        assertEquals(seed.get("tokenCount"), blocks.get("seedTokenCount"));
    }
    @Test void sourceRepresentationKeepsSeedCoverageInAllFourDimensions() {
        var trace = preChat(fixture());
        var blocks = map(last(trace, "classificationSelection").get("sourcePacking"));
        var seed = map(blocks.get("seedSelection"));
        for (String dimension : List.of("Mention", "Relevant", "Context", "Bound"))
            assertTrue(new LinkedHashSet<>((Collection<?>) blocks.get("sent" + dimension + "CandidateIds"))
                .containsAll((Collection<?>) seed.get("sent" + dimension + "CandidateIds")));
    }
    @Test void generationPacketAndSelectionReferToSameExactOriginalProofs() {
        var f = fixture(); var trace = preChat(f);
        var packet = last(trace, "generationEvidence");
        var audit = last(trace, "classificationSelection");
        assertEquals(packet.get("proofIds"), audit.get("selectedProofIds"));
        assertEquals(packet.get("tokenCount"), audit.get("tokenCount"));
        assertTrue((Integer) packet.get("tokenCount") <= EvidenceContext.MAX_BUDGET);
        var prepared = ClassificationCoverageProofs.prepare(QUESTION, f.catalog(), f.ledger());
        @SuppressWarnings("unchecked") var ids = (List<String>) packet.get("proofIds");
        assertTrue(prepared.proofs().validate(f.ledger(), ids).isEmpty());
    }
    @Test void completeAvailableCatalogIsDiagnosticAndDoesNotBecomeGenerationProofPool() {
        var f = fixture(); var trace = preChat(f);
        var audit = last(trace, "classificationSelection");
        var blocks = map(audit.get("sourcePacking"));
        var prepared = ClassificationCoverageProofs.prepare(QUESTION, f.catalog(), f.ledger());
        assertEquals(prepared.proofs().usedMetadata(prepared.proofs().spans().stream()
            .map(ClassificationProofCatalog.ProofSpan::id).toList()), blocks.get("availableProofCatalog"));
        assertEquals(last(trace, "generationEvidence").get("proofIds"), blocks.get("selectedProofIds"));
        assertEquals(ClassificationCoverageProofs.POLICY_SOURCE_PACKING,
            last(trace, "classificationEvidenceCoverage").get("packingPolicy"));
    }
    @Test void incompleteInventoryNeverDispatchesGenerationInNewEntry() {
        var f = fixture(); AiClient ai = mock(AiClient.class);
        var partial = new GraphClassificationContract.Catalog(true, "", f.catalog().candidates(), f.catalog().text(),
            Map.of(), true, false, "技术栈");
        var outcome = new GraphClassificationFlow().runSourceBlocks(QUESTION, QueryEvidencePlan.fromQuestion(QUESTION),
            f.ledger(), partial, ai, new ArrayList<>());
        assertFalse(outcome.supported());
        assertEquals("CLASSIFICATION_INVENTORY_INCOMPLETE", outcome.coverageReason());
        verifyNoInteractions(ai);
    }

    private static List<Map<String, Object>> preChat(Fixture f) {
        AiClient ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt())).thenThrow(new BudgetStopException("SYNTHETIC_PRE_CHAT_STOP"));
        List<Map<String, Object>> trace = new ArrayList<>();
        assertThrows(BudgetStopException.class, () -> new GraphClassificationFlow().runSourceBlocks(QUESTION,
            QueryEvidencePlan.fromQuestion(QUESTION), f.ledger(), f.catalog(), ai, trace));
        verify(ai, times(1)).chat(anyList(), eq(1200));
        return trace;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
    private static Map<String, Object> last(List<Map<String, Object>> trace, String key) {
        return trace.stream().filter(row -> row.containsKey(key)).reduce((first, second) -> second)
            .map(row -> map(row.get(key))).orElseThrow();
    }
    private static Fixture fixture() {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        String title = "项目甲的完整授权原始来源标题".repeat(10);
        List<GraphClassificationContract.Candidate> candidates = new ArrayList<>();
        List<EvidenceLedger.RelationFact> edges = new ArrayList<>();
        List<EvidenceLedger.EntityFact> entities = new ArrayList<>();
        entities.add(new EvidenceLedger.EntityFact(42L, title, "ASSET", ""));
        StringBuilder body = new StringBuilder();
        for (int n = 1; n <= 8; n++) {
            String name = "CloudModel" + n;
            String line = name + " 是跨语言模型客户端。";
            body.append(line).append('\n');
            candidates.add(new GraphClassificationContract.Candidate(n, 42L, 100L + n, title,
                name, "SKILL", "MENTIONS", "OUT", List.of(500L + n)));
            var edge = new EvidenceLedger.RelationFact(500L + n, 42L, 100L + n, "MENTIONS", null, null,
                "ASSET:7", 9L, line);
            edges.add(edge); when(ledger.relation(edge.id())).thenReturn(Optional.of(edge));
            entities.add(new EvidenceLedger.EntityFact(100L + n, name, "SKILL", ""));
        }
        var chunk = new EvidenceLedger.ChunkFact(9L, "ASSET", 7L, title, body.toString(), 0, body.length());
        when(ledger.chunks()).thenReturn(List.of(chunk)); when(ledger.chunk(9L)).thenReturn(Optional.of(chunk));
        when(ledger.entities()).thenReturn(entities); when(ledger.relations()).thenReturn(edges);
        for (var entity : entities) when(ledger.entity(entity.id())).thenReturn(Optional.of(entity));
        var catalog = new GraphClassificationContract.Catalog(true, "", candidates, "完整八项候选目录\n",
            Map.of(), true, true, "技术栈 不属于语言甲生态");
        return new Fixture(catalog, ledger);
    }
}
