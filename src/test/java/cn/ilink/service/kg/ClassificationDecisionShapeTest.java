package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClassificationDecisionShapeTest {
    @Test
    void allFourReasonsAndEightyEightMembersFitTheMinifiedStressShape() {
        var catalog = catalog(88); var proofs = Set.of("p1", "p5", "p9", "p10", "p17", "p19", "p23", "p49", "p58");
        for (boolean stringIds : List.of(false, true)) {
            String shape = ClassificationDecisionShape.stressPartition(catalog, proofs, stringIds);
            var parsed = GraphClassificationContract.parseDecisions(shape, catalog);
            assertTrue(parsed.complete(), parsed.failures().toString());
            assertEquals(4, parsed.groups().size());
            assertEquals(88, parsed.groups().stream().mapToInt(group -> group.ids().size()).sum());
            assertTrue(EvidenceContext.tokens(shape) <= 1200, "Stress shape exceeds the frozen completion limit");
        }
    }

    @Test
    void estimateReportsItsScopeInsteadOfSupplierEnforcementOrSemanticProof() {
        var metadata = ClassificationDecisionShape.assess(catalog(88), Set.of("p1", "p10", "p58"));
        assertEquals(88, metadata.get("candidateCount")); assertEquals(3, metadata.get("sentProofCount"));
        assertEquals(1200, metadata.get("completionLimit"));
        assertEquals(false, metadata.get("hardOutputGuarantee"));
        assertEquals(false, metadata.get("providerSchemaEnforced")); assertEquals(false, metadata.get("semanticProposal"));
        assertEquals("PROMPT_AND_STRICT_PARSER", metadata.get("decodingMode"));
    }

    @Test
    void shortCatalogHasNoEmptyDecisionGroups() {
        var catalog = catalog(2);
        var parsed = GraphClassificationContract.parseDecisions(
            ClassificationDecisionShape.stressPartition(catalog, Set.of("p1"), false), catalog);
        assertTrue(parsed.complete(), parsed.failures().toString()); assertEquals(2, parsed.groups().size());
    }

    @Test
    void proofOrderingCannotChangeTheReportedShape() {
        var catalog = catalog(8);
        assertEquals(ClassificationDecisionShape.assess(catalog, new java.util.LinkedHashSet<>(List.of("p9", "p1", "p5"))),
            ClassificationDecisionShape.assess(catalog, new java.util.LinkedHashSet<>(List.of("p5", "p9", "p1"))));
    }

    @Test
    void unavailableProofsDoNotProduceALegalFactualDecision() {
        var catalog = catalog(4);
        var parsed = GraphClassificationContract.parseDecisions(
            ClassificationDecisionShape.stressPartition(catalog, Set.of(), false), catalog);
        assertFalse(parsed.complete()); assertTrue(parsed.failures().contains("CLASSIFICATION_PROOF_REQUIRED"));
        assertEquals(false, ClassificationDecisionShape.assess(catalog, Set.of()).get("semanticProposal"));
    }

    private static GraphClassificationContract.Catalog catalog(int count) {
        var candidates = new ArrayList<GraphClassificationContract.Candidate>();
        for (int i = 1; i <= count; i++) candidates.add(new GraphClassificationContract.Candidate(i, 1L,
            100L + i, "测试文档", "候选" + i, "SKILL", "MENTIONS", "OUT", List.of(200L + i)));
        return new GraphClassificationContract.Catalog(true, "", candidates, "测试目录", Map.of(), true, true, "技术栈");
    }
}
