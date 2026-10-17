package cn.ilink.controller;

import cn.ilink.common.Result;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.UserSkillMapper;
import cn.ilink.service.NotificationService;
import cn.ilink.service.TeamApplicationWorkflowService;
import cn.ilink.service.TeamInviteService;
import cn.ilink.service.TeamMembershipService;
import cn.ilink.service.UserService;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复测报告 N2 的回归：requiredMemberCount=1 或已满的队伍，
 * 申请在提交时就应该被拒绝，而不是等到审批环节才失败。
 */
class TeamControllerJoinTeamTest {

    private TeamController controllerWith(TeamDemandServiceImpl teamDemandService,
                                          TeamApplicationServiceImpl teamApplicationService,
                                          TeamMembershipService membershipService) {
        return new TeamController(
            teamDemandService,
            teamApplicationService,
            mock(UserService.class),
            mock(NotificationService.class),
            mock(UserSkillMapper.class),
            mock(TeamApplicationWorkflowService.class),
            membershipService,
            mock(TeamInviteService.class),
            mock(cn.ilink.service.impl.CompetitionServiceImpl.class),
            mock(cn.ilink.service.TeamLibraryService.class));
    }

    private User joiner() {
        User user = new User();
        user.setId(2L);
        user.setRole("STUDENT");
        return user;
    }

    @Test
    void joinTeamRejectsAtSubmissionWhenTeamIsFull() {
        TeamDemandServiceImpl teamDemandService = mock(TeamDemandServiceImpl.class);
        TeamApplicationServiceImpl teamApplicationService = mock(TeamApplicationServiceImpl.class);
        TeamMembershipService membershipService = mock(TeamMembershipService.class);
        TeamController controller = controllerWith(teamDemandService, teamApplicationService, membershipService);

        TeamDemand team = new TeamDemand();
        team.setId(9L);
        team.setStatus("OPEN");
        team.setRequiredMemberCount(1);
        team.setCreatorId(1L);
        when(teamDemandService.getById(9L)).thenReturn(team);
        when(membershipService.isFull(team)).thenReturn(true);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", joiner());

        ResponseEntity<Result<?>> response = controller.joinTeam(Map.of("teamId", "9"), session);

        assertEquals(400, response.getStatusCodeValue());
        assertEquals(400, response.getBody().getCode());
        // 申请在满员判定处就被拦下，不能走到申请记录查询/写入
        verify(teamApplicationService, never()).getOne(any());
    }

    @Test
    void joinTeamProceedsWhenTeamHasRoom() {
        TeamDemandServiceImpl teamDemandService = mock(TeamDemandServiceImpl.class);
        TeamApplicationServiceImpl teamApplicationService = mock(TeamApplicationServiceImpl.class);
        TeamMembershipService membershipService = mock(TeamMembershipService.class);
        TeamController controller = controllerWith(teamDemandService, teamApplicationService, membershipService);

        TeamDemand team = new TeamDemand();
        team.setId(9L);
        team.setStatus("OPEN");
        team.setRequiredMemberCount(3);
        team.setCreatorId(1L);
        when(teamDemandService.getById(9L)).thenReturn(team);
        when(membershipService.isFull(team)).thenReturn(false);
        // 没有历史申请记录
        when(teamApplicationService.getOne(any())).thenReturn(null);
        when(teamApplicationService.save(any())).thenReturn(true);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", joiner());

        ResponseEntity<Result<?>> response = controller.joinTeam(Map.of("teamId", "9"), session);

        // 有空位时不应被满员判定拦下（后续逻辑按既有流程走）
        verify(membershipService).isFull(team);
        verify(teamApplicationService).getOne(any());
        assertEquals(200, response.getBody().getCode());
    }
}
