package cn.ilink.teamagent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamAgentIntentRouterTest {
    private final TeamAgentIntentRouter router = new TeamAgentIntentRouter();
    private final TeamAgentScope studentScope = new TeamAgentScope(
        7L, 20L, "系统队", "TEAMING", TeamAgentScope.TeamRole.STUDENT,
        TeamAgentScope.TaskReadMode.RELATION_ONLY, 3, "嵌入式竞赛",
        null, Date.from(Instant.parse("2026-12-31T00:00:00Z")), Instant.now());

    @Test
    void actionIntentOutranksRiskAndMetricsKeywords() {
        assertEquals(TeamAgentIntentRouter.Route.ACTION_PLAN,
            router.route("请安排任务并检查风险和完成率", "AUTO", studentScope));
    }

    @Test
    void actionPlanningOutranksAssigneeMetricsKeyword() {
        assertEquals(TeamAgentIntentRouter.Route.ACTION_PLAN,
            router.route("请为团队规划两个后续任务并明确负责人", "ACTION_PLAN", studentScope));
    }

    @Test
    void weeklyReportOutranksActionKeywords() {
        assertEquals(TeamAgentIntentRouter.Route.WEEKLY_DRAFT,
            router.route("生成周报并创建任务", "AUTO", studentScope));
    }

    @Test
    void explicitModeIsAFallbackNotAKeywordOverride() {
        assertEquals(TeamAgentIntentRouter.Route.TEAM_METRICS,
            router.route("现在有多少个任务", "KNOWLEDGE_QUICK", studentScope));
        assertEquals(TeamAgentIntentRouter.Route.KNOWLEDGE_QUICK,
            router.route("解释一下竞赛规则", "KNOWLEDGE_QUICK", studentScope));
    }

    @Test
    void knowledgeRequirementOnlyCoversKnowledgeRoutes() {
        assertTrue(router.requiresKnowledge(TeamAgentIntentRouter.Route.KNOWLEDGE_DEEP));
        assertFalse(router.requiresKnowledge(TeamAgentIntentRouter.Route.TEAM_METRICS));
        assertFalse(router.requiresKnowledge(TeamAgentIntentRouter.Route.ACTION_PLAN));
        assertFalse(router.requiresKnowledge(TeamAgentIntentRouter.Route.COLLAB_DOCUMENT));
        assertFalse(router.requiresKnowledge(TeamAgentIntentRouter.Route.MATERIAL_REVIEW));
    }

    @Test
    void collaborationIntentsAreRecognised() {
        assertEquals(TeamAgentIntentRouter.Route.COLLAB_DOCUMENT,
            router.route("帮我起草一份参赛方案文档", "AUTO", studentScope));
        assertEquals(TeamAgentIntentRouter.Route.COLLAB_TABLE,
            router.route("做一张备赛甘特表格", "AUTO", studentScope));
        assertEquals(TeamAgentIntentRouter.Route.MATERIAL_REVIEW,
            router.route("报名材料还缺什么", "AUTO", studentScope));
    }

    @Test
    void explicitTaskWordingOutranksCollaborationKeywords() {
        assertEquals(TeamAgentIntentRouter.Route.ACTION_PLAN,
            router.route("安排任务，把材料整理和文档写作分给成员", "AUTO", studentScope));
    }

    @Test
    void collaborationModeIsAFallback() {
        assertEquals(TeamAgentIntentRouter.Route.COLLAB_DOCUMENT,
            router.route("继续下一步", "COLLAB_DOCUMENT", studentScope));
        assertEquals(TeamAgentIntentRouter.Route.MATERIAL_REVIEW,
            router.route("继续下一步", "MATERIAL_REVIEW", studentScope));
    }

    @Test
    void collaborationRoutesNeedActionProposalsOnlyWhenTheyWrite() {
        assertTrue(router.requiresActionProposal(TeamAgentIntentRouter.Route.COLLAB_DOCUMENT));
        assertTrue(router.requiresActionProposal(TeamAgentIntentRouter.Route.COLLAB_TABLE));
        assertFalse(router.requiresActionProposal(TeamAgentIntentRouter.Route.MATERIAL_REVIEW));
        assertTrue(router.usesCollaborationAssets(TeamAgentIntentRouter.Route.MATERIAL_REVIEW));
    }
}
