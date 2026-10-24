package cn.ilink.teamagent;

import cn.ilink.entity.TeamAgentAuditLog;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.mapper.TaskCommentMapper;
import cn.ilink.mapper.TaskParticipantMapper;
import cn.ilink.mapper.TaskReviewMapper;
import cn.ilink.mapper.TaskSubmissionMapper;
import cn.ilink.mapper.TeamAgentAuditLogMapper;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.mapper.TeamTaskMapper;
import cn.ilink.service.NotificationService;
import cn.ilink.service.UserService;
import cn.ilink.service.kg.KgIndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamTaskCommandServiceTest {
    private static final Long TEAM_ID = 20L;
    private static final Long OWNER_ID = 7L;
    private static final Long MEMBER_ID = 8L;
    private static final Long OTHER_ID = 9L;

    private TeamDemandMapper teamMapper;
    private TeamTaskMapper taskMapper;
    private TaskParticipantMapper participantMapper;
    private TeamAgentAuditLogMapper auditMapper;
    private NotificationService notificationService;
    private UserService userService;
    private KgIndexService indexService;
    private TeamTaskSnapshotSerializer snapshotSerializer;
    private TeamTaskCommandService service;

    @BeforeEach
    void setUp() {
        teamMapper = mock(TeamDemandMapper.class);
        taskMapper = mock(TeamTaskMapper.class);
        participantMapper = mock(TaskParticipantMapper.class);
        auditMapper = mock(TeamAgentAuditLogMapper.class);
        notificationService = mock(NotificationService.class);
        userService = mock(UserService.class);
        indexService = mock(KgIndexService.class);
        snapshotSerializer = mock(TeamTaskSnapshotSerializer.class);
        service = new TeamTaskCommandService(
            teamMapper,
            taskMapper,
            participantMapper,
            mock(TaskCommentMapper.class),
            mock(TaskSubmissionMapper.class),
            mock(TaskReviewMapper.class),
            mock(TeamApplicationMapper.class),
            userService,
            notificationService,
            indexService,
            auditMapper,
            mock(TeamTaskPolicy.class),
            snapshotSerializer,
            new ObjectMapper());

        when(userService.getById(anyLong())).thenAnswer(invocation ->
            user(invocation.getArgument(0)));
        when(auditMapper.insert(any(TeamAgentAuditLog.class))).thenReturn(1);
        when(indexService.enqueueUpsert(
            anyString(), anyLong(), any(), anyString(), anyString(), anyLong(), anyLong()))
            .thenReturn(true);
    }

    @Test
    void dissolutionCancelsEveryOpenTaskWithVersionIndexAuditAndAggregateNotice() {
        TeamDemand team = team("TEAMING");
        TeamTask pending = task(11L, "PENDING", MEMBER_ID, 3);
        TeamTask review = task(12L, "REVIEW", OTHER_ID, 5);
        TeamTask ownerTask = task(13L, "IN_PROGRESS", OWNER_ID, 1);
        when(teamMapper.selectByIdForUpdate(TEAM_ID)).thenReturn(team);
        when(taskMapper.selectOpenByTeamIdForUpdate(TEAM_ID))
            .thenReturn(List.of(pending, review, ownerTask));
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(taskMapper.selectById(11L)).thenReturn(cancelled(pending));
        when(taskMapper.selectById(12L)).thenReturn(cancelled(review));
        when(taskMapper.selectById(13L)).thenReturn(cancelled(ownerTask));
        when(snapshotSerializer.serialize(any(TeamTask.class))).thenReturn("snapshot");

        int changed = service.cancelOpenTasksForDissolution(
            new TeamTaskCommands.CancelOpenTasksForDissolution(
                TEAM_ID, OWNER_ID, "dissolve-request"));

        assertEquals(3, changed);
        verify(taskMapper, times(3)).update(any(), any());
        verify(indexService, times(3)).enqueueUpsert(
            eq("TASK"), anyLong(), any(), eq("snapshot"), eq("TEAM"),
            eq(TEAM_ID), anyLong());
        verify(auditMapper, times(3)).insert(any(TeamAgentAuditLog.class));
        verify(notificationService).create(eq(MEMBER_ID), eq(OWNER_ID),
            eq("TASK_CANCELLED"), anyString(), anyString(), eq(TEAM_ID));
        verify(notificationService).create(eq(OTHER_ID), eq(OWNER_ID),
            eq("TASK_CANCELLED"), anyString(), anyString(), eq(TEAM_ID));
        verify(notificationService, never()).create(eq(OWNER_ID), anyLong(),
            eq("TASK_CANCELLED"), anyString(), anyString(), anyLong());

        ArgumentCaptor<TeamAgentAuditLog> logs =
            ArgumentCaptor.forClass(TeamAgentAuditLog.class);
        verify(auditMapper, times(3)).insert(logs.capture());
        assertTrue(logs.getAllValues().stream().allMatch(log ->
            "TASK_CANCELLED_BY_TEAM_DISSOLUTION".equals(log.getEventType())));
        assertTrue(logs.getAllValues().stream().allMatch(log ->
            "dissolve-request".equals(log.getRequestId())));
    }

    @Test
    void nonOwnerCannotCancelTasksForDissolution() {
        when(teamMapper.selectByIdForUpdate(TEAM_ID)).thenReturn(team("TEAMING"));

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> service.cancelOpenTasksForDissolution(
                new TeamTaskCommands.CancelOpenTasksForDissolution(
                    TEAM_ID, MEMBER_ID, null)));

        assertEquals(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, error.getErrorCode());
        verify(taskMapper, never()).selectOpenByTeamIdForUpdate(anyLong());
        verify(taskMapper, never()).update(any(), any());
        verify(auditMapper, never()).insert(any(TeamAgentAuditLog.class));
    }

    @Test
    void inactiveTeamCannotCancelTasks() {
        when(teamMapper.selectByIdForUpdate(TEAM_ID)).thenReturn(team("DISSOLVED"));

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> service.cancelOpenTasksForDissolution(
                new TeamTaskCommands.CancelOpenTasksForDissolution(
                    TEAM_ID, OWNER_ID, null)));

        assertEquals(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, error.getErrorCode());
        verify(taskMapper, never()).selectOpenByTeamIdForUpdate(anyLong());
    }

    @Test
    void memberReleaseOnlyTouchesOpenAssignmentsAndRetiresParticipation() {
        TeamDemand team = team("TEAMING");
        TeamTask open = task(21L, "IN_PROGRESS", MEMBER_ID, 4);
        TeamTask historical = task(22L, "COMPLETED", MEMBER_ID, 7);
        TeamTask released = task(21L, "IN_PROGRESS", null, 5);
        when(teamMapper.selectByIdForUpdate(TEAM_ID)).thenReturn(team);
        when(taskMapper.selectList(any())).thenReturn(List.of(open));
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(taskMapper.selectById(21L)).thenReturn(released);
        when(snapshotSerializer.serialize(released)).thenReturn("released-snapshot");
        when(participantMapper.update(any(), any())).thenReturn(1);

        int changed = service.releaseMemberTasks(
            new TeamTaskCommands.ReleaseMemberTasks(
                TEAM_ID, MEMBER_ID, MEMBER_ID, "MEMBER_LEFT", "leave-request"));

        assertEquals(1, changed);
        assertEquals(MEMBER_ID, historical.getAssignedTo());
        verify(taskMapper, times(1)).update(any(), any());
        verify(indexService).enqueueUpsert(
            eq("TASK"), eq(21L), any(), eq("released-snapshot"), eq("TEAM"),
            eq(TEAM_ID), eq(OWNER_ID));
        verify(participantMapper).update(any(), any());
        verify(notificationService).create(
            eq(OWNER_ID), eq(MEMBER_ID), eq("TASK_UNASSIGNED"),
            anyString(), anyString(), eq(TEAM_ID));

        ArgumentCaptor<TeamAgentAuditLog> log =
            ArgumentCaptor.forClass(TeamAgentAuditLog.class);
        verify(auditMapper).insert(log.capture());
        assertEquals("TASK_UNASSIGNED", log.getValue().getEventType());
        assertEquals("leave-request", log.getValue().getRequestId());
    }

    private static TeamDemand team(String status) {
        TeamDemand team = new TeamDemand();
        team.setId(TEAM_ID);
        team.setCreatorId(OWNER_ID);
        team.setTitle("竞赛队伍");
        team.setStatus(status);
        return team;
    }

    private static TeamTask task(Long id, String status, Long assignee, int version) {
        TeamTask task = new TeamTask();
        task.setId(id);
        task.setTeamId(TEAM_ID);
        task.setTaskTitle("任务" + id);
        task.setStatus(status);
        task.setAssignedTo(assignee);
        task.setCreatedBy(OWNER_ID);
        task.setVersion(version);
        return task;
    }

    private static TeamTask cancelled(TeamTask source) {
        TeamTask task = new TeamTask();
        task.setId(source.getId());
        task.setTeamId(source.getTeamId());
        task.setTaskTitle(source.getTaskTitle());
        task.setStatus("CANCELLED");
        task.setAssignedTo(source.getAssignedTo());
        task.setCreatedBy(source.getCreatedBy());
        task.setVersion(source.getVersion() + 1);
        return task;
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
