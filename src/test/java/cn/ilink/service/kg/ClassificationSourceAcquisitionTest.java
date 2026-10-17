package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClassificationSourceAcquisitionTest {
    private static final String QUESTION = "「项目甲」在图谱中关联的技术栈，哪些不属于语言甲生态？";
    private record Fixture(GraphClassificationContract.Catalog catalog, EvidenceLedger ledger,
                           ClassificationCoverageProofs.Prepared prepared) { }

    @Test void missingSourceFollowsRealRelationInsteadOfModelDocumentId() {
        var f = fixture(1, List.of(), "POST:7", false);
        var plan = assess(f, true, 4);
        assertEquals(List.of(1), plan.missingCandidateIds());
        assertEquals(Map.of("relation_id", 501L, "member_entity_id", 101L), plan.requests().get(0).parameters());
        assertEquals("POST:7", plan.requests().get(0).sourceDocId());
        assertFalse(plan.metadata().get("semanticSupportEstablished").equals(true));
    }
    @Test void existingAvailableProofDoesNotSpendToolForUnsentEvidence() {
        var f = fixture(1, List.of(chunk(9, 7, "CloudModel1 是模型客户端。")), "POST:7", false);
        assertEquals("NO_SOURCE_GAP", assess(f, true, 4).status());
        assertTrue(assess(f, true, 4).requests().isEmpty());
    }
    @Test void SameNamedTextInDifferentDocumentDoesNotFillDeclaredSource() {
        var f = fixture(1, List.of(chunk(9, 8, "CloudModel1 是模型客户端。")), "POST:7", false);
        assertEquals(List.of(1), assess(f, true, 4).missingCandidateIds());
    }
    @Test void unrelatedChunkFromCorrectDocumentDoesNotFillGap() {
        var f = fixture(1, List.of(chunk(9, 7, "服务支持多种模型提供方。")), "POST:7", false);
        assertEquals(1, assess(f, true, 4).requests().size());
    }
    @Test void missingToolPreservesGapWithoutMakingRequests() {
        var plan = assess(fixture(1, List.of(), "POST:7", false), false, 4);
        assertEquals("TOOL_UNAVAILABLE", plan.status());
        assertEquals(List.of(1), plan.missingCandidateIds());
        assertTrue(plan.requests().isEmpty());
    }
    @Test void exhaustedToolBudgetDoesNotScheduleRead() {
        var plan = assess(fixture(1, List.of(), "POST:7", false), true, 0);
        assertEquals("TOOL_BUDGET_EXHAUSTED", plan.status());
        assertTrue(plan.requests().isEmpty());
    }
    @Test void manyGapsKeepAllMissingIdsButScheduleAtMostTwoReads() {
        var plan = assess(fixture(5, List.of(), "POST:7", false), true, 8);
        assertEquals(List.of(1, 2, 3, 4, 5), plan.missingCandidateIds());
        assertEquals(List.of(1, 2), plan.requests().stream().map(ClassificationSourceAcquisition.Need::candidateId).toList());
    }
    @Test void oneRemainingToolSchedulesOnlyOneRead() {
        assertEquals(1, assess(fixture(5, List.of(), "POST:7", false), true, 1).requests().size());
    }
    @Test void malformedSourceIsNotConvertedToAnotherDocument() {
        var plan = assess(fixture(1, List.of(), "POST:7:8", false), true, 4);
        assertEquals("NO_DECLARED_SOURCE", plan.status());
        assertEquals(List.of(1), plan.noDeclaredSourceCandidateIds());
    }
    @Test void absentDeclaredSourceDoesNotBecomeGlobalAbsenceAssertion() {
        var plan = assess(fixture(1, List.of(), null, false), true, 4);
        assertEquals("NO_DECLARED_SOURCE", plan.status());
        assertTrue(plan.requests().isEmpty());
        assertEquals(List.of(1), plan.missingCandidateIds());
    }
    @Test void wrongRelationEndpointsCannotSupplySourceRequest() {
        var f = fixture(1, List.of(), "POST:7", false);
        when(f.ledger().relation(501L)).thenReturn(Optional.of(new EvidenceLedger.RelationFact(
            501L, 999L, 101L, "MENTIONS", null, null, "POST:7", null, null)));
        assertTrue(assess(f, true, 4).requests().isEmpty());
    }
    @Test void inboundMemberKeepsItsOwnEndpointIdentity() {
        var plan = assess(fixture(1, List.of(), "ASSET:7", true), true, 4);
        assertEquals(101L, plan.requests().get(0).memberEntityId());
        assertEquals("ASSET:7", plan.requests().get(0).sourceDocId());
    }
    @Test void temporalCatalogIsExplicitlySkipped() {
        var f = fixture(1, List.of(), "POST:7", false);
        var skipped = new GraphClassificationContract.Catalog(false, "TEMPORAL_QUERY", f.catalog().candidates(),
            "", Map.of(), true, true, "");
        assertEquals("NOT_GRAPH_CLASSIFICATION", ClassificationSourceAcquisition.plan(
            skipped, f.ledger(), f.prepared(), true, 4).status());
    }
    @Test void incompleteInventoryDoesNotTriggerClassificationAcquisition() {
        var f = fixture(1, List.of(), "POST:7", false);
        var partial = new GraphClassificationContract.Catalog(true, "", f.catalog().candidates(),
            "", Map.of(), true, false, "");
        assertEquals("INVENTORY_UNCONFIRMED", ClassificationSourceAcquisition.plan(
            partial, f.ledger(), f.prepared(), true, 4).status());
    }
    @Test void incompleteGraphQueryDoesNotClaimCompleteMemberSourceScope() {
        var f = fixture(1, List.of(), "POST:7", false);
        var partial = new GraphClassificationContract.Catalog(true, "", f.catalog().candidates(),
            "", Map.of(), false, true, "");
        assertTrue(ClassificationSourceAcquisition.plan(partial, f.ledger(), f.prepared(), true, 4).requests().isEmpty());
    }
    @Test void acquiredExactTextResolvesProofAvailabilityWithoutAssigningCategory() {
        var f = fixture(1, List.of(), "POST:7", false);
        assertFalse(ClassificationSourceAcquisition.hasProof(f.catalog(), 1, f.prepared(), f.ledger()));
        var added = chunk(9, 7, "CloudModel1 是模型客户端。\n");
        when(f.ledger().chunks()).thenReturn(List.of(added));
        when(f.ledger().chunk(9L)).thenReturn(Optional.of(added));
        var prepared = ClassificationCoverageProofs.prepare(QUESTION, f.catalog(), f.ledger());
        assertTrue(ClassificationSourceAcquisition.hasProof(f.catalog(), 1, prepared, f.ledger()));
        assertFalse(ClassificationSourceAcquisition.hasProof(f.catalog(), 2, prepared, f.ledger()));
    }

    private static ClassificationSourceAcquisition.Plan assess(Fixture f, boolean tools, int remaining) {
        return ClassificationSourceAcquisition.plan(f.catalog(), f.ledger(), f.prepared(), tools, remaining);
    }
    private static EvidenceLedger.ChunkFact chunk(long id, long doc, String body) {
        return new EvidenceLedger.ChunkFact(id, "POST", doc, "某份来源资料", body, 0, body.length());
    }
    private static Fixture fixture(int count, List<EvidenceLedger.ChunkFact> chunks, String source, boolean inbound) {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        List<GraphClassificationContract.Candidate> candidates = new ArrayList<>();
        List<EvidenceLedger.RelationFact> edges = new ArrayList<>();
        List<EvidenceLedger.EntityFact> entities = new ArrayList<>();
        entities.add(new EvidenceLedger.EntityFact(42L, "项目甲", "ASSET", ""));
        for (int number = 1; number <= count; number++) {
            long member = 100L + number, relation = 500L + number;
            String name = "CloudModel" + number;
            candidates.add(new GraphClassificationContract.Candidate(number, 42L, member, "项目甲", name,
                "SKILL", "MENTIONS", inbound ? "IN" : "OUT", List.of(relation)));
            var edge = new EvidenceLedger.RelationFact(relation, inbound ? member : 42L, inbound ? 42L : member,
                "MENTIONS", null, null, source, null, null);
            edges.add(edge);
            when(ledger.relation(relation)).thenReturn(Optional.of(edge));
            entities.add(new EvidenceLedger.EntityFact(member, name, "SKILL", ""));
        }
        when(ledger.chunks()).thenReturn(chunks);
        when(ledger.relations()).thenReturn(edges);
        when(ledger.entities()).thenReturn(entities);
        for (var chunk : chunks) when(ledger.chunk(chunk.id())).thenReturn(Optional.of(chunk));
        for (var entity : entities) when(ledger.entity(entity.id())).thenReturn(Optional.of(entity));
        var catalog = new GraphClassificationContract.Catalog(true, "", candidates, "完整目录\n",
            Map.of(), true, true, "技术栈 不属于语言甲生态");
        return new Fixture(catalog, ledger, ClassificationCoverageProofs.prepare(QUESTION, catalog, ledger));
    }
}
