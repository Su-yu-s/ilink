package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubjectScopeLedgerTest {
    private static final String PARENT = "高校计算机大赛";
    private static final String CHILD = "高校计算机大赛 AIGC 创新赛";
    private static final String QUESTION = "知识图谱里，" + PARENT + "与" + CHILD + "分别由什么组织主办？";

    @Test
    void unquotedComparisonRetainsEveryRequestedSubjectAndDoesNotReuseParentAsChild() {
        QueryIntent intent = QueryIntent.fromQuestion(QUESTION);
        assertEquals(List.of(PARENT, CHILD), intent.subjects());
        assertTrue(QueryIntent.subjectRequests(QUESTION).complete());
        assertFalse(intent.focusText().contains("AIGC"));

        EvidenceLedger ledger = ledger(List.of(entity(1, PARENT)), List.of());
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(true);
        SubjectScopeLedger.Analysis scope = SubjectScopeLedger.assess(QUESTION, ledger);
        assertTrue(scope.subjects().get(0).queryComplete());
        assertFalse(scope.subjects().get(1).exact());
        assertFalse(scope.subjects().get(1).queryComplete());
        assertFalse(scope.queryComplete());
    }

    @Test
    void mixedQuotedAndUnquotedSubjectsAreKeptAndConjunctionInsideTitleIsNotSplit() {
        assertEquals(List.of(PARENT, CHILD), QueryIntent.fromQuestion(
            "图谱中，“" + PARENT + "”与" + CHILD + "分别由什么组织主办？").subjects());
        assertEquals(List.of("研发与创新平台", "协作平台"), QueryIntent.fromQuestion(
            "图谱中，《研发与创新平台》与《协作平台》分别有哪些关系？").subjects());
    }

    @Test
    void uniqueFuzzyNameCannotGrantCompleteScope() {
        EvidenceLedger ledger = ledger(List.of(entity(1, PARENT + "总决赛")), List.of());
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(true);
        SubjectScopeLedger.Analysis scope = SubjectScopeLedger.assess("图谱中，" + PARENT + "有哪些关系？", ledger);
        assertEquals(SubjectScopeLedger.Resolution.FUZZY_UNCONFIRMED, scope.subjects().get(0).resolution());
        assertFalse(scope.queryComplete());
    }

    @Test
    void interrogativeRelationTargetIsNotAddedAsAnotherRequestedSubject() {
        QueryIntent intent = QueryIntent.fromQuestion("2026 年 3 月，前端视觉重构报告与哪个竞赛有关联？");
        assertEquals(List.of("前端视觉重构报告"), intent.subjects());
        assertTrue(QueryIntent.subjectRequests("前端视觉重构报告与哪些赛事有关联？").complete());
    }

    @Test
    void focusKeepsRequestedAttributeAfterRemovingSubjectAndLocationWords() {
        String question = "《分析报告》在图谱里关联的技术栈中，哪些不属于某语言生态？";
        EvidenceLedger ledger = ledger(List.of(
            new EvidenceLedger.EntityFact(1L, "分析报告", "POST", ""),
            new EvidenceLedger.EntityFact(2L, "图表组件", "SKILL", ""),
            new EvidenceLedger.EntityFact(3L, "研究协会", "ORGANIZATION", "")),
            List.of(relation(10, 1, 2, "MENTIONS"), relation(11, 1, 3, "RELATED_TO")));
        assertTrue(QueryIntent.fromQuestion(question).focusText().contains("技术栈"));
        GraphAnswerScope.Scope scope = GraphAnswerScope.describe(question, ledger);
        assertEquals(1, scope.candidateCount());
        assertEquals(java.util.Set.of(10L), scope.relationIds());
    }

    @Test
    void graphScopeUsesBothAnchorViewsAndDoesNotInferTargetTypeFromSubjectNames() {
        EvidenceLedger ledger = ledger(List.of(entity(1, PARENT), entity(2, CHILD)),
            List.of(relation(10, 1, 2, "PART_OF")));
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(true);
        when(ledger.completeRelations("expand_neighbors", 2L)).thenReturn(true);
        GraphAnswerScope.Scope scope = GraphAnswerScope.describe(
            "知识图谱里，" + PARENT + "与" + CHILD + "有哪些关系？", ledger);
        assertEquals(2, scope.candidateCount());
        assertEquals(2, scope.shownCount());
        assertEquals(java.util.Set.of(10L), scope.relationIds());
        assertTrue(scope.text().contains("主体#1 OUT"));
        assertTrue(scope.text().contains("主体#2 IN"));

        EvidenceLedger.EntityFact competition = entity(3, "创新竞赛平台");
        EvidenceLedger.EntityFact organization = new EvidenceLedger.EntityFact(4L, "研究协会", "ORGANIZATION", "");
        EvidenceLedger hosting = ledger(List.of(competition, organization), List.of(relation(11, 3, 4, "HOSTED_BY")));
        when(hosting.completeRelations("expand_neighbors", 3L)).thenReturn(true);
        assertEquals(1, GraphAnswerScope.describe("图谱中，创新竞赛平台由什么组织主办？", hosting).candidateCount());
    }

    @Test
    void separatelyResolvedChildStillRequiresItsOwnCompleteQuery() {
        EvidenceLedger ledger = ledger(List.of(entity(1, PARENT), entity(2, CHILD)), List.of());
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(true);
        SubjectScopeLedger.Analysis scope = SubjectScopeLedger.assess(QUESTION, ledger);
        assertEquals(2L, scope.subjects().get(1).entityId());
        assertFalse(scope.queryComplete());
        assertEquals(1, scope.absenceViolations(CHILD + "：当前授权范围无图谱关系边。").size());
    }

    @Test
    void sameNameEntitiesRemainAmbiguousEvenIfOneOrBothWereFullyQueried() {
        EvidenceLedger ledger = ledger(List.of(entity(1, PARENT), entity(2, PARENT)), List.of());
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(true);
        when(ledger.completeRelations("expand_neighbors", 2L)).thenReturn(true);
        SubjectScopeLedger.Analysis scope = SubjectScopeLedger.assess("图谱中，" + PARENT + "有哪些关系？", ledger);
        assertEquals(SubjectScopeLedger.Resolution.AMBIGUOUS, scope.subjects().get(0).resolution());
        assertEquals(List.of(1L, 2L), scope.subjects().get(0).matchingEntityIds());
        assertEquals(null, scope.subjects().get(0).entityId());
        assertFalse(scope.queryComplete());
    }

    @Test
    void sharedEdgeIsRetainedSeparatelyForBothSubjectScopes() {
        EvidenceLedger.RelationFact edge = relation(10, 1, 2, "PART_OF");
        EvidenceLedger ledger = ledger(List.of(entity(1, PARENT), entity(2, CHILD)), List.of(edge));
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(true);
        when(ledger.completeRelations("expand_neighbors", 2L)).thenReturn(true);
        SubjectScopeLedger.Analysis scope = SubjectScopeLedger.assess(QUESTION, ledger);
        assertTrue(scope.queryComplete());
        assertEquals(List.of(10L), scope.subjects().get(0).relationIds());
        assertEquals(List.of(10L), scope.subjects().get(1).relationIds());
    }

    @Test
    void completeAuthorizedEmptyQueryAllowsOnlyExplicitlyScopedAbsence() {
        EvidenceLedger ledger = ledger(List.of(entity(1, PARENT)), List.of());
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(true);
        SubjectScopeLedger.Analysis scope = SubjectScopeLedger.assess("图谱中，" + PARENT + "有哪些关系？", ledger);
        assertTrue(scope.absenceViolations(PARENT + "：当前授权范围未找到图谱关系边。").isEmpty());
        assertEquals(1, scope.absenceViolations(PARENT + "：图谱未收录该边。").size());
        assertEquals(1, scope.absenceViolations(PARENT + "：无图谱关系边。").size());
        assertEquals(1, scope.absenceViolations(PARENT + "：当前授权范围证明整个图谱无关系边。").size());
    }

    @Test
    void acronymInsideSubjectNameIsNotTreatedAsAnAbsenceRelationTypeFilter() {
        EvidenceLedger ledger = ledger(List.of(entity(2, CHILD)), List.of(relation(10, 2, 3, "HOSTED_BY")));
        when(ledger.completeRelations("expand_neighbors", 2L)).thenReturn(true);
        SubjectScopeLedger.Analysis scope = SubjectScopeLedger.assess("图谱中，" + CHILD + "有哪些关系？", ledger);
        assertEquals(1, scope.absenceViolations(CHILD + "：当前授权范围无图谱关系边。").size());
    }

    @Test
    void existingRequestedRelationContradictsEvenAnAuthorizedScopedAbsence() {
        EvidenceLedger ledger = ledger(List.of(entity(1, PARENT)), List.of(relation(10, 1, 3, "HOSTED_BY")));
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(true);
        SubjectScopeLedger.Analysis scope = SubjectScopeLedger.assess("图谱中，" + PARENT + "有哪些主办关系？", ledger);
        assertEquals(1, scope.absenceViolations(PARENT + "：当前授权范围未找到主办关系。").size());
        assertTrue(scope.absenceViolations(PARENT + "：当前授权范围未找到 MENTIONS 关系。").isEmpty());
    }

    @Test
    void quotationsAndInstructionsAreNotAssistantAbsenceAssertions() {
        SubjectScopeLedger.Analysis scope = SubjectScopeLedger.assess(QUESTION, ledger(List.of(), List.of()));
        assertTrue(scope.absenceViolations("> 原文写图谱未收录该边。\n"
            + "原文使用了“图谱未收录该边”的表述。\n"
            + "不能据此断言图谱未收录该边。\n"
            + "```\n图谱未收录该边\n```\n"
            + "本次证据未直接展示该边。").isEmpty());
        assertEquals(1, scope.absenceViolations("不能确认主办者，但图谱未收录该边。").size());
        assertEquals(1, scope.absenceViolations("不能据此断言名称相同，不过图谱未收录该边。").size());
    }

    @Test
    void honestUncertaintyDoesNotAssertAbsenceButAffirmationAndContrastStillDo() {
        SubjectScopeLedger.Analysis scope = SubjectScopeLedger.assess(QUESTION, ledger(List.of(), List.of()));
        assertTrue(scope.absenceViolations("不能认定图谱没有该关系。").isEmpty());
        assertTrue(scope.absenceViolations("无法证明图谱未收录该边。").isEmpty());
        assertTrue(scope.absenceViolations("无法确定图谱没有该关系。").isEmpty());
        assertTrue(scope.absenceViolations("无法判断图谱不存在该关系。").isEmpty());
        assertEquals(1, scope.absenceViolations("已认定图谱没有该关系。").size());
        assertEquals(1, scope.absenceViolations("无法证明主体同名，但图谱没有该关系。").size());
        assertEquals(1, scope.absenceViolations("不能认定主体同名，图谱未收录该边。").size());
    }

    @Test
    void protectedEarlierAbsenceCannotHideLaterAssertionOrShareItsAuthorizationScope() {
        SubjectScopeLedger.Analysis unresolved = SubjectScopeLedger.assess(QUESTION, ledger(List.of(), List.of()));
        assertEquals(1, unresolved.absenceViolations("无法证明图谱没有边，但图谱未收录该边。").size());

        EvidenceLedger ledger = ledger(List.of(entity(1, PARENT)), List.of());
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(true);
        SubjectScopeLedger.Analysis complete = SubjectScopeLedger.assess("图谱中，" + PARENT + "有哪些关系？", ledger);
        assertEquals(1, complete.absenceViolations("当前授权范围未找到关系，但图谱未收录该边。").size());
    }

    @Test
    void anExplicitParentMentionIsNotDiscardedWhenChildNameAlsoContainsIt() {
        EvidenceLedger ledger = ledger(List.of(entity(1, PARENT), entity(2, CHILD)), List.of());
        when(ledger.completeRelations("expand_neighbors", 2L)).thenReturn(true);
        SubjectScopeLedger.Analysis scope = SubjectScopeLedger.assess(QUESTION, ledger);
        assertEquals(1, scope.absenceViolations(PARENT + "与" + CHILD + "在当前授权范围无图谱关系边。").size());
        assertTrue(scope.absenceViolations(CHILD + "在当前授权范围无图谱关系边。").isEmpty());
    }

    @Test
    void unscopedAbsenceRequiresEveryRequestedSubjectAndSubjectLimitNeverClaimsCompleteness() {
        EvidenceLedger ledger = ledger(List.of(entity(1, PARENT), entity(2, CHILD)), List.of());
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(true);
        SubjectScopeLedger.Analysis scope = SubjectScopeLedger.assess(QUESTION, ledger);
        assertEquals(1, scope.absenceViolations("当前授权范围没有图谱关系边。").size());
        QueryIntent.SubjectRequests request = QueryIntent.subjectRequests("甲平台与乙平台与丙平台与丁平台与戊平台分别有哪些图谱关系？");
        assertEquals(4, request.subjects().size());
        assertFalse(request.complete());
        assertEquals("SUBJECT_LIMIT", request.reason());
    }

    private static EvidenceLedger ledger(List<EvidenceLedger.EntityFact> entities,
                                         List<EvidenceLedger.RelationFact> relations) {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.entities()).thenReturn(entities);
        when(ledger.relations()).thenReturn(relations);
        for (EvidenceLedger.EntityFact entity : entities)
            when(ledger.entity(entity.id())).thenReturn(Optional.of(entity));
        return ledger;
    }

    private static EvidenceLedger.EntityFact entity(long id, String name) {
        return new EvidenceLedger.EntityFact(id, name, "COMPETITION", "");
    }

    private static EvidenceLedger.RelationFact relation(long id, long source, long target, String type) {
        return new EvidenceLedger.RelationFact(id, source, target, type, null, null, null, null, "");
    }
}
