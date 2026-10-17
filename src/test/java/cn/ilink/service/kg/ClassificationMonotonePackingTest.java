package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Synthetic sources only: no saved replay, model, database, private fixture or classification gold. */
class ClassificationMonotonePackingTest {
    private static final String QUESTION = "《项目A》哪些技术不属于Java生态？";
    private static final List<String> DIMENSIONS = List.of("Mention", "Relevant", "Context", "Bound");

    @Test
    void budgetChainContinuesTheSeedAndPreservesMemberIdentitiesAndCumulativeHistory() {
        var fixture = chainFixture();
        var prepared = prepare(fixture);
        var first = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        var middle = prepared.extendMonotoneCoverage(fixture.catalog().text(), first, 2500);
        var last = prepared.extendMonotoneCoverage(fixture.catalog().text(), middle, 3000);
        assertEquals("INITIAL", first.diagnostics().get("runMode"));
        for (var pair : List.of(List.of(first, middle), List.of(middle, last))) {
            var before = pair.get(0); var after = pair.get(1);
            assertEquals("CONTINUATION", after.diagnostics().get("runMode"));
            assertEquals(before.selection().proofIds(), after.diagnostics().get("seedProofIds"));
            assertEquals(before.selection().tokenCount(), after.diagnostics().get("seedTokenCount"));
            assertEquals(before.diagnostics().get("selectionSteps"), after.diagnostics().get("seedSelectionSteps"));
            assertEquals(history(before), history(after).subList(0, history(before).size()));
            assertTrue(memberSet(after, "Relevant").size() > memberSet(before, "Relevant").size(),
                "each additional 500 tokens must admit a previously unsent member in this fixture");
            assertTrue(prepared.preservesMemberCoverage(before.selection(), after.selection()));
            assertMemberSetsPreserved(before, after);
        }
        int limit = 2 * prepared.proofs().spans().size();
        for (var packed : List.of(first, middle, last)) {
            assertEquals(limit, packed.diagnostics().get("stepLimit"));
            assertEquals(history(packed).size(), packed.diagnostics().get("selectionSteps"));
            assertTrue(history(packed).size() <= limit, "the whole chain shares one 2*N history limit");
            assertTrue(packed.selection().withinBudget());
            assertTrue(packed.selection().tokenCount() <= (Integer) packed.diagnostics().get("budget"));
            assertEquals(EvidenceContext.tokens(packed.selection().text()), packed.selection().tokenCount());
            assertTrue(packed.selection().text().startsWith(fixture.catalog().text()));
            assertMonotoneHistory(history(packed));
        }
        assertEquals(first.selection().tokenCount(), middle.diagnostics().get("initialTokenCount"));
        assertEquals(middle.selection().tokenCount(), last.diagnostics().get("initialTokenCount"));
        assertEquals(3000, last.diagnostics().get("budget"));
    }

    @Test
    void sameBudgetAcceptsACompleteSeedWithoutRestartingItsSelectionOrHistory() {
        var fixture = fixture("Alpha 前端。\nBeta 服务端。", List.of(member(1, "Alpha"), member(2, "Beta")), List.of());
        var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        var continued = prepared.extendMonotoneCoverage(fixture.catalog().text(), seed, 2000);
        assertEquals(seed.selection(), continued.selection());
        assertEquals(history(seed), history(continued));
        assertEquals(0, continued.diagnostics().get("continuationSteps"));
        assertEquals("OBJECTIVE_COVERAGE_COMPLETE", continued.diagnostics().get("terminationReason"));
        assertMemberSetsPreserved(seed, continued);
    }

    @Test
    void structuralWindowUpgradeMayReplaceProofIdsWhilePreservingAllFourMemberSets() {
        String heading = "# Alpha " + "详细记录 ".repeat(28) + "：";
        String beta = "Beta 使用独立运行时。", gamma = "Gamma 后端。";
        var fixture = fixture(heading + '\n' + beta + '\n' + gamma,
            List.of(member(1, "Alpha"), member(2, "Beta"), member(3, "Gamma")),
            List.of(edge(702, 102, beta), edge(703, 103, gamma)));
        var prepared = prepare(fixture);
        var full = prepared.selectMonotoneCoverage(fixture.catalog().text(), 3000);
        var fullHistory = history(full);
        int upgradeIndex = -1;
        for (int index = 1; index < fullHistory.size(); index++)
            if (!((List<?>) fullHistory.get(index).get("removedProofIds")).isEmpty()) { upgradeIndex = index; break; }
        assertTrue(upgradeIndex > 0, "the fixture must exercise an overlapping window upgrade");
        int seedBudget = (Integer) fullHistory.get(upgradeIndex - 1).get("tokenCount");
        assertTrue((Integer) fullHistory.get(upgradeIndex).get("tokenCount") > seedBudget);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), seedBudget);
        var upgraded = prepared.extendMonotoneCoverage(fixture.catalog().text(), seed, 3000);
        assertTrue((Integer) upgraded.diagnostics().get("continuationReplacementSteps") > 0);
        assertFalse(upgraded.selection().proofIds().containsAll(seed.selection().proofIds()),
            "proof identity may change when its exact containing window supplies the same members");
        assertTrue(upgraded.selection().text().contains(heading + '\n' + beta));
        assertEquals(Set.of(1, 2, 3), memberSet(upgraded, "Relevant"));
        assertEquals(Set.of(1, 2, 3), memberSet(upgraded, "Mention"));
        assertEquals(Set.of(1, 2, 3), memberSet(upgraded, "Context"));
        assertEquals(Set.of(2, 3), memberSet(upgraded, "Bound"));
        assertMemberSetsPreserved(seed, upgraded);
        assertMonotoneHistory(history(upgraded));
        assertNoOverlap(prepared, upgraded.selection());
    }

    @Test
    void compactBucketsExpandToAllEightyEightMembersWithTheirCompleteIdenticalProofSets() {
        List<GraphClassificationContract.Candidate> candidates = new ArrayList<>();
        List<EvidenceLedger.RelationFact> edges = new ArrayList<>();
        for (int number = 1; number <= 88; number++) {
            String name = Set.of(1, 2, 4, 5, 7).contains(number) ? "Alpha"
                : Set.of(3, 6).contains(number) ? "Beta" : number <= 9 ? "Gamma" : "Missing";
            candidates.add(member(number, name));
            if (name.equals("Beta")) edges.add(edge(700 + number, 100 + number, "Beta 使用独立运行时。"));
        }
        var fixture = fixture("Alpha Beta 前端。\nBeta 使用独立运行时。\nGamma 服务端。", candidates, edges);
        var prepared = prepare(fixture);
        var packed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        var expanded = expandedMapping(packed.selection().text());
        var rows = mapping(prepared.monotoneSelectionMetadata(packed.selection().proofIds()));
        assertEquals(88, expanded.size());
        assertEquals(88, rows.size());
        for (var row : rows) assertEquals(row.get("sentProofIds"), expanded.get((Integer) row.get("candidateId")));
        assertEquals(expanded.get(1), expanded.get(7));
        assertEquals(expanded.get(3), expanded.get(6));
        assertTrue(expanded.get(3).containsAll(expanded.get(1)));
        assertTrue(expanded.get(3).size() > expanded.get(1).size(), "bucket identity uses the full proof set");
        assertEquals(expanded.get(1).size(), expanded.get(8).size());
        assertNotEquals(expanded.get(1), expanded.get(8), "equal cardinality must not merge different proof sets");
        assertTrue(packed.selection().text().contains("\n1-2,4-5,7->"));
        assertTrue(packed.selection().text().contains("\n3,6->"));
        assertTrue(packed.selection().text().endsWith("10-88->[]\n"));
        for (int number = 10; number <= 88; number++) assertEquals(List.of(), expanded.get(number));
        assertEquals("IDENTICAL_PROOF_SET_MEMBER_RANGES_V1", packed.diagnostics().get("mappingFormat"));
        assertTrue(packed.selection().withinBudget());
    }

    @Test
    void continuationPreservesExactSourceCoordinatesTextAndProofIdentity() {
        var fixture = chainFixture();
        var prepared = prepare(fixture);
        var original = prepared.proofs().spans().stream().map(ClassificationProofCatalog.ProofSpan::metadata).toList();
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        var packed = prepared.extendMonotoneCoverage(fixture.catalog().text(), seed, 3000);
        assertEquals(original, prepared.proofs().spans().stream().map(ClassificationProofCatalog.ProofSpan::metadata).toList());
        assertTrue(prepared.proofs().validate(fixture.ledger(), packed.selection().proofIds()).isEmpty());
        for (String id : packed.selection().proofIds()) {
            var proof = prepared.proofs().span(id).orElseThrow();
            assertEquals(fixture.ledger().chunk(proof.chunkId()).orElseThrow().text().substring(proof.start(), proof.end()), proof.text());
            assertTrue(packed.selection().text().contains(id + "=c#" + proof.chunkId() + '@' + proof.start() + ':' + proof.end()
                + " [" + proof.docType() + ':' + proof.docId() + "] " + proof.title() + '：' + proof.text() + '\n'));
        }
        assertNoOverlap(prepared, packed.selection());
    }

    @Test
    void fixedPrefixAndCompleteDirectorySurviveInsufficientBudgetAndAbsentSources() {
        var fixture = fixture("Alpha 前端。", List.of(member(1, "Alpha"), member(2, "Missing")), List.of());
        var prepared = prepare(fixture);
        String prefix = "完整固定范围不可删除。".repeat(500);
        var packed = prepared.selectMonotoneCoverage(prefix, 80);
        assertFalse(packed.selection().withinBudget());
        assertTrue(packed.selection().proofIds().isEmpty());
        assertTrue(packed.selection().text().startsWith(prefix));
        assertTrue(packed.selection().text().contains(fixture.catalog().text()));
        assertEquals(Map.of(1, List.of(), 2, List.of()), expandedMapping(packed.selection().text()));
        assertEquals("FIXED_TEXT_OVER_BUDGET", packed.diagnostics().get("terminationReason"));
        assertTrue(packed.selection().text().contains("不等于全局无证据"));
        assertTrue(packed.selection().text().contains("不能自动赋予U/I/E"));
        assertRejected(prepared, prefix, packed, 3000, "MONOTONE_SEED_NOT_WITHIN_BUDGET");
        var absentFixture = fixture("│\n---\nUnrelated 记录。", List.of(member(1, "Alpha"), member(2, "Missing")), List.of());
        var absent = prepare(absentFixture).selectMonotoneCoverage("完整固定范围\n", 2000);
        assertTrue(absent.selection().withinBudget());
        assertTrue(absent.selection().proofIds().isEmpty());
        assertTrue(absent.selection().text().contains(absentFixture.catalog().text()));
        assertEquals("NO_AVAILABLE_PROOF", absent.diagnostics().get("terminationReason"));
        assertEquals(Map.of(1, List.of(), 2, List.of()), expandedMapping(absent.selection().text()));
        assertEquals(true, absent.diagnostics().get("semanticSupportNotEstablished"));
    }

    @Test
    void hardCapClampsRequestsAndCountsTheCompleteRenderedTextWithoutTruncation() {
        var fixture = chainFixture();
        var prepared = prepare(fixture);
        var maximum = prepared.selectMonotoneCoverage(fixture.catalog().text(), 3000);
        var excess = prepared.selectMonotoneCoverage(fixture.catalog().text(), Integer.MAX_VALUE);
        assertEquals(maximum.selection(), excess.selection());
        assertEquals(3000, excess.diagnostics().get("budget"));
        assertTrue(excess.selection().tokenCount() <= 3000);
        assertEquals(EvidenceContext.tokens(excess.selection().text()), excess.selection().tokenCount());
        assertEquals(excess.selection().tokenCount(), excess.diagnostics().get("currentTokenCount"));
        assertEquals(fixture.catalog().candidates().size(), expandedMapping(excess.selection().text()).size());
        var negative = prepared.selectMonotoneCoverage(fixture.catalog().text(), -1);
        assertEquals(0, negative.diagnostics().get("budget"));
        assertEquals(prepared.selectMonotoneCoverage(fixture.catalog().text(), 0).selection(), negative.selection());
        assertFalse(negative.selection().withinBudget());
        assertTrue(negative.selection().text().startsWith(fixture.catalog().text()));
    }

    @Test
    void nullSelectionAndLegacyPolicyCannotBecomeContinuationSeeds() {
        var fixture = fixture("Alpha 前端。", List.of(member(1, "Alpha")), List.of());
        var prepared = prepare(fixture);
        assertRejected(prepared, fixture.catalog().text(), null, 3000, "MONOTONE_SEED_REQUIRED");
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        assertRejected(prepared, fixture.catalog().text(), new ClassificationCoverageProofs.MemberSelection(null, seed.diagnostics()),
            3000, "MONOTONE_SEED_REQUIRED");
        assertRejected(prepared, fixture.catalog().text(), prepared.selectMemberCoverage(fixture.catalog().text(), 2000),
            3000, "MONOTONE_SEED_POLICY_MISMATCH");
    }

    @Test
    void changedPrefixAndForeignSourceCatalogAreRejectedBeforeExtension() {
        var fixture = fixture("Alpha 前端。", List.of(member(1, "Alpha")), List.of());
        var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        assertRejected(prepared, fixture.catalog().text() + "更改范围\n", seed, 3000, "MONOTONE_SEED_PREFIX_MISMATCH");
        var changed = fixture("Alpha 后端。", List.of(member(1, "Alpha")), List.of());
        assertRejected(prepare(changed), fixture.catalog().text(), seed, 3000, "MONOTONE_SEED_SOURCE_MISMATCH");
        var changedIdentity = fixture("Alpha 前端。", List.of(candidate(1, 201, "Alpha", 701)), List.of());
        assertRejected(prepare(changedIdentity), fixture.catalog().text(), seed, 3000, "MONOTONE_SEED_SOURCE_MISMATCH");
    }

    @Test
    void alteredSeedTextTokenAccountingOrSelectionMetadataIsRejected() {
        var fixture = fixture("Alpha 前端。", List.of(member(1, "Alpha")), List.of());
        var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        var selection = seed.selection();
        assertRejected(prepared, fixture.catalog().text(), withSelection(seed, new ClassificationProofCatalog.Selection(
            selection.text() + "伪造", selection.tokenCount(), selection.proofIds(), selection.omittedSpanCount(), true)),
            3000, "MONOTONE_SEED_RENDER_MISMATCH");
        assertRejected(prepared, fixture.catalog().text(), withSelection(seed, new ClassificationProofCatalog.Selection(
            selection.text(), selection.tokenCount() + 1, selection.proofIds(), selection.omittedSpanCount(), true)),
            3000, "MONOTONE_SEED_RENDER_MISMATCH");
        assertRejected(prepared, fixture.catalog().text(), withSelection(seed, new ClassificationProofCatalog.Selection(
            selection.text(), selection.tokenCount(), selection.proofIds(), selection.omittedSpanCount() + 1, true)),
            3000, "MONOTONE_SEED_SELECTION_MISMATCH");
        for (String field : List.of("tokenCount", "currentTokenCount"))
            assertRejected(prepared, fixture.catalog().text(), changedDiagnostics(seed, field, selection.tokenCount() + 1),
                3000, "MONOTONE_SEED_SELECTION_MISMATCH");
        for (String field : List.of("fixedTextTokens", "availableProofCount"))
            assertRejected(prepared, fixture.catalog().text(), changedDiagnostics(seed, field, (Integer) seed.diagnostics().get(field) + 1),
                3000, "MONOTONE_SEED_SELECTION_MISMATCH");
        assertRejected(prepared, fixture.catalog().text(), changedDiagnostics(seed, "sentMentionCandidateIds", List.of()),
            3000, "MONOTONE_SEED_COVERAGE_MISMATCH");
    }

    @Test
    void loweringTheSeedBudgetIsRejectedEvenWhenItsCurrentTextWouldStillFit() {
        var fixture = fixture("Alpha 前端。", List.of(member(1, "Alpha")), List.of());
        var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        assertTrue(seed.selection().tokenCount() < 1999);
        assertRejected(prepared, fixture.catalog().text(), seed, 1999, "MONOTONE_EXTENSION_BUDGET_TOO_SMALL");
        assertRejected(prepared, fixture.catalog().text(), seed, -1, "MONOTONE_EXTENSION_BUDGET_TOO_SMALL");
        assertRejected(prepared, fixture.catalog().text(), changedDiagnostics(seed, "budget", 3001),
            3000, "MONOTONE_EXTENSION_BUDGET_TOO_SMALL");
        assertRejected(prepared, fixture.catalog().text(), changedDiagnostics(seed, "requestedBudget", 1999),
            3000, "MONOTONE_SEED_BUDGET_INVALID");
    }

    @Test
    void duplicateUnknownAndOverlappingProofCoordinatesCannotEnterASeed() {
        String heading = "# Alpha：", fact = "Beta 使用独立运行时。";
        var fixture = fixture(heading + '\n' + fact, List.of(member(1, "Alpha"), member(2, "Beta")), List.of(edge(702, 102, fact)));
        var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        String headingId = proofId(prepared, heading), windowId = proofId(prepared, heading + '\n' + fact);
        assertRejected(prepared, fixture.catalog().text(), withSelection(seed, ids(headingId, headingId)),
            3000, "MONOTONE_SEED_DUPLICATE_PROOF");
        assertRejected(prepared, fixture.catalog().text(), withSelection(seed, ids("p99999999")),
            3000, "MONOTONE_SEED_UNKNOWN_PROOF");
        assertRejected(prepared, fixture.catalog().text(), withSelection(seed, ids(headingId, windowId)),
            3000, "MONOTONE_SEED_OVERLAPPING_PROOF");
    }

    @Test
    void forgedTransitionsAndResetCountersCannotBypassTheSharedTwoNHistoryCap() {
        var fixture = fixture("Alpha 前端。\nBeta 服务端。", List.of(member(1, "Alpha"), member(2, "Beta")), List.of());
        var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        int limit = 2 * prepared.proofs().spans().size();
        assertFalse(history(seed).isEmpty());
        assertRejected(prepared, fixture.catalog().text(), changedDiagnostics(seed, "stepLimit", limit + 1),
            3000, "MONOTONE_SEED_STEP_LIMIT_INVALID");
        assertRejected(prepared, fixture.catalog().text(), changedDiagnostics(seed, "selectionSteps", limit + 1),
            3000, "MONOTONE_SEED_STEP_LIMIT_INVALID");
        Map<String, Object> reset = new LinkedHashMap<>(seed.diagnostics());
        reset.put("selectionSteps", 0); reset.put("replacementSteps", 0); reset.put("selectionHistory", List.of());
        assertRejected(prepared, fixture.catalog().text(), new ClassificationCoverageProofs.MemberSelection(seed.selection(), reset),
            3000, "MONOTONE_SEED_HISTORY_PROOFS_MISMATCH");
        for (var change : Map.<String, Object>of("step", 7, "relevantGain", 999, "removedProofIds", List.of("p99999999"),
                "mentionCandidateIds", List.of(), "incrementalTokens", -999).entrySet()) {
            List<Map<String, Object>> forged = new ArrayList<>(history(seed));
            Map<String, Object> step = new LinkedHashMap<>(forged.get(0)); step.put(change.getKey(), change.getValue());
            forged.set(0, step);
            assertRejected(prepared, fixture.catalog().text(), changedDiagnostics(seed, "selectionHistory", forged),
                3000, "MONOTONE_SEED_HISTORY");
        }
        List<Map<String, Object>> nullHistory = new ArrayList<>(history(seed));
        Map<String, Object> nullStep = new LinkedHashMap<>(nullHistory.get(0)); nullStep.put("phase", null); nullHistory.set(0, nullStep);
        assertRejected(prepared, fixture.catalog().text(), changedDiagnostics(seed, "selectionHistory", nullHistory),
            3000, "MONOTONE_SEED_HISTORY_INVALID");
        var continued = prepared.extendMonotoneCoverage(fixture.catalog().text(), seed, 3000);
        var repeated = prepared.extendMonotoneCoverage(fixture.catalog().text(), continued, 3000);
        assertEquals(limit, repeated.diagnostics().get("stepLimit"));
        assertEquals(history(seed), history(repeated));
        assertTrue(history(repeated).size() <= limit);
    }

    @Test
    void legacyAndFrozenC1SelectionAndMetadataRemainUnchangedAfterC2Continuation() {
        var fixture = fixture("Alpha 前端。\nBeta 服务端。", List.of(member(1, "Alpha"), member(2, "Beta")), List.of());
        var prepared = prepare(fixture);
        var legacy = prepared.select(fixture.catalog().text(), 2000);
        var legacyMetadata = prepared.metadata(legacy.proofIds());
        var c1 = prepared.selectMemberCoverage(fixture.catalog().text(), 2000);
        var c1Metadata = prepared.memberSelectionMetadata(c1.selection().proofIds());
        var c2 = prepared.extendMonotoneCoverage(fixture.catalog().text(),
            prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000), 3000);
        assertEquals(legacy, prepared.select(fixture.catalog().text(), 2000));
        assertEquals(legacyMetadata, prepared.metadata(legacy.proofIds()));
        assertEquals(c1, prepared.selectMemberCoverage(fixture.catalog().text(), 2000));
        assertEquals(c1Metadata, prepared.memberSelectionMetadata(c1.selection().proofIds()));
        assertEquals("classification-candidate-context-v2", legacyMetadata.get("packingPolicy"));
        assertEquals("classification-member-coverage-v1", c1.diagnostics().get("packingPolicy"));
        assertEquals("classification-member-monotone-v2", c2.diagnostics().get("packingPolicy"));
        assertEquals(ClassificationCoverageProofs.POLICY_MONOTONE_COVERAGE,
            prepared.monotoneSelectionMetadata(c2.selection().proofIds()).get("packingPolicy"));
        String cited = c2.selection().proofIds().get(0);
        var citedMetadata = prepared.monotoneSelectionMetadata(c2.selection().proofIds(), List.of(cited));
        assertEquals("PROPOSAL_GLOBAL_PROOF_POOL", citedMetadata.get("citedCoverageScope"));
        assertEquals(true, citedMetadata.get("semanticSupportNotEstablished"));
        assertFalse(citedMetadata.containsKey("supported"));
        assertFalse(c2.selection().text().contains("检索对应 p"));
        assertTrue(legacy.text().contains("检索对应 p"));
    }

    private static void assertRejected(ClassificationCoverageProofs.Prepared prepared, String prefix,
            ClassificationCoverageProofs.MemberSelection seed, int budget, String reason) {
        var error = assertThrows(IllegalArgumentException.class, () -> prepared.extendMonotoneCoverage(prefix, seed, budget));
        assertTrue(error.getMessage().startsWith(reason), error.getMessage());
    }

    private static ClassificationCoverageProofs.MemberSelection withSelection(ClassificationCoverageProofs.MemberSelection seed,
            ClassificationProofCatalog.Selection selection) {
        return new ClassificationCoverageProofs.MemberSelection(selection, seed.diagnostics());
    }

    private static ClassificationCoverageProofs.MemberSelection changedDiagnostics(ClassificationCoverageProofs.MemberSelection seed,
            String key, Object value) {
        Map<String, Object> diagnostics = new LinkedHashMap<>(seed.diagnostics()); diagnostics.put(key, value);
        return new ClassificationCoverageProofs.MemberSelection(seed.selection(), diagnostics);
    }

    private static ClassificationProofCatalog.Selection ids(String... proofIds) {
        return new ClassificationProofCatalog.Selection("", 0, List.of(proofIds), 0, true);
    }

    private static void assertMemberSetsPreserved(ClassificationCoverageProofs.MemberSelection before,
            ClassificationCoverageProofs.MemberSelection after) {
        for (String dimension : DIMENSIONS) {
            assertEquals(memberSet(before, dimension), new LinkedHashSet<>((Collection<?>) after.diagnostics().get("seed" + dimension + "CandidateIds")));
            assertTrue(memberSet(after, dimension).containsAll(memberSet(before, dimension)), dimension + " member identities must survive");
        }
    }

    private static Set<?> memberSet(ClassificationCoverageProofs.MemberSelection packed, String dimension) {
        return new LinkedHashSet<>((Collection<?>) packed.diagnostics().get("sent" + dimension + "CandidateIds"));
    }

    private static void assertMonotoneHistory(List<Map<String, Object>> history) {
        Map<String, Set<?>> previous = new LinkedHashMap<>();
        for (String dimension : DIMENSIONS) previous.put(dimension, Set.of());
        int number = 0;
        for (var step : history) {
            assertEquals(++number, step.get("step"));
            boolean objectiveGain = false;
            for (var dimension : DIMENSIONS) {
                String key = Character.toLowerCase(dimension.charAt(0)) + dimension.substring(1) + "CandidateIds";
                Set<?> members = new LinkedHashSet<>((Collection<?>) step.get(key));
                assertTrue(members.containsAll(previous.get(dimension)), dimension + " coverage cannot shrink within history");
                if (!dimension.equals("Mention") && members.size() > previous.get(dimension).size()) objectiveGain = true;
                previous.put(dimension, members);
            }
            assertTrue(objectiveGain, "proof count or repeated mentions alone cannot consume a step");
        }
    }

    private static Map<Integer, List<String>> expandedMapping(String text) {
        String heading = "【成员可选证明；编号区间逐成员共用右侧证明；仅此次已展示】\n";
        int start = text.indexOf(heading);
        assertTrue(start >= 0);
        String mapping = text.substring(start + heading.length());
        mapping = mapping.substring(mapping.indexOf('\n') + 1);
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        for (String line : mapping.split("\\R")) {
            String[] sides = line.split("->", 2); assertEquals(2, sides.length, line);
            List<String> proofs = sides[1].equals("[]") ? List.of() : List.of(sides[1].split(","));
            for (String range : sides[0].split(",")) {
                String[] limits = range.split("-", 2);
                int from = Integer.parseInt(limits[0]), to = limits.length == 1 ? from : Integer.parseInt(limits[1]);
                assertTrue(to >= from);
                for (int member = from; member <= to; member++) assertNull(result.put(member, proofs), "member occurs exactly once: " + member);
            }
        }
        return result;
    }

    private static void assertNoOverlap(ClassificationCoverageProofs.Prepared prepared, ClassificationProofCatalog.Selection selected) {
        var spans = selected.proofIds().stream().map(id -> prepared.proofs().span(id).orElseThrow()).toList();
        for (int first = 0; first < spans.size(); first++) for (int second = first + 1; second < spans.size(); second++)
            assertFalse(spans.get(first).chunkId().equals(spans.get(second).chunkId())
                && spans.get(first).start() < spans.get(second).end() && spans.get(second).start() < spans.get(first).end());
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

    private record Fixture(GraphClassificationContract.Catalog catalog, EvidenceLedger ledger) { }

    private static ClassificationCoverageProofs.Prepared prepare(Fixture fixture) {
        return ClassificationCoverageProofs.prepare(QUESTION, fixture.catalog(), fixture.ledger());
    }

    private static Fixture chainFixture() {
        List<GraphClassificationContract.Candidate> candidates = new ArrayList<>();
        List<EvidenceLedger.RelationFact> edges = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (int number = 1; number <= 44; number++) {
            String name = "Tech" + number;
            String fact = name + " 使用独立运行时，" + "原始记录 ".repeat(45) + "。";
            candidates.add(member(number, name)); edges.add(edge(700 + number, 100 + number, fact));
            body.append(fact).append('\n');
        }
        return fixture(body.toString(), candidates, edges);
    }

    private static GraphClassificationContract.Candidate member(int number, String name) {
        return candidate(number, 100 + number, name, 700 + number);
    }

    private static GraphClassificationContract.Candidate candidate(int number, long member, String name, long relation) {
        return new GraphClassificationContract.Candidate(number, 42L, member, "项目A", name, "SKILL", "MENTIONS", "OUT", List.of(relation));
    }

    private static Fixture fixture(String body, List<GraphClassificationContract.Candidate> candidates, List<EvidenceLedger.RelationFact> edges) {
        var chunk = new EvidenceLedger.ChunkFact(9L, "POST", 6L, "项目A", body, 0, body.length());
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        List<EvidenceLedger.EntityFact> entities = new ArrayList<>();
        entities.add(new EvidenceLedger.EntityFact(42L, "项目A", "POST", ""));
        for (var candidate : candidates) entities.add(new EvidenceLedger.EntityFact(candidate.otherId(), candidate.name(), "SKILL", ""));
        when(ledger.chunks()).thenReturn(List.of(chunk)); when(ledger.relations()).thenReturn(edges); when(ledger.entities()).thenReturn(entities);
        when(ledger.chunk(any())).thenAnswer(call -> chunk.id().equals(call.getArgument(0)) ? java.util.Optional.of(chunk) : java.util.Optional.empty());
        when(ledger.relation(any())).thenAnswer(call -> edges.stream().filter(item -> item.id().equals(call.getArgument(0))).findFirst());
        when(ledger.entity(any())).thenAnswer(call -> entities.stream().filter(item -> item.id().equals(call.getArgument(0))).findFirst());
        StringBuilder directory = new StringBuilder("【全部授权候选】\n");
        for (var candidate : candidates) directory.append(candidate.number()).append(':').append(candidate.name()).append('\n');
        var catalog = new GraphClassificationContract.Catalog(true, "", candidates, directory.toString(), Map.of(), true, true, "技术归属");
        return new Fixture(catalog, ledger);
    }

    private static EvidenceLedger.RelationFact edge(long id, long member, String fact) {
        return new EvidenceLedger.RelationFact(id, 42L, member, "MENTIONS", null, null, "POST:6", 9L, fact);
    }
}
