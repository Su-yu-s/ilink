package cn.ilink.controller;

import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.dto.ProjectMilestoneDTO;
import cn.ilink.security.LoginAttemptService;
import cn.ilink.service.HomeStatsService;
import cn.ilink.service.ProjectMilestoneService;
import cn.ilink.service.TeamAccessService;
import cn.ilink.vo.ProjectMilestoneVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProjectMilestoneController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProjectMilestoneControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProjectMilestoneService projectMilestoneService;

    @MockBean
    private TeamAccessService teamAccessService;

    @MockBean
    private cn.ilink.service.impl.TeamDemandServiceImpl teamDemandService;

    @MockBean
    private LoginAttemptService loginAttemptService;

    @MockBean
    private HomeStatsService homeStatsService;

    @Test
    void milestoneListRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/team/3/milestones"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void nonParticipantCannotReadMilestoneList() throws Exception {
        User user = user(9L);
        given(teamAccessService.isTeamParticipant(3L, 9L)).willReturn(false);

        mockMvc.perform(get("/api/team/3/milestones").session(sessionFor(user)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        verify(projectMilestoneService, never()).getByTeam(any());
    }

    @Test
    void teamParticipantCanReadMilestoneList() throws Exception {
        User user = user(9L);
        given(teamAccessService.isTeamParticipant(3L, 9L)).willReturn(true);
        given(projectMilestoneService.getByTeam(3L)).willReturn(Collections.emptyList());

        mockMvc.perform(get("/api/team/3/milestones").session(sessionFor(user)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void nonParticipantCannotReadOrModifyMilestoneById() throws Exception {
        User user = user(9L);
        ProjectMilestoneVO milestone = new ProjectMilestoneVO();
        milestone.setId(8L);
        milestone.setTeamId(3L);
        given(projectMilestoneService.getById(8L)).willReturn(milestone);
        given(teamAccessService.isTeamParticipant(3L, 9L)).willReturn(false);

        mockMvc.perform(get("/api/milestones/8").session(sessionFor(user)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(put("/api/milestones/8")
                .session(sessionFor(user))
                .contentType("application/json")
                .content("{\"milestoneName\":\"should not save\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        verify(projectMilestoneService, never()).update(anyLong(), any(ProjectMilestoneDTO.class));
    }

    /**
     * 核实报告 P1-2：不存在的日期（2026-13-45）必须 400，不能被宽松解析滚成 2027-02-14 静默落库。
     */
    @Test
    void nonExistentDueDateIsRejectedWithBadRequest() throws Exception {
        User user = user(9L);
        given(teamAccessService.isTeamParticipant(3L, 9L)).willReturn(true);
        TeamDemand team = new TeamDemand();
        team.setId(3L);
        team.setStatus("TEAMING");
        given(teamDemandService.getById(3L)).willReturn(team);

        mockMvc.perform(post("/api/team/3/milestones")
                .session(sessionFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"milestoneName\":\"T4日期错\",\"dueDate\":\"2026-13-45\",\"completionRate\":0}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        verify(projectMilestoneService, never()).create(any(ProjectMilestoneDTO.class), anyLong());
    }

    /** 合法 yyyy-MM-dd 仍应创建成功 */
    @Test
    void validDueDateIsAccepted() throws Exception {
        User user = user(9L);
        given(teamAccessService.isTeamParticipant(3L, 9L)).willReturn(true);
        TeamDemand team = new TeamDemand();
        team.setId(3L);
        team.setStatus("TEAMING");
        given(teamDemandService.getById(3L)).willReturn(team);
        given(projectMilestoneService.create(any(ProjectMilestoneDTO.class), anyLong())).willReturn(true);
        given(projectMilestoneService.getByTeam(3L)).willReturn(Collections.emptyList());

        mockMvc.perform(post("/api/team/3/milestones")
                .session(sessionFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"milestoneName\":\"T4正常\",\"dueDate\":\"2026-12-31\",\"completionRate\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        return user;
    }

    private MockHttpSession sessionFor(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", user);
        return session;
    }
}
