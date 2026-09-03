package cn.ilink.service;

import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamDemandMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamApplicationWorkflowServiceTest {

    private TeamApplicationMapper applicationMapper;
    private TeamDemandMapper teamMapper;
    private NotificationService notificationService;
    private TeamApplicationWorkflowService service;

    @BeforeEach
    void setUp() {
        applicationMapper = mock(TeamApplicationMapper.class);
        teamMapper = mock(TeamDemandMapper.class);
        notificationService = mock(NotificationService.class);
        service = new TeamApplicationWorkflowService(applicationMapper, teamMapper, notificationService);
    }

    @Test
    void approvingLastSlotLocksRowsAndMovesTeamToTeaming() {
        TeamApplication application = application("PENDING");
        TeamDemand team = team(2);
        when(applicationMapper.selectByIdForUpdate(10L)).thenReturn(application);
        when(teamMapper.selectByIdForUpdate(20L)).thenReturn(team);
        when(applicationMapper.selectCount(any())).thenReturn(1L, 2L);
        when(applicationMapper.updateById(application)).thenReturn(1);
        when(teamMapper.updateById(team)).thenReturn(1);

        TeamApplication result = service.review(10L, 7L, "APPROVED", "欢迎加入");

        assertEquals("APPROVED", result.getStatus());
        assertEquals("TEAMING", team.getStatus());
        verify(applicationMapper).selectByIdForUpdate(10L);
        verify(teamMapper).selectByIdForUpdate(20L);
        verify(notificationService).create(eq(8L), eq(7L), eq("TEAM_APPROVED"),
            eq("申请通过"), any(String.class), eq(20L));
    }

    @Test
    void fullTeamRejectsApprovalWithoutUpdatingApplication() {
        TeamApplication application = application("PENDING");
        TeamDemand team = team(1);
        when(applicationMapper.selectByIdForUpdate(10L)).thenReturn(application);
        when(teamMapper.selectByIdForUpdate(20L)).thenReturn(team);
        when(applicationMapper.selectCount(any())).thenReturn(1L);

        TeamApplicationWorkflowService.WorkflowException error = assertThrows(
            TeamApplicationWorkflowService.WorkflowException.class,
            () -> service.review(10L, 7L, "APPROVED", null));

        assertEquals(400, error.getStatus());
        verify(applicationMapper, never()).updateById(any());
    }

    @Test
    void processedApplicationCannotBeReviewedAgain() {
        TeamApplication application = application("APPROVED");
        when(applicationMapper.selectByIdForUpdate(10L)).thenReturn(application);
        when(teamMapper.selectByIdForUpdate(20L)).thenReturn(team(3));

        TeamApplicationWorkflowService.WorkflowException error = assertThrows(
            TeamApplicationWorkflowService.WorkflowException.class,
            () -> service.review(10L, 7L, "REJECTED", "信息不符合要求无法通过"));

        assertEquals(400, error.getStatus());
        verify(applicationMapper, never()).updateById(any());
    }

    private TeamApplication application(String status) {
        TeamApplication application = new TeamApplication();
        application.setId(10L);
        application.setTeamId(20L);
        application.setUserId(8L);
        application.setStatus(status);
        return application;
    }

    private TeamDemand team(int requiredCount) {
        TeamDemand team = new TeamDemand();
        team.setId(20L);
        team.setCreatorId(7L);
        team.setTitle("竞赛队伍");
        team.setStatus("OPEN");
        team.setRequiredMemberCount(requiredCount);
        return team;
    }
}
