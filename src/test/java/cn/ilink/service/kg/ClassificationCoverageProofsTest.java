package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClassificationCoverageProofsTest {
    private static final String QUESTION = "《项目A》哪些技术不属于Java生态？";

    @Test
    void allProofsRetainExactAuthorizedCoordinatesIncludingStructuralWindow() {
        String body = "开场。\n### 前端技术栈：\nAlpha 用于界面。\n│\nBeta 用于服务端。";
        var fixture = fixture(body, List.of(candidate(1, 42, "项目A", 101, "Alpha", 701),
            candidate(2, 42, "项目A", 102, "Beta", 702)));
        var prepared = prepare(fixture);
        assertTrue(prepared.proofs().validate(fixture.ledger(), prepared.proofs().spans().stream()
            .map(ClassificationProofCatalog.ProofSpan::id).toList()).isEmpty());
        assertTrue(prepared.proofs().spans().stream().anyMatch(proof -> proof.text().equals("### 前端技术栈：\nAlpha 用于界面。")));
        for (var proof : prepared.proofs().spans()) assertEquals(body.substring(proof.start(), proof.end()), proof.text());
    }

    @Test
    void ordinaryTwoSentencesRemainIndependentAndStableLocalIds() {
        var fixture = fixture("Alpha用于界面。\nBeta用于服务端。", List.of(candidate(1, 42, "项目A", 101, "Alpha", 701),
            candidate(2, 42, "项目A", 102, "Beta", 702)));
        var spans = prepare(fixture).proofs().spans();
        assertEquals(List.of("p1", "p2"), spans.stream().map(ClassificationProofCatalog.ProofSpan::id).toList());
        assertEquals(List.of("Alpha用于界面。", "Beta用于服务端。"), spans.stream().map(ClassificationProofCatalog.ProofSpan::text).toList());
    }

    @Test
    void pureSymbolFactAndBorderCannotCreateAProofOrMemberBinding() {
        String body = "│\n---\nAlpha 用于界面。";
        var fixture = fixture(body, List.of(candidate(1, 42, "项目A", 101, "Alpha", 701)),
            List.of(edge(701, 42, 101, "POST:6", 9L, "│")));
        var prepared = prepare(fixture);
        assertFalse(prepared.proofs().spans().isEmpty());
        assertTrue(prepared.proofs().spans().stream().allMatch(proof -> proof.text().codePoints().anyMatch(Character::isLetterOrDigit)));
        assertTrue(prepared.proofs().spans().stream().allMatch(proof -> proof.relationIds().isEmpty()));
        assertEquals(2, prepared.metadata(List.of()).get("removedSymbolSpanCount"));
    }

    @Test
    void selectionNeverRepeatsOverlappingSourceCoordinates() {
        var fixture = fixture("### 前端：\nAlpha用于界面。\nBeta用于界面。",
            List.of(candidate(1, 42, "项目A", 101, "Alpha", 701), candidate(2, 42, "项目A", 102, "Beta", 702)));
        var prepared = prepare(fixture);
        var selected = prepared.select(fixture.catalog().text(), 2000);
        var spans = selected.proofIds().stream().map(id -> prepared.proofs().span(id).orElseThrow()).toList();
        for (int first = 0; first < spans.size(); first++) for (int second = first + 1; second < spans.size(); second++)
            assertFalse(spans.get(first).chunkId().equals(spans.get(second).chunkId())
                && spans.get(first).start() < spans.get(second).end() && spans.get(second).start() < spans.get(first).end());
    }

    @Test
    void largeSummaryCannotSuppressAffordableShortMemberContext() {
        String body = "Alpha介绍".repeat(500) + "。\n### 技术栈：\nBeta 用于界面。";
        var fixture = fixture(body, List.of(candidate(1, 42, "项目A", 101, "Alpha", 701),
            candidate(2, 42, "项目A", 102, "Beta", 702)));
        var prepared = prepare(fixture);
        var selected = prepared.select(fixture.catalog().text(), 250);
        assertTrue(selected.withinBudget());
        assertTrue(selected.text().contains("Beta 用于界面。"));
        assertFalse(selected.text().contains("Alpha介绍".repeat(500)));
        assertTrue(prepared.needsExpansion(selected));
    }

    @Test
    void completeInventoryAndMemberProofMappingAreInsideSameBudget() {
        var fixture = fixture("Alpha 用于前端。\nBeta 用于服务端。", List.of(candidate(1, 42, "项目A", 101, "Alpha", 701),
            candidate(2, 42, "项目A", 102, "Beta", 702)));
        var prepared = prepare(fixture);
        var selection = prepared.select(fixture.catalog().text(), 2000);
        assertTrue(selection.text().startsWith(fixture.catalog().text()));
        assertTrue(selection.text().contains("检索对应 p1:1；名称/关系出处检索定位，不证明类别。"));
        assertTrue(selection.text().contains("检索对应 p2:2；名称/关系出处检索定位，不证明类别。"));
        assertEquals(EvidenceContext.tokens(selection.text()), selection.tokenCount());
        assertTrue(selection.withinBudget());
        assertFalse(prepared.needsExpansion(selection));
    }

    @Test
    void oversizedInventoryIsRetainedAndReportedRatherThanSilentlyTruncated() {
        var fixture = fixture("Alpha 用于界面。", List.of(candidate(1, 42, "项目A", 101, "Alpha", 701)));
        String prefix = "完整候选不能删除。".repeat(500);
        var selected = prepare(fixture).select(prefix, 80);
        assertTrue(selected.text().startsWith(prefix));
        assertTrue(selected.proofIds().isEmpty());
        assertFalse(selected.withinBudget());
    }

    @Test
    void shortAsciiNamesDoNotMatchLargerIdentifiers() {
        var candidates = List.of(candidate(1, 42, "项目A", 101, "Java", 701),
            candidate(2, 42, "项目A", 102, "ai", 702), candidate(3, 42, "项目A", 103, "Query", 703));
        var fixture = fixture("JavaScript 前端。\nai-service 后端。\nQueryIntent 查询。", candidates);
        assertTrue(prepare(fixture).proofs().spans().isEmpty());
        var exact = fixture("Java 语言。\nai 协议。\nQuery 模块。", candidates);
        assertEquals(List.of(1, 2, 3), prepare(exact).metadata(List.of()).get("availableMentionCandidateIds"));
    }

    @Test
    void terminalEnglishPunctuationDoesNotHideLiteralName() {
        var fixture = fixture("Name.\nJava-language 技术。\nJavaScript 技术。", List.of(
            candidate(1, 42, "项目A", 101, "Name", 701), candidate(2, 42, "项目A", 102, "Java", 702)));
        assertEquals(List.of(1), prepare(fixture).metadata(List.of()).get("availableMentionCandidateIds"));
    }

    @Test
    void partialAsciiRelationFactCannotCutANameOutOfLargerIdentifier() {
        var member = candidate(1, 42, "项目A", 101, "Java", 701);
        var fixture = fixture("JavaScript 前端。", List.of(member), List.of(edge(701, 42, 101, "POST:6", 9L, "Java")));
        assertTrue(prepare(fixture).proofs().spans().isEmpty());
        var sliced = new ClassificationProofCatalog.ProofSpan("p1", 9L, "POST", 6L, "项目A", 0, 4, "Java", Set.of(701L));
        assertFalse(ClassificationCoverageProofs.relevantToMember(member, sliced, fixture.ledger()));
    }

    @Test
    void sourceGuardRejectsStaleOrFabricatedCoordinatesBeforeMemberMatching() {
        var member = candidate(1, 42, "项目A", 101, "Alpha", 701);
        var fixture = fixture("Beta 技术。", List.of(member));
        var fabricated = new ClassificationProofCatalog.ProofSpan("p1", 9L, "POST", 6L, "项目A", 0, 5, "Alpha", Set.of());
        assertFalse(ClassificationCoverageProofs.relevantToMember(member, fabricated, fixture.ledger()));
        var wrongDoc = new ClassificationProofCatalog.ProofSpan("p1", 9L, "POST", 7L, "项目A", 0, 4, "Beta", Set.of());
        assertFalse(ClassificationCoverageProofs.relevantToMember(member, wrongDoc, fixture.ledger()));
    }

    @Test
    void sameCandidateNameCannotCrossDistinctSubjectDocuments() {
        var a = candidate(1, 42, "同名项目", 101, "Alpha", 701);
        var b = candidate(2, 43, "同名项目", 102, "Alpha", 702);
        var chunk = new EvidenceLedger.ChunkFact(9L, "POST", 6L, "同名项目", "Alpha 用于界面。", 0, 13);
        var edges = List.of(edge(701, 42, 101, "POST:6", 9L, "Alpha 用于界面。"),
            edge(702, 43, 102, "POST:7", 10L, "Alpha 用于服务端。"));
        var fixture = fixture(List.of(chunk), List.of(a, b), edges);
        var proof = prepare(fixture).proofs().spans().get(0);
        assertTrue(ClassificationCoverageProofs.relevantToMember(a, proof, fixture.ledger()));
        assertFalse(ClassificationCoverageProofs.relevantToMember(b, proof, fixture.ledger()));
    }

    @Test
    void sameSubjectOtherRelationCannotBindUnmentionedMember() {
        var member = candidate(1, 42, "项目A", 101, "Alpha", 701);
        var fixture = fixture("Beta 用于界面。", List.of(member), List.of(
            edge(701, 42, 101, "POST:6", 9L, "Alpha 用于服务端。"),
            edge(702, 42, 102, "POST:6", 9L, "Beta 用于界面。")));
        var proof = new ClassificationProofCatalog.ProofSpan("p1", 9L, "POST", 6L, "项目A", 0,
            "Beta 用于界面。".length(), "Beta 用于界面。", Set.of(702L));
        assertFalse(ClassificationCoverageProofs.relevantToMember(member, proof, fixture.ledger()));
        assertTrue(prepare(fixture).proofs().spans().isEmpty());
    }

    @Test
    void explicitMemberFactBindsOnlyItsAuthorizedDocumentAndChunk() {
        var member = candidate(1, 42, "项目A", 101, "Alpha", 701);
        var chunks = List.of(new EvidenceLedger.ChunkFact(8L, "POST", 6L, "项目A", "该技术使用独立运行时。", 0, 12),
            new EvidenceLedger.ChunkFact(9L, "POST", 6L, "项目A", "该技术使用独立运行时。", 0, 12));
        var fixture = fixture(chunks, List.of(member), List.of(edge(701, 42, 101, "POST:6", 9L, "该技术使用独立运行时。")));
        var spans = prepare(fixture).proofs().spans();
        assertEquals(1, spans.size());
        assertEquals(9L, spans.get(0).chunkId());
        assertEquals(Set.of(701L), spans.get(0).relationIds());
        var prepared = prepare(fixture);
        var selected = prepared.select(fixture.catalog().text(), 2000);
        var metadata = prepared.metadata(selected.proofIds(), selected.proofIds());
        assertEquals(List.of(), metadata.get("availableMentionCandidateIds"));
        assertEquals(List.of(1), metadata.get("availableBoundCandidateIds"));
        assertEquals(List.of(1), metadata.get("citedRelevantCandidateIds"));
        assertTrue(selected.text().contains("检索对应 p1:1；名称/关系出处检索定位，不证明类别。"));
    }

    @Test
    void expansionIncludesUnsentExactBindingsEvenWhenNameCoverageIsAlreadyComplete() {
        String fact = "确切关系说明".repeat(300) + "。";
        var member = candidate(1, 42, "项目A", 101, "Alpha", 701);
        var fixture = fixture("Alpha 名称。\n" + fact, List.of(member), List.of(edge(701, 42, 101, "POST:6", 9L, fact)));
        var prepared = prepare(fixture);
        var selected = prepared.select(fixture.catalog().text(), 200);
        assertEquals(List.of(1), prepared.metadata(selected.proofIds()).get("sentMentionCandidateIds"));
        assertEquals(List.of(), prepared.metadata(selected.proofIds()).get("sentBoundCandidateIds"));
        assertTrue(prepared.needsExpansion(selected));
    }

    @Test
    void omittedRedundantProseDoesNotAutomaticallyExpandBudget() {
        var fixture = fixture("Alpha 用于前端。\nAlpha 用于前端界面。", List.of(candidate(1, 42, "项目A", 101, "Alpha", 701)));
        var prepared = prepare(fixture);
        var selected = prepared.select(fixture.catalog().text(), 2000);
        assertEquals(1, selected.proofIds().size());
        assertEquals(1, selected.omittedSpanCount());
        assertFalse(prepared.needsExpansion(selected));
    }

    @Test
    void relatedDocumentOtherChunkCanSupplyNamedContextWithoutInventingRelationBinding() {
        var member = candidate(1, 42, "主体标题", 101, "Alpha", 701);
        var chunk = new EvidenceLedger.ChunkFact(9L, "POST", 6L, "架构摘录", "Alpha 使用独立运行时。", 0, 14);
        var fixture = fixture(List.of(chunk), List.of(member), List.of(edge(701, 42, 101, "POST:6", 8L, "Alpha 技术。")));
        var spans = prepare(fixture).proofs().spans();
        assertEquals(1, spans.size());
        assertTrue(spans.get(0).relationIds().isEmpty());
        assertTrue(ClassificationCoverageProofs.relevantToMember(member, spans.get(0), fixture.ledger()));
    }

    @Test
    void preparationAndSelectionAreDeterministicEvenWhenLedgerOrderChanges() {
        var members = List.of(candidate(1, 42, "项目A", 101, "Alpha", 701), candidate(2, 42, "项目A", 102, "Beta", 702));
        var chunks = List.of(new EvidenceLedger.ChunkFact(9L, "POST", 6L, "项目A", "Alpha 技术。", 0, 9),
            new EvidenceLedger.ChunkFact(10L, "POST", 6L, "项目A", "Beta 技术。", 0, 8));
        var first = prepare(fixture(chunks, members, List.of()));
        var second = prepare(fixture(List.of(chunks.get(1), chunks.get(0)), members, List.of()));
        assertEquals(first.proofs().spans(), second.proofs().spans());
        assertEquals(first.select("全部候选", 2000), second.select("全部候选", 2000));
    }

    @Test
    void retrievalMetadataNeverAwardsSemanticSupportOrDropsUnmatchedCandidates() {
        var fixture = fixture("Alpha 名称。", List.of(candidate(1, 42, "项目A", 101, "Alpha", 701),
            candidate(2, 42, "项目A", 102, "Missing", 702)));
        var prepared = prepare(fixture);
        var selection = prepared.select(fixture.catalog().text(), 2000);
        var metadata = prepared.metadata(selection.proofIds(), selection.proofIds());
        assertEquals(ClassificationCoverageProofs.POLICY, metadata.get("packingPolicy"));
        assertEquals(true, metadata.get("retrievalOnly"));
        assertEquals(true, metadata.get("semanticSupportNotEstablished"));
        assertEquals("PROPOSAL_GLOBAL_PROOF_POOL", metadata.get("citedCoverageScope"));
        assertEquals(2, ((Collection<?>) metadata.get("memberProofMapping")).size());
        assertTrue(selection.text().contains("2:Missing"));
        assertFalse(metadata.containsKey("supported"));
        assertFalse(metadata.containsKey("classification"));
    }

    private record Fixture(GraphClassificationContract.Catalog catalog, EvidenceLedger ledger) { }
    private static ClassificationCoverageProofs.Prepared prepare(Fixture fixture) {
        return ClassificationCoverageProofs.prepare(QUESTION, fixture.catalog(), fixture.ledger());
    }
    private static GraphClassificationContract.Candidate candidate(int number, long anchor, String title, long member,
            String name, long relation) {
        return new GraphClassificationContract.Candidate(number, anchor, member, title, name, "SKILL", "MENTIONS", "OUT", List.of(relation));
    }
    private static Fixture fixture(String body, List<GraphClassificationContract.Candidate> candidates) {
        return fixture(body, candidates, List.of());
    }
    private static Fixture fixture(String body, List<GraphClassificationContract.Candidate> candidates,
            List<EvidenceLedger.RelationFact> edges) {
        return fixture(List.of(new EvidenceLedger.ChunkFact(9L, "POST", 6L, candidates.get(0).anchorName(), body, 0, body.length())), candidates, edges);
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
        when(ledger.chunk(any())).thenAnswer(call -> chunks.stream().filter(chunk -> chunk.id().equals(call.getArgument(0))).findFirst());
        when(ledger.relation(any())).thenAnswer(call -> edges.stream().filter(edge -> edge.id().equals(call.getArgument(0))).findFirst());
        when(ledger.entity(any())).thenAnswer(call -> entities.stream().filter(entity -> entity.id().equals(call.getArgument(0))).findFirst());
        StringBuilder prefix = new StringBuilder("【全部授权候选】\n");
        for (var member : candidates) prefix.append(member.number()).append(':').append(member.name()).append('\n');
        var catalog = new GraphClassificationContract.Catalog(true, "", candidates, prefix.toString(), Map.of(), true, true, "技术归属");
        return new Fixture(catalog, ledger);
    }
    private static EvidenceLedger.RelationFact edge(long id, long anchor, long member, String doc, Long chunk, String fact) {
        return new EvidenceLedger.RelationFact(id, anchor, member, "MENTIONS", null, null, doc, chunk, fact);
    }
}
