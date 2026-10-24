package cn.ilink.teamagent;

import cn.ilink.config.AiProperties;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.kg.AgenticGraphRagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamAgentBusinessAnswerServiceTest {

    @Test
    void actionPlanRetriesOnceAfterInvalidModelJsonShape() {
        TeamAgentMetricsService metricsService = mock(TeamAgentMetricsService.class);
        TeamAgentActionProposalService proposalService = mock(TeamAgentActionProposalService.class);
        AiClient aiClient = mock(AiClient.class);
        TeamAgentScope scope = scope();
        TeamAgentMetricsService.Snapshot snapshot = snapshot(scope);
        when(metricsService.snapshot(scope)).thenReturn(snapshot);

        String invalid = """
            {"answer":"建议创建任务","actions":[
              {"actionType":"CREATE_TASK","after":"not-an-object","reason":"补齐测试"}
            ]}
            """;
        String valid = """
            {"answer":"建议创建测试报告任务","actions":[
              {"actionType":"CREATE_TASK","after":{
                "taskTitle":"完成测试报告","taskDescription":"整理验收结果",
                "taskType":"DOCUMENTATION","priority":2,"assignedTo":75,
                "requiresReview":true
              },"reason":"补齐测试材料","evidence":[]}
            ]}
            """;
        when(aiClient.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult(invalid, 10, 20),
                new AiClient.AiChatResult(valid, 10, 20));

        AiProperties aiProperties = new AiProperties();
        aiProperties.setApiKey("test-key");
        TeamAgentBusinessAnswerService service = new TeamAgentBusinessAnswerService(
            metricsService,
            mock(TeamScopedGraphRagAdapter.class),
            new TeamAgentActionSchema(),
            proposalService,
            mock(cn.ilink.collab.TeamCollaborationAgentTools.class),
            aiClient,
            aiProperties,
            new TeamAgentProperties(),
            new ObjectMapper());

        TeamAgentBusinessAnswerService.Answer answer = service.answer(
            TeamAgentIntentRouter.Route.ACTION_PLAN, "创建测试报告任务",
            scope, 1L, 2L,
            mock(AgenticGraphRagService.AgenticStreamListener.class));

        assertEquals(2, answer.modelCalls());
        assertEquals(1, answer.actionDrafts().size());
        assertEquals("CREATE_TASK", answer.actionDrafts().get(0).actionType());
        verify(aiClient, times(2)).chat(anyList(), anyInt());
    }

    private TeamAgentMetricsService.Snapshot snapshot(TeamAgentScope scope) {
        TeamDemand team = new TeamDemand();
        team.setId(93L);
        team.setTitle("测试团队");
        team.setStatus("TEAMING");
        team.setCreatorId(75L);
        User leader = new User();
        leader.setId(75L);
        leader.setRealName("测试队长");
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("generatedAt", "2026-10-23T02:00:00Z");
        return new TeamAgentMetricsService.Snapshot(
            team, scope, List.of(), List.of(), List.of(), 1L, 0L,
            counts,
            new TeamAgentMetricsService.KnowledgeFreshness("FRESH", 0L, 0L, null),
            Map.of(75L, leader), List.of(), 0L, Instant.now(), Instant.now().plusSeconds(3600));
    }

    private TeamAgentScope scope() {
        return new TeamAgentScope(
            75L, 93L, "测试团队", "TEAMING",
            TeamAgentScope.TeamRole.LEADER, TeamAgentScope.TaskReadMode.ALL,
            1, "测试竞赛", null, null, Instant.now());
    }
}
