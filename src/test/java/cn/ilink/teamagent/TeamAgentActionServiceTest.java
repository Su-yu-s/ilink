package cn.ilink.teamagent;

import cn.ilink.entity.TeamAgentAction;
import cn.ilink.entity.TeamAgentActionGroup;
import cn.ilink.entity.TeamAgentConversation;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.entity.TeamDemand;
import cn.ilink.mapper.TeamAgentActionGroupMapper;
import cn.ilink.mapper.TeamAgentActionMapper;
import cn.ilink.mapper.TeamAgentAuditLogMapper;
import cn.ilink.mapper.TeamAgentConversationMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import cn.ilink.mapper.TeamDemandMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamAgentActionServiceTest {
    private TeamAgentAuthorization authorization;
    private TeamDemandMapper teamMapper;
    private TeamAgentActionGroupMapper groupMapper;
    private TeamAgentActionMapper actionMapper;
    private TeamAgentConversationMapper conversationMapper;
    private TeamAgentRunMapper runMapper;
    private TeamAgentActionHandlerRegistry handlerRegistry;
    private TeamAgentActionService service;

    @BeforeEach
    void setUp() {
        authorization = mock(TeamAgentAuthorization.class);
        teamMapper = mock(TeamDemandMapper.class);
        groupMapper = mock(TeamAgentActionGroupMapper.class);
        actionMapper = mock(TeamAgentActionMapper.class);
        conversationMapper = mock(TeamAgentConversationMapper.class);
        runMapper = mock(TeamAgentRunMapper.class);
        handlerRegistry = mock(TeamAgentActionHandlerRegistry.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
            .thenAnswer(ignored -> new SimpleTransactionStatus());
        when(authorization.requireScope(20L, 7L)).thenReturn(scope());
        service = new TeamAgentActionService(
            authorization,
            teamMapper,
            groupMapper,
            actionMapper,
            mock(TeamAgentAuditLogMapper.class),
            conversationMapper,
            runMapper,
            handlerRegistry,
            new ObjectMapper(),
            transactionManager);
    }

    @Test
    void confirmationExecutesOnceAndOpensUndoWindow() {
        TeamAgentActionGroup group = group(TeamAgentActionGroup.PENDING, 2,
            new Date(System.currentTimeMillis() + 60_000));
        TeamAgentAction action = action(TeamAgentAction.STATUS_PENDING, 1, null);
        stubActiveSource();
        when(groupMapper.selectOne(any())).thenReturn(null);
        when(groupMapper.selectByIdForUpdate(90L)).thenReturn(group);
        when(groupMapper.selectById(90L)).thenReturn(group);
        when(groupMapper.updateById(group)).thenReturn(1);
        when(actionMapper.selectList(any())).thenReturn(List.of(action));
        when(actionMapper.selectByIdForUpdate(50L)).thenReturn(action);
        when(actionMapper.updateById(action)).thenReturn(1);
        when(handlerRegistry.execute(any(), anyLong(), any(), any()))
            .thenReturn(Map.of("taskId", 70L, "version", 3));
        when(handlerRegistry.supportsUndo(action)).thenReturn(true);

        Map<String, Object> result = service.confirm(20L, 7L, 90L,
            new TeamAgentRequests.ConfirmActions(List.of(50L), 2,
                "2bbd4b8f-81b8-46a3-94c4-6c700d19a98c", "live-editor-token"));

        assertEquals(TeamAgentActionGroup.EXECUTED, group.getStatus());
        assertEquals(TeamAgentAction.STATUS_EXECUTED, action.getStatus());
        assertEquals(TeamAgentAction.UNDO_AVAILABLE, action.getUndoStatus());
        assertEquals(TeamAgentActionGroup.EXECUTED, result.get("status"));
        verify(handlerRegistry).execute(any(), anyLong(), any(), eq("live-editor-token"));
    }

    @Test
    void expiredGroupBecomesExpiredAndNeverExecutesBusinessAction() {
        TeamAgentActionGroup group = group(TeamAgentActionGroup.PENDING, 2,
            new Date(System.currentTimeMillis() - 1_000));
        TeamAgentAction action = action(TeamAgentAction.STATUS_PENDING, 1, null);
        stubActiveSource();
        when(groupMapper.selectOne(any())).thenReturn(null);
        when(groupMapper.selectByIdForUpdate(90L)).thenReturn(group);
        when(groupMapper.selectById(90L)).thenReturn(group);
        when(groupMapper.updateById(group)).thenReturn(1);
        when(actionMapper.selectList(any())).thenReturn(List.of(action));
        when(actionMapper.selectByIdForUpdate(50L)).thenReturn(action);
        when(actionMapper.updateById(action)).thenReturn(1);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> service.confirm(20L, 7L, 90L,
                new TeamAgentRequests.ConfirmActions(List.of(50L), 2,
                    "d2611e13-348a-4dbd-8d6e-3a36f4db6d1e", null)));

        assertEquals(TeamAgentErrorCode.ACTION_GROUP_EXPIRED, error.getErrorCode());
        assertEquals(TeamAgentActionGroup.EXPIRED, group.getStatus());
        assertEquals(TeamAgentAction.STATUS_EXPIRED, action.getStatus());
        verify(handlerRegistry, never()).execute(any(), anyLong(), any(), any());
    }

    @Test
    void terminalGroupCannotBeConfirmedByAStalePage() {
        TeamAgentActionGroup group = group(TeamAgentActionGroup.EXECUTED, 4,
            new Date(System.currentTimeMillis() + 60_000));
        stubActiveSource();
        when(groupMapper.selectOne(any())).thenReturn(null);
        when(groupMapper.selectByIdForUpdate(90L)).thenReturn(group);
        when(groupMapper.selectById(90L)).thenReturn(group);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> service.confirm(20L, 7L, 90L,
                new TeamAgentRequests.ConfirmActions(List.of(50L), 2,
                    "76b9de4c-06ff-4bb6-a6ee-f4056249c3c2", null)));

        assertEquals(TeamAgentErrorCode.ACTION_ALREADY_FINALIZED, error.getErrorCode());
        verify(handlerRegistry, never()).execute(any(), anyLong(), any(), any());
    }

    @Test
    void dismissReplayReturnsSameTerminalStateWithoutRepeatingWrites() {
        TeamAgentActionGroup group = group(TeamAgentActionGroup.PENDING, 2,
            new Date(System.currentTimeMillis() + 60_000));
        TeamAgentAction action = action(TeamAgentAction.STATUS_PENDING, 1, null);
        stubActiveSource();
        stubPendingDecision(group, action);
        TeamAgentRequests.DismissActions request = new TeamAgentRequests.DismissActions(
            2, "1941f0f8-5f40-4f76-b3e9-7973d89890d2");

        Map<String, Object> first = service.dismiss(20L, 7L, 90L, request);
        Map<String, Object> replay = service.dismiss(20L, 7L, 90L, request);

        assertEquals(TeamAgentActionGroup.CANCELLED, first.get("status"));
        assertEquals(TeamAgentActionGroup.CANCELLED, replay.get("status"));
        assertEquals(TeamAgentAction.STATUS_CANCELLED, action.getStatus());
        verify(groupMapper, times(1)).updateById(group);
        verify(actionMapper, times(1)).updateById(action);
    }

    @Test
    void dismissRejectsRequestIdReuseWithDifferentVersion() {
        TeamAgentActionGroup group = group(TeamAgentActionGroup.PENDING, 2,
            new Date(System.currentTimeMillis() + 60_000));
        TeamAgentAction action = action(TeamAgentAction.STATUS_PENDING, 1, null);
        stubActiveSource();
        stubPendingDecision(group, action);
        String requestId = "a649bca4-d6b7-4d03-b815-406667ca72db";

        service.dismiss(20L, 7L, 90L,
            new TeamAgentRequests.DismissActions(2, requestId));
        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> service.dismiss(20L, 7L, 90L,
                new TeamAgentRequests.DismissActions(3, requestId)));

        assertEquals(TeamAgentErrorCode.IDEMPOTENCY_KEY_REUSED, error.getErrorCode());
        assertEquals(TeamAgentActionGroup.CANCELLED, group.getStatus());
        verify(groupMapper, times(1)).updateById(group);
    }

    @Test
    void undoReplayReturnsTheFirstSuccessfulResultWithoutCallingHandlerAgain() {
        TeamAgentActionGroup group = group(TeamAgentActionGroup.EXECUTED, 4,
            new Date(System.currentTimeMillis() + 60_000));
        TeamAgentAction action = undoableAction(3);
        stubExecutedUndoSource(group, action, action);
        when(handlerRegistry.undo(any(), anyLong(), any(), any()))
            .thenReturn(Map.of("restoredPriority", 2));
        TeamAgentRequests.UndoAction request = new TeamAgentRequests.UndoAction(
            3, "e42b7e73-6c97-4c62-ae0e-cbde98b2630a", "live-editor-token");

        Map<String, Object> first = service.undo(20L, 7L, 50L, request);
        Map<String, Object> replay = service.undo(20L, 7L, 50L, request);

        assertEquals(TeamAgentAction.UNDONE, first.get("undoStatus"));
        assertEquals(TeamAgentAction.UNDONE, replay.get("undoStatus"));
        assertEquals(TeamAgentAction.UNDONE, action.getUndoStatus());
        verify(handlerRegistry, times(1)).undo(any(), anyLong(), any(),
            eq("live-editor-token"));
        verify(actionMapper, times(2)).updateById(action);
    }

    @Test
    void undoRejectsRequestIdReuseWithDifferentVersion() {
        TeamAgentActionGroup group = group(TeamAgentActionGroup.EXECUTED, 4,
            new Date(System.currentTimeMillis() + 60_000));
        TeamAgentAction action = undoableAction(3);
        stubExecutedUndoSource(group, action, action);
        when(handlerRegistry.undo(any(), anyLong(), any(), any())).thenReturn(Map.of());
        String requestId = "6a541292-e89c-45f6-8874-09930fa472a7";

        service.undo(20L, 7L, 50L,
            new TeamAgentRequests.UndoAction(3, requestId, null));
        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> service.undo(20L, 7L, 50L,
                new TeamAgentRequests.UndoAction(4, requestId, null)));

        assertEquals(TeamAgentErrorCode.IDEMPOTENCY_KEY_REUSED, error.getErrorCode());
        verify(handlerRegistry, times(1)).undo(any(), anyLong(), any(), any());
    }

    @Test
    void expiredUndoIsPersistedAfterTheBusinessTransactionRollsBack() {
        TeamAgentActionGroup group = group(TeamAgentActionGroup.EXECUTED, 4,
            new Date(System.currentTimeMillis() + 60_000));
        TeamAgentAction action = undoableAction(3);
        action.setUndoDeadline(new Date(System.currentTimeMillis() - 1_000));
        TeamAgentAction inTransaction = copyAction(action);
        stubExecutedUndoSource(group, action, inTransaction, action);
        when(actionMapper.updateById(any())).thenReturn(1);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> service.undo(20L, 7L, 50L,
                new TeamAgentRequests.UndoAction(3,
                    "8dec335b-020d-4b33-975c-532ae9f6be5b", null)));

        assertEquals(TeamAgentErrorCode.UNDO_EXPIRED, error.getErrorCode());
        assertEquals(TeamAgentAction.UNDO_EXPIRED, action.getUndoStatus());
        verify(handlerRegistry, never()).undo(any(), anyLong(), any(), any());
        verify(actionMapper, times(2)).updateById(any());
    }

    @Test
    void irrecoverableUndoConflictIsFinalizedWithoutPartialCompensation() {
        TeamAgentActionGroup group = group(TeamAgentActionGroup.EXECUTED, 4,
            new Date(System.currentTimeMillis() + 60_000));
        TeamAgentAction action = undoableAction(3);
        TeamAgentAction inTransaction = copyAction(action);
        stubExecutedUndoSource(group, action, inTransaction, action);
        when(actionMapper.updateById(any())).thenReturn(1);
        when(handlerRegistry.undo(any(), anyLong(), any(), any()))
            .thenThrow(new TeamAgentException(TeamAgentErrorCode.UNDO_CONFLICT,
                "任务已被后续操作修改"));

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> service.undo(20L, 7L, 50L,
                new TeamAgentRequests.UndoAction(3,
                    "e7ba1781-838e-488e-8d97-e5e539c38046", null)));

        assertEquals(TeamAgentErrorCode.UNDO_CONFLICT, error.getErrorCode());
        assertEquals(TeamAgentAction.UNDO_FAILED, action.getUndoStatus());
        assertEquals(TeamAgentErrorCode.UNDO_CONFLICT.name(), action.getUndoErrorCode());
        verify(handlerRegistry, times(1)).undo(any(), anyLong(), any(), any());
        verify(actionMapper, times(2)).updateById(any());
    }

    @Test
    void staleUndoVersionLeavesTheUndoWindowAvailableForRetry() {
        TeamAgentActionGroup group = group(TeamAgentActionGroup.EXECUTED, 4,
            new Date(System.currentTimeMillis() + 60_000));
        TeamAgentAction action = undoableAction(3);
        stubExecutedUndoSource(group, action, action);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> service.undo(20L, 7L, 50L,
                new TeamAgentRequests.UndoAction(2,
                    "9d7bf103-eabf-44ba-a1c1-f462f23c4c9c", null)));

        assertEquals(TeamAgentErrorCode.ACTION_VERSION_CONFLICT, error.getErrorCode());
        assertEquals(TeamAgentAction.UNDO_AVAILABLE, action.getUndoStatus());
        assertEquals(3, action.getVersion());
        verify(handlerRegistry, never()).undo(any(), anyLong(), any(), any());
        verify(actionMapper, never()).updateById(any());
    }

    private void stubPendingDecision(TeamAgentActionGroup group, TeamAgentAction action) {
        when(groupMapper.selectOne(any())).thenReturn(null);
        when(groupMapper.selectByIdForUpdate(90L)).thenReturn(group);
        when(groupMapper.selectById(90L)).thenReturn(group);
        when(groupMapper.updateById(group)).thenReturn(1);
        when(actionMapper.selectList(any())).thenReturn(List.of(action));
        when(actionMapper.selectByIdForUpdate(50L)).thenReturn(action);
        when(actionMapper.updateById(action)).thenReturn(1);
    }

    private void stubExecutedUndoSource(TeamAgentActionGroup group,
                                        TeamAgentAction initial,
                                        TeamAgentAction... locked) {
        stubActiveSource();
        when(actionMapper.selectById(50L)).thenReturn(initial);
        when(groupMapper.selectById(90L)).thenReturn(group);
        when(groupMapper.selectByIdForUpdate(90L)).thenReturn(group);
        when(actionMapper.selectByIdForUpdate(50L))
            .thenReturn(locked[0], java.util.Arrays.copyOfRange(locked, 1, locked.length));
        when(actionMapper.updateById(any())).thenReturn(1);
    }

    private TeamAgentAction undoableAction(int version) {
        TeamAgentAction action = action(TeamAgentAction.STATUS_EXECUTED, version,
            TeamAgentAction.UNDO_AVAILABLE);
        action.setUndoDeadline(new Date(System.currentTimeMillis() + 60_000));
        return action;
    }

    private TeamAgentAction copyAction(TeamAgentAction source) {
        TeamAgentAction copy = action(source.getStatus(), source.getVersion(),
            source.getUndoStatus());
        copy.setUndoDeadline(source.getUndoDeadline());
        copy.setUndoRequestId(source.getUndoRequestId());
        copy.setUndoRequestHash(source.getUndoRequestHash());
        return copy;
    }

    private void stubActiveSource() {
        TeamDemand team = new TeamDemand();
        team.setId(20L);
        team.setStatus("TEAMING");
        when(teamMapper.selectByIdForUpdate(20L)).thenReturn(team);
        TeamAgentConversation conversation = new TeamAgentConversation();
        conversation.setId(30L);
        conversation.setTeamId(20L);
        conversation.setOwnerUserId(7L);
        conversation.setStatus(TeamAgentConversation.STATUS_ACTIVE);
        when(conversationMapper.selectById(30L)).thenReturn(conversation);
        TeamAgentRun run = new TeamAgentRun();
        run.setId(40L);
        run.setTeamId(20L);
        run.setUserId(7L);
        run.setConversationId(30L);
        run.setStatus(TeamAgentRun.STATUS_COMPLETED);
        when(runMapper.selectById(40L)).thenReturn(run);
    }

    private TeamAgentActionGroup group(String status, Integer version, Date expiresAt) {
        TeamAgentActionGroup group = new TeamAgentActionGroup();
        group.setId(90L);
        group.setTeamId(20L);
        group.setConversationId(30L);
        group.setRunId(40L);
        group.setInitiatorUserId(7L);
        group.setStatus(status);
        group.setVersion(version);
        group.setExpiresAt(expiresAt);
        group.setCreatedAt(new Date());
        return group;
    }

    private TeamAgentAction action(String status, Integer version, String undoStatus) {
        TeamAgentAction action = new TeamAgentAction();
        action.setId(50L);
        action.setGroupId(90L);
        action.setTeamId(20L);
        action.setSeq(1);
        action.setSchemaVersion(TeamAgentActionSchema.VERSION);
        action.setActionType("SET_TASK_PRIORITY");
        action.setTargetType("TASK");
        action.setTargetId(70L);
        action.setStatus(status);
        action.setUndoStatus(undoStatus);
        action.setAfterJson("{\"priority\":1}");
        action.setVersion(version);
        return action;
    }

    private TeamAgentScope scope() {
        return new TeamAgentScope(
            7L, 20L, "系统队", "TEAMING", TeamAgentScope.TeamRole.LEADER,
            TeamAgentScope.TaskReadMode.ALL, null, null, "自定义竞赛",
            Date.from(Instant.parse("2026-12-31T00:00:00Z")), Instant.now());
    }
}
