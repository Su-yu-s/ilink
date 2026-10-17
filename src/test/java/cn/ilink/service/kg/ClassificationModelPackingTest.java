package cn.ilink.service.kg;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Synthetic source/identity boundaries only; these tests do not establish classification quality. */
class ClassificationModelPackingTest {
    private static final String TITLE = "授权工程资料";
    private static final String QUESTION = "该工程关联的技术中，哪些不属于指定语言生态？";
    private static final List<String> DIMENSIONS = List.of("Mention", "Relevant", "Context", "Bound");

    @Test
    void actualD1ProofsAndAllFourMemberSetsAreRetained() {
        var fixture = fixture(36, 12); var prepared = prepare(fixture); var seed = seed(prepared, fixture, 2000);
        var packed = pack(prepared, fixture, seed, 3000);
        assertTrue(packed.selection().proofIds().containsAll(seed.selection().proofIds()));
        for (String dimension : DIMENSIONS) {
            assertTrue(members(packed, dimension).containsAll(members(seed, dimension)));
            assertEquals(List.of(), packed.diagnostics().get("lostSeed" + dimension + "CandidateIds"));
        }
    }

    @Test
    void originalCatalogNumericRelationLabelsAreNotRepeatedInTheModelView() {
        var fixture = fixture(20, 2); var prepared = prepare(fixture);
        var packed = pack(prepared, fixture, seed(prepared, fixture, 2000), 3000);
        assertFalse(packed.selection().text().contains("r#"));
        assertFalse(packed.selection().text().startsWith(fixture.catalog().text()));
        for (var candidate : fixture.catalog().candidates()) assertFalse(candidate.relationIds().isEmpty());
        assertEquals(ClassificationModelContext.project(fixture.catalog()).metadata(), packed.diagnostics().get("projection"));
    }

    @Test
    void transposedIndexRecoversEveryMembersExactSentProofListIncludingEmptyMembers() {
        var fixture = fixture(12, 3); List<GraphClassificationContract.Candidate> candidates = new ArrayList<>(fixture.catalog().candidates());
        candidates.add(member(13, "UnrecordedTech"));
        var altered = new Fixture(catalog(candidates), fixture.ledger()); var prepared = prepare(altered);
        var packed = pack(prepared, altered, seed(prepared, altered, 2000), 3000);
        var decoded = decodeIndex(packed.selection().text(), candidates.size());
        for (var row : mapping(prepared.metadata(packed.selection().proofIds())))
            assertEquals(row.get("sentProofIds"), decoded.get(row.get("candidateId")));
        assertEquals(List.of(), decoded.get(13));
        assertEquals(List.of(13), packed.diagnostics().get("noAvailableSourceCandidateIds"));
    }

    @Test
    void availableCatalogCoordinatesAndTypedBindingsRemainUnchanged() {
        var fixture = fixture(12, 3); var prepared = prepare(fixture);
        var packed = pack(prepared, fixture, seed(prepared, fixture, 2000), 3000);
        assertEquals(prepared.proofs().spans().stream().map(ClassificationProofCatalog.ProofSpan::metadata).toList(),
            packed.diagnostics().get("availableProofCatalog"));
        assertTrue(prepared.proofs().validate(fixture.ledger(), packed.selection().proofIds()).isEmpty());
        assertEquals(ClassificationModelContext.sources(prepared.proofs(), packed.selection().proofIds()).metadata(), packed.diagnostics().get("sources"));
    }

    @Test
    void hardBudgetIncludesProjectionSourcesMappingAndNotices() {
        var fixture = fixture(36, 12); var prepared = prepare(fixture);
        var packed = pack(prepared, fixture, seed(prepared, fixture, 2000), 3000);
        assertEquals(EvidenceContext.tokens(packed.selection().text()), packed.selection().tokenCount());
        assertEquals(packed.selection().tokenCount(), packed.diagnostics().get("tokenCount"));
        assertTrue(packed.selection().withinBudget()); assertTrue(packed.selection().tokenCount() <= 3000);
        assertTrue(packed.selection().text().contains("不能自动赋予U/I/E"));
        assertEquals(true, packed.diagnostics().get("semanticSupportNotEstablished"));
    }

    @Test
    void aLargerBudgetAppendsEvidenceWithoutReplacingAnySeedProof() {
        var fixture = fixture(48, 16); var prepared = prepare(fixture); var seed = seed(prepared, fixture, 2000);
        var packed = pack(prepared, fixture, seed, 3000);
        assertTrue(packed.selection().proofIds().size() > seed.selection().proofIds().size());
        assertTrue(members(packed, "Relevant").size() > members(seed, "Relevant").size());
        assertEquals(0, packed.diagnostics().get("replacementSteps"));
        assertTrue(packed.selection().proofIds().containsAll(seed.selection().proofIds()));
    }

    @Test
    void everyRecordedTransitionPreservesProofsAndAllFourDimensions() {
        var fixture = fixture(40, 12); var prepared = prepare(fixture); var seed = seed(prepared, fixture, 2000);
        var packed = pack(prepared, fixture, seed, 3000); Set<String> priorIds = new LinkedHashSet<>(seed.selection().proofIds());
        Map<String, Set<?>> prior = new LinkedHashMap<>(); for (String dimension : DIMENSIONS) prior.put(dimension, members(seed, dimension));
        int number = 0;
        for (var step : history(packed)) {
            assertEquals(++number, step.get("step")); assertEquals(List.of(), step.get("removedProofIds"));
            @SuppressWarnings("unchecked") List<String> ids = (List<String>) step.get("selectedProofIds");
            assertTrue(ids.containsAll(priorIds)); assertEquals(priorIds.size() + 1, ids.size());
            assertTrue((Integer) step.get("tokenCount") <= 3000); priorIds = new LinkedHashSet<>(ids);
            for (String dimension : DIMENSIONS) {
                String key = Character.toLowerCase(dimension.charAt(0)) + dimension.substring(1) + "CandidateIds";
                Set<?> next = new LinkedHashSet<>((Collection<?>) step.get(key)); assertTrue(next.containsAll(prior.get(dimension))); prior.put(dimension, next);
            }
        }
        assertEquals(number, packed.diagnostics().get("selectionSteps"));
        assertTrue(number <= prepared.proofs().spans().size()); assertEquals(priorIds, new LinkedHashSet<>(packed.selection().proofIds()));
    }

    @Test
    void identicalInputsProduceIdenticalSelectionsAndDiagnostics() {
        var fixture = fixture(24, 8); var prepared = prepare(fixture); var seed = seed(prepared, fixture, 2000);
        var first = pack(prepared, fixture, seed, 3000); var second = pack(prepared, fixture, seed, 3000);
        assertEquals(first.selection(), second.selection()); assertEquals(first.diagnostics(), second.diagnostics());
    }

    @Test
    void nativeC2FallbackIsAcceptedOnlyWithItsCanonicalHistoryAndAccounting() {
        var fixture = fixture(12, 3); var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        var packed = pack(prepared, fixture, seed, 3000);
        assertEquals(ClassificationCoverageProofs.POLICY_MONOTONE_COVERAGE, packed.diagnostics().get("seedPolicy"));
        assertTrue(packed.selection().proofIds().containsAll(seed.selection().proofIds()));
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture, altered(seed, "selectionHistory", List.of()), 3000));
    }

    @Test
    void effectiveBudgetIsCappedAtThreeThousandAndNeverShrinksTheSeedBudget() {
        var fixture = fixture(12, 3); var prepared = prepare(fixture); var seed = seed(prepared, fixture, 2000);
        var packed = pack(prepared, fixture, seed, 10000);
        assertEquals(3000, packed.diagnostics().get("budget")); assertTrue(packed.selection().tokenCount() <= 3000);
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture, seed, 1999));
    }

    @Test
    void nullSeedAndUnsupportedSeedPolicyAreRejected() {
        var fixture = fixture(12, 3); var prepared = prepare(fixture); var seed = seed(prepared, fixture, 2000);
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture, null, 3000));
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture, altered(seed, "packingPolicy", "untrusted"), 3000));
    }

    @Test
    void foreignCatalogOrSourceFingerprintsCannotBeSubstituted() {
        var fixture = fixture(12, 3); var prepared = prepare(fixture); var seed = seed(prepared, fixture, 2000);
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture, altered(seed, "prefixSha256", "foreign"), 3000));
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture, altered(seed, "sourceCatalogSha256", "foreign"), 3000));
        var changed = fixture(12, 4);
        assertThrows(IllegalArgumentException.class, () -> pack(prepare(changed), changed, seed, 3000));
    }

    @Test
    void duplicateOrUnknownSeedProofIdsAreRejected() {
        var fixture = fixture(12, 3); var prepared = prepare(fixture); var seed = seed(prepared, fixture, 2000);
        var ids = new ArrayList<>(seed.selection().proofIds()); ids.add(ids.get(0));
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture, withIds(seed, ids), 3000));
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture, withIds(seed, List.of("p999999")), 3000));
    }

    @Test
    void seedTextTokenAndOmittedCountsAreReconstructedRatherThanTrusted() {
        var fixture = fixture(12, 3); var prepared = prepare(fixture); var seed = seed(prepared, fixture, 2000); var selection = seed.selection();
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture, new ClassificationCoverageProofs.MemberSelection(
            new ClassificationProofCatalog.Selection(selection.text() + "X", selection.tokenCount(), selection.proofIds(), selection.omittedSpanCount(), true), seed.diagnostics()), 3000));
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture, new ClassificationCoverageProofs.MemberSelection(
            new ClassificationProofCatalog.Selection(selection.text(), selection.tokenCount() + 1, selection.proofIds(), selection.omittedSpanCount(), true), seed.diagnostics()), 3000));
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture, new ClassificationCoverageProofs.MemberSelection(
            new ClassificationProofCatalog.Selection(selection.text(), selection.tokenCount(), selection.proofIds(), selection.omittedSpanCount() + 1, true), seed.diagnostics()), 3000));
    }

    @Test
    void everySeedCoverageDimensionIsRecomputed() {
        var fixture = fixture(12, 3); var prepared = prepare(fixture); var seed = seed(prepared, fixture, 2000);
        for (String dimension : DIMENSIONS)
            assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture,
                altered(seed, "sent" + dimension + "CandidateIds", List.of(999)), 3000));
    }

    @Test
    void sourceRawCharacterAndFullAvailableCatalogClaimsAreChecked() {
        var fixture = fixture(12, 3); var prepared = prepare(fixture); var seed = seed(prepared, fixture, 2000);
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture, altered(seed, "rawSourceCharacters", 0), 3000));
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture, altered(seed, "availableProofCatalog", List.of()), 3000));
    }

    @Test
    void fabricatedD1ContinuationHistoryCannotInventCompletedTransitions() {
        var fixture = fixture(36, 12); var prepared = prepare(fixture); var seed = seed(prepared, fixture, 2000);
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture, altered(seed, "continuationSteps", 999), 3000));
        assertThrows(IllegalArgumentException.class, () -> pack(prepared, fixture,
            altered(seed, "sourceBlockSelectionHistory", List.of(Map.of("proofId", "p999999", "step", 1))), 3000));
    }

    @Test
    void forgedProjectionCannotChangeItsTextCountsOrTypedCatalogIdentity() {
        var fixture = fixture(12, 3); var prepared = prepare(fixture); var seed = seed(prepared, fixture, 2000);
        var view = ClassificationModelContext.project(fixture.catalog());
        assertThrows(IllegalArgumentException.class, () -> prepared.selectModelContext(new ClassificationModelContext.Projection(
            view.text() + "changed", view.tokenCount(), view.shownCount(), true, view.typedCatalogSha256()), seed, 3000));
        assertThrows(IllegalArgumentException.class, () -> prepared.selectModelContext(new ClassificationModelContext.Projection(
            view.text(), view.tokenCount(), view.shownCount() - 1, true, view.typedCatalogSha256()), seed, 3000));
        assertThrows(IllegalArgumentException.class, () -> prepared.selectModelContext(new ClassificationModelContext.Projection(
            view.text(), view.tokenCount(), view.shownCount(), true, "foreign"), seed, 3000));
    }

    @Test
    void incompleteInventoryDoesNotBecomeCompleteThroughCompactProjection() {
        var fixture = fixture(12, 3); var original = fixture.catalog();
        var incomplete = new GraphClassificationContract.Catalog(true, "", original.candidates(), original.text(), original.metadata(), true, false, original.focusText());
        var altered = new Fixture(incomplete, fixture.ledger()); var prepared = prepare(altered);
        var seed = prepared.selectSourceBlocks(incomplete.text(), 2000);
        assertThrows(IllegalArgumentException.class, () -> prepared.selectModelContext(ClassificationModelContext.project(incomplete), seed, 3000));
    }

    @Test
    void unionPresentationKeepsEveryTypedProofAndDoesNotCreateSemanticApproval() {
        var fixture = fixture(24, 5); var prepared = prepare(fixture); var seed = seed(prepared, fixture, 2000);
        var packed = pack(prepared, fixture, seed, 3000); var sources = ClassificationModelContext.sources(prepared.proofs(), packed.selection().proofIds());
        assertEquals(packed.selection().proofIds().size(), sources.proofCount());
        assertTrue(sources.sourceSpanCount() <= sources.proofCount());
        for (String id : packed.selection().proofIds()) {
            var proof = prepared.proofs().span(id).orElseThrow(); assertTrue(sources.text().contains(id + "@" + proof.start() + ":" + proof.end()));
        }
        assertEquals(List.of(), prepared.metadata(packed.selection().proofIds(), List.of()).get("citedRelevantCandidateIds"));
        assertEquals(true, packed.diagnostics().get("retrievalOnly")); assertEquals(true, packed.diagnostics().get("semanticSupportNotEstablished"));
    }

    private record Fixture(GraphClassificationContract.Catalog catalog, EvidenceLedger ledger) { }

    private static Fixture fixture(int count, int repetitions) {
        List<GraphClassificationContract.Candidate> candidates = new ArrayList<>(); List<EvidenceLedger.RelationFact> edges = new ArrayList<>();
        List<EvidenceLedger.EntityFact> entities = new ArrayList<>(); entities.add(new EvidenceLedger.EntityFact(42L, TITLE, "POST", ""));
        StringBuilder body = new StringBuilder();
        for (int number = 1; number <= count; number++) {
            String name = "Tech" + number; String fact = name + " 使用独立运行时😀，" + "原始架构记录 ".repeat(repetitions) + "。";
            candidates.add(member(number, name)); body.append(fact).append('\n');
            edges.add(new EvidenceLedger.RelationFact(510000L + number, 42L, 100L + number, "MENTIONS", null, null, "POST:6", 9L, fact));
            entities.add(new EvidenceLedger.EntityFact(100L + number, name, "SKILL", ""));
        }
        List<EvidenceLedger.ChunkFact> chunks = List.of(new EvidenceLedger.ChunkFact(9L, "POST", 6L, TITLE, body.toString(), 0, body.length()));
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.chunks()).thenReturn(chunks); when(ledger.relations()).thenReturn(edges); when(ledger.entities()).thenReturn(entities);
        when(ledger.chunk(any())).thenAnswer(call -> chunks.stream().filter(chunk -> chunk.id().equals(call.getArgument(0))).findFirst());
        when(ledger.relation(any())).thenAnswer(call -> edges.stream().filter(edge -> edge.id().equals(call.getArgument(0))).findFirst());
        when(ledger.entity(any())).thenAnswer(call -> entities.stream().filter(entity -> entity.id().equals(call.getArgument(0))).findFirst());
        return new Fixture(catalog(candidates), ledger);
    }

    private static GraphClassificationContract.Candidate member(int number, String name) {
        return new GraphClassificationContract.Candidate(number, 42L, 100L + number, TITLE, name, "SKILL", "MENTIONS", "OUT", List.of(510000L + number));
    }

    private static GraphClassificationContract.Catalog catalog(List<GraphClassificationContract.Candidate> candidates) {
        StringBuilder text = new StringBuilder("【授权候选编号；关联不证明分类】\n【主体#42 授权工程资料 OUT MENTIONS SKILL】\n");
        for (var candidate : candidates) text.append(candidate.number()).append(':').append(candidate.name()).append(" r#")
            .append(candidate.relationIds().get(0)).append('\n');
        return new GraphClassificationContract.Catalog(true, "", candidates, text.toString(), Map.of("targetType", "SKILL"), true, true, "指定语言生态");
    }

    private static ClassificationCoverageProofs.Prepared prepare(Fixture fixture) {
        return ClassificationCoverageProofs.prepare(QUESTION, fixture.catalog(), fixture.ledger());
    }
    private static ClassificationCoverageProofs.MemberSelection seed(ClassificationCoverageProofs.Prepared prepared, Fixture fixture, int budget) {
        return prepared.selectSourceBlocks(fixture.catalog().text(), prepared.selectMonotoneCoverage(fixture.catalog().text(), budget), budget);
    }
    private static ClassificationCoverageProofs.MemberSelection pack(ClassificationCoverageProofs.Prepared prepared, Fixture fixture,
            ClassificationCoverageProofs.MemberSelection seed, int budget) {
        return prepared.selectModelContext(ClassificationModelContext.project(fixture.catalog()), seed, budget);
    }
    private static ClassificationCoverageProofs.MemberSelection altered(ClassificationCoverageProofs.MemberSelection seed, String key, Object value) {
        var diagnostics = new LinkedHashMap<>(seed.diagnostics()); diagnostics.put(key, value);
        return new ClassificationCoverageProofs.MemberSelection(seed.selection(), diagnostics);
    }
    private static ClassificationCoverageProofs.MemberSelection withIds(ClassificationCoverageProofs.MemberSelection seed, List<String> ids) {
        var value = seed.selection(); return new ClassificationCoverageProofs.MemberSelection(
            new ClassificationProofCatalog.Selection(value.text(), value.tokenCount(), ids, value.omittedSpanCount(), value.withinBudget()), seed.diagnostics());
    }
    private static Set<?> members(ClassificationCoverageProofs.MemberSelection packed, String dimension) {
        return new LinkedHashSet<>((Collection<?>) packed.diagnostics().get("sent" + dimension + "CandidateIds"));
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapping(Map<String, Object> metadata) { return (List<Map<String, Object>>) metadata.get("memberProofMapping"); }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> history(ClassificationCoverageProofs.MemberSelection packed) {
        return (List<Map<String, Object>>) packed.diagnostics().get("modelContextSelectionHistory");
    }
    private static Map<Integer, List<String>> decodeIndex(String text, int count) {
        String heading = "【证明对应成员；编号区间逐成员展开；仅此次已展示】\n";
        String index = text.substring(text.indexOf(heading) + heading.length()); index = index.substring(index.indexOf('\n') + 1);
        Map<Integer, List<String>> result = new LinkedHashMap<>(); for (int number = 1; number <= count; number++) result.put(number, new ArrayList<>());
        for (String line : index.split("\\R")) {
            String[] sides = line.split("->", 2); assertEquals(2, sides.length);
            String ranges = sides[1].split("；", 2)[0]; if (ranges.equals("[]")) continue;
            for (String range : ranges.split(",")) {
                String[] ends = range.split("-", 2); int from = Integer.parseInt(ends[0]), to = ends.length == 1 ? from : Integer.parseInt(ends[1]);
                for (int number = from; number <= to; number++) {
                    assertTrue(result.containsKey(number));
                    if (sides[0].equals("none")) assertTrue(result.get(number).isEmpty()); else result.get(number).add(sides[0]);
                }
            }
        }
        return result;
    }
}
