package cn.ilink.service;

import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamDemandMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转让与解散。
 * 这两条是「创建者唯一的退出路径」，判定要点是：只有创建者能做，且并发下不能出现两个创建者。
 */
class TeamOwnershipServiceTest {

    private static final Long TEAM_ID = 20L;
    private static final Long OWNER_ID = 7L;
    private static final Long MEMBER_ID = 8L;

    private TeamDemandMapper teamDemandMapper;
    private TeamApplicationMapper teamApplicationMapper;
    private TeamMembershipService membershipService;
    private TeamTaskService teamTaskService;
    private NotificationService notificationService;
    private UserService userService;
    private TeamOwnershipService service;

    @BeforeEach
    void setUp() {
        teamDemandMapper = mock(TeamDemandMapper.class);
        teamApplicationMapper = mock(TeamApplicationMapper.class);
        membershipService = mock(TeamMembershipService.class);
        teamTaskService = mock(TeamTaskService.class);
        notificationService = mock(NotificationService.class);
        userService = mock(UserService.class);
        service = new TeamOwnershipService(teamDemandMapper, teamApplicationMapper, membershipService,
            teamTaskService, notificationService, userService);

        when(teamDemandMapper.selectByIdForUpdate(TEAM_ID)).thenReturn(team("OPEN"));
        when(teamDemandMapper.update(any(), any())).thenReturn(1);
        when(userService.getById(anyLong())).thenAnswer(invocation -> user(invocation.getArgument(0)));
    }

    @Test
    void onlyOwnerCanTransfer() {
        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.transfer(TEAM_ID, MEMBER_ID, 9L));

        assertEquals(403, error.getStatus());
        verify(teamDemandMapper, never()).update(any(), any());
    }

    @Test
    void transferTargetMustBeAnApprovedMember() {
        when(membershipService.findRow(TEAM_ID, 9L)).thenReturn(null);

        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.transfer(TEAM_ID, OWNER_ID, 9L));

        assertEquals(400, error.getStatus());
        verify(teamDemandMapper, never()).update(any(), any());
    }

    @Test
    void cannotTransferToSelf() {
        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.transfer(TEAM_ID, OWNER_ID, OWNER_ID));

        assertEquals(400, error.getStatus());
    }

    @Test
    void transferMovesOwnershipAndRebalancesMemberRows() {
        TeamApplication newOwnerRow = row(MEMBER_ID, TeamMembershipService.STATUS_APPROVED);
        newOwnerRow.setId(99L);
        when(membershipService.findRow(TEAM_ID, MEMBER_ID)).thenReturn(newOwnerRow);
        when(membershipService.findRow(TEAM_ID, OWNER_ID)).thenReturn(null);
        when(teamApplicationMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.transfer(TEAM_ID, OWNER_ID, MEMBER_ID);

        // 新任创建者不能再占一行，否则成员列表里会出现两次
        verify(teamApplicationMapper).deleteById(99L);
        // 原创建者落回普通成员，否则他既不是创建者也不在成员表里，等于凭空消失
        verify(membershipService).upsertRow(eq(null), eq(TEAM_ID), eq(OWNER_ID), eq(OWNER_ID),
            eq(TeamMembershipService.STATUS_APPROVED), any(), eq(null));
        verify(notificationService).create(eq(MEMBER_ID), eq(OWNER_ID),
            eq("TEAM_OWNER_TRANSFERRED"), any(String.class), any(String.class), eq(TEAM_ID));
    }

    @Test
    void transferFailsWhenConcurrentUpdateLoses() {
        // 带条件的 UPDATE 影响行数为 0：说明我此刻已不是创建者（并发双转让）
        when(teamDemandMapper.update(any(), any())).thenReturn(0);
        when(membershipService.findRow(TEAM_ID, MEMBER_ID)).thenReturn(row(MEMBER_ID, TeamMembershipService.STATUS_APPROVED));

        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.transfer(TEAM_ID, OWNER_ID, MEMBER_ID));

        assertEquals(400, error.getStatus());
        verify(teamApplicationMapper, never()).deleteById(anyLong());
    }

    @Test
    void transferRejectsFinishedTeam() {
        when(teamDemandMapper.selectByIdForUpdate(TEAM_ID)).thenReturn(team("CLOSED"));

        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.transfer(TEAM_ID, OWNER_ID, MEMBER_ID));

        assertEquals(400, error.getStatus());
    }

    @Test
    void onlyOwnerCanDissolve() {
        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.dissolve(TEAM_ID, MEMBER_ID));

        assertEquals(403, error.getStatus());
    }

    @Test
    void alreadyDissolvedTeamCannotBeDissolvedAgain() {
        when(teamDemandMapper.selectByIdForUpdate(TEAM_ID)).thenReturn(team("DISSOLVED"));

        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.dissolve(TEAM_ID, OWNER_ID));

        assertEquals(400, error.getStatus());
    }

    @Test
    void dissolveVoidsMembersCancelsTasksAndNotifies() {
        TeamApplication member = row(MEMBER_ID, TeamMembershipService.STATUS_APPROVED);
        TeamApplication pending = row(9L, TeamMembershipService.STATUS_PENDING);
        pending.setInitiatorId(OWNER_ID);
        when(teamApplicationMapper.selectList(any())).thenReturn(java.util.Arrays.asList(member, pending));

        int notified = service.dissolve(TEAM_ID, OWNER_ID);

        assertEquals(1, notified);
        // 软删：只改状态，协作历史（任务/群聊/里程碑）全部保留
        verify(teamDemandMapper, never()).deleteById(anyLong());
        verify(teamApplicationMapper).update(any(), any());
        verify(teamTaskService).update(any());
        verify(notificationService).create(eq(MEMBER_ID), eq(OWNER_ID), eq("TEAM_DISSOLVED"),
            any(String.class), any(String.class), eq(TEAM_ID));
        // 待确认的邀请要作废，不能再有人点到「同意加入」
        verify(notificationService).markInviteHandled(9L, TEAM_ID);
        verify(notificationService, never()).create(eq(9L), anyLong(), any(), any(), any(), anyLong());
    }

    private static TeamDemand team(String status) {
        TeamDemand team = new TeamDemand();
        team.setId(TEAM_ID);
        team.setTitle("竞赛队伍");
        team.setCreatorId(OWNER_ID);
        team.setStatus(status);
        return team;
    }

    private static TeamApplication row(Long userId, String status) {
        TeamApplication row = new TeamApplication();
        row.setTeamId(TEAM_ID);
        row.setUserId(userId);
        row.setInitiatorId(userId);
        row.setStatus(status);
        row.setMemberRole(TeamMembershipService.ROLE_STUDENT);
        return row;
    }

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setRole("STUDENT");
        user.setRealName("用户" + id);
        user.setUsername("u" + id);
        return user;
    }
}
