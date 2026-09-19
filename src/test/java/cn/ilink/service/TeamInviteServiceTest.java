package cn.ilink.service;

import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamDemandMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 邀请与成员进出的编排逻辑。
 * 这里不碰真实 SQL，重点验证「跳过谁、为什么跳过、谁有权操作」。
 */
class TeamInviteServiceTest {

    private static final Long TEAM_ID = 20L;
    private static final Long OWNER_ID = 7L;

    private TeamDemandServiceImpl teamDemandService;
    private TeamApplicationServiceImpl teamApplicationService;
    private TeamApplicationMapper teamApplicationMapper;
    private TeamDemandMapper teamDemandMapper;
    private TeamMembershipService membershipService;
    private TeamTaskService teamTaskService;
    private NotificationService notificationService;
    private UserService userService;
    private TeamInviteService service;

    @BeforeEach
    void setUp() {
        teamDemandService = mock(TeamDemandServiceImpl.class);
        teamApplicationService = mock(TeamApplicationServiceImpl.class);
        teamApplicationMapper = mock(TeamApplicationMapper.class);
        teamDemandMapper = mock(TeamDemandMapper.class);
        membershipService = mock(TeamMembershipService.class);
        teamTaskService = mock(TeamTaskService.class);
        notificationService = mock(NotificationService.class);
        userService = mock(UserService.class);
        service = new TeamInviteService(teamDemandService, teamApplicationService, teamApplicationMapper,
            teamDemandMapper, membershipService, teamTaskService, notificationService, userService);

        when(teamDemandService.getById(TEAM_ID)).thenReturn(team("OPEN"));
        when(membershipService.canManageMembers(any(), eq(OWNER_ID))).thenReturn(true);
        when(membershipService.isFull(any())).thenReturn(false);
    }

    @Test
    void inviteSkipsMemberAndPendingAndInvitesTheRest() {
        when(userService.listByIds(any())).thenReturn(Arrays.asList(user(1L, "STUDENT", "小明"), user(2L, "STUDENT", "小红")));
        when(teamApplicationService.list(any(Wrapper.class))).thenReturn(Arrays.asList(
            row(1L, TeamMembershipService.STATUS_APPROVED, OWNER_ID, null),
            row(2L, TeamMembershipService.STATUS_PENDING, OWNER_ID, null)));
        stubUsers(OWNER_ID, "队长");

        TeamInviteService.InviteOutcome outcome =
            service.invite(TEAM_ID, OWNER_ID, Arrays.asList(1L, 2L, 3L));

        assertTrue(outcome.invited.isEmpty(), "已经在队和已在待确认的人都不该被重复邀请");
        List<Map<String, Object>> skipped = outcome.skipped;
        assertEquals(3, skipped.size());
        assertEquals("ALREADY_MEMBER", skipped.get(0).get("reason"));
        assertEquals("ALREADY_INVITED", skipped.get(1).get("reason"));
        // 查不到的用户（3 号已被删除）同样按跳过处理，不能写库
        assertEquals("USER_NOT_FOUND", skipped.get(2).get("reason"));
        verify(membershipService, never()).upsertRow(any(), anyLong(), anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void inviteRejoinsUsersWhoLeftBefore() {
        User target = user(3L, "STUDENT", "小明");
        when(userService.listByIds(any())).thenReturn(Collections.singletonList(target));
        when(teamApplicationService.list(any(Wrapper.class))).thenReturn(Collections.singletonList(
            row(3L, TeamMembershipService.STATUS_REMOVED, 3L, null)));
        stubUsers(OWNER_ID, "队长");

        TeamInviteService.InviteOutcome outcome = service.invite(TEAM_ID, OWNER_ID, Collections.singletonList(3L));

        assertEquals(Collections.singletonList(3L), outcome.invited);
        assertTrue(outcome.skipped.isEmpty());
        verify(notificationService).create(eq(3L), eq(OWNER_ID), eq("TEAM_INVITE"),
            eq("团队邀请"), any(String.class), eq(TEAM_ID));
    }

    @Test
    void inviteSkipsEveryoneWhenTeamIsFull() {
        when(membershipService.isFull(any())).thenReturn(true);
        when(userService.listByIds(any())).thenReturn(Collections.singletonList(user(3L, "STUDENT", "小明")));
        when(teamApplicationService.list(any(Wrapper.class))).thenReturn(Collections.emptyList());
        stubUsers(OWNER_ID, "队长");

        TeamInviteService.InviteOutcome outcome = service.invite(TEAM_ID, OWNER_ID, Collections.singletonList(3L));

        assertTrue(outcome.invited.isEmpty());
        assertEquals("TEAM_FULL", outcome.skipped.get(0).get("reason"));
        verify(notificationService, never()).create(anyLong(), anyLong(), any(), any(), any(), anyLong());
    }

    @Test
    void inviteRequiresManagementPermission() {
        when(membershipService.canManageMembers(any(), eq(9L))).thenReturn(false);

        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.invite(TEAM_ID, 9L, Collections.singletonList(3L)));

        assertEquals(403, error.getStatus());
    }

    @Test
    void inviteRejectsDissolvedTeam() {
        when(teamDemandService.getById(TEAM_ID)).thenReturn(team("DISSOLVED"));

        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.invite(TEAM_ID, OWNER_ID, Collections.singletonList(3L)));

        assertEquals(400, error.getStatus());
    }

    @Test
    void acceptMarksApprovedAndLocksTeamWhenFull() {
        TeamApplication invite = row(3L, TeamMembershipService.STATUS_PENDING, OWNER_ID, 1L);
        invite.setId(1L);
        when(teamApplicationMapper.selectByIdForUpdate(1L)).thenReturn(invite);
        when(teamDemandMapper.selectByIdForUpdate(TEAM_ID)).thenReturn(team("OPEN"));
        when(teamApplicationMapper.updateById(any())).thenReturn(1);
        // 加入前还有名额，加入后才满 —— 只有这个序列才会走到「队伍转 TEAMING」
        when(membershipService.isFull(any())).thenReturn(false, true);

        TeamApplication result = service.respond(1L, 3L, true);

        assertEquals(TeamMembershipService.STATUS_APPROVED, result.getStatus());
        verify(teamDemandMapper).updateById(any());
        verify(notificationService).markInviteHandled(3L, TEAM_ID);
    }

    @Test
    void acceptRefusesWhenTeamFilledUpWhileInviteWasPending() {
        TeamApplication invite = row(3L, TeamMembershipService.STATUS_PENDING, OWNER_ID, 1L);
        invite.setId(1L);
        when(teamApplicationMapper.selectByIdForUpdate(1L)).thenReturn(invite);
        when(teamDemandMapper.selectByIdForUpdate(TEAM_ID)).thenReturn(team("OPEN"));
        when(membershipService.isFull(any())).thenReturn(true);

        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.respond(1L, 3L, true));

        assertEquals(400, error.getStatus());
        verify(teamApplicationMapper, never()).updateById(any());
    }

    @Test
    void anApplicationCannotBeSelfAccepted() {
        // initiator 等于本人 = 这是本人提交的入队申请，不能自己点同意
        TeamApplication application = row(3L, TeamMembershipService.STATUS_PENDING, 3L, 1L);
        application.setId(1L);
        when(teamApplicationMapper.selectByIdForUpdate(1L)).thenReturn(application);

        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.respond(1L, 3L, true));

        assertEquals(400, error.getStatus());
    }

    @Test
    void handledInvitationCannotBeRespondedTwice() {
        TeamApplication invite = row(3L, TeamMembershipService.STATUS_APPROVED, OWNER_ID, 1L);
        invite.setId(1L);
        when(teamApplicationMapper.selectByIdForUpdate(1L)).thenReturn(invite);

        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.respond(1L, 3L, true));

        assertEquals(400, error.getStatus());
        verify(teamApplicationMapper, never()).updateById(any());
    }

    @Test
    void onlyTheInviteeCanRespond() {
        TeamApplication invite = row(3L, TeamMembershipService.STATUS_PENDING, OWNER_ID, 1L);
        invite.setId(1L);
        when(teamApplicationMapper.selectByIdForUpdate(1L)).thenReturn(invite);

        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.respond(1L, 999L, true));

        assertEquals(403, error.getStatus());
    }

    @Test
    void creatorCannotLeaveByRemovingHimself() {
        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.removeMember(TEAM_ID, OWNER_ID, OWNER_ID));

        assertEquals(400, error.getStatus());
    }

    @Test
    void leavingMyselfMarksLeftAndClearsMyTasks() {
        TeamApplication me = row(9L, TeamMembershipService.STATUS_APPROVED, 9L, 1L);
        when(membershipService.findRow(TEAM_ID, 9L)).thenReturn(me);
        when(teamApplicationMapper.updateById(any())).thenReturn(1);

        service.removeMember(TEAM_ID, 9L, 9L);

        assertEquals(TeamMembershipService.STATUS_LEFT, me.getStatus());
        verify(teamTaskService).update(any());
        // 自己退出不需要给自己发通知
        verify(notificationService, never()).create(anyLong(), anyLong(), any(), any(), any(), anyLong());
    }

    @Test
    void ownerRemovingSomeoneMarksRemovedAndNotifies() {
        TeamApplication target = row(9L, TeamMembershipService.STATUS_APPROVED, 9L, 1L);
        when(membershipService.findRow(TEAM_ID, 9L)).thenReturn(target);
        when(membershipService.canRemove(any(), eq(OWNER_ID), eq(9L), any())).thenReturn(true);
        when(teamApplicationMapper.updateById(any())).thenReturn(1);

        service.removeMember(TEAM_ID, OWNER_ID, 9L);

        assertEquals(TeamMembershipService.STATUS_REMOVED, target.getStatus());
        verify(notificationService).create(eq(9L), eq(OWNER_ID), eq("TEAM_MEMBER_REMOVED"),
            any(String.class), any(String.class), eq(TEAM_ID));
    }

    @Test
    void cannotRemoveSomeoneWhoIsNotAnApprovedMember() {
        when(membershipService.findRow(TEAM_ID, 9L)).thenReturn(
            row(9L, TeamMembershipService.STATUS_PENDING, OWNER_ID, 1L));

        TeamMembershipException error = assertThrows(TeamMembershipException.class,
            () -> service.removeMember(TEAM_ID, OWNER_ID, 9L));

        assertEquals(404, error.getStatus());
    }

    private void stubUsers(Long id, String name) {
        when(userService.getById(id)).thenReturn(user(id, "TEACHER", name));
    }

    private static TeamDemand team(String status) {
        TeamDemand team = new TeamDemand();
        team.setId(TEAM_ID);
        team.setTitle("竞赛队伍");
        team.setCreatorId(OWNER_ID);
        team.setStatus(status);
        return team;
    }

    private static TeamApplication row(Long userId, String status, Long initiatorId, Long id) {
        TeamApplication row = new TeamApplication();
        row.setId(id);
        row.setTeamId(TEAM_ID);
        row.setUserId(userId);
        row.setInitiatorId(initiatorId);
        row.setStatus(status);
        row.setMemberRole(TeamMembershipService.ROLE_STUDENT);
        return row;
    }

    private static User user(Long id, String role, String realName) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setRealName(realName);
        user.setUsername("u" + id);
        return user;
    }
}
