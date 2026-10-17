package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryEvidencePlanTest {
    @Test
    void attributeQuestionsRequireSourceEvidenceWithoutGraphEdges() {
        for (String question : java.util.List.of("看板列宽是多少像素？", "某竞赛是什么级别？",
            "产品的上手成本是什么？", "系统的固定流程有哪三步？", "侧边栏成员卡片展示哪些内容？")) {
            QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion(question);
            assertTrue(plan.requiresSourceText(), question);
            assertFalse(plan.requiresRelation(), question);
        }
    }

    @Test
    void sourceAndRelationQuestionRequiresBothKindsOfEvidence() {
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion("哪些帖子提到 LitePT？");
        assertTrue(plan.requiresRelation());
        assertTrue(plan.requiresSourceText());
        assertFalse(plan.requiresTemporal());
    }

    @Test
    void datedQuestionRequiresTemporalRelationEvidence() {
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion("2026 年 3 月关联哪些竞赛？");
        assertTrue(plan.requiresRelation());
        assertTrue(plan.requiresTemporal());
        assertEquals(LocalDate.parse("2026-03-01"), plan.timeRange().orElseThrow().startInclusive());
        assertEquals(LocalDate.parse("2026-04-01"), plan.timeRange().orElseThrow().endExclusive());
    }

    @Test
    void broadDatedQuestionRequestsTheWholeVisibleGraphWindow() {
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion(
            "2026 年 3 月期间，有哪些平台记录与竞赛产生了关联？");
        assertTrue(plan.globalTemporal());
        assertTrue(plan.requiresTemporal());

        QueryEvidencePlan specific = QueryEvidencePlan.fromQuestion(
            "2026 年 3 月，团队空间关联了哪个竞赛？");
        assertFalse(specific.globalTemporal());
    }

    @Test
    void detailQuestionRequiresOriginalText() {
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion("上传 bug 根因是什么？");
        assertTrue(plan.requiresSourceText());
        assertFalse(plan.requiresTemporal());
    }

    @Test
    void incidentalMentionIsASourceQuestionRatherThanGraphQuestion() {
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion(
            "平台技术分享里提到的 LitePT 是什么方向的论文，提出了哪些设计？");
        assertTrue(plan.requiresSourceText());
        assertFalse(plan.requiresRelation());
    }

    @Test
    void interfaceNavigationQuestionRequiresOriginalText() {
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion(
            "团队空间的左侧边栏包含哪些功能导航？");
        assertTrue(plan.requiresSourceText());
        assertFalse(plan.requiresRelation());
    }

    @Test
    void genericAdviceNeedsNoPlatformEvidence() {
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion("如何备赛？");
        assertFalse(plan.requiresRelation());
        assertFalse(plan.requiresSourceText());
        assertFalse(plan.requiresTemporal());
    }

    @Test
    void unresolvedExplicitDateCannotBeInvented() {
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion("2026 年 13 月关联了什么？");
        assertTrue(plan.requiresTemporal());
        assertTrue(plan.temporalUnresolved());
        assertTrue(plan.timeRange().isEmpty());
    }

    @Test
    void relationStatusQuestionRequiresHistoricalEdgesWithoutInventingDate() {
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion(
            "截至评测日，Agent 记忆机制与两个竞赛的关联关系分别仍有效还是已结束？");
        assertTrue(plan.requiresRelation());
        assertTrue(plan.requiresTemporal());
        assertTrue(plan.historicalStatus());
        assertFalse(plan.temporalUnresolved());
        assertTrue(plan.timeRanges().isEmpty());

        QueryEvidencePlan contestStatus = QueryEvidencePlan.fromQuestion("哪些竞赛已经结束了？");
        assertFalse(contestStatus.historicalStatus());
    }

    @Test
    void twoMonthBroadQuestionRetainsBothWindows() {
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion(
            "2026 年 2 月和 4 月都有关联竞赛的记录有哪些？");
        assertTrue(plan.requiresRelation());
        assertTrue(plan.requiresTemporal());
        assertTrue(plan.globalTemporal());
        assertFalse(plan.historicalStatus());
        assertEquals(2, plan.timeRanges().size());
        assertEquals(LocalDate.parse("2026-02-01"), plan.timeRanges().get(0).startInclusive());
        assertEquals(LocalDate.parse("2026-04-01"), plan.timeRanges().get(1).startInclusive());
    }
}
