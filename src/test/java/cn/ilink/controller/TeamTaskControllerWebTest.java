package cn.ilink.controller;

import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.TaskParticipantMapper;
import cn.ilink.mapper.TaskSubmissionMapper;
import cn.ilink.service.NotificationService;
import cn.ilink.service.TeamTaskService;
import cn.ilink.service.UserService;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import cn.ilink.service.kg.KgIndexService;
import cn.ilink.teamagent.TeamAgentErrorCode;
import cn.ilink.teamagent.TeamAgentException;
import cn.ilink.teamagent.TeamTaskCommandService;
import cn.ilink.teamagent.TeamTaskCommands;
import cn.ilink.vo.TeamTaskVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务创建权限回归：
 * 队长可给任意成员分配；普通成员仅允许创建“分配给自己”的任务
 * （任务被分配者本人拆解子任务/自建任务时使用），他人任务不可代拆。
 */
@WebMvcTest(controllers = TeamTaskController.class)
@AutoConfigureMockMvc(addFilters = false)
class TeamTaskControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TeamTaskService teamTaskService;

    @MockBean
    private TeamTaskCommandService teamTaskCommandService;

    @MockBean
    private TeamDemandServiceImpl teamDemandService;

    @MockBean
    private TeamApplicationServiceImpl teamApplicationService;

    @MockBean
    private TaskSubmissionMapper taskSubmissionMapper;

    @MockBean
    private TaskParticipantMapper taskParticipantMapper;

    @MockBean
    private UserService userService;

    @MockBean
    private KgIndexService kgIndexService;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private cn.ilink.mapper.TaskReviewMapper taskReviewMapper;

    private MockHttpSession session;
    private TeamTaskVO created;

    @BeforeEach
    void setUp() {
        TeamDemand team = new TeamDemand();
        team.setId(3L);
        team.setStatus("TEAMING");
        team.setCreatorId(9L);
        given(teamDemandService.getById(3L)).willReturn(team);
        // 成员 7 是已批准的团队成员
        given(teamApplicationService.count(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
            .willReturn(1L);

        User member = new User();
        member.setId(7L);
        member.setUsername("member");
        member.setRealName("成员七");
        session = new MockHttpSession();
        session.setAttribute("user", member);

        created = new TeamTaskVO();
        created.setId(50L);
        created.setTaskTitle("子任务");
        given(teamTaskService.getTaskById(50L)).willReturn(created);
        given(teamTaskCommandService.createTask(any())).willReturn(
            new TeamTaskCommands.MutationResult(50L, 1, "{}", "任务已创建"));
        given(teamTaskCommandService.reviewTask(any())).willReturn(
            new TeamTaskCommands.MutationResult(11L, 2, "{}", "批阅已保存"));
        given(teamTaskCommandService.changeStatus(any())).willReturn(
            new TeamTaskCommands.MutationResult(11L, 2, "{}", "状态已更新"));
    }

    private String body(Long assignedTo) {
        return "{\"taskTitle\":\"完成子任务\",\"taskType\":\"DEVELOPMENT\",\"priority\":2,"
            + "\"estimatedHours\":2,\"assignedTo\":" + assignedTo + "}";
    }

    @Test
    void memberCanCreateTaskAssignedToThemselves() throws Exception {
        // 被分配者本人拆解任务后落库：非队长 + 分配给自己 → 放行
        mockMvc.perform(post("/api/team/3/tasks")
                .session(session)
                .contentType("application/json")
                .content(body(7L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(teamTaskCommandService).createTask(argThat(command ->
            command != null && Long.valueOf(7L).equals(command.actorUserId())));
    }

    @Test
    void memberCannotAssignTaskToOthers() throws Exception {
        // 非队长不能把任务分配给别人
        doThrow(new TeamAgentException(TeamAgentErrorCode.TEAM_ACCESS_DENIED, "不能代替他人创建任务"))
            .when(teamTaskCommandService).createTask(argThat(command ->
                command != null && Long.valueOf(9L).equals(command.assignedTo())));

        mockMvc.perform(post("/api/team/3/tasks")
                .session(session)
                .contentType("application/json")
                .content(body(9L)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        verify(teamTaskCommandService).createTask(argThat(command ->
            command != null && Long.valueOf(9L).equals(command.assignedTo())));
    }

    @Test
    void closedTeamKeepsHistoricalTasksReadable() throws Exception {
        TeamDemand closedTeam = new TeamDemand();
        closedTeam.setId(3L);
        closedTeam.setStatus("CLOSED");
        closedTeam.setCreatorId(9L);
        given(teamDemandService.getById(3L)).willReturn(closedTeam);
        TeamTaskVO historical = new TeamTaskVO();
        historical.setId(50L);
        historical.setTaskTitle("历史任务");
        historical.setAssignedTo(7L);
        given(teamTaskService.getTasksByTeam(3L))
            .willReturn(java.util.List.of(historical));
        cn.ilink.entity.TeamTask historicalEntity = new cn.ilink.entity.TeamTask();
        historicalEntity.setId(50L);
        historicalEntity.setTeamId(3L);
        historicalEntity.setAssignedTo(7L);
        given(teamTaskService.getById(50L)).willReturn(historicalEntity);
        given(teamTaskService.getTaskById(50L)).willReturn(historical);

        mockMvc.perform(get("/api/team/3/tasks?teamId=3").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].id").value(50));
        mockMvc.perform(get("/api/tasks/50").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.id").value(50));
    }

    private cn.ilink.entity.TeamTask reviewTask(Long assignedTo) {
        cn.ilink.entity.TeamTask task = new cn.ilink.entity.TeamTask();
        task.setId(11L);
        task.setTeamId(3L);
        task.setTaskTitle("待批阅任务");
        task.setStatus("REVIEW");
        task.setAssignedTo(assignedTo);
        task.setRequiresReview(true);
        return task;
    }

    @Test
    void rejectWithoutCommentIsRejected() throws Exception {
        // 驳回是否定动作：必须至少填写一条点评
        given(teamTaskService.getById(11L)).willReturn(reviewTask(9L));
        doThrow(new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID, "驳回必须填写评语"))
            .when(teamTaskCommandService).reviewTask(argThat(command ->
                command != null && "RETURNED".equals(command.action())
                    && (command.overallComment() == null || command.overallComment().isBlank())));
        User leader = new User();
        leader.setId(9L);
        MockHttpSession leaderSession = new MockHttpSession();
        leaderSession.setAttribute("user", leader);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/tasks/11/review")
                .session(leaderSession)
                .contentType("application/json")
                .content("{\"action\":\"RETURNED\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        verify(taskReviewMapper, never()).insert(any(cn.ilink.entity.TaskReview.class));
    }

    @Test
    void reviewStoresRecord() throws Exception {
        // 通过时状态流转并落批阅记录（导师与队长同路径，权限差异在端到端覆盖）
        given(teamTaskService.getById(11L)).willReturn(reviewTask(7L));
        User leader = new User();
        leader.setId(9L);
        MockHttpSession leaderSession = new MockHttpSession();
        leaderSession.setAttribute("user", leader);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/tasks/11/review")
                .session(leaderSession)
                .contentType("application/json")
                .content("{\"action\":\"COMPLETED\",\"overall\":\"完成质量很好\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(teamTaskCommandService).reviewTask(argThat(command ->
            command != null && "COMPLETED".equals(command.action())));
    }

    @Test
    void commentVerdictDoesNotTouchStatus() throws Exception {
        // 补充点评：不触发状态流转
        given(teamTaskService.getById(11L)).willReturn(reviewTask(7L));
        User leader = new User();
        leader.setId(9L);
        MockHttpSession leaderSession = new MockHttpSession();
        leaderSession.setAttribute("user", leader);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/tasks/11/review")
                .session(leaderSession)
                .contentType("application/json")
                .content("{\"action\":\"COMMENT\",\"overall\":\"补充一点\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(teamTaskCommandService).reviewTask(argThat(command ->
            command != null && "COMMENT".equals(command.action())));
    }

    @Test
    void directCompleteBlockedWhenReviewRequired() throws Exception {
        cn.ilink.entity.TeamTask task = reviewTask(7L);
        task.setRequiresReview(true);
        given(teamTaskService.getById(11L)).willReturn(task);

        mockMvc.perform(post("/api/tasks/11/complete").session(session))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void directCompleteSucceedsWithoutReviewRequirement() throws Exception {
        cn.ilink.entity.TeamTask task = reviewTask(7L);
        task.setRequiresReview(false);
        given(teamTaskService.getById(11L)).willReturn(task);
        mockMvc.perform(post("/api/tasks/11/complete").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void leaderStillAssignsAnyone() throws Exception {
        // 队长给任意成员分配的原路径保持不变
        User leader = new User();
        leader.setId(9L);
        leader.setUsername("leader");
        MockHttpSession leaderSession = new MockHttpSession();
        leaderSession.setAttribute("user", leader);

        mockMvc.perform(post("/api/team/3/tasks")
                .session(leaderSession)
                .contentType("application/json")
                .content(body(7L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(teamTaskCommandService).createTask(argThat(command ->
            command != null && Long.valueOf(9L).equals(command.actorUserId())));
    }

    @Test
    void updateRejectsJsonNullBodyBeforeConversion() throws Exception {
        cn.ilink.entity.TeamTask task = reviewTask(7L);
        given(teamTaskService.getById(11L)).willReturn(task);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/tasks/11")
                .session(session)
                .contentType("application/json")
                .content("null"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        verify(teamTaskCommandService, never()).updateTask(any());
    }
}
