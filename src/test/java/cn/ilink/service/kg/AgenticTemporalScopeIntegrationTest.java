package cn.ilink.service.kg;

import cn.ilink.mapper.KgAgentTraceMapper;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.kg.tool.GraphTool;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Temporal coverage stays independent of non-temporal candidate classification and neighbor coverage. */
class AgenticTemporalScopeIntegrationTest {
    private static final String SHORT_TITLE = "前端视觉重构报告";
    private static final String TITLE = "前端页面视觉重构升级报告";

    @Test
    void completeWindowWithShortDocumentTitleIsConditionalAndMakesNoChatRequest() {
        AiClient ai = mock(AiClient.class);
        var answer = service(ledger(TITLE, true, true), ai).query(question(SHORT_TITLE), 8L);
        assertTrue(answer.answer.contains("若你指的是《" + TITLE + "》"));
        assertFalse(answer.answer.contains("仅覆盖本次可核对部分"));
        assertTrue(answer.answer.contains("r#10"));
        assertEquals("CONDITIONAL", last(answer, "answerCoverage"));
        assertEquals("DOCUMENT_TITLE_MATCH_UNCONFIRMED", last(answer, "coverageReason"));
        Map<?, ?> scope = (Map<?, ?>) last(answer, "temporalAnswerScope");
        assertEquals(false, scope.get("requestedSubjectsConfirmed"));
        assertEquals(true, scope.get("resolvedWindowComplete"));
        assertEquals(false, scope.get("requestedScopeComplete"));
        assertEquals("TEMPORAL_QUERY", last(answer, "classificationSkipReason"));
        verifyNoInteractions(ai);
    }

    @Test
    void truncatedWindowKeepsPartialWarningEvenWithDocumentTitleEvidence() {
        AiClient ai = mock(AiClient.class);
        var answer = service(ledger(TITLE, false, true), ai).query(question(SHORT_TITLE), 8L);
        assertTrue(answer.answer.contains("仅覆盖本次可核对部分"));
        assertEquals("PARTIAL", last(answer, "answerCoverage"));
        assertEquals(false, ((Map<?, ?>) last(answer, "temporalAnswerScope")).get("resolvedWindowComplete"));
        verifyNoInteractions(ai);
    }

    @Test
    void exactSubjectAndCompleteWindowDoNotRequireNeighborExpansionOrClassification() {
        AiClient ai = mock(AiClient.class);
        var answer = service(ledger(TITLE, true, true), ai).query(question(TITLE), 8L);
        assertFalse(answer.answer.contains("仅覆盖本次可核对部分"));
        assertEquals("COMPLETE", last(answer, "answerCoverage"));
        assertEquals("TEMPORAL_QUERY", last(answer, "classificationSkipReason"));
        assertTrue(answer.trace.stream().noneMatch(step -> step.containsKey("graphCandidateInventory")));
        verifyNoInteractions(ai);
    }

    @Test
    void exactCompleteEmptyWindowCanReturnNoMatchWithoutChat() {
        AiClient ai = mock(AiClient.class);
        var answer = service(ledger(TITLE, true, false), ai).query(question(TITLE), 8L);
        assertTrue(answer.answer.contains("没有符合所问条件的竞赛关联"));
        assertEquals("COMPLETE", last(answer, "answerCoverage"));
        assertEquals(true, ((Map<?, ?>) last(answer, "temporalAnswerScope")).get("requestedScopeComplete"));
        verifyNoInteractions(ai);
    }

    @Test
    void unresolvedTitleCannotUseEmptyTemporalToolResultToSkipSemanticVerification() {
        AiClient ai = mock(AiClient.class);
        String query = question(SHORT_TITLE);
        var result = new AnswerEvidenceVerifier().verifySemantic(query,
            "在指定时间段内，没有符合所问条件的竞赛关联。",
            ledger(TITLE, true, false), QueryEvidencePlan.fromQuestion(query), ai);
        assertFalse(result.supported());
        assertTrue(result.reasons().stream().anyMatch(reason ->
            reason.startsWith("TEMPORAL_ABSENCE_SCOPE_UNCONFIRMED")));
        verifyNoInteractions(ai);
    }

    @Test
    void partialWindowCannotSupportNoMatchInModelFallback() {
        AiClient ai = mock(AiClient.class);
        String query = question(TITLE);
        var result = new AnswerEvidenceVerifier().verifySemantic(query,
            "在指定时间段内，没有符合所问条件的竞赛关联。",
            ledger(TITLE, false, false), QueryEvidencePlan.fromQuestion(query), ai);
        assertFalse(result.supported());
        assertTrue(result.reasons().stream().anyMatch(reason ->
            reason.startsWith("TEMPORAL_ABSENCE_SCOPE_UNCONFIRMED")));
        verifyNoInteractions(ai);
    }

    @Test
    void sourceOnlyPropertyAnswerIsNotDiscardedWhenGraphTraversalIsIncomplete() {
        String query = "知识图谱里，中国高校计算机大赛与中国高校计算机大赛 AIGC 创新赛分别由什么组织主办？";
        var host = new EvidenceLedger.EntityFact(301L, "全国高等学校计算机教育研究会", "ORGANIZATION", "");
        var chunk = new EvidenceLedger.ChunkFact(336L, "COMPETITION", 53L,
            "中国高校计算机大赛 AIGC 创新赛",
            "竞赛名称：中国高校计算机大赛 AIGC 创新赛；主办方：全国高等学校计算机教育研究会。", 0, 50);
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.entities()).thenReturn(List.of(host));
        when(ledger.entity(301L)).thenReturn(Optional.of(host));
        when(ledger.relations()).thenReturn(List.of());
        when(ledger.chunks()).thenReturn(List.of(chunk));
        when(ledger.chunk(336L)).thenReturn(Optional.of(chunk));
        when(ledger.containsChunk(336L)).thenReturn(true);
        when(ledger.coverage()).thenReturn(List.of());

        AiClient ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult(
                "中国高校计算机大赛 AIGC 创新赛由全国高等学校计算机教育研究会主办。c#336", 1, 1))
            .thenReturn(new AiClient.AiChatResult(
                "{\"facts\":\"SUPPORTED\",\"coverage\":\"INCOMPLETE\",\"unsupported\":[],"
                    + "\"missing\":[\"授权图谱候选未完整展示\"]}", 1, 1));
        EvidenceLedgerFactory factory = mock(EvidenceLedgerFactory.class);
        when(factory.create()).thenReturn(ledger);
        var service = new AgenticGraphRagService(mock(GraphRagService.class),
            mock(KgAgentTraceMapper.class), ai, List.of(), factory);

        var answer = service.query(query, 8L);

        assertTrue(answer.answer.contains("全国高等学校计算机教育研究会"));
        assertTrue(answer.answer.contains("c#336"));
        assertFalse(answer.answer.startsWith("以下是《"));
        assertEquals("COMPLETE_SOURCE_ONLY", last(answer, "answerCoverage"));
        assertEquals("PARTIAL", last(answer, "graphCoverage"));
    }

    private static String question(String title) {
        return "2026 年 3 月，" + title + "与哪个竞赛有关联？";
    }

    private static EvidenceLedger ledger(String title, boolean complete, boolean hasMatch) {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        var source = new EvidenceLedger.EntityFact(1L, title, "POST", "");
        var target = new EvidenceLedger.EntityFact(2L, "校园创新赛", "COMPETITION", "");
        var edge = new EvidenceLedger.RelationFact(10L, 1L, 2L, "RELATED_TO",
            day("2026-03-05"), day("2026-04-01"), "POST:6", 99L, "工程记录与校园创新赛关联。");
        var chunk = new EvidenceLedger.ChunkFact(99L, "POST", 6L, title,
            "工程记录与校园创新赛关联。", 0, 14);
        when(ledger.entities()).thenReturn(List.of(source, target));
        when(ledger.entity(1L)).thenReturn(Optional.of(source));
        when(ledger.entity(2L)).thenReturn(Optional.of(target));
        when(ledger.containsEntity(1L)).thenReturn(true);
        when(ledger.containsEntity(2L)).thenReturn(true);
        when(ledger.relations()).thenReturn(hasMatch ? List.of(edge) : List.of());
        if (hasMatch) when(ledger.relation(10L)).thenReturn(Optional.of(edge));
        when(ledger.chunks()).thenReturn(List.of(chunk));
        when(ledger.chunk(99L)).thenReturn(Optional.of(chunk));
        when(ledger.containsChunk(99L)).thenReturn(true);
        when(ledger.hasToolResult("query_temporal")).thenReturn(true);
        when(ledger.completeTemporal(eq(1L), any(KgTimeRange.class))).thenReturn(complete);
        // completeRelations deliberately stays false: it is not a temporal completeness requirement.
        return ledger;
    }

    private static AgenticGraphRagService service(EvidenceLedger ledger, AiClient ai) {
        EvidenceLedgerFactory factory = mock(EvidenceLedgerFactory.class);
        when(factory.create()).thenReturn(ledger);
        GraphTool temporal = new GraphTool() {
            public String getName() { return "query_temporal"; }
            public String getDescription() { return "isolated temporal fixture"; }
            public String getParametersSchema() { return "{}"; }
            public Object execute(Map<String, Object> params, Long userId) {
                return Map.of("relations", List.of());
            }
        };
        return new AgenticGraphRagService(mock(GraphRagService.class), mock(KgAgentTraceMapper.class),
            ai, List.of(temporal), factory);
    }

    private static Object last(AgenticGraphRagService.AgenticAnswer answer, String key) {
        return answer.trace.stream().filter(step -> step.containsKey(key)).reduce((first, second) -> second)
            .orElseThrow().get(key);
    }

    private static Date day(String value) {
        return Date.from(LocalDate.parse(value).atStartOfDay(KgTimeRange.ZONE).toInstant());
    }
}
