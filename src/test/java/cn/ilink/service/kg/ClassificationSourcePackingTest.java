package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Synthetic source fixtures only; no models, database, evaluation cases or saved replay. */
class ClassificationSourcePackingTest {
    private static final List<String> DIMENSIONS = List.of("Mention", "Relevant", "Context", "Bound");
    private static final String TITLE = "项目A详细完整原始标题".repeat(8);
    private static final String QUESTION = "哪些技术不属于目标生态？";

    @Test
    void lengthDelimitedRecordsRecoverEveryExactSourceIncludingUnicodeAndEmbeddedNewlines() {
        String title = "项目A😀\n标题\\n字符".repeat(6);
        var fixture = structuralFixture(title);
        var prepared = prepare(fixture);
        var packed = prepared.selectSourceBlocks(fixture.catalog().text(), 2000);
        assertEquals(true, packed.diagnostics().get("sourceBlockAdopted"));
        var recovered = recover(packed.selection().text());
        assertEquals(packed.selection().proofIds(), new ArrayList<>(recovered.keySet()));
        assertTrue(recovered.values().stream().anyMatch(record -> record.text().contains("\n")),
            "a selected continuous structural window must preserve its original embedded newline");
        for (String id : packed.selection().proofIds()) {
            var proof = prepared.proofs().span(id).orElseThrow();
            var actual = recovered.get(id);
            assertEquals(proof.chunkId(), actual.chunk()); assertEquals(proof.docType(), actual.docType());
            assertEquals(proof.docId(), actual.doc()); assertEquals(proof.title(), actual.title());
            assertEquals(proof.start(), actual.start()); assertEquals(proof.end(), actual.end());
            assertEquals(proof.text(), actual.text());
            assertEquals(fixture.ledger().chunk(proof.chunkId()).orElseThrow().text().substring(proof.start(), proof.end()), actual.text());
        }
        assertTrue(prepared.proofs().validate(fixture.ledger(), packed.selection().proofIds()).isEmpty());
    }

    @Test
    void repeatedSourceIdentityAppearsOncePerChunkWithoutRemovingAnySourceText() {
        var fixture = multiLineFixture(TITLE, 16, 2);
        var prepared = prepare(fixture);
        var packed = prepared.selectSourceBlocks(fixture.catalog().text(), 3000);
        assertEquals(true, packed.diagnostics().get("sourceBlockAdopted"));
        long chunkCount = packed.selection().proofIds().stream().map(id -> prepared.proofs().span(id).orElseThrow().chunkId()).distinct().count();
        assertEquals(chunkCount, packed.selection().text().lines().filter(line -> line.startsWith("c#")).count());
        assertTrue((Integer) packed.diagnostics().get("representationSavingAdopted") > 0);
        assertEquals(packed.selection().proofIds().size(), recover(packed.selection().text()).size());
    }

    @Test
    void equalTitlesFromDifferentDocumentsRetainSeparateSourceIdentities() {
        var fixture = multiDocumentFixture(false);
        var prepared = prepare(fixture);
        var packed = prepared.selectSourceBlocks(fixture.catalog().text(), 3000);
        assertEquals(true, packed.diagnostics().get("sourceBlockAdopted"));
        var docs = recover(packed.selection().text()).values().stream().map(SourceRecord::doc).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(6L, 7L), docs);
        assertTrue(prepared.proofs().validate(fixture.ledger(), packed.selection().proofIds()).isEmpty());
    }

    @Test
    void separateChunksInTheSameDocumentKeepTheirOwnLocalCoordinates() {
        var fixture = multiDocumentFixture(true);
        var prepared = prepare(fixture);
        var packed = prepared.selectSourceBlocks(fixture.catalog().text(), 3000);
        assertEquals(true, packed.diagnostics().get("sourceBlockAdopted"));
        var records = recover(packed.selection().text());
        assertEquals(Set.of(9L, 10L), records.values().stream().map(SourceRecord::chunk).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(6L), records.values().stream().map(SourceRecord::doc).collect(java.util.stream.Collectors.toSet()));
        for (var record : records.values()) assertEquals(record.text(), fixture.ledger().chunk(record.chunk()).orElseThrow().text().substring(record.start(), record.end()));
    }

    @Test
    void suppliedSeedPreservesAllFourMemberSetsAndRemainsImmutable() {
        var fixture = multiLineFixture(TITLE, 30, 8);
        var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        String originalText = seed.selection().text(); var originalMetadata = new LinkedHashMap<>(seed.diagnostics());
        var packed = prepared.selectSourceBlocks(fixture.catalog().text(), seed, 2000);
        assertPreserved(seed, packed);
        assertEquals(originalText, seed.selection().text()); assertEquals(originalMetadata, seed.diagnostics());
        assertTrue(prepared.preservesMemberCoverage(seed.selection(), packed.selection()));
        assertEquals(seed.selection().proofIds(), packed.diagnostics().get("seedProofIds"));
    }

    @Test
    void hardBudgetIncludesInventorySourceHeadersRawTextAndCompleteMemberIndex() {
        var fixture = multiLineFixture(TITLE, 40, 18);
        var prepared = prepare(fixture);
        var packed = prepared.selectSourceBlocks(fixture.catalog().text(), 2000);
        assertTrue(packed.selection().withinBudget()); assertTrue(packed.selection().tokenCount() <= 2000);
        assertEquals(EvidenceContext.tokens(packed.selection().text()), packed.selection().tokenCount());
        assertTrue(packed.selection().text().startsWith(fixture.catalog().text()));
        assertEquals(40, expandedMapping(packed.selection().text()).size());
        assertEquals(packed.selection().tokenCount(), packed.diagnostics().get("sourceBlockTokens"));
    }

    @Test
    void upperBudgetIsCappedAtThreeThousand() {
        var fixture = multiLineFixture(TITLE, 40, 20);
        var prepared = prepare(fixture);
        var packed = prepared.selectSourceBlocks(fixture.catalog().text(), 10000);
        assertEquals(3000, packed.diagnostics().get("budget")); assertTrue(packed.selection().withinBudget());
        assertTrue(packed.selection().tokenCount() <= 3000);
    }

    @Test
    void oversizedFixedPrefixReturnsTheOriginalSeedAndNeverClaimsCompressionWasAdopted() {
        var fixture = multiLineFixture(TITLE, 4, 2);
        var prepared = prepare(fixture);
        String prefix = "完整查询范围不可删除。".repeat(600);
        var seed = prepared.selectMonotoneCoverage(prefix, 80);
        var packed = prepared.selectSourceBlocks(prefix, seed, 80);
        assertEquals(seed.selection(), packed.selection()); assertEquals(false, packed.diagnostics().get("sourceBlockAdopted"));
        assertEquals(0, packed.diagnostics().get("representationSavingAdopted"));
        assertEquals(ClassificationCoverageProofs.POLICY_MONOTONE_COVERAGE, packed.diagnostics().get("selectedPackingPolicy"));
        assertEquals("SEED_NOT_WITHIN_BUDGET", packed.diagnostics().get("stoppedReason"));
        assertFalse(packed.selection().withinBudget()); assertTrue(packed.selection().text().contains(fixture.catalog().text()));
    }

    @Test
    void absentMemberSourcesRemainEmptyAndDoNotBecomeAnAutomaticClassification() {
        var fixture = multiLineFixture(TITLE, 12, 2);
        List<GraphClassificationContract.Candidate> members = new ArrayList<>(fixture.catalog().candidates());
        members.add(member(13, "MissingTech", TITLE));
        var catalog = catalog(members);
        var prepared = ClassificationCoverageProofs.prepare(QUESTION, catalog, fixture.ledger());
        var packed = prepared.selectSourceBlocks(catalog.text(), 2000);
        assertEquals(List.of(), expandedMapping(packed.selection().text()).get(13));
        var row = mapping(prepared.sourcePackingSelectionMetadata(packed.selection().proofIds())).stream()
            .filter(item -> item.get("candidateId").equals(13)).findFirst().orElseThrow();
        assertEquals("NO_AVAILABLE_PROOF", row.get("evidenceDeliveryStatus"));
        assertEquals(true, packed.diagnostics().get("semanticSupportNotEstablished"));
        assertTrue(packed.selection().text().contains("不能自动赋予U/I/E"));
    }

    @Test
    void eightyEightMemberIndexContainsEveryMemberAndExactDeliveredProofIds() {
        var fixture = multiLineFixture(TITLE, 12, 2);
        List<GraphClassificationContract.Candidate> members = new ArrayList<>(fixture.catalog().candidates());
        for (int number = 13; number <= 88; number++) members.add(member(number, "Absent" + number, TITLE));
        var catalog = catalog(members);
        var prepared = ClassificationCoverageProofs.prepare(QUESTION, catalog, fixture.ledger());
        var packed = prepared.selectSourceBlocks(catalog.text(), 3000);
        var index = expandedMapping(packed.selection().text()); assertEquals(88, index.size());
        for (var row : mapping(prepared.sourcePackingSelectionMetadata(packed.selection().proofIds())))
            assertEquals(row.get("sentProofIds"), index.get(row.get("candidateId")));
        assertTrue(packed.selection().text().contains("13-88->[]"));
    }

    @Test
    void freedPresentationSpaceAddsAPreviouslyUnsentRelevantMember() {
        var fixture = multiLineFixture(TITLE, 36, 5);
        var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        var packed = prepared.selectSourceBlocks(fixture.catalog().text(), seed, 2000);
        assertEquals(true, packed.diagnostics().get("sourceBlockAdopted"));
        assertTrue(members(packed, "Relevant").size() > members(seed, "Relevant").size());
        assertTrue((Integer) packed.diagnostics().get("continuationSteps") > 0);
        assertPreserved(seed, packed); assertTrue(packed.selection().withinBudget());
        assertEquals(true, packed.diagnostics().get("semanticSupportNotEstablished"));
    }

    @Test
    void diagnosticsContainAllAvailableProofsIncludingThoseNotSent() {
        var fixture = multiLineFixture(TITLE, 40, 35);
        var prepared = prepare(fixture);
        var packed = prepared.selectSourceBlocks(fixture.catalog().text(), 2000);
        assertEquals(prepared.proofs().spans().stream().map(ClassificationProofCatalog.ProofSpan::metadata).toList(),
            packed.diagnostics().get("availableProofCatalog"));
        assertTrue(prepared.proofs().spans().size() > packed.selection().proofIds().size());
    }

    @Test
    void identicalInputsProduceTheSameSelectionPresentationAndActualStepHistory() {
        var fixture = multiLineFixture(TITLE, 30, 8);
        var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        var first = prepared.selectSourceBlocks(fixture.catalog().text(), seed, 2000);
        var second = prepared.selectSourceBlocks(fixture.catalog().text(), seed, 2000);
        assertEquals(first.selection(), second.selection()); assertEquals(first.diagnostics(), second.diagnostics());
    }

    @Test
    void continuationHistoryRecordsActualProofIdsAndEveryTransitionPreservesAllFourDimensions() {
        var fixture = multiLineFixture(TITLE, 36, 5);
        var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        var packed = prepared.selectSourceBlocks(fixture.catalog().text(), seed, 2000);
        var prior = new LinkedHashMap<String, Set<?>>();
        for (String dimension : DIMENSIONS) prior.put(dimension, members(seed, dimension));
        int stepNumber = (Integer) seed.diagnostics().get("selectionSteps");
        for (var step : history(packed)) {
            assertEquals(++stepNumber, step.get("step"));
            assertTrue((Integer) step.get("tokenCount") <= 2000);
            @SuppressWarnings("unchecked") List<String> ids = (List<String>) step.get("selectedProofIds");
            assertTrue(prepared.proofs().validate(fixture.ledger(), ids).isEmpty());
            for (String dimension : DIMENSIONS) {
                String key = Character.toLowerCase(dimension.charAt(0)) + dimension.substring(1) + "CandidateIds";
                Set<?> current = new LinkedHashSet<>((Collection<?>) step.get(key));
                assertTrue(current.containsAll(prior.get(dimension))); prior.put(dimension, current);
            }
        }
        assertEquals(stepNumber, packed.diagnostics().get("selectionSteps"));
        assertTrue(stepNumber <= 2 * prepared.proofs().spans().size());
        assertEquals(packed.selection().proofIds(), history(packed).get(history(packed).size() - 1).get("selectedProofIds"));
    }

    @Test
    void suppliedSeedFromAnotherPrefixOrSourceCatalogIsRejected() {
        var fixture = multiLineFixture(TITLE, 12, 2);
        var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        assertThrows(IllegalArgumentException.class, () -> prepared.selectSourceBlocks("另一范围", seed, 2000));
        var changed = multiLineFixture(TITLE, 12, 3);
        assertThrows(IllegalArgumentException.class, () -> prepare(changed).selectSourceBlocks(fixture.catalog().text(), seed, 2000));
    }

    @Test
    void malformedSuppliedSeedCannotSubstituteUnknownProofsOrItsOwnCoverageClaims() {
        var fixture = multiLineFixture(TITLE, 12, 2);
        var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        var unknown = new ClassificationProofCatalog.Selection(seed.selection().text(), seed.selection().tokenCount(),
            List.of("p999999"), seed.selection().omittedSpanCount(), seed.selection().withinBudget());
        assertThrows(IllegalArgumentException.class, () -> prepared.selectSourceBlocks(fixture.catalog().text(),
            new ClassificationCoverageProofs.MemberSelection(unknown, seed.diagnostics()), 2000));
        var altered = new LinkedHashMap<>(seed.diagnostics()); altered.put("sentRelevantCandidateIds", List.of(999));
        assertThrows(IllegalArgumentException.class, () -> prepared.selectSourceBlocks(fixture.catalog().text(),
            new ClassificationCoverageProofs.MemberSelection(seed.selection(), altered), 2000));
    }

    @Test
    void lowerBudgetCannotPretendToRetainAHigherBudgetSeed() {
        var fixture = multiLineFixture(TITLE, 12, 2);
        var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        assertThrows(IllegalArgumentException.class, () -> prepared.selectSourceBlocks(fixture.catalog().text(), seed, 1999));
        assertThrows(IllegalArgumentException.class, () -> prepared.selectSourceBlocks(fixture.catalog().text(), null, 2000));
    }

    @Test
    void deliveredCoverageAndCitedCoverageRemainDistinctAndDoNotCertifySemanticTruth() {
        var fixture = multiLineFixture(TITLE, 12, 2);
        var prepared = prepare(fixture);
        var packed = prepared.selectSourceBlocks(fixture.catalog().text(), 2000);
        var metadata = prepared.sourcePackingSelectionMetadata(packed.selection().proofIds(), List.of());
        assertFalse(((List<?>) metadata.get("sentRelevantCandidateIds")).isEmpty());
        assertEquals(List.of(), metadata.get("citedRelevantCandidateIds"));
        assertEquals(true, metadata.get("semanticSupportNotEstablished")); assertEquals(true, metadata.get("retrievalOnly"));
    }

    @Test
    void aMoreExpensiveSingleProofPresentationRetainsTheOriginalFormatAndTruthfulSavingsLedger() {
        var fixture = multiLineFixture("项目A", 1, 1);
        var prepared = prepare(fixture);
        var seed = prepared.selectMonotoneCoverage(fixture.catalog().text(), 2000);
        var packed = prepared.selectSourceBlocks(fixture.catalog().text(), seed, 2000);
        assertTrue((Integer) packed.diagnostics().get("sourceBlockSeedTokens") > seed.selection().tokenCount());
        assertEquals(false, packed.diagnostics().get("sourceBlockAdopted")); assertEquals(seed.selection(), packed.selection());
        assertEquals(0, packed.diagnostics().get("representationSavingAdopted"));
        assertEquals("SOURCE_BLOCK_PRESENTATION_COST_INCREASE", packed.diagnostics().get("stoppedReason"));
        assertPreserved(seed, packed);
    }

    private record Fixture(GraphClassificationContract.Catalog catalog, EvidenceLedger ledger) { }
    private record SourceRecord(Long chunk, String docType, Long doc, String title, int start, int end, String text) { }

    private static Fixture multiLineFixture(String title, int count, int repetitions) {
        StringBuilder body = new StringBuilder(); List<GraphClassificationContract.Candidate> candidates = new ArrayList<>();
        List<EvidenceLedger.RelationFact> edges = new ArrayList<>();
        for (int number = 1; number <= count; number++) {
            String name = "Tech" + number;
            String fact = name + " 使用独立运行时😀，" + "原始记录 ".repeat(repetitions) + "。";
            candidates.add(member(number, name, title)); body.append(fact).append('\n');
            edges.add(new EvidenceLedger.RelationFact(700L + number, 42L, 100L + number, "MENTIONS", null, null, "POST:6", 9L, fact));
        }
        return fixture(candidates, List.of(new EvidenceLedger.ChunkFact(9L, "POST", 6L, title, body.toString(), 0, body.length())), edges);
    }

    private static Fixture multiDocumentFixture(boolean sameDocument) {
        List<GraphClassificationContract.Candidate> candidates = new ArrayList<>();
        List<EvidenceLedger.RelationFact> edges = new ArrayList<>(); List<EvidenceLedger.ChunkFact> chunks = new ArrayList<>();
        for (int group = 0; group < 2; group++) {
            long chunk = 9L + group, doc = sameDocument ? 6L : 6L + group; StringBuilder body = new StringBuilder();
            for (int index = 1; index <= 8; index++) {
                int number = group * 8 + index; String name = "Tech" + number, fact = name + " 使用独立运行时。";
                candidates.add(member(number, name, TITLE)); body.append(fact).append('\n');
                edges.add(new EvidenceLedger.RelationFact(700L + number, 42L, 100L + number, "MENTIONS", null, null,
                    "POST:" + doc, chunk, fact));
            }
            chunks.add(new EvidenceLedger.ChunkFact(chunk, "POST", doc, TITLE, body.toString(), 0, body.length()));
        }
        return fixture(candidates, chunks, edges);
    }

    private static Fixture structuralFixture(String title) {
        var original = multiLineFixture(title, 12, 2);
        var first = original.ledger().chunks().iterator().next();
        String body = first.text(); int endOfFirstLine = body.indexOf('\n');
        body = "# Tech1 部署：\n" + body.substring(endOfFirstLine + 1);
        return fixture(original.catalog().candidates(),
            List.of(new EvidenceLedger.ChunkFact(first.id(), first.docType(), first.docId(), first.title(), body, 0, body.length())),
            new ArrayList<>(original.ledger().relations()));
    }

    private static Fixture fixture(List<GraphClassificationContract.Candidate> candidates,
            List<EvidenceLedger.ChunkFact> chunks, List<EvidenceLedger.RelationFact> edges) {
        EvidenceLedger ledger = mock(EvidenceLedger.class); List<EvidenceLedger.EntityFact> entities = new ArrayList<>();
        entities.add(new EvidenceLedger.EntityFact(42L, candidates.get(0).anchorName(), "POST", ""));
        for (var candidate : candidates) entities.add(new EvidenceLedger.EntityFact(candidate.otherId(), candidate.name(), "SKILL", ""));
        when(ledger.chunks()).thenReturn(chunks); when(ledger.relations()).thenReturn(edges); when(ledger.entities()).thenReturn(entities);
        when(ledger.chunk(any())).thenAnswer(call -> chunks.stream().filter(chunk -> chunk.id().equals(call.getArgument(0))).findFirst());
        when(ledger.relation(any())).thenAnswer(call -> edges.stream().filter(edge -> edge.id().equals(call.getArgument(0))).findFirst());
        when(ledger.entity(any())).thenAnswer(call -> entities.stream().filter(entity -> entity.id().equals(call.getArgument(0))).findFirst());
        return new Fixture(catalog(candidates), ledger);
    }

    private static GraphClassificationContract.Candidate member(int number, String name, String title) {
        return new GraphClassificationContract.Candidate(number, 42L, 100L + number, title, name, "SKILL", "MENTIONS", "OUT", List.of(700L + number));
    }

    private static GraphClassificationContract.Catalog catalog(List<GraphClassificationContract.Candidate> members) {
        StringBuilder directory = new StringBuilder("【全部授权候选】\n");
        for (var member : members) directory.append(member.number()).append(':').append(member.name()).append('\n');
        return new GraphClassificationContract.Catalog(true, "", members, directory.toString(), Map.of(), true, true, "技术归属");
    }

    private static ClassificationCoverageProofs.Prepared prepare(Fixture fixture) {
        return ClassificationCoverageProofs.prepare(QUESTION, fixture.catalog(), fixture.ledger());
    }

    private static Set<?> members(ClassificationCoverageProofs.MemberSelection selection, String dimension) {
        return new LinkedHashSet<>((Collection<?>) selection.diagnostics().get("sent" + dimension + "CandidateIds"));
    }

    private static void assertPreserved(ClassificationCoverageProofs.MemberSelection seed, ClassificationCoverageProofs.MemberSelection packed) {
        for (String dimension : DIMENSIONS) {
            assertTrue(members(packed, dimension).containsAll(members(seed, dimension)));
            assertEquals(List.of(), packed.diagnostics().get("lostSeed" + dimension + "CandidateIds"));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> history(ClassificationCoverageProofs.MemberSelection packed) {
        return (List<Map<String, Object>>) packed.diagnostics().get("sourceBlockSelectionHistory");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapping(Map<String, Object> metadata) {
        return (List<Map<String, Object>>) metadata.get("memberProofMapping");
    }

    private static Map<Integer, List<String>> expandedMapping(String text) {
        String heading = "【成员可选证明；编号区间逐成员共用右侧证明；仅此次已展示】\n";
        String mapping = text.substring(text.indexOf(heading) + heading.length());
        mapping = mapping.substring(mapping.indexOf('\n') + 1); Map<Integer, List<String>> result = new LinkedHashMap<>();
        for (String line : mapping.split("\\R")) {
            String[] sides = line.split("->", 2); assertEquals(2, sides.length);
            List<String> ids = sides[1].equals("[]") ? List.of() : List.of(sides[1].split(","));
            for (String range : sides[0].split(",")) {
                String[] ends = range.split("-", 2); int from = Integer.parseInt(ends[0]), to = ends.length == 1 ? from : Integer.parseInt(ends[1]);
                for (int member = from; member <= to; member++) assertNull(result.put(member, ids));
            }
        }
        return result;
    }

    /** This independent decoder consumes lengths, so proof-looking strings inside original text cannot become headers. */
    private static Map<String, SourceRecord> recover(String text) {
        String heading = "【授权连续原文；块内p#共享c#出处；长度为UTF16字符数；原文不改写】\n";
        int cursor = text.indexOf(heading); assertTrue(cursor >= 0); cursor += heading.length();
        var documentPattern = Pattern.compile("c#(\\d+) \\[(.*?):(\\d+)] title=(.*)");
        var proofPattern = Pattern.compile("(p\\d+)@(\\d+):(\\d+) chars=(\\d+)");
        Long chunk = null, doc = null; String docType = null, title = null;
        Map<String, SourceRecord> result = new LinkedHashMap<>();
        while (!text.startsWith("【原文选择范围】", cursor)) {
            int newline = text.indexOf('\n', cursor); assertTrue(newline >= cursor); String line = text.substring(cursor, newline); cursor = newline + 1;
            if (line.startsWith("c#")) {
                var match = documentPattern.matcher(line); assertTrue(match.matches());
                chunk = Long.valueOf(match.group(1)); docType = match.group(2); doc = Long.valueOf(match.group(3)); title = unescape(match.group(4));
            } else {
                var match = proofPattern.matcher(line); assertTrue(match.matches()); assertNotNull(chunk);
                int size = Integer.parseInt(match.group(4)); String raw = text.substring(cursor, cursor + size); cursor += size;
                assertEquals('\n', text.charAt(cursor++));
                assertNull(result.put(match.group(1), new SourceRecord(chunk, docType, doc, title,
                    Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)), raw)));
            }
        }
        return result;
    }

    private static String unescape(String value) {
        StringBuilder decoded = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\') decoded.append(current);
            else { char next = value.charAt(++index); decoded.append(next == 'n' ? '\n' : next == 'r' ? '\r' : next); }
        }
        return decoded.toString();
    }
}
