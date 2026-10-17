package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemporalAnswerScopeTest {
    private static final String SHORT_TITLE = "前端视觉重构报告";
    private static final String CANONICAL_TITLE = "前端页面视觉重构升级报告";
    private static final String QUESTION = "2026 年 3 月，" + SHORT_TITLE + "与哪个竞赛有关联？";
    private static final KgTimeRange MARCH = new KgTimeRange(
        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));

    @Test
    void uniqueDocumentAbbreviationKeepsRequestedIdentityUnconfirmedAndWindowComplete() {
        EvidenceLedger ledger = documentLedger(CANONICAL_TITLE, true, true);
        TemporalAnswerScope.Analysis result = assess(QUESTION, ledger);
        assertFalse(result.requestedSubjectsConfirmed());
        assertFalse(result.requestedScopeComplete());
        assertTrue(result.resolvedWindowComplete());
        assertTrue(result.conditionalDocumentListAllowed());
        assertEquals(CANONICAL_TITLE, result.resolvedTitle());
        assertEquals(TemporalAnswerScope.Resolution.FUZZY_UNCONFIRMED, result.subjects().get(0).resolution());
        assertEquals(1L, result.subjects().get(0).entityId().longValue());
        assertEquals(false, result.metadata().get("requestedScopeComplete"));
        assertEquals(true, result.metadata().get("resolvedWindowComplete"));
    }

    @Test
    void exactDocumentSubjectWithCompleteWindowDoesNotRequireNeighborQuery() {
        EvidenceLedger ledger = documentLedger(CANONICAL_TITLE, true, true);
        TemporalAnswerScope.Analysis result = assess("2026年3月，" + CANONICAL_TITLE + "与哪个竞赛有关联？", ledger);
        assertTrue(result.requestedSubjectsConfirmed());
        assertTrue(result.resolvedWindowComplete());
        assertTrue(result.requestedScopeComplete());
        assertFalse(result.conditionalDocumentListAllowed());
        assertFalse(ledger.completeRelations("expand_neighbors", 1L));
    }

    @Test
    void exactSubjectWithTruncatedWindowRemainsPartial() {
        EvidenceLedger ledger = documentLedger(CANONICAL_TITLE, false, true);
        TemporalAnswerScope.Analysis result = assess("2026年3月，" + CANONICAL_TITLE + "与哪个竞赛有关联？", ledger);
        assertTrue(result.requestedSubjectsConfirmed());
        assertFalse(result.resolvedWindowComplete());
        assertFalse(result.requestedScopeComplete());
        assertFalse(result.conditionalDocumentListAllowed());
    }

    @Test
    void approximateDocumentWithTruncatedWindowCannotUseConditionalList() {
        TemporalAnswerScope.Analysis result = assess(QUESTION, documentLedger(CANONICAL_TITLE, false, true));
        assertFalse(result.resolvedWindowComplete());
        assertFalse(result.conditionalDocumentListAllowed());
    }

    @Test
    void twoApproximateDocumentsStayAmbiguousEvenIfOneWindowWasQueried() {
        EvidenceLedger ledger = ledger(List.of(post(1, CANONICAL_TITLE),
            post(3, "前端页面视觉重构改造报告"), competition(2, "示例竞赛")), List.of(relation()), List.of(chunk(CANONICAL_TITLE)));
        when(ledger.completeTemporal(1L, MARCH)).thenReturn(true);
        TemporalAnswerScope.Analysis result = assess(QUESTION, ledger);
        assertEquals(TemporalAnswerScope.Resolution.AMBIGUOUS, result.subjects().get(0).resolution());
        assertEquals(List.of(1L, 3L), result.subjects().get(0).matchingEntityIds());
        assertFalse(result.requestedSubjectsConfirmed());
        assertFalse(result.resolvedWindowComplete());
        assertFalse(result.conditionalDocumentListAllowed());
    }

    @Test
    void sameNameEntityIdsDoNotResolveByNameAlone() {
        EvidenceLedger ledger = ledger(List.of(post(1, CANONICAL_TITLE), post(3, CANONICAL_TITLE)), List.of(), List.of());
        when(ledger.completeTemporal(1L, MARCH)).thenReturn(true);
        TemporalAnswerScope.Analysis result = assess("2026年3月，" + CANONICAL_TITLE + "与哪个竞赛有关联？", ledger);
        assertEquals(TemporalAnswerScope.Resolution.AMBIGUOUS, result.subjects().get(0).resolution());
        assertFalse(result.requestedScopeComplete());
        assertFalse(result.conditionalDocumentListAllowed());
    }

    @Test
    void competitionParentNameNeverUsesChildCompetitionAsConditionalDocument() {
        EvidenceLedger ledger = ledger(List.of(competition(1, "高校计算机大赛总决赛"),
            competition(2, "示例竞赛")), List.of(relation()), List.of(chunk("高校计算机大赛总决赛")));
        when(ledger.completeTemporal(1L, MARCH)).thenReturn(true);
        TemporalAnswerScope.Analysis result = assess("2026年3月，高校计算机大赛与哪个竞赛有关联？", ledger);
        assertFalse(result.requestedSubjectsConfirmed());
        assertTrue(result.resolvedWindowComplete());
        assertFalse(result.conditionalDocumentListAllowed());
    }

    @Test
    void reverseLongRequestedNameCannotBeReplacedByShorterRetrievedParentTitle() {
        EvidenceLedger ledger = documentLedger(SHORT_TITLE, true, true);
        TemporalAnswerScope.Analysis result = assess("2026年3月，" + CANONICAL_TITLE + "与哪个竞赛有关联？", ledger);
        assertEquals(TemporalAnswerScope.Resolution.FUZZY_UNCONFIRMED, result.subjects().get(0).resolution());
        assertFalse(result.conditionalDocumentListAllowed());
        assertFalse(result.requestedScopeComplete());
    }

    @Test
    void oneUnresolvedSubjectPreventsCompleteMultipleSubjectScope() {
        EvidenceLedger ledger = ledger(List.of(post(1, "甲方报告"), competition(2, "示例竞赛")),
            List.of(relation()), List.of(chunk("甲方报告")));
        when(ledger.completeTemporal(1L, MARCH)).thenReturn(true);
        TemporalAnswerScope.Analysis result = assess("2026年3月，甲方报告与乙方报告分别与哪个竞赛有关联？", ledger);
        assertEquals(2, result.subjects().size());
        assertEquals(TemporalAnswerScope.Resolution.UNRESOLVED, result.subjects().get(1).resolution());
        assertFalse(result.requestedSubjectsConfirmed());
        assertFalse(result.resolvedWindowComplete());
        assertFalse(result.conditionalDocumentListAllowed());
    }

    @Test
    void approximateSubjectWithEmptyWindowNeverAuthorizesAbsence() {
        EvidenceLedger ledger = ledger(List.of(post(1, CANONICAL_TITLE)), List.of(), List.of(chunk(CANONICAL_TITLE)));
        when(ledger.completeTemporal(1L, MARCH)).thenReturn(true);
        TemporalAnswerScope.Analysis result = assess(QUESTION, ledger);
        assertTrue(result.resolvedWindowComplete());
        assertFalse(result.requestedScopeComplete());
        assertFalse(result.conditionalDocumentListAllowed());
    }

    @Test
    void approximateDocumentWithoutAuthorizedCanonicalTitleCannotUseConditionalList() {
        EvidenceLedger ledger = documentLedger(CANONICAL_TITLE, true, false);
        assertFalse(assess(QUESTION, ledger).conditionalDocumentListAllowed());
    }

    @Test
    void countExhaustiveBinaryAndNegativeQuestionsRequireConfirmedSubjects() {
        EvidenceLedger ledger = documentLedger(CANONICAL_TITLE, true, true);
        for (String requirement : List.of("总共关联多少个竞赛？", "所有竞赛关联是什么？", "完整竞赛集合是什么？",
            "是否与示例竞赛有关联？", "有没有竞赛关联？", "没有竞赛关联吗？", "有0条竞赛关联吗？",
            "与示例竞赛有关联？", "与哪些竞赛无关？", "关联竞赛数量是什么？")) {
            TemporalAnswerScope.Analysis result = assess("2026年3月，" + SHORT_TITLE + requirement, ledger);
            assertFalse(result.conditionalDocumentListAllowed(), requirement);
            assertFalse(result.requestedScopeComplete(), requirement);
        }
    }

    @Test
    void positiveRelationOutsideRequestedWindowCannotAuthorizeConditionalList() {
        EvidenceLedger.RelationFact outside = new EvidenceLedger.RelationFact(11L, 1L, 2L, "MENTIONS",
            date(LocalDate.of(2026, 4, 1)), null, null, null, "");
        EvidenceLedger ledger = ledger(List.of(post(1, CANONICAL_TITLE), competition(2, "示例竞赛")),
            List.of(outside), List.of(chunk(CANONICAL_TITLE)));
        when(ledger.completeTemporal(1L, MARCH)).thenReturn(true);
        assertFalse(assess(QUESTION, ledger).conditionalDocumentListAllowed());
    }

    @Test
    void globalWindowUsesItsOwnAuthorizedQueryCoverage() {
        EvidenceLedger ledger = ledger(List.of(), List.of(), List.of());
        when(ledger.completeTemporal(null, MARCH)).thenReturn(true);
        TemporalAnswerScope.Analysis result = assess("2026年3月有哪些竞赛关联记录？", ledger);
        assertTrue(result.globalTemporal());
        assertTrue(result.requestedSubjectsConfirmed());
        assertTrue(result.requestedScopeComplete());
        assertTrue(result.subjects().isEmpty());
        assertFalse(result.conditionalDocumentListAllowed());
    }

    @Test
    void globalMultipleWindowsRequireEveryAuthorizedWindowToBeComplete() {
        KgTimeRange april = new KgTimeRange(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1));
        QueryEvidencePlan plan = new QueryEvidencePlan(true, true, false, false, true,
            Optional.of(MARCH), false, List.of(MARCH, april));
        EvidenceLedger ledger = ledger(List.of(), List.of(), List.of());
        when(ledger.completeTemporal(null, MARCH)).thenReturn(true);
        TemporalAnswerScope.Analysis result = TemporalAnswerScope.assess("2026年3月和4月有哪些竞赛关联记录？", plan, ledger);
        assertFalse(result.resolvedWindowComplete());
        assertFalse(result.requestedScopeComplete());
    }

    @Test
    void unconfirmedDocumentTitleCannotAssertNoMatchingCompetitionsEvenWithEmptyCompleteWindow() {
        EvidenceLedger ledger = ledger(List.of(post(1, CANONICAL_TITLE)), List.of(), List.of(chunk(CANONICAL_TITLE)));
        when(ledger.completeTemporal(1L, MARCH)).thenReturn(true);
        for (String answer : List.of("在指定时间段内，没有符合所问条件的竞赛关联。", "未找到竞赛关联。",
            "没有关系。", "该文档的竞赛关联不存在。", "竞赛关联为零。", "0条竞赛关联。")) {
            List<String> failures = absenceViolations(QUESTION, ledger, answer);
            assertEquals(1, failures.size(), answer);
            assertTrue(failures.get(0).startsWith("TEMPORAL_ABSENCE_SCOPE_UNCONFIRMED:"));
        }
    }

    @Test
    void exactSubjectWithIncompleteWindowCannotAssertNoMatchingRelations() {
        EvidenceLedger ledger = documentLedger(CANONICAL_TITLE, false, true);
        assertEquals(1, absenceViolations("2026年3月，" + CANONICAL_TITLE + "与哪些竞赛有关联？", ledger,
            "在指定时间段内，没有符合所问条件且仍有效的竞赛关联。").size());
    }

    @Test
    void missingSecondSubjectCannotBeHiddenByFirstSubjectsCompleteTemporalQuery() {
        EvidenceLedger ledger = ledger(List.of(post(1, "甲方报告")), List.of(), List.of(chunk("甲方报告")));
        when(ledger.completeTemporal(1L, MARCH)).thenReturn(true);
        assertEquals(1, absenceViolations("2026年3月，甲方报告与乙方报告分别与哪些竞赛有关联？", ledger,
            "在指定时间段内，两份报告没有竞赛关联。").size());
    }

    @Test
    void epistemicLimitationsAndUnrelatedMissingDatesAreNotAbsenceClaims() {
        EvidenceLedger ledger = documentLedger(CANONICAL_TITLE, false, true);
        for (String answer : List.of("无法确认是否存在竞赛关联。", "不能证明没有关系。",
            "本次证据不足，无法断言竞赛关联不存在。", "没有证据证明没有竞赛关联。",
            "关系没有截止日期。", "没有截止日期的关系仍然存在。", "本次证据未展示竞赛关联。")) {
            assertTrue(absenceViolations(QUESTION, ledger, answer).isEmpty(), answer);
        }
    }

    @Test
    void quotedSourcesAndCodeAreNotAssistantAbsenceAssertions() {
        EvidenceLedger ledger = documentLedger(CANONICAL_TITLE, false, true);
        for (String answer : List.of("原文写道：“没有关系”。", "> 没有竞赛关联。",
            "```text\n竞赛关联不存在。\n```", "示例文字 `没有关系`，不能作为结论。")) {
            assertTrue(absenceViolations(QUESTION, ledger, answer).isEmpty(), answer);
        }
    }

    @Test
    void disclaimerDoesNotShieldSubsequentIndependentAbsenceAssertion() {
        EvidenceLedger ledger = documentLedger(CANONICAL_TITLE, false, true);
        for (String answer : List.of("无法确认是否存在关系，但没有竞赛关联。",
            "不能证明没有关系，竞赛关联不存在。", "原文写道：“没有关系”；没有符合条件的竞赛关联。")) {
            assertEquals(1, absenceViolations(QUESTION, ledger, answer).size(), answer);
        }
    }

    @Test
    void exactCompleteAndGlobalCompleteWindowsDoNotFailThisScopeGuard() {
        EvidenceLedger exact = documentLedger(CANONICAL_TITLE, true, true);
        assertTrue(absenceViolations("2026年3月，" + CANONICAL_TITLE + "与哪些竞赛有关联？", exact,
            "没有符合条件的竞赛关联。").isEmpty());
        EvidenceLedger global = ledger(List.of(), List.of(), List.of());
        when(global.completeTemporal(null, MARCH)).thenReturn(true);
        assertTrue(absenceViolations("2026年3月有哪些竞赛关联记录？", global,
            "没有符合条件的竞赛关联。").isEmpty());
        assertEquals(1, absenceViolations("2026年3月有哪些竞赛关联记录？", ledger(List.of(), List.of(), List.of()),
            "没有符合条件的竞赛关联。").size());
    }

    private static List<String> absenceViolations(String question, EvidenceLedger ledger, String answer) {
        return TemporalAnswerScope.absenceViolations(question, QueryEvidencePlan.fromQuestion(question), ledger, answer);
    }

    private static TemporalAnswerScope.Analysis assess(String question, EvidenceLedger ledger) {
        return TemporalAnswerScope.assess(question, QueryEvidencePlan.fromQuestion(question), ledger);
    }

    private static EvidenceLedger documentLedger(String title, boolean completeWindow, boolean includeChunk) {
        EvidenceLedger ledger = ledger(List.of(post(1, title), competition(2, "示例竞赛")), List.of(relation()),
            includeChunk ? List.of(chunk(title)) : List.of());
        when(ledger.completeTemporal(1L, MARCH)).thenReturn(completeWindow);
        return ledger;
    }

    private static EvidenceLedger ledger(List<EvidenceLedger.EntityFact> entities,
                                         List<EvidenceLedger.RelationFact> relations,
                                         List<EvidenceLedger.ChunkFact> chunks) {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.entities()).thenReturn(entities);
        when(ledger.relations()).thenReturn(relations);
        when(ledger.chunks()).thenReturn(chunks);
        for (EvidenceLedger.EntityFact entity : entities)
            when(ledger.entity(entity.id())).thenReturn(Optional.of(entity));
        return ledger;
    }

    private static EvidenceLedger.EntityFact post(long id, String name) {
        return new EvidenceLedger.EntityFact(id, name, "POST", "");
    }

    private static EvidenceLedger.EntityFact competition(long id, String name) {
        return new EvidenceLedger.EntityFact(id, name, "COMPETITION", "");
    }

    private static EvidenceLedger.ChunkFact chunk(String title) {
        return new EvidenceLedger.ChunkFact(20L, "ASSET", 3L, title, title + "原文。", 0, 100);
    }

    private static EvidenceLedger.RelationFact relation() {
        return new EvidenceLedger.RelationFact(10L, 1L, 2L, "MENTIONS",
            date(LocalDate.of(2026, 3, 5)), date(LocalDate.of(2026, 4, 1)), null, 20L, "");
    }

    private static Date date(LocalDate value) {
        return Date.from(value.atStartOfDay(KgTimeRange.ZONE).toInstant());
    }
}
