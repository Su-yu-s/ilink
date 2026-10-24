package cn.ilink.service.kg;

import cn.ilink.mapper.KgAgentTraceMapper;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.kg.tool.GraphTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Production entry-point gates; all external dependencies are isolated mocks. */
class StageAScopeIntegrationTest {
    private static final String PARENT = "全国高校计算机创新创意大赛";
    private static final String CHILD = PARENT + " AIGC 创新赛";
    private static final String QUESTION = "知识图谱里，" + PARENT + "与" + CHILD + "分别由什么组织主办？";

    @Test
    void globalAbsenceForUnqueriedChildDoesNotReachSemanticModel() {
        EvidenceLedger ledger = hostingLedger();
        AiClient ai = mock(AiClient.class);
        var checked = new AnswerEvidenceVerifier().verifySemantic(QUESTION,
            PARENT + "由研究协会主办（r#10）。\n" + CHILD + "：图谱未收录该边，仅引用原文 c#99。",
            ledger, QueryEvidencePlan.fromQuestion(QUESTION), ai);
        assertFalse(checked.supported());
        assertTrue(checked.reasons().stream().anyMatch(reason -> reason.startsWith("GRAPH_ABSENCE_GLOBAL_SCOPE")));
        verifyNoInteractions(ai);
    }

    @Test
    void authorizedAbsenceStillRequiresChildQueryRatherThanParentQuery() {
        EvidenceLedger ledger = hostingLedger();
        var checked = new AnswerEvidenceVerifier().verify(QUESTION,
            CHILD + "：当前授权范围未找到主办关系。c#99", ledger, QueryEvidencePlan.fromQuestion(QUESTION));
        assertFalse(checked.supported());
        assertTrue(checked.reasons().stream().anyMatch(reason -> reason.startsWith("GRAPH_ABSENCE_UNQUERIED_SUBJECT")));
    }

    @Test
    void answerIntroducedGraphAbsenceIsCheckedEvenWhenQuestionOmitsGraphWord() {
        String question = PARENT + "与" + CHILD + "分别由什么组织主办？";
        AiClient ai = mock(AiClient.class);
        var checked = new AnswerEvidenceVerifier().verifySemantic(question,
            CHILD + "图谱未收录该边。c#99", hostingLedger(), QueryEvidencePlan.fromQuestion(question), ai);
        assertFalse(checked.supported());
        assertTrue(checked.reasons().stream().anyMatch(reason -> reason.startsWith("GRAPH_ABSENCE_GLOBAL_SCOPE")));
        verifyNoInteractions(ai);
    }

    @Test
    void realRelationIdWithWrongObjectDoesNotReachSemanticModel() {
        var post = entity(1, "工程记录", "POST");
        var controller = entity(2, "Controller", "SKILL");
        var widget = entity(3, "ChartWidget", "SKILL");
        EvidenceLedger ledger = ledger(List.of(post, controller, widget), List.of(relation(10, 1, 2, "MENTIONS")));
        AiClient ai = mock(AiClient.class);
        String question = "工程记录在图谱里提到了哪些技能？";
        var checked = new AnswerEvidenceVerifier().verifySemantic(question,
            "ChartWidget：前端组件（r#10）。", ledger, QueryEvidencePlan.fromQuestion(question), ai);
        assertFalse(checked.supported());
        assertTrue(checked.reasons().contains("RELATION_ENDPOINT_MISMATCH:r#10"));
        verifyNoInteractions(ai);
    }

    @Test
    void serviceQueriesBothSubjectsAndTwoParentHostsCannotCompleteTheComparison() {
        EvidenceLedger ledger = hostingLedger();
        AiClient ai = partialAnswerClient(PARENT + "由研究协会主办（r#10）。\n"
            + CHILD + "的主办关系尚未由本次图谱证据确认。c#99");
        List<Long> expanded = new ArrayList<>();
        var answer = service(ledger, ai, expanded).query(QUESTION, 8L);
        assertEquals(List.of(1L, 2L), expanded);
        assertFalse(answer.trace.stream().anyMatch(step -> "STRUCTURED_HOST_SUMMARY".equals(step.get("answerMode"))));
        assertTrue(answer.trace.stream().anyMatch(step ->
            "SOURCE_SCOPED_RECORD_ANSWER".equals(step.get("answerMode"))
                && "SOURCE_SCOPED".equals(step.get("answerCoverage"))
                && "PARTIAL".equals(step.get("graphCoverage"))), answer.trace.toString());
        Map<?, ?> scope = latestScope(answer);
        assertEquals(2, scope.get("requestedCount"));
        assertEquals(false, scope.get("queryComplete"));
        List<?> rows = (List<?>) scope.get("subjects");
        assertEquals(true, ((Map<?, ?>) rows.get(0)).get("queryComplete"));
        assertEquals(false, ((Map<?, ?>) rows.get(1)).get("queryComplete"));
    }

    @Test
    void sameNameAnchorsAreNotAutomaticallyMergedOrExpanded() {
        EvidenceLedger ledger = ledger(List.of(entity(1, PARENT, "COMPETITION"),
            entity(2, PARENT, "COMPETITION")), List.of());
        List<Long> expanded = new ArrayList<>();
        var answer = service(ledger, partialAnswerClient("本次原文说明赛事概况。c#99"), expanded)
            .query("图谱里，" + PARENT + "关联的技能有哪些？", 8L);
        assertTrue(expanded.isEmpty());
        assertEquals(false, latestScope(answer).get("queryComplete"));
        Map<?, ?> subject = (Map<?, ?>) ((List<?>) latestScope(answer).get("subjects")).get(0);
        assertEquals("AMBIGUOUS", subject.get("resolution"));
    }

    @Test
    void fuzzyParentCannotReturnCompleteStructuredChildRelations() {
        var parent = entity(1, PARENT, "COMPETITION");
        var skill = entity(3, "工程分析", "SKILL");
        EvidenceLedger ledger = ledger(List.of(parent, skill), List.of(relation(10, 1, 3, "REQUIRES")));
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(true);
        List<Long> expanded = new ArrayList<>();
        var answer = service(ledger, partialAnswerClient("本次原文说明赛事概况。c#99"), expanded)
            .query("图谱里，" + CHILD + "关联的技能有哪些？", 8L);
        assertFalse(answer.trace.stream().anyMatch(step -> "STRUCTURED_RELATION_SUMMARY".equals(step.get("answerMode"))));
        assertEquals(false, latestScope(answer).get("queryComplete"));
    }

    private static AiClient partialAnswerClient(String answer) {
        AiClient ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult(answer, 10, 20))
            .thenReturn(new AiClient.AiChatResult(
                "{\"facts\":\"SUPPORTED\",\"coverage\":\"INCOMPLETE\",\"unsupported\":[],\"missing\":[\"范围尚未完整\"]}", 10, 20));
        return ai;
    }

    private static AgenticGraphRagService service(EvidenceLedger ledger, AiClient ai, List<Long> expanded) {
        EvidenceLedgerFactory factory = mock(EvidenceLedgerFactory.class);
        when(factory.create()).thenReturn(ledger);
        GraphTool neighbors = new GraphTool() {
            public String getName() { return "expand_neighbors"; }
            public String getDescription() { return "isolated test tool"; }
            public String getParametersSchema() { return "{}"; }
            public Object execute(Map<String, Object> params, Long userId) {
                expanded.add(((Number) params.get("entity_id")).longValue());
                return Map.of("neighbors", List.of());
            }
        };
        return new AgenticGraphRagService(mock(GraphRagService.class), mock(KgAgentTraceMapper.class),
            ai, List.of(neighbors), factory);
    }

    private static EvidenceLedger hostingLedger() {
        EvidenceLedger ledger = ledger(List.of(entity(1, PARENT, "COMPETITION"),
            entity(2, CHILD, "COMPETITION"), entity(3, "研究协会", "ORGANIZATION"),
            entity(4, "创新协会", "ORGANIZATION")),
            List.of(relation(10, 1, 3, "HOSTED_BY"), relation(11, 1, 4, "HOSTED_BY")));
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(true);
        return ledger;
    }

    private static EvidenceLedger ledger(List<EvidenceLedger.EntityFact> entities,
                                          List<EvidenceLedger.RelationFact> relations) {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.entities()).thenReturn(entities);
        when(ledger.relations()).thenReturn(relations);
        for (var entity : entities) when(ledger.entity(entity.id())).thenReturn(Optional.of(entity));
        for (var relation : relations) when(ledger.relation(relation.id())).thenReturn(Optional.of(relation));
        var chunk = new EvidenceLedger.ChunkFact(99L, "POST", 6L, PARENT,
            "赛事概况：具体组织应分别核对。", 0, 18);
        when(ledger.chunks()).thenReturn(List.of(chunk));
        when(ledger.chunk(99L)).thenReturn(Optional.of(chunk));
        when(ledger.containsChunk(99L)).thenReturn(true);
        for (var entity : entities) when(ledger.containsEntity(entity.id())).thenReturn(true);
        return ledger;
    }

    private static Map<?, ?> latestScope(AgenticGraphRagService.AgenticAnswer answer) {
        return (Map<?, ?>) answer.trace.stream().filter(step -> step.containsKey("subjectScopes"))
            .reduce((first, last) -> last).orElseThrow().get("subjectScopes");
    }

    private static EvidenceLedger.EntityFact entity(long id, String name, String type) {
        return new EvidenceLedger.EntityFact(id, name, type, "");
    }

    private static EvidenceLedger.RelationFact relation(long id, long source, long target, String type) {
        return new EvidenceLedger.RelationFact(id, source, target, type, null, null, null, null, "");
    }
}
