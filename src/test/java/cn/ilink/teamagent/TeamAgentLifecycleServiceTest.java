package cn.ilink.teamagent;

import cn.ilink.entity.TeamAgentAction;
import cn.ilink.entity.TeamAgentActionGroup;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.entity.TeamDemand;
import cn.ilink.mapper.TeamAgentActionGroupMapper;
import cn.ilink.mapper.TeamAgentActionMapper;
import cn.ilink.mapper.TeamAgentAuditLogMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import cn.ilink.mapper.TeamDemandMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamAgentLifecycleServiceTest {
    private TeamDemandMapper teamMapper;
    private TeamAgentActionGroupMapper groupMapper;
    private TeamAgentActionMapper actionMapper;
    private TeamAgentAuditLogMapper auditMapper;
    private TeamAgentRunMapper runMapper;
    private TeamAgentEventService eventService;
    private TeamAgentAdmissionController admission;
    private TeamAgentLifecycleService service;

    @BeforeEach
    void setUp() {
        teamMapper = mock(TeamDemandMapper.class);
        groupMapper = mock(TeamAgentActionGroupMapper.class);
        actionMapper = mock(TeamAgentActionMapper.class);
        auditMapper = mock(TeamAgentAuditLogMapper.class);
        runMapper = mock(TeamAgentRunMapper.class);
        eventService = mock(TeamAgentEventService.class);
        admission = mock(TeamAgentAdmissionController.class);
        service = new TeamAgentLifecycleService(
            teamMapper, groupMapper, actionMapper, auditMapper, runMapper,
            eventService, admission);
        when(teamMapper.selectByIdForUpdate(20L)).thenReturn(team());
    }

    @Test
    void memberRevocationCancelsPendingGroupQueuedRunAndReleasesPermit() {
        TeamAgentActionGroup group = pendingGroup();
        TeamAgentAction pendingAction = action(50L, TeamAgentAction.STATUS_PENDING,
            TeamAgentAction.UNDO_NOT_SUPPORTED);
        TeamAgentRun queued = run(60L, TeamAgentRun.STATUS_QUEUED, "KNOWLEDGE_QUICK");

        when(groupMapper.selectList(any())).thenReturn(List.of(group), List.of());
        when(groupMapper.selectByIdForUpdate(90L)).thenReturn(group);
        when(groupMapper.updateById(group)).thenReturn(1);
        when(actionMapper.selectList(any())).thenReturn(List.of(pendingAction));
        when(actionMapper.selectByIdForUpdate(50L)).thenReturn(pendingAction);
        when(actionMapper.updateById(pendingAction)).thenReturn(1);
        when(runMapper.selectList(any())).thenReturn(List.of(queued));
        when(runMapper.selectByIdForUpdate(60L)).thenReturn(queued);
        when(eventService.appendLocked(eq(queued), eq("run.cancelled"), any())).thenReturn(2L);
        when(runMapper.updateById(queued)).thenReturn(1);

        service.onMemberAccessRevoked(20L, 7L, "成员已移除");

        assertEquals(TeamAgentActionGroup.CANCELLED, group.getStatus());
        assertEquals("SYSTEM_CANCELLED", group.getDecisionType());
        assertNull(group.getClientRequestId());
        assertEquals(TeamAgentAction.STATUS_CANCELLED, pendingAction.getStatus());
        assertEquals(TeamAgentRun.STATUS_CANCELLED, queued.getStatus());
        verify(eventService).appendLocked(eq(queued), eq("run.cancelled"), any());
        verify(admission).release(60L, 20L, 7L);
        verify(auditMapper, atLeastOnce()).insert(any());
    }

    @Test
    void teamCloseStopsActionPlanningRunButAllowsKnowledgeRunToFinish() {
        TeamAgentRun knowledge = run(61L, TeamAgentRun.STATUS_RUNNING, "KNOWLEDGE_DEEP");
        TeamAgentRun actionPlan = run(62L, TeamAgentRun.STATUS_RUNNING, "ACTION_PLAN");
        when(groupMapper.selectList(any())).thenReturn(List.of());
        when(runMapper.selectList(any())).thenReturn(List.of(knowledge, actionPlan));
        when(runMapper.selectByIdForUpdate(62L)).thenReturn(actionPlan);
        when(runMapper.updateById(actionPlan)).thenReturn(1);

        service.onTeamClosed(20L);

        verify(runMapper, never()).selectByIdForUpdate(61L);
        verify(runMapper).selectByIdForUpdate(62L);
        assertEquals(TeamAgentRun.STATUS_RUNNING, knowledge.getStatus());
        assertNull(knowledge.getCancelRequestedAt());
        assertEquals(TeamAgentRun.STATUS_RUNNING, actionPlan.getStatus());
        assertTrue(actionPlan.getCancelRequestId().startsWith("system:"));
        assertNotNull(actionPlan.getCancelRequestedAt());
    }

    @Test
    void teamDissolutionCancelsAllQueuedRunsAndReleasesEachPermit() {
        TeamAgentRun first = run(63L, TeamAgentRun.STATUS_QUEUED, "KNOWLEDGE_QUICK");
        TeamAgentRun second = run(64L, TeamAgentRun.STATUS_QUEUED, "ACTION_PLAN");
        second.setUserId(8L);
        when(groupMapper.selectList(any())).thenReturn(List.of());
        when(runMapper.selectList(any())).thenReturn(List.of(first, second));
        when(runMapper.selectByIdForUpdate(63L)).thenReturn(first);
        when(runMapper.selectByIdForUpdate(64L)).thenReturn(second);
        when(eventService.appendLocked(any(), eq("run.cancelled"), any())).thenReturn(1L);
        when(runMapper.updateById(any())).thenReturn(1);

        service.onTeamDissolved(20L);

        ArgumentCaptor<TeamAgentRun> captor = ArgumentCaptor.forClass(TeamAgentRun.class);
        verify(runMapper, org.mockito.Mockito.times(2)).updateById(captor.capture());
        assertEquals(List.of(TeamAgentRun.STATUS_CANCELLED, TeamAgentRun.STATUS_CANCELLED),
            captor.getAllValues().stream().map(TeamAgentRun::getStatus).toList());
        verify(admission).release(63L, 20L, 7L);
        verify(admission).release(64L, 20L, 8L);
    }

    private TeamDemand team() {
        TeamDemand team = new TeamDemand();
        team.setId(20L);
        team.setTitle("系统队");
        team.setStatus("TEAMING");
        return team;
    }

    private TeamAgentActionGroup pendingGroup() {
        TeamAgentActionGroup group = new TeamAgentActionGroup();
        group.setId(90L);
        group.setTeamId(20L);
        group.setConversationId(80L);
        group.setRunId(81L);
        group.setInitiatorUserId(7L);
        group.setStatus(TeamAgentActionGroup.PENDING);
        group.setClientRequestId("old-request");
        group.setRequestHash("old-hash");
        group.setExpiresAt(new java.util.Date(System.currentTimeMillis() + 60_000));
        group.setVersion(2);
        return group;
    }

    private TeamAgentAction action(Long id, String status, String undoStatus) {
        TeamAgentAction action = new TeamAgentAction();
        action.setId(id);
        action.setGroupId(90L);
        action.setTeamId(20L);
        action.setSeq(1);
        action.setStatus(status);
        action.setUndoStatus(undoStatus);
        action.setVersion(1);
        return action;
    }

    private TeamAgentRun run(Long id, String status, String route) {
        TeamAgentRun run = new TeamAgentRun();
        run.setId(id);
        run.setTeamId(20L);
        run.setConversationId(80L);
        run.setUserId(7L);
        run.setStatus(status);
        run.setRequestedMode("AUTO");
        run.setResolvedRoute("ACTION_PLAN".equals(route) ? route : null);
        run.setVersion(2);
        run.setEventSeq(0L);
        return run;
    }
}
