package cn.ilink.controller;

import cn.ilink.config.AiProperties;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.service.TeamTaskService;
import cn.ilink.service.UserService;
import cn.ilink.service.ai.AiAssistantService;
import cn.ilink.service.ai.AiUsageService;
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
import static org.mockito.ArgumentMatchers.isNull;
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
    private AiUsageService aiUsageService;

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
    void breakdownHasNoDailyCap() throws Exception {
        // 已取消每日调用上限：无论当天用过多少次都应正常放行（用量只记录、不拦截）
        given(aiAssistantService.breakdownTask(any(TeamTask.class), any()))
            .willReturn(Arrays.asList(new LinkedHashMap<String, Object>() {{ put("title", "子任务一"); }}));

        mockMvc.perform(post("/api/team/3/ai/task-breakdown")
                .session(session)
                .contentType("application/json")
                .content("{\"taskId\":11}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(aiAssistantService).breakdownTask(any(TeamTask.class), any());
        verify(aiUsageService).record(eq(9L), eq(3L), eq("TASK_BREAKDOWN"), any(), any(), eq(true));
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

        verify(aiUsageService).record(eq(9L), eq(3L), eq("TASK_BREAKDOWN"), any(), any(), eq(true));
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

    @Test
    void competitionQaReturnsReasoningSourcesAndElapsed() throws Exception {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("title", "官网公告");
        source.put("url", "https://example.com/a");
        given(aiAssistantService.answerCompetitionQuestion(any(), any())).willReturn(
            new AiAssistantService.QaAnswer("答案正文", "我先查了官网。", Arrays.asList(source), 2500L));

        mockMvc.perform(post("/api/ai/competition-qa")
                .session(session)
                .contentType("application/json")
                .content("{\"question\":\"怎么报名？\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.answer").value("答案正文"))
            // 前端「已思考」折叠区靠这三个字段渲染
            .andExpect(jsonPath("$.data.reasoning").value("我先查了官网。"))
            .andExpect(jsonPath("$.data.sources[0].title").value("官网公告"))
            .andExpect(jsonPath("$.data.sources[0].url").value("https://example.com/a"))
            .andExpect(jsonPath("$.data.elapsedMs").value(2500));
    }

    @Test
    void competitionQaAnswersGeneralQuestionWithoutCompetition() throws Exception {
        given(aiAssistantService.answerCompetitionQuestion(any(), any())).willReturn(new AiAssistantService.QaAnswer("通用回答", null, java.util.Collections.emptyList(), 1200L));

        mockMvc.perform(post("/api/ai/competition-qa")
                .session(session)
                .contentType("application/json")
                .content("{\"question\":\"怎么准备数学建模？\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.answer").value("通用回答"))
            .andExpect(jsonPath("$.data.competitionName").doesNotExist());

        // 不传竞赛时应以 null 传入，走「联网搜索 + 模型知识」的通用提问
        verify(aiAssistantService).answerCompetitionQuestion(eq("怎么准备数学建模？"), isNull());
    }

    @Test
    void competitionQaTreatsBlankCompetitionIdAsGeneralQuestion() throws Exception {
        given(aiAssistantService.answerCompetitionQuestion(any(), any())).willReturn(new AiAssistantService.QaAnswer("通用回答", null, java.util.Collections.emptyList(), 1200L));

        // 前端下拉框留空时可能传空串，同样应按通用提问处理而不是报错
        mockMvc.perform(post("/api/ai/competition-qa")
                .session(session)
                .contentType("application/json")
                .content("{\"question\":\"问题\",\"competitionId\":\"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(aiAssistantService).answerCompetitionQuestion(eq("问题"), isNull());
    }

    @Test
    void competitionQaRejectsNonNumericCompetitionId() throws Exception {
        mockMvc.perform(post("/api/ai/competition-qa")
                .session(session)
                .contentType("application/json")
                .content("{\"question\":\"问题\",\"competitionId\":\"abc\"}"))
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
