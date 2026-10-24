package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceRecordExcerptSelectorTest {
    private static final String M3 =
        "团队空间模块开发日志在图谱里关联了哪些前端交互设计和拖拽接口预留？";
    private static final String M4 =
        "《java面试的一些要点》在图谱里关联的技能中，与「代理」相关的有哪些？";

    @Test
    void combinesAdjacentRelevantChunksFromTheUniqueNamedRecord() {
        EvidenceLedger ledger = ledger(List.of(
            chunk(341L, "POST", 3L, "Link 团队空间模块开发日志",
                "任务协作看板采用 Kanban；拖拽预留 data-task-id 和 data-status。"),
            chunk(342L, "POST", 3L, "Link 团队空间模块开发日志",
                "下一步接入后端 CRUD API，包括拖拽更新接口预留。"),
            chunk(262L, "ASSET", 2L, "iLink 团队空间模块开发成果",
                "成果总结也提到看板和拖拽，但不是问题指定的开发日志。")));

        SourceRecordExcerptSelector.Selection selection =
            SourceRecordExcerptSelector.select(M3, ledger).orElse(null);

        assertNotNull(selection);
        assertEquals(Set.of(341L, 342L),
            selection.chunks().stream().map(EvidenceLedger.ChunkFact::id)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(selection.text().contains("Kanban"));
        assertTrue(selection.text().contains("data-task-id"));
        assertTrue(selection.text().contains("拖拽更新接口预留"));
        assertFalse(selection.text().contains("部分结果"));
        assertFalse(selection.text().contains("尚不能确认"));
    }

    @Test
    void excludesUnrelatedChunksWhenOneRecordContainsManyTopics() {
        EvidenceLedger ledger = ledger(List.of(
            chunk(344L, "POST", 5L, "java面试的一些要点",
                "反射之后开始介绍动态代理。"),
            chunk(345L, "POST", 5L, "java面试的一些要点",
                "JDK 动态代理使用 InvocationHandler；CGLIB 借助 ASM。"),
            chunk(346L, "POST", 5L, "java面试的一些要点",
                "后续讨论 hashcode、NIO 和序列化。")));

        SourceRecordExcerptSelector.Selection selection =
            SourceRecordExcerptSelector.select(M4, ledger).orElse(null);

        assertNotNull(selection);
        assertEquals(Set.of(344L, 345L),
            selection.chunks().stream().map(EvidenceLedger.ChunkFact::id)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(selection.text().contains("JDK 动态代理"));
        assertTrue(selection.text().contains("CGLIB"));
        assertFalse(selection.text().contains("hashcode"));
    }

    @Test
    void ambiguousNamedRecordsStayOnTheClarificationPath() {
        EvidenceLedger ledger = ledger(List.of(
            chunk(1L, "POST", 10L, "java面试的一些要点", "动态代理 A"),
            chunk(2L, "POST", 11L, "java面试的一些要点", "动态代理 B")));

        assertTrue(SourceRecordExcerptSelector.select(M4, ledger).isEmpty());
    }

    @Test
    void serviceExitPublishesAllSelectedChunksAndMarksOnlyGraphCoveragePartial() {
        EvidenceLedger ledger = ledger(List.of(
            chunk(344L, "POST", 5L, "java面试的一些要点", "动态代理题目。"),
            chunk(345L, "POST", 5L, "java面试的一些要点",
                "JDK 动态代理与 CGLIB 动态代理。")));
        List<Map<String, Object>> trace = new ArrayList<>();

        AgenticGraphRagService.AgenticAnswer answer =
            AgenticGraphRagService.namedRecordSourceAnswer(M4, ledger, trace, 1, 2);

        assertNotNull(answer);
        assertTrue(answer.answer.contains("c#344"));
        assertTrue(answer.answer.contains("c#345"));
        assertEquals(2, answer.citations.size());
        assertEquals("STRUCTURED_NAMED_RECORD_SOURCE", trace.get(1).get("answerMode"));
        assertEquals("SOURCE_SCOPED", trace.get(1).get("answerCoverage"));
        assertEquals("PARTIAL", trace.get(1).get("graphCoverage"));
        assertEquals(false, trace.get(1).get("absenceConclusionAllowed"));
    }

    @Test
    void nonGraphQuestionDoesNotUseTheGraphFallback() {
        EvidenceLedger ledger = ledger(List.of(
            chunk(345L, "POST", 5L, "java面试的一些要点",
                "JDK 动态代理使用 InvocationHandler；CGLIB 借助 ASM。")));

        assertNull(AgenticGraphRagService.namedRecordSourceAnswer(
            "《java面试的一些要点》里关于代理的原文有哪些？", ledger, new ArrayList<>(), 1, 1));
    }

    private static EvidenceLedger ledger(List<EvidenceLedger.ChunkFact> chunks) {
        return EvidenceLedger.preAuthorized(List.of(), List.of(), chunks,
            List.of("search_documents"));
    }

    private static EvidenceLedger.ChunkFact chunk(Long id, String type, Long docId,
                                                   String title, String text) {
        return new EvidenceLedger.ChunkFact(id, type, docId, title, text, 0, text.length());
    }
}
