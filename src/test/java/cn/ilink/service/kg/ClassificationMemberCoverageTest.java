package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Synthetic source fixtures only; no model, database, saved-batch replay or classification gold. */
class ClassificationMemberCoverageTest {
    private static final String QUESTION = "《项目A》哪些技术不属于Java生态？";

    @Test
    void missingRelevantMemberPrecedesRepeatedContextForAlreadyCoveredMember() {
        var fixture = fixture("Alpha 名称。\nAlpha 使用独立运行时。\nBeta " + "原始记录 ".repeat(30) + "。",
            List.of(member(1, "Alpha"), member(2, "Beta")), List.of());
        var prepared = prepare(fixture);
        var packed = prepared.selectMemberCoverage(fixture.catalog().text(), 2000);
        var history = history(packed);
        assertTrue(history.size() >= 3);
        assertEquals("Alpha 名称。", prepared.proofs().span((String) history.get(0).get("proofId")).orElseThrow().text());
        assertEquals("RELEVANT", history.get(1).get("phase"));
        assertEquals(List.of(1, 2), history.get(1).get("relevantCandidateIds"));
        assertTrue(prepared.proofs().span((String) history.get(1).get("proofId")).orElseThrow().text().startsWith("Beta "));
        assertEquals("CONTEXT_BOUND", history.get(2).get("phase"));
        assertEquals(List.of(1, 2), prepared.memberSelectionMetadata(packed.selection().proofIds()).get("sentRelevantCandidateIds"));
    }

    @Test
    void exactBoundOnlyMemberCanBeCoveredWithoutInventingMentionOrContext() {
        String fact = "该项登记在此。";
        var fixture = fixture("Alpha 名称。\n" + fact, List.of(member(1, "Alpha"), member(2, "Beta")),
            List.of(edge(702, 42, 102, "POST:6", 9L, fact)));
        var prepared = prepare(fixture);
        var packed = prepared.selectMemberCoverage(fixture.catalog().text(), 2000);
        var metadata = prepared.memberSelectionMetadata(packed.selection().proofIds());
        assertEquals(List.of(1, 2), metadata.get("sentRelevantCandidateIds"));
        assertEquals(List.of(1), metadata.get("sentMentionCandidateIds"));
        assertEquals(List.of(2), metadata.get("sentBoundCandidateIds"));
        assertEquals(List.of(), metadata.get("sentContextCandidateIds"));
        assertTrue(packed.selection().text().contains(fact));
        assertEquals(true, metadata.get("semanticSupportNotEstablished"));
    }

    @Test
    void hardBudgetIncludesCompleteDirectoryCoordinatesSourceAndEveryReverseMapping() {
        var fixture = fixture("Alpha 前端。\nBeta 服务端。", List.of(member(1, "Alpha"), member(2, "Beta")), List.of());
        var prepared = prepare(fixture);
        var full = prepared.selectMemberCoverage(fixture.catalog().text(), 3000).selection();
        int budget = full.tokenCount() - 1;
        var packed = prepared.selectMemberCoverage(fixture.catalog().text(), budget);
        var selected = packed.selection();
        assertTrue(selected.withinBudget());
        assertTrue(selected.tokenCount() <= budget);
        assertEquals(EvidenceContext.tokens(selected.text()), selected.tokenCount());
        assertEquals(selected.tokenCount(), packed.diagnostics().get("tokenCount"));
        assertTrue(selected.text().startsWith(fixture.catalog().text()));
        assertTrue(selected.proofIds().size() < full.proofIds().size());
        assertTrue(selected.text().contains("\n1->"));
        assertTrue(selected.text().contains("\n2->"));
        assertTrue(selected.text().contains("不等于全局无证据"));
        assertTrue(selected.text().contains("不能自动赋予U/I/E"));
        assertFalse(selected.text().contains("检索对应 p"));
        for (String id : selected.proofIds()) {
            var proof = prepared.proofs().span(id).orElseThrow();
            assertTrue(selected.text().contains(id + "=c#" + proof.chunkId() + '@' + proof.start() + ':' + proof.end()));
            assertTrue(selected.text().contains(proof.text()));
            assertEquals(fixture.ledger().chunk(proof.chunkId()).orElseThrow().text().substring(proof.start(), proof.end()), proof.text());
        }
    }

    @Test
    void oversizedPrefixRetainsInventoryAndEmptyMappingAndReportsAvailableButUnsent() {
        var fixture = fixture("Alpha 名称。", List.of(member(1, "Alpha"), member(2, "Missing")), List.of());
        var prepared = prepare(fixture);
        String prefix = "完整范围不可删除。".repeat(500);
        var packed = prepared.selectMemberCoverage(prefix, 80);
        assertFalse(packed.selection().withinBudget());
        assertTrue(packed.selection().proofIds().isEmpty());
        assertTrue(packed.selection().text().startsWith(prefix));
        assertTrue(packed.selection().text().contains(fixture.catalog().text()));
        assertTrue(packed.selection().text().endsWith("1->[]\n2->[]\n"));
        assertEquals("FIXED_TEXT_OVER_BUDGET", packed.diagnostics().get("terminationReason"));
        var metadata = prepared.memberSelectionMetadata(packed.selection().proofIds());
        assertEquals(List.of(1), metadata.get("availableButUnsentCandidateIds"));
        assertEquals("AVAILABLE_BUT_UNSENT", mapping(metadata).get(0).get("evidenceDeliveryStatus"));
        assertEquals("NO_AVAILABLE_PROOF", mapping(metadata).get(1).get("evidenceDeliveryStatus"));
    }

    @Test
    void requestedBudgetIsClampedToZeroThroughThreeThousandWithoutTruncation() {
        var fixture = fixture("Alpha 前端。\nBeta " + "记录 ".repeat(600) + "。",
            List.of(member(1, "Alpha"), member(2, "Beta")), List.of());
        var prepared = prepare(fixture);
        var maximum = prepared.selectMemberCoverage(fixture.catalog().text(), 3000);
        var excess = prepared.selectMemberCoverage(fixture.catalog().text(), Integer.MAX_VALUE);
        assertEquals(maximum.selection(), excess.selection());
        assertEquals(3000, excess.diagnostics().get("budget"));
        assertTrue(excess.selection().tokenCount() <= 3000);
        var negative = prepared.selectMemberCoverage(fixture.catalog().text(), -1);
        assertEquals(0, negative.diagnostics().get("budget"));
        assertEquals(prepared.selectMemberCoverage(fixture.catalog().text(), 0).selection(), negative.selection());
        assertFalse(negative.selection().withinBudget());
        assertTrue(negative.selection().text().contains(fixture.catalog().text()));
    }

    @Test
    void structuralWindowCanReplaceCheapShortSpanWithoutLosingAnyCoveredDimension() {
        String heading = "# Alpha " + "详细记录 ".repeat(28) + "：";
        String beta = "Beta 使用独立运行时。", gamma = "Gamma 后端。";
        var fixture = fixture(heading + '\n' + beta + '\n' + gamma,
            List.of(member(1, "Alpha"), member(2, "Beta"), member(3, "Gamma")),
            List.of(edge(702, 42, 102, "POST:6", 9L, beta), edge(703, 42, 103, "POST:6", 9L, gamma)));
        var prepared = prepare(fixture);
        var packed = prepared.selectMemberCoverage(fixture.catalog().text(), 2000);
        assertTrue(((Number) packed.diagnostics().get("replacementSteps")).intValue() > 0);
        assertTrue(history(packed).stream().anyMatch(step -> !((List<?>) step.get("removedProofIds")).isEmpty()));
        assertTrue(packed.selection().text().contains(heading + '\n' + beta));
        var metadata = prepared.memberSelectionMetadata(packed.selection().proofIds());
        assertEquals(List.of(1, 2, 3), metadata.get("sentRelevantCandidateIds"));
        assertEquals(List.of(1, 2, 3), metadata.get("sentMentionCandidateIds"));
        assertEquals(List.of(2, 3), metadata.get("sentBoundCandidateIds"));
        assertEquals(List.of(1, 2, 3), metadata.get("sentContextCandidateIds"));
        assertMonotoneCoverage(history(packed));
        assertNoOverlap(prepared, packed.selection());
        assertTrue(prepared.proofs().validate(fixture.ledger(), packed.selection().proofIds()).isEmpty());
    }

    @Test
    void expansionPreservationChecksMemberSetsIncludingMentionInsteadOfOnlyCounts() {
        String fact = "该项使用独立运行时。";
        var fixture = fixture("Alpha 名称。\n" + fact + "\nBeta 名称。",
            List.of(member(1, "Alpha"), member(2, "Beta")), List.of(edge(701, 42, 101, "POST:6", 9L, fact)));
        var prepared = prepare(fixture);
        String mention = proofId(prepared, "Alpha 名称。"), boundContext = proofId(prepared, fact), other = proofId(prepared, "Beta 名称。");
        var baseline = ids(mention);
        assertFalse(prepared.preservesMemberCoverage(baseline, ids(boundContext)), "same relevant member loses its existing literal mention");
        assertFalse(prepared.preservesMemberCoverage(baseline, ids(other)), "equal coverage counts can cover a different member");
        assertFalse(prepared.preservesMemberCoverage(ids(boundContext), baseline), "same relevant member loses bound/context coverage");
        assertTrue(prepared.preservesMemberCoverage(baseline, ids(mention, boundContext)));
        assertTrue(prepared.preservesMemberCoverage(ids(boundContext), ids(mention, boundContext)));
        assertTrue(prepared.preservesMemberCoverage(ids(), baseline));
        assertFalse(prepared.preservesMemberCoverage(null, baseline));
    }

    @Test
    void sameNamedMembersCannotCrossDistinctSubjectIdentityOrSourceDocument() {
        var first = candidate(1, 42, "同名项目", 101, "Alpha", 701);
        var second = candidate(2, 43, "同名项目", 102, "Alpha", 702);
        var chunk = chunk(9, 6, "同名项目", "Alpha 前端。");
        var fixture = fixture(List.of(chunk), List.of(first, second), List.of(
            edge(701, 42, 101, "POST:6", 9L, "Alpha 前端。"), edge(702, 43, 102, "POST:7", 10L, "Alpha 服务端。")));
        var prepared = prepare(fixture);
        var packed = prepared.selectMemberCoverage(fixture.catalog().text(), 2000);
        var rows = mapping(prepared.memberSelectionMetadata(packed.selection().proofIds()));
        assertFalse(((List<?>) rows.get(0).get("sentProofIds")).isEmpty());
        assertEquals(List.of(), rows.get(1).get("availableProofIds"));
        assertEquals(List.of(), rows.get(1).get("sentProofIds"));
        assertTrue(packed.selection().text().endsWith("2->[]\n"));
        var proof = prepared.proofs().span(packed.selection().proofIds().get(0)).orElseThrow();
        assertTrue(ClassificationCoverageProofs.relevantToMember(first, proof, fixture.ledger()));
        assertFalse(ClassificationCoverageProofs.relevantToMember(second, proof, fixture.ledger()));
        var fabricated = new ClassificationProofCatalog.ProofSpan("p999", 9L, "POST", 6L, "同名项目", 0, 5, "Omega", Set.of());
        assertFalse(ClassificationCoverageProofs.relevantToMember(first, fabricated, fixture.ledger()));
    }

    @Test
    void noAvailableProofRetainsEveryCandidateWithEmptyMappingAndNoAutomaticDisposition() {
        var fixture = fixture("│\n---\nUnrelated 记录。", List.of(member(1, "Alpha"), member(2, "Beta")), List.of());
        var prepared = prepare(fixture);
        var packed = prepared.selectMemberCoverage(fixture.catalog().text(), 2000);
        assertTrue(packed.selection().withinBudget());
        assertTrue(packed.selection().proofIds().isEmpty());
        assertTrue(packed.selection().text().startsWith(fixture.catalog().text()));
        assertTrue(packed.selection().text().endsWith("1->[]\n2->[]\n"));
        assertEquals("NO_AVAILABLE_PROOF", packed.diagnostics().get("terminationReason"));
        assertEquals(0, packed.diagnostics().get("stepLimit"));
        var metadata = prepared.memberSelectionMetadata(List.of());
        assertEquals(2, mapping(metadata).size());
        assertTrue(mapping(metadata).stream().allMatch(row -> "NO_AVAILABLE_PROOF".equals(row.get("evidenceDeliveryStatus"))));
        assertEquals(true, metadata.get("retrievalOnly"));
        assertFalse(metadata.containsKey("groups"));
        assertFalse(metadata.containsKey("classification"));
        assertFalse(metadata.containsKey("supported"));
    }

    @Test
    void ledgerOrderDoesNotChangeSelectionAndStrictGainTerminatesWithinProofCountBound() {
        var members = List.of(member(1, "Alpha"), member(2, "Beta"));
        var chunks = List.of(chunk(9, 6, "项目A", "Alpha 前端。"), chunk(10, 6, "项目A", "Beta 服务端。"));
        var first = prepare(fixture(chunks, members, List.of()));
        var second = prepare(fixture(List.of(chunks.get(1), chunks.get(0)), members, List.of()));
        var packed = first.selectMemberCoverage("固定前缀", 2000);
        assertEquals(packed, second.selectMemberCoverage("固定前缀", 2000));
        assertEquals(2 * first.proofs().spans().size(), packed.diagnostics().get("stepLimit"));
        assertTrue(((Number) packed.diagnostics().get("selectionSteps")).intValue()
            <= ((Number) packed.diagnostics().get("stepLimit")).intValue());
        assertEquals("OBJECTIVE_COVERAGE_COMPLETE", packed.diagnostics().get("terminationReason"));
        assertEquals("RELEVANT_CONTEXT_BOUND_OBJECTIVE", packed.diagnostics().get("terminationScope"));
        assertMonotoneCoverage(history(packed));
        assertThrows(UnsupportedOperationException.class, () -> packed.diagnostics().put("terminationReason", "changed"));
        assertThrows(UnsupportedOperationException.class, () -> history(packed).add(Map.of()));
    }

    @Test
    void cheapNonOverlappingRepeatedFragmentIsNotSentForProofCountAlone() {
        var fixture = fixture("Alpha 前端。\nAlpha 前端。", List.of(member(1, "Alpha")), List.of());
        var prepared = prepare(fixture);
        var packed = prepared.selectMemberCoverage(fixture.catalog().text(), 2000);
        assertEquals(2, prepared.proofs().spans().size());
        assertEquals(1, packed.selection().proofIds().size());
        assertEquals(List.of("p1"), packed.selection().proofIds());
        assertEquals(1, packed.selection().omittedSpanCount());
        assertEquals(1, packed.diagnostics().get("selectionSteps"));
        assertEquals("OBJECTIVE_COVERAGE_COMPLETE", packed.diagnostics().get("terminationReason"));
    }

    @Test
    void alreadySentRelatedProofCanBeChosenForAnotherMemberButMappingDoesNotChooseIt() {
        var fixture = fixture("Alpha 和 Beta 均有记录。", List.of(member(1, "Alpha"), member(2, "Beta")), List.of());
        var prepared = prepare(fixture);
        var packed = prepared.selectMemberCoverage(fixture.catalog().text(), 2000);
        String id = packed.selection().proofIds().get(0);
        var metadata = prepared.memberSelectionMetadata(packed.selection().proofIds());
        assertEquals(List.of(), metadata.get("citedRelevantCandidateIds"));
        assertEquals(List.of(id), mapping(metadata).get(0).get("sentProofIds"));
        assertEquals(List.of(id), mapping(metadata).get(1).get("sentProofIds"));
        assertTrue(packed.selection().text().endsWith("1->" + id + "\n2->" + id + "\n"));
        String proposal = "{\"groups\":[{\"s\":\"I\",\"reason\":\"MATCH\",\"ids\":\"1\",\"proofs\":[\"" + id
            + "\"]},{\"s\":\"E\",\"reason\":\"OUTSIDE_TYPE\",\"ids\":\"2\",\"proofs\":[\"" + id + "\"]}]}";
        var parsed = GraphClassificationContract.parseDecisions(proposal, fixture.catalog());
        assertTrue(parsed.complete());
        assertEquals(List.of(id), parsed.groups().get(0).proofs());
        assertEquals(List.of(id), parsed.groups().get(1).proofs());
        var cited = prepared.memberSelectionMetadata(packed.selection().proofIds(), List.of(id));
        assertEquals(List.of(1, 2), cited.get("citedRelevantCandidateIds"));
        assertEquals("PROPOSAL_GLOBAL_PROOF_POOL", cited.get("citedCoverageScope"));
        assertEquals(true, cited.get("semanticSupportNotEstablished"));
        assertFalse(cited.containsKey("supported"));
    }

    @Test
    void legacySelectionAndPolicyRemainUnchangedAfterIndependentMemberSelection() {
        var fixture = fixture("Alpha 前端。\nBeta 服务端。", List.of(member(1, "Alpha"), member(2, "Beta")), List.of());
        var prepared = prepare(fixture);
        var legacy = prepared.select(fixture.catalog().text(), 2000);
        var legacyMetadata = prepared.metadata(legacy.proofIds());
        var member = prepared.selectMemberCoverage(fixture.catalog().text(), 2000);
        assertEquals(legacy, prepared.select(fixture.catalog().text(), 2000));
        assertEquals(legacyMetadata, prepared.metadata(legacy.proofIds()));
        assertEquals("classification-candidate-context-v2", legacyMetadata.get("packingPolicy"));
        assertTrue(legacy.text().contains("检索对应 p1:1；名称/关系出处检索定位，不证明类别。"));
        assertTrue(legacy.text().contains("检索对应 p2:2；名称/关系出处检索定位，不证明类别。"));
        assertEquals("classification-member-coverage-v1", member.diagnostics().get("packingPolicy"));
        assertEquals(ClassificationCoverageProofs.POLICY_MEMBER_COVERAGE,
            prepared.memberSelectionMetadata(member.selection().proofIds()).get("packingPolicy"));
        assertFalse(member.selection().text().contains("检索对应 p1:"));
    }

    private static void assertNoOverlap(ClassificationCoverageProofs.Prepared prepared, ClassificationProofCatalog.Selection selected) {
        var spans = selected.proofIds().stream().map(id -> prepared.proofs().span(id).orElseThrow()).toList();
        for (int first = 0; first < spans.size(); first++) for (int second = first + 1; second < spans.size(); second++)
            assertFalse(spans.get(first).chunkId().equals(spans.get(second).chunkId())
                && spans.get(first).start() < spans.get(second).end() && spans.get(second).start() < spans.get(first).end());
    }

    private static void assertMonotoneCoverage(List<Map<String, Object>> history) {
        Map<String, Set<Integer>> previous = new java.util.LinkedHashMap<>();
        for (String dimension : List.of("mentionCandidateIds", "relevantCandidateIds", "contextCandidateIds", "boundCandidateIds"))
            previous.put(dimension, Set.of());
        for (Map<String, Object> step : history) {
            boolean strict = false;
            for (var entry : previous.entrySet()) {
                @SuppressWarnings("unchecked") var current = new LinkedHashSet<>((Collection<Integer>) step.get(entry.getKey()));
                assertTrue(current.containsAll(entry.getValue()), "existing " + entry.getKey() + " must survive a replacement");
                if (!entry.getKey().equals("mentionCandidateIds") && current.size() > entry.getValue().size()) strict = true;
                entry.setValue(current);
            }
            assertTrue(strict, "every accepted step strictly improves relevant/context/bound lexicographic coverage");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> history(ClassificationCoverageProofs.MemberSelection packed) {
        return (List<Map<String, Object>>) packed.diagnostics().get("selectionHistory");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapping(Map<String, Object> metadata) {
        return (List<Map<String, Object>>) metadata.get("memberProofMapping");
    }

    private static String proofId(ClassificationCoverageProofs.Prepared prepared, String text) {
        return prepared.proofs().spans().stream().filter(proof -> proof.text().equals(text)).findFirst().orElseThrow().id();
    }

    private static ClassificationProofCatalog.Selection ids(String... proofIds) {
        return new ClassificationProofCatalog.Selection("", 0, List.of(proofIds), 0, true);
    }

    private record Fixture(GraphClassificationContract.Catalog catalog, EvidenceLedger ledger) { }

    private static ClassificationCoverageProofs.Prepared prepare(Fixture fixture) {
        return ClassificationCoverageProofs.prepare(QUESTION, fixture.catalog(), fixture.ledger());
    }

    private static GraphClassificationContract.Candidate member(int number, String name) {
        return candidate(number, 42, "项目A", 100 + number, name, 700 + number);
    }

    private static GraphClassificationContract.Candidate candidate(int number, long anchor, String title, long member,
            String name, long relation) {
        return new GraphClassificationContract.Candidate(number, anchor, member, title, name, "SKILL", "MENTIONS", "OUT", List.of(relation));
    }

    private static EvidenceLedger.ChunkFact chunk(long id, long doc, String title, String body) {
        return new EvidenceLedger.ChunkFact(id, "POST", doc, title, body, 0, body.length());
    }

    private static Fixture fixture(String body, List<GraphClassificationContract.Candidate> candidates, List<EvidenceLedger.RelationFact> edges) {
        return fixture(List.of(chunk(9, 6, candidates.get(0).anchorName(), body)), candidates, edges);
    }

    private static Fixture fixture(List<EvidenceLedger.ChunkFact> chunks, List<GraphClassificationContract.Candidate> candidates,
            List<EvidenceLedger.RelationFact> edges) {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        List<EvidenceLedger.EntityFact> entities = new ArrayList<>();
        for (var member : candidates) {
            if (entities.stream().noneMatch(entity -> entity.id().equals(member.anchorId())))
                entities.add(new EvidenceLedger.EntityFact(member.anchorId(), member.anchorName(), "POST", ""));
            entities.add(new EvidenceLedger.EntityFact(member.otherId(), member.name(), "SKILL", ""));
        }
        when(ledger.chunks()).thenReturn(chunks); when(ledger.relations()).thenReturn(edges); when(ledger.entities()).thenReturn(entities);
        when(ledger.chunk(any())).thenAnswer(call -> chunks.stream().filter(item -> item.id().equals(call.getArgument(0))).findFirst());
        when(ledger.relation(any())).thenAnswer(call -> edges.stream().filter(item -> item.id().equals(call.getArgument(0))).findFirst());
        when(ledger.entity(any())).thenAnswer(call -> entities.stream().filter(item -> item.id().equals(call.getArgument(0))).findFirst());
        StringBuilder prefix = new StringBuilder("【全部授权候选】\n");
        for (var member : candidates) prefix.append(member.number()).append(':').append(member.name()).append('\n');
        var catalog = new GraphClassificationContract.Catalog(true, "", candidates, prefix.toString(), Map.of(), true, true, "技术归属");
        return new Fixture(catalog, ledger);
    }

    private static EvidenceLedger.RelationFact edge(long id, long anchor, long member, String doc, Long chunk, String fact) {
        return new EvidenceLedger.RelationFact(id, anchor, member, "MENTIONS", null, null, doc, chunk, fact);
    }
}
