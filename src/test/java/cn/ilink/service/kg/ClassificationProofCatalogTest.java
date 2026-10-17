package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClassificationProofCatalogTest {
    private static final String QUESTION = "《演示项目》关联技能里哪些属于前端技术？";

    @Test
    void stableIdsAndUtf16CoordinatesRecoverExactOriginalSentences() {
        String body = "  😀图形能力用于界面。\r\nRust 用于服务端。\n";
        EvidenceLedger ledger = ledger(List.of(chunk(9, 6, body)), List.of());
        var first = ClassificationProofCatalog.prepare(QUESTION, ledger, List.of(101L));
        var second = ClassificationProofCatalog.prepare(QUESTION, ledger, List.of(101L));
        assertEquals(first.spans(), second.spans());
        assertEquals(List.of("p1", "p2"), first.spans().stream().map(ClassificationProofCatalog.ProofSpan::id).toList());
        assertEquals(2, first.spans().get(0).start());
        for (var span : first.spans()) assertEquals(span.text(), body.substring(span.start(), span.end()));
        assertEquals("😀图形能力用于界面。", first.spans().get(0).text());
        assertEquals("POST", first.spans().get(0).docType());
        assertEquals(6L, first.spans().get(0).docId());
    }

    @Test
    void chunkOrderDoesNotChangeProofIds() {
        var a = chunk(7, 6, "第一句。第二句。");
        var b = chunk(8, 6, "另一片段。");
        var first = ClassificationProofCatalog.prepare(QUESTION, ledger(List.of(b, a), List.of()), List.of());
        var second = ClassificationProofCatalog.prepare(QUESTION, ledger(List.of(a, b), List.of()), List.of());
        assertEquals(first.spans(), second.spans());
        assertEquals(7L, first.spans().get(0).chunkId());
    }

    @Test
    void decimalVersionsAndIdentifiersAreNotSplitAtInternalDots() {
        String body = "Vue 3.2 uses app.vue. It renders UI!\nMySQL 8.0 服务。";
        var catalog = ClassificationProofCatalog.prepare(QUESTION, ledger(List.of(chunk(9, 6, body)), List.of()), List.of());
        assertEquals(List.of("Vue 3.2 uses app.vue.", "It renders UI!", "MySQL 8.0 服务。"),
            catalog.spans().stream().map(ClassificationProofCatalog.ProofSpan::text).toList());
    }

    @Test
    void relationFactBindsOnlyToItsExplicitSameDocumentAndChunk() {
        var wrongChunk = chunk(7, 6, "Vue 用于界面。");
        var actualChunk = chunk(9, 6, "本方案中 Vue 用于界面。");
        var edge = relation(701, "POST:6", 9L, "Vue 用于界面。");
        var catalog = ClassificationProofCatalog.prepare(QUESTION,
            ledger(List.of(wrongChunk, actualChunk), List.of(edge)), List.of(101L));
        assertTrue(catalog.spans().stream().filter(span -> span.chunkId().equals(7L))
            .allMatch(span -> span.relationIds().isEmpty()));
        var bound = catalog.spans().stream().filter(span -> span.relationIds().contains(701L)).findFirst().orElseThrow();
        assertEquals(9L, bound.chunkId());
        assertEquals("本方案中 Vue 用于界面。", bound.text());
    }

    @Test
    void matchingFactInAnotherDocumentCannotBecomeChunkProof() {
        var catalog = ClassificationProofCatalog.prepare(QUESTION,
            ledger(List.of(chunk(9, 6, "Vue 用于界面。")),
                List.of(relation(701, "POST:7", null, "Vue 用于界面。"))), List.of(101L));
        assertTrue(catalog.spans().stream().allMatch(span -> span.relationIds().isEmpty()));
    }

    @Test
    void missingExactFactDoesNotInventOrRewriteSourceText() {
        var catalog = ClassificationProofCatalog.prepare(QUESTION,
            ledger(List.of(chunk(9, 6, "Vue 用于界面。")),
                List.of(relation(701, "POST:6", 9L, "Vue 和 Rust 都用于界面。"))), List.of(101L));
        assertEquals(1, catalog.spans().size());
        assertEquals("Vue 用于界面。", catalog.spans().get(0).text());
        assertTrue(catalog.spans().get(0).relationIds().isEmpty());
    }

    @Test
    void factCrossingSentencesKeepsTheContinuousOriginalRange() {
        String body = "Vue 用于界面。\nRust 用于服务端。\n无关第三句。";
        String fact = "Vue 用于界面。\nRust 用于服务端。";
        var catalog = ClassificationProofCatalog.prepare(QUESTION,
            ledger(List.of(chunk(9, 6, body)), List.of(relation(701, "POST:6", 9L, fact))), List.of(101L));
        var span = catalog.spans().stream().filter(proof -> proof.relationIds().contains(701L)).findFirst().orElseThrow();
        assertEquals(fact, span.text());
        assertEquals(fact, body.substring(span.start(), span.end()));
        assertFalse(span.text().contains("无关第三句"));
    }

    @Test
    void selectionBudgetIncludesWholeCandidateInventoryAndSelectionNotice() {
        String prefix = "【全部候选】\n1|Vue|r#701\n2|Rust|r#702\n3|Arduino|r#703\n";
        var catalog = ClassificationProofCatalog.prepare(QUESTION,
            ledger(List.of(chunk(9, 6, "Vue 用于前端界面。Rust 用于服务端。Arduino 用于硬件。")), List.of()), List.of(101L));
        var selected = catalog.select(prefix, EvidenceContext.DEFAULT_BUDGET);
        assertTrue(selected.text().startsWith(prefix));
        assertTrue(selected.withinBudget());
        assertEquals(EvidenceContext.tokens(selected.text()), selected.tokenCount());
        assertFalse(selected.proofIds().isEmpty());
        assertTrue(selected.text().contains("3|Arduino|r#703"));
        assertTrue(selected.text().contains("未展示原文不证明候选应排除或属性不存在"));
    }

    @Test
    void oversizedInventoryRemainsIntactAndIsReportedOverBudget() {
        String prefix = "完整候选和处置不能被静默删除。".repeat(500);
        var catalog = ClassificationProofCatalog.prepare(QUESTION,
            ledger(List.of(chunk(9, 6, "Vue 用于界面。")), List.of()), List.of(101L));
        var selection = catalog.select(prefix, 80);
        assertTrue(selection.text().startsWith(prefix));
        assertFalse(selection.withinBudget());
        assertTrue(selection.proofIds().isEmpty());
    }

    @Test
    void oversizedSentenceIsOmittedWholeRatherThanTruncated() {
        String longSentence = "界面说明".repeat(500) + "。";
        var catalog = ClassificationProofCatalog.prepare(QUESTION,
            ledger(List.of(chunk(9, 6, longSentence + "Vue 用于前端界面。")), List.of()), List.of(101L));
        var selection = catalog.select("全部候选：Vue、Rust。", 200);
        assertTrue(selection.withinBudget());
        assertEquals(List.of("p2"), selection.proofIds());
        assertFalse(selection.text().contains("界面说明"));
        assertTrue(selection.text().contains("Vue 用于前端界面。"));
    }

    @Test
    void selectionUsesQuestionAndActualNamesWithoutTechnologyAllowlist() {
        String body = "无关的营销介绍。".repeat(8) + "\nArduino 用于用户指定的硬件控制。\n";
        var ledger = ledger(List.of(chunk(9, 6, body)), List.of());
        var catalog = ClassificationProofCatalog.prepare("《演示项目》哪些技能用于硬件控制？", ledger, List.of(103L));
        var selection = catalog.select("全部候选：Arduino、Vue。", 130);
        assertTrue(selection.text().contains("Arduino 用于用户指定的硬件控制。"));
        assertTrue(selection.proofIds().contains("p9"));
    }

    @Test
    void unknownProofAndNullProofAreRejected() {
        EvidenceLedger ledger = ledger(List.of(chunk(9, 6, "Vue 用于界面。")), List.of());
        var catalog = ClassificationProofCatalog.prepare(QUESTION, ledger, List.of());
        assertEquals(List.of("UNKNOWN_PROOF:p999"), catalog.validate(ledger, List.of("p999")));
        List<String> nullable = new ArrayList<>(); nullable.add(null);
        assertEquals(List.of("UNKNOWN_PROOF:null"), catalog.validate(ledger, nullable));
        assertTrue(catalog.span(null).isEmpty());
    }

    @Test
    void removedAuthorizedChunkIsRejectedInsteadOfBeingReloaded() {
        var original = ledger(List.of(chunk(9, 6, "Vue 用于界面。")), List.of());
        var catalog = ClassificationProofCatalog.prepare(QUESTION, original, List.of());
        var current = ledger(List.of(), List.of());
        assertEquals(List.of("UNAUTHORIZED_PROOF:p1"), catalog.validate(current, List.of("p1")));
    }

    @Test
    void changedCoordinatesCannotPassValidation() {
        var catalog = ClassificationProofCatalog.prepare(QUESTION,
            ledger(List.of(chunk(9, 6, "Vue 用于界面。")), List.of()), List.of());
        var current = ledger(List.of(chunk(9, 6, "短。")), List.of());
        assertEquals(List.of("PROOF_OFFSET_INVALID:p1"), catalog.validate(current, List.of("p1")));
    }

    @Test
    void changedTextCannotPassValidation() {
        var catalog = ClassificationProofCatalog.prepare(QUESTION,
            ledger(List.of(chunk(9, 6, "Vue 用于界面。")), List.of()), List.of());
        var current = ledger(List.of(chunk(9, 6, "Vue 用于后端。")), List.of());
        assertEquals(List.of("PROOF_TEXT_MISMATCH:p1"), catalog.validate(current, List.of("p1")));
    }

    @Test
    void changedDocumentCannotPassValidation() {
        var catalog = ClassificationProofCatalog.prepare(QUESTION,
            ledger(List.of(chunk(9, 6, "Vue 用于界面。")), List.of()), List.of());
        var current = ledger(List.of(chunk(9, 7, "Vue 用于界面。")), List.of());
        assertEquals(List.of("PROOF_DOCUMENT_MISMATCH:p1"), catalog.validate(current, List.of("p1")));
    }

    @Test
    void auditMetadataContainsOnlyActuallyUsedProofSpans() {
        var ledger = ledger(List.of(chunk(9, 6, "Vue 用于界面。Rust 用于服务端。")), List.of());
        var catalog = ClassificationProofCatalog.prepare(QUESTION, ledger, List.of());
        var metadata = catalog.usedMetadata(List.of("p2", "p2"));
        assertEquals(1, metadata.size());
        assertEquals("p2", metadata.get(0).get("proofId"));
        assertEquals("Rust 用于服务端。", metadata.get(0).get("text"));
        assertEquals(9L, metadata.get(0).get("chunkId"));
        assertTrue(catalog.validate(ledger, List.of("p2")).isEmpty());
        assertFalse(catalog.render(List.of("p2")).contains("Vue 用于界面。"));
    }

    private static EvidenceLedger ledger(List<EvidenceLedger.ChunkFact> chunks,
                                         List<EvidenceLedger.RelationFact> relations) {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.chunks()).thenReturn(chunks);
        when(ledger.relations()).thenReturn(relations);
        when(ledger.chunk(any())).thenAnswer(call -> chunks.stream()
            .filter(chunk -> chunk.id().equals(call.getArgument(0))).findFirst());
        when(ledger.relation(any())).thenAnswer(call -> relations.stream()
            .filter(edge -> edge.id().equals(call.getArgument(0))).findFirst());
        when(ledger.entity(any())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            String name = 101L == id ? "Vue" : 102L == id ? "Rust" : 103L == id ? "Arduino" : "演示项目";
            return Optional.of(new EvidenceLedger.EntityFact(id, name, id == 42L ? "POST" : "SKILL", ""));
        });
        return ledger;
    }

    private static EvidenceLedger.ChunkFact chunk(long id, long document, String text) {
        return new EvidenceLedger.ChunkFact(id, "POST", document, "演示项目", text, 0, text.length());
    }
    private static EvidenceLedger.RelationFact relation(long id, String document, Long chunk, String text) {
        return new EvidenceLedger.RelationFact(id, 42L, 101L, "MENTIONS", null, null, document, chunk, text);
    }
}
