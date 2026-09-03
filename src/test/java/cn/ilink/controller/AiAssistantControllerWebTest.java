package cn.ilink.controller;

import cn.ilink.config.AiProperties;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.service.TeamTaskService;
import cn.ilink.service.UserService;
import cn.ilink.service.ai.AiAssistantService;
import cn.ilink.service.ai.AiQuotaService;
import cn.ilink.service.impl.CompetitionServiceImpl;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AiAssistantController.class)
@AutoConfigureMockMvc(addFilters = false)
class AiAssistantControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiAssistantService aiAssistantService;

    @MockBean
    private AiQuotaService aiQuotaService;

    @MockBean
    private AiProperties aiProperties;

    @MockBean
    private TeamDemandServiceImpl teamDemandService;

    @MockBean
    private TeamApplicationServiceImpl teamApplicationService;

    @MockBean
    private TeamTaskService teamTaskService;

    @MockBean
    private CompetitionServiceImpl competitionService;

    @MockBean
    private UserService userService;

    private User leader;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        leader = new User();
        leader.setId(9L);
        leader.setUsername("leader");
        session = sessionFor(leader);

        TeamDemand team = new TeamDemand();
        team.setId(3L);
        team.setTitle("测试团队");
        team.setCreatorId(9L);
        given(teamDemandService.getById(3L)).willReturn(team);

        TeamTask task = new TeamTask();
        task.setId(11L);
        task.setTeamId(3L);
        task.setTaskTitle("完成任务书");
        given(teamTaskService.getById(11L)).willReturn(task);

        given(aiProperties.isEnabled()).willReturn(true);
        given(aiAssistantService.isConfigured()).willReturn(true);
        given(aiQuotaService.isOverQuota(9L)).willReturn(false);
        given(aiQuotaService.dailyQuota()).willReturn(20);
        given(aiQuotaService.usedToday(9L)).willReturn(0L);
    }

    @Test
    void breakdownRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/team/3/ai/task-breakdown")
                .contentType("application/json")
                .content("{\"taskId\":11}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void breakdownRejectsNonParticipant() throws Exception {
        User outsider = new User();
        outsider.setId(99L);
        outsider.setUsername("outsider");
        given(teamApplicationService.count(any(Wrapper.class))).willReturn(0L);

        mockMvc.perform(post("/api/team/3/ai/task-breakdown")
                .session(sessionFor(outsider))
                .contentType("application/json")
                .content("{\"taskId\":11}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        verify(aiAssistantService, never()).breakdownTask(any(), any());
    }

    @Test
    void breakdownRejectsOverQuotaWithoutExternalCall() throws Exception {
        given(aiQuotaService.isOverQuota(9L)).willReturn(true);

        mockMvc.perform(post("/api/team/3/ai/task-breakdown")
                .session(session)
                .contentType("application/json")
                .content("{\"taskId\":11}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(429));

        // 关键约束：超配额时绝不发起外部 AI 调用
        verify(aiAssistantService, never()).breakdownTask(any(), any());
    }

    @Test
    void breakdownRejectsUnconfiguredService() throws Exception {
        given(aiAssistantService.isConfigured()).willReturn(false);

        mockMvc.perform(post("/api/team/3/ai/task-breakdown")
                .session(session)
                .contentType("application/json")
                .content("{\"taskId\":11}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(503));

        verify(aiAssistantService, never()).breakdownTask(any(), any());
    }

    @Test
    void breakdownHappyPathRecordsQuota() throws Exception {
        Map<String, Object> subtask = new LinkedHashMap<>();
        subtask.put("title", "子任务一");
        given(aiAssistantService.breakdownTask(any(TeamTask.class), any()))
            .willReturn(Arrays.asList(subtask));

        mockMvc.perform(post("/api/team/3/ai/task-breakdown")
                .session(session)
                .contentType("application/json")
                .content("{\"taskId\":11}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].title").value("子任务一"));

        verify(aiQuotaService).record(eq(9L), eq(3L), eq("TASK_BREAKDOWN"), any(), any(), eq(true));
    }

    @Test
    void breakdownRejectsTaskOfOtherTeam() throws Exception {
        TeamTask foreign = new TeamTask();
        foreign.setId(12L);
        foreign.setTeamId(999L);
        given(teamTaskService.getById(12L)).willReturn(foreign);

        mockMvc.perform(post("/api/team/3/ai/task-breakdown")
                .session(session)
                .contentType("application/json")
                .content("{\"taskId\":12}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404));

        verify(aiAssistantService, never()).breakdownTask(any(), any());
    }

    @Test
    void weeklyReportIsLocalAndNeverCallsAi() throws Exception {
        given(teamTaskService.list(any(Wrapper.class))).willReturn(Arrays.asList());

        mockMvc.perform(get("/api/team/3/weekly-report").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.counts.total").value(0));

        // 关键约束：周报为纯本地聚合，不触发任何 AI 外呼
        verify(aiAssistantService, never()).breakdownTask(any(), any());
        verify(aiAssistantService, never()).answerCompetitionQuestion(any(), any());
    }

    @Test
    void competitionQaRejectsEmptyQuestion() throws Exception {
        mockMvc.perform(post("/api/ai/competition-qa")
                .session(session)
                .contentType("application/json")
                .content("{\"question\":\"\",\"competitionId\":1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        verify(aiAssistantService, never()).answerCompetitionQuestion(any(), any());
    }

    @Test
    void competitionQaRejectsMissingCompetition() throws Exception {
        given(competitionService.getById(anyLong())).willReturn(null);

        mockMvc.perform(post("/api/ai/competition-qa")
                .session(session)
                .contentType("application/json")
                .content("{\"question\":\"这个比赛怎么报名？\",\"competitionId\":1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        verify(aiAssistantService, never()).answerCompetitionQuestion(any(), any());
    }

    private MockHttpSession sessionFor(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", user);
        return session;
    }
}
