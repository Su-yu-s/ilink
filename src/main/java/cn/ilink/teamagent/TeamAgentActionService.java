package cn.ilink.teamagent;

import cn.ilink.entity.TeamAgentAction;
import cn.ilink.entity.TeamAgentActionGroup;
import cn.ilink.entity.TeamAgentAuditLog;
import cn.ilink.entity.TeamAgentConversation;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.entity.TeamDemand;
import cn.ilink.mapper.TeamAgentActionGroupMapper;
import cn.ilink.mapper.TeamAgentActionMapper;
import cn.ilink.mapper.TeamAgentAuditLogMapper;
import cn.ilink.mapper.TeamAgentConversationMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import cn.ilink.mapper.TeamDemandMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The application-service boundary for Agent action decisions. Business
 * actions and their state transitions are committed atomically; a durable
 * failure transition is written only after a business transaction rolls back.
 */
@Service
public class TeamAgentActionService {
    private static final Set<String> GROUP_STATUSES = Set.of(
        TeamAgentActionGroup.PENDING, TeamAgentActionGroup.EXECUTED,
        TeamAgentActionGroup.FAILED, TeamAgentActionGroup.EXPIRED,
        TeamAgentActionGroup.CANCELLED);
    private static final Set<String> UNDOABLE = Set.of(
        "CREATE_TASK", "CREATE_SUBTASK", "ASSIGN_TASK",
        "SET_TASK_PRIORITY", "SET_TASK_DEADLINE",
        "CREATE_TEAM_DOCUMENT", "APPEND_TEAM_DOCUMENT", "CREATE_TEAM_TABLE",
        "UPDATE_TEAM_TABLE_ROWS", "CREATE_MATERIAL_REQUIREMENT",
        "LINK_MATERIAL_EVIDENCE", "SET_MATERIAL_REVIEW");
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final TeamAgentAuthorization authorization;
    private final TeamDemandMapper teamMapper;
    private final TeamAgentActionGroupMapper groupMapper;
    private final TeamAgentActionMapper actionMapper;
    private final TeamAgentAuditLogMapper auditMapper;
    private final TeamAgentConversationMapper conversationMapper;
    private final TeamAgentRunMapper runMapper;
    private final TeamAgentActionHandlerRegistry handlerRegistry;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate failureTransaction;

    public TeamAgentActionService(TeamAgentAuthorization authorization,
                                  TeamDemandMapper teamMapper,
                                  TeamAgentActionGroupMapper groupMapper,
                                  TeamAgentActionMapper actionMapper,
                                  TeamAgentAuditLogMapper auditMapper,
                                  TeamAgentConversationMapper conversationMapper,
                                  TeamAgentRunMapper runMapper,
                                  TeamAgentActionHandlerRegistry handlerRegistry,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.authorization = authorization;
        this.teamMapper = teamMapper;
        this.groupMapper = groupMapper;
        this.actionMapper = actionMapper;
        this.auditMapper = auditMapper;
        this.conversationMapper = conversationMapper;
        this.runMapper = runMapper;
        this.handlerRegistry = handlerRegistry;
        this.objectMapper = objectMapper;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.failureTransaction = new TransactionTemplate(transactionManager);
        this.failureTransaction.setPropagationBehavior(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String, Object> list(Long teamId, Long userId, Long conversationId,
                                    String rawStatus, int limit, String cursor) {
        authorization.requireScope(teamId, userId);
        String status = rawStatus == null || rawStatus.isBlank()
            ? null : rawStatus.trim().toUpperCase(Locale.ROOT);
        if (status != null && !GROUP_STATUSES.contains(status)) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                "动作组状态不合法");
        }
        if (conversationId != null) {
            requireActiveConversation(teamId, userId, conversationId);
        }
        expireOverdue(teamId, userId);

        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        LambdaQueryWrapper<TeamAgentActionGroup> query =
            new LambdaQueryWrapper<TeamAgentActionGroup>()
                .eq(TeamAgentActionGroup::getTeamId, teamId)
                .eq(TeamAgentActionGroup::getInitiatorUserId, userId);
        if (conversationId != null) {
            query.eq(TeamAgentActionGroup::getConversationId, conversationId);
        }
        if (status != null) query.eq(TeamAgentActionGroup::getStatus, status);
        Cursor decoded = decodeCursor(cursor);
        if (decoded != null) {
            query.and(w -> w.lt(TeamAgentActionGroup::getCreatedAt, new Date(decoded.time()))
                .or(inner -> inner.eq(TeamAgentActionGroup::getCreatedAt, new Date(decoded.time()))
                    .lt(TeamAgentActionGroup::getId, decoded.id())));
        }
        query.orderByDesc(TeamAgentActionGroup::getCreatedAt)
            .orderByDesc(TeamAgentActionGroup::getId)
            .last("LIMIT " + (safeLimit + 1));
        List<TeamAgentActionGroup> rows = groupMapper.selectList(query);
        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) rows = rows.subList(0, safeLimit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (TeamAgentActionGroup group : rows) {
            if (conversationVisible(group)) items.add(groupView(group, false));
        }
        String next = hasMore && !rows.isEmpty()
            ? encodeCursor(rows.get(rows.size() - 1)) : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("nextCursor", next);
        return result;
    }

    public Map<String, Object> detail(Long teamId, Long userId, Long groupId) {
        authorization.requireScope(teamId, userId);
        TeamAgentActionGroup group = requireOwnedGroup(teamId, userId, groupId);
        expireGroupIfNeeded(group);
        group = requireOwnedGroup(teamId, userId, groupId);
        return groupView(group, true);
    }

    public Map<String, Object> confirm(Long teamId, Long userId, Long groupId,
                                       TeamAgentRequests.ConfirmActions request) {
        authorization.requireScope(teamId, userId);
        if (request == null || request.selectedActionIds() == null
            || request.selectedActionIds().isEmpty()
            || request.expectedGroupVersion() == null) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                "请选择动作并提交动作组版本");
        }
        String requestId = TeamAgentConversationService.requireUuid(
            request.clientRequestId(), "clientRequestId");
        List<Long> selected = request.selectedActionIds().stream()
            .filter(Objects::nonNull).distinct().sorted().toList();
        if (selected.isEmpty() || selected.size() != request.selectedActionIds().size()) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_SELECTION_INVALID,
                "选中动作不能为空且不能重复");
        }
        String hash = decisionHash("CONFIRM", groupId, selected,
            request.expectedGroupVersion());
        Map<String, Object> replay = replayDecision(
            teamId, userId, groupId, requestId, hash);
        if (replay != null) return replay;
        try {
            writeTransaction.executeWithoutResult(status ->
                applyConfirmation(teamId, userId, groupId, requestId, hash,
                    selected, request.expectedGroupVersion(), request.existingLeaseToken()));
        } catch (DuplicateKeyException race) {
            Map<String, Object> raced = replayDecision(
                teamId, userId, groupId, requestId, hash);
            if (raced != null) return raced;
            throw idempotencyReused();
        } catch (TeamAgentException failure) {
            if (failure.getErrorCode() == TeamAgentErrorCode.ACTION_GROUP_EXPIRED) {
                expireGroupAfterFailedDecision(groupId);
                throw failure;
            }
            finalizeFailure(teamId, userId, groupId, requestId, hash, selected,
                request.expectedGroupVersion(), failure);
            throw failure;
        }
        return detail(teamId, userId, groupId);
    }

    public Map<String, Object> dismiss(Long teamId, Long userId, Long groupId,
                                       TeamAgentRequests.DismissActions request) {
        authorization.requireScope(teamId, userId);
        if (request == null || request.expectedGroupVersion() == null) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                "请提交动作组版本");
        }
        String requestId = TeamAgentConversationService.requireUuid(
            request.clientRequestId(), "clientRequestId");
        String hash = decisionHash("DISMISS", groupId, List.of(),
            request.expectedGroupVersion());
        Map<String, Object> replay = replayDecision(
            teamId, userId, groupId, requestId, hash);
        if (replay != null) return replay;
        try {
            writeTransaction.executeWithoutResult(status ->
                applyDismiss(teamId, userId, groupId, requestId, hash,
                    request.expectedGroupVersion()));
        } catch (DuplicateKeyException race) {
            Map<String, Object> raced = replayDecision(
                teamId, userId, groupId, requestId, hash);
            if (raced != null) return raced;
            throw idempotencyReused();
        } catch (TeamAgentException failure) {
            if (failure.getErrorCode() == TeamAgentErrorCode.ACTION_GROUP_EXPIRED) {
                expireGroupAfterFailedDecision(groupId);
                throw failure;
            }
            finalizeFailure(teamId, userId, groupId, requestId, hash, List.of(),
                request.expectedGroupVersion(), failure);
            throw failure;
        }
        return detail(teamId, userId, groupId);
    }

    public Map<String, Object> undo(Long teamId, Long userId, Long actionId,
                                    TeamAgentRequests.UndoAction request) {
        authorization.requireScope(teamId, userId);
        if (request == null || request.expectedActionVersion() == null) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                "请提交动作版本");
        }
        String requestId = TeamAgentConversationService.requireUuid(
            request.clientRequestId(), "clientRequestId");
        TeamAgentAction initial = actionMapper.selectById(actionId);
        if (initial == null || !Objects.equals(initial.getTeamId(), teamId)) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_NOT_FOUND,
                "动作不存在");
        }
        TeamAgentActionGroup initialGroup = requireOwnedGroup(teamId, userId,
            initial.getGroupId());
        if (!Objects.equals(initialGroup.getInitiatorUserId(), userId)) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_NOT_FOUND,
                "动作不存在");
        }
        String hash = undoHash(actionId, request.expectedActionVersion());
        if (requestId.equals(initial.getUndoRequestId())) {
            if (!Objects.equals(hash, initial.getUndoRequestHash())) {
                throw idempotencyReused();
            }
            return actionView(initial);
        }
        if (initial.getUndoStatus() != null
            && !TeamAgentAction.UNDO_AVAILABLE.equals(initial.getUndoStatus())
            && !TeamAgentAction.UNDOING.equals(initial.getUndoStatus())) {
            throw new TeamAgentException(TeamAgentErrorCode.UNDO_ALREADY_FINALIZED,
                "该动作的撤销已经结束");
        }
        try {
            writeTransaction.executeWithoutResult(status ->
                applyUndo(teamId, userId, actionId, requestId, hash,
                    request.expectedActionVersion(), request.existingLeaseToken()));
        } catch (TeamAgentException failure) {
            if (failure.getErrorCode() == TeamAgentErrorCode.UNDO_ALREADY_FINALIZED
                || failure.getErrorCode() == TeamAgentErrorCode.ACTION_NOT_FOUND) {
                throw failure;
            }
            if (failure.getErrorCode() == TeamAgentErrorCode.UNDO_EXPIRED) {
                finalizeUndoExpiry(teamId, userId, actionId, requestId);
                throw failure;
            }
            if (failure.getErrorCode() == TeamAgentErrorCode.UNDO_CONFLICT) {
                finalizeUndoFailure(teamId, userId, actionId, requestId, hash, failure);
            }
            throw failure;
        }
        return actionView(actionMapper.selectById(actionId));
    }

    private void applyConfirmation(Long teamId, Long userId, Long groupId,
                                   String requestId, String hash,
                                   List<Long> selectedIds, Integer expectedVersion,
                                   String existingLeaseToken) {
        lockActionableTeam(teamId, userId);
        TeamAgentActionGroup group = groupMapper.selectByIdForUpdate(groupId);
        requireGroupOwnership(group, teamId, userId);
        if (isReplay(group, requestId, hash)) return;
        requirePendingVersion(group, expectedVersion);
        if (expired(group)) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_GROUP_EXPIRED,
                "动作组已超过确认期限");
        }
        requireCompletedRunAndConversation(group);
        List<TeamAgentAction> actions = actionsForUpdate(groupId);
        Map<Long, TeamAgentAction> byId = new LinkedHashMap<>();
        for (TeamAgentAction action : actions) byId.put(action.getId(), action);
        Set<Long> selectedSet = new HashSet<>(selectedIds);
        List<TeamAgentAction> selected = new ArrayList<>();
        for (Long id : selectedIds) {
            TeamAgentAction action = byId.get(id);
            if (action == null || !TeamAgentAction.STATUS_PENDING.equals(action.getStatus())) {
                throw new TeamAgentException(TeamAgentErrorCode.ACTION_SELECTION_INVALID,
                    "选中动作不属于该动作组或已不可执行");
            }
        }
        for (TeamAgentAction action : actions) {
            if (selectedSet.contains(action.getId())) selected.add(action);
        }
        validateSelection(actions, selected);
        for (TeamAgentAction action : selected) {
            assertCooldown(group, action);
        }

        Date now = new Date();
        group.setStatus(TeamAgentActionGroup.CONFIRMING);
        group.setClientRequestId(requestId);
        group.setDecisionType("CONFIRM");
        group.setRequestHash(hash);
        group.setDecidedAt(now);
        group.setUpdatedAt(now);
        group.setVersion(next(group.getVersion()));
        if (groupMapper.updateById(group) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_VERSION_CONFLICT,
                "动作组已被其他请求更新");
        }

        for (TeamAgentAction action : actions) {
            if (!selected.contains(action)) {
                cancelAction(action, now);
            }
        }
        for (TeamAgentAction action : selected) {
            action.setStatus(TeamAgentAction.STATUS_CONFIRMING);
            action.setUpdatedAt(now);
            action.setVersion(next(action.getVersion()));
            if (actionMapper.updateById(action) != 1) {
                throw new TeamAgentException(TeamAgentErrorCode.ACTION_VERSION_CONFLICT,
                    "动作状态已变化");
            }
            Map<String, Object> result;
            try {
                result = handlerRegistry.execute(action, userId, requestId, existingLeaseToken);
            } catch (TeamAgentException failure) {
                throw failure;
            }
            action.setStatus(TeamAgentAction.STATUS_EXECUTED);
            action.setResultJson(write(result));
            action.setExecutedAt(new Date());
            action.setUpdatedAt(new Date());
            action.setVersion(next(action.getVersion()));
            if (UNDOABLE.contains(action.getActionType())
                && handlerRegistry.supportsUndo(action)) {
                action.setUndoStatus(TeamAgentAction.UNDO_AVAILABLE);
                action.setUndoDeadline(Date.from(Instant.now().plusSeconds(600)));
            } else {
                action.setUndoStatus(TeamAgentAction.UNDO_NOT_SUPPORTED);
            }
            if (actionMapper.updateById(action) != 1) {
                throw new TeamAgentException(TeamAgentErrorCode.ACTION_VERSION_CONFLICT,
                    "动作结果无法保存");
            }
            audit(group, action, userId, requestId, "ACTION_EXECUTED", "SUCCESS",
                Map.of("actionType", action.getActionType(),
                    "targetId", Objects.toString(action.getTargetId(), ""),
                    "result", result));
        }
        group.setStatus(TeamAgentActionGroup.EXECUTED);
        group.setExecutedAt(new Date());
        group.setUpdatedAt(new Date());
        group.setErrorCode(null);
        group.setErrorMessage(null);
        group.setVersion(next(group.getVersion()));
        if (groupMapper.updateById(group) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                "动作组终态保存失败");
        }
        audit(group, null, userId, requestId, "ACTION_GROUP_CONFIRMED", "SUCCESS",
            Map.of("selectedActionIds", selectedIds));
    }

    private void applyDismiss(Long teamId, Long userId, Long groupId,
                              String requestId, String hash, Integer expectedVersion) {
        lockActionableTeam(teamId, userId);
        TeamAgentActionGroup group = groupMapper.selectByIdForUpdate(groupId);
        requireGroupOwnership(group, teamId, userId);
        if (isReplay(group, requestId, hash)) return;
        requirePendingVersion(group, expectedVersion);
        if (expired(group)) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_GROUP_EXPIRED,
                "动作组已超过确认期限");
        }
        requireCompletedRunAndConversation(group);
        List<TeamAgentAction> actions = actionsForUpdate(groupId);
        Date now = new Date();
        group.setStatus(TeamAgentActionGroup.CANCELLED);
        group.setClientRequestId(requestId);
        group.setDecisionType("DISMISS");
        group.setRequestHash(hash);
        group.setDecidedAt(now);
        group.setUpdatedAt(now);
        group.setVersion(next(group.getVersion()));
        if (groupMapper.updateById(group) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_VERSION_CONFLICT,
                "动作组已被其他请求更新");
        }
        for (TeamAgentAction action : actions) {
            if (TeamAgentAction.STATUS_PENDING.equals(action.getStatus())) {
                cancelAction(action, now);
            }
        }
        audit(group, null, userId, requestId, "ACTION_GROUP_DISMISSED", "SUCCESS",
            Map.of());
    }

    private void applyUndo(Long teamId, Long userId, Long actionId,
                           String requestId, String hash, Integer expectedVersion,
                           String existingLeaseToken) {
        lockActionableTeam(teamId, userId);
        TeamAgentAction snapshot = actionMapper.selectById(actionId);
        if (snapshot == null || !Objects.equals(snapshot.getTeamId(), teamId)) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_NOT_FOUND,
                "动作不存在");
        }
        TeamAgentActionGroup group = groupMapper.selectByIdForUpdate(snapshot.getGroupId());
        requireGroupOwnership(group, teamId, userId);
        requireActiveConversation(group.getTeamId(), userId, group.getConversationId());
        if (!TeamAgentActionGroup.EXECUTED.equals(group.getStatus())) {
            throw new TeamAgentException(TeamAgentErrorCode.UNDO_NOT_AVAILABLE,
                "动作组尚未成功执行");
        }
        TeamAgentAction action = actionMapper.selectByIdForUpdate(actionId);
        if (action == null || !Objects.equals(action.getGroupId(), group.getId())) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_NOT_FOUND,
                "动作不存在");
        }
        if (requestId.equals(action.getUndoRequestId())) {
            if (!Objects.equals(hash, action.getUndoRequestHash())) throw idempotencyReused();
            return;
        }
        if (!TeamAgentAction.STATUS_EXECUTED.equals(action.getStatus())
            || !TeamAgentAction.UNDO_AVAILABLE.equals(action.getUndoStatus())) {
            throw new TeamAgentException(TeamAgentErrorCode.UNDO_ALREADY_FINALIZED,
                "该动作已不能撤销");
        }
        if (!Objects.equals(currentVersion(action.getVersion()), expectedVersion)) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_VERSION_CONFLICT,
                "动作状态已变化，请刷新后重试");
        }
        if (action.getUndoDeadline() == null || !action.getUndoDeadline().after(new Date())) {
            action.setUndoStatus(TeamAgentAction.UNDO_EXPIRED);
            action.setUpdatedAt(new Date());
            action.setVersion(next(action.getVersion()));
            actionMapper.updateById(action);
            throw new TeamAgentException(TeamAgentErrorCode.UNDO_EXPIRED,
                "撤销期限已结束");
        }
        Date now = new Date();
        action.setUndoStatus(TeamAgentAction.UNDOING);
        action.setUndoRequestId(requestId);
        action.setUndoRequestHash(hash);
        action.setUpdatedAt(now);
        action.setVersion(next(action.getVersion()));
        if (actionMapper.updateById(action) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_VERSION_CONFLICT,
                "撤销请求发生冲突");
        }
        Map<String, Object> result = handlerRegistry.undo(
            action, userId, requestId, existingLeaseToken);
        action.setUndoStatus(TeamAgentAction.UNDONE);
        action.setUndoResultJson(write(result));
        action.setUndoErrorCode(null);
        action.setUndoErrorMessage(null);
        action.setUndoneAt(new Date());
        action.setUpdatedAt(new Date());
        action.setVersion(next(action.getVersion()));
        if (actionMapper.updateById(action) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                "撤销结果保存失败");
        }
        audit(group, action, userId, requestId, "ACTION_UNDONE", "SUCCESS",
            Map.of("result", result));
    }

    private void finalizeFailure(Long teamId, Long userId, Long groupId,
                                 String requestId, String hash, List<Long> selectedIds,
                                 Integer expectedVersion, TeamAgentException failure) {
        try {
            failureTransaction.executeWithoutResult(status -> {
                TeamAgentActionGroup group = groupMapper.selectByIdForUpdate(groupId);
                if (group == null || !Objects.equals(group.getTeamId(), teamId)
                    || !Objects.equals(group.getInitiatorUserId(), userId)) return;
                if (!TeamAgentActionGroup.PENDING.equals(group.getStatus())
                    && !TeamAgentActionGroup.CONFIRMING.equals(group.getStatus())) return;
                if (group.getRequestHash() != null && !Objects.equals(group.getRequestHash(), hash)) {
                    return;
                }
                Date now = new Date();
                group.setStatus(TeamAgentActionGroup.FAILED);
                if (group.getClientRequestId() == null) {
                    group.setClientRequestId(requestId);
                    group.setDecisionType("CONFIRM");
                    group.setRequestHash(hash);
                    group.setDecidedAt(now);
                }
                group.setErrorCode(failure.getErrorCode().name());
                group.setErrorMessage(TeamAgentMarkdownSanitizer.sanitize(
                    failure.getMessage(), 1000));
                group.setUpdatedAt(now);
                group.setVersion(next(group.getVersion()));
                groupMapper.updateById(group);
                Set<Long> selected = new HashSet<>(selectedIds);
                for (TeamAgentAction action : actionsForUpdate(groupId)) {
                    if (TeamAgentAction.STATUS_PENDING.equals(action.getStatus())
                        || TeamAgentAction.STATUS_CONFIRMING.equals(action.getStatus())) {
                        action.setStatus(selected.contains(action.getId())
                            ? TeamAgentAction.STATUS_FAILED
                            : TeamAgentAction.STATUS_CANCELLED);
                        if (selected.contains(action.getId())) {
                            action.setErrorCode(failure.getErrorCode().name());
                            action.setErrorMessage(TeamAgentMarkdownSanitizer.sanitize(
                                failure.getMessage(), 1000));
                        }
                        action.setUpdatedAt(now);
                        action.setVersion(next(action.getVersion()));
                        actionMapper.updateById(action);
                    }
                }
                audit(group, null, userId, requestId, "ACTION_GROUP_FAILED", "FAILED",
                    Map.of("errorCode", failure.getErrorCode().name(),
                        "errorMessage", Objects.toString(failure.getMessage(), ""),
                        "expectedGroupVersion", expectedVersion));
            });
        } catch (RuntimeException ignored) {
            // The original business error remains authoritative. A DB failure
            // intentionally leaves the group PENDING for a safe retry.
        }
    }

    private void finalizeUndoFailure(Long teamId, Long userId, Long actionId,
                                     String requestId, String hash,
                                     TeamAgentException failure) {
        try {
            failureTransaction.executeWithoutResult(status -> {
                TeamAgentAction action = actionMapper.selectByIdForUpdate(actionId);
                if (action == null || !Objects.equals(action.getTeamId(), teamId)) return;
                TeamAgentActionGroup group = groupMapper.selectByIdForUpdate(action.getGroupId());
                if (group == null || !Objects.equals(group.getInitiatorUserId(), userId)) return;
                boolean stillAvailable = TeamAgentAction.UNDO_AVAILABLE.equals(
                    action.getUndoStatus());
                boolean ownsInFlight = TeamAgentAction.UNDOING.equals(action.getUndoStatus())
                    && Objects.equals(action.getUndoRequestId(), requestId);
                if (!stillAvailable && !ownsInFlight) return;
                if (!Objects.equals(action.getUndoRequestId(), requestId)) {
                    action.setUndoRequestId(requestId);
                    action.setUndoRequestHash(hash);
                }
                action.setUndoStatus(TeamAgentAction.UNDO_FAILED);
                action.setUndoErrorCode(failure.getErrorCode().name());
                action.setUndoErrorMessage(TeamAgentMarkdownSanitizer.sanitize(
                    failure.getMessage(), 1000));
                action.setUpdatedAt(new Date());
                action.setVersion(next(action.getVersion()));
                actionMapper.updateById(action);
                audit(group, action, userId, requestId, "ACTION_UNDO_FAILED", "FAILED",
                    Map.of("errorCode", failure.getErrorCode().name(),
                        "errorMessage", Objects.toString(failure.getMessage(), "")));
            });
        } catch (RuntimeException ignored) {
            // Leave UNDOING AVAILABLE after rollback for a safe retry.
        }
    }

    private void finalizeUndoExpiry(Long teamId, Long userId, Long actionId,
                                    String requestId) {
        try {
            failureTransaction.executeWithoutResult(status -> {
                TeamAgentAction action = actionMapper.selectByIdForUpdate(actionId);
                if (action == null || !Objects.equals(action.getTeamId(), teamId)
                    || !TeamAgentAction.UNDO_AVAILABLE.equals(action.getUndoStatus())) {
                    return;
                }
                TeamAgentActionGroup group = groupMapper.selectByIdForUpdate(action.getGroupId());
                if (group == null || !Objects.equals(group.getInitiatorUserId(), userId)) return;
                action.setUndoStatus(TeamAgentAction.UNDO_EXPIRED);
                action.setUpdatedAt(new Date());
                action.setVersion(next(action.getVersion()));
                actionMapper.updateById(action);
                audit(group, action, userId, requestId, "ACTION_UNDO_EXPIRED", "EXPIRED",
                    Map.of("errorCode", TeamAgentErrorCode.UNDO_EXPIRED.name()));
            });
        } catch (RuntimeException ignored) {
            // Expiry is also enforced by the next read/write attempt.
        }
    }

    private void expireOverdue(Long teamId, Long userId) {
        List<TeamAgentActionGroup> rows = groupMapper.selectList(
            new LambdaQueryWrapper<TeamAgentActionGroup>()
                .eq(TeamAgentActionGroup::getTeamId, teamId)
                .eq(TeamAgentActionGroup::getInitiatorUserId, userId)
                .eq(TeamAgentActionGroup::getStatus, TeamAgentActionGroup.PENDING)
                .lt(TeamAgentActionGroup::getExpiresAt, new Date())
                .orderByAsc(TeamAgentActionGroup::getId)
                .last("LIMIT 100"));
        for (TeamAgentActionGroup row : rows) expireGroupIfNeeded(row);
    }

    private void expireGroupAfterFailedDecision(Long groupId) {
        TeamAgentActionGroup current = groupId == null ? null
            : groupMapper.selectById(groupId);
        if (current != null) expireGroupIfNeeded(current);
    }

    private void expireGroupIfNeeded(TeamAgentActionGroup candidate) {
        if (!TeamAgentActionGroup.PENDING.equals(candidate.getStatus())
            || candidate.getExpiresAt() == null
            || candidate.getExpiresAt().after(new Date())) return;
        writeTransaction.executeWithoutResult(status -> {
            TeamAgentActionGroup group = groupMapper.selectByIdForUpdate(candidate.getId());
            if (group != null && TeamAgentActionGroup.PENDING.equals(group.getStatus())
                && expired(group)) {
                expireLocked(group, null);
            }
        });
    }

    private void expireLocked(TeamAgentActionGroup group, String requestId) {
        Date now = new Date();
        group.setStatus(TeamAgentActionGroup.EXPIRED);
        group.setUpdatedAt(now);
        group.setVersion(next(group.getVersion()));
        groupMapper.updateById(group);
        for (TeamAgentAction action : actionsForUpdate(group.getId())) {
            if (TeamAgentAction.STATUS_PENDING.equals(action.getStatus())) {
                action.setStatus(TeamAgentAction.STATUS_EXPIRED);
                action.setUpdatedAt(now);
                action.setVersion(next(action.getVersion()));
                actionMapper.updateById(action);
            }
        }
        audit(group, null, group.getInitiatorUserId(), requestId,
            "ACTION_GROUP_EXPIRED", "EXPIRED", Map.of());
    }

    private Map<String, Object> replayDecision(Long teamId, Long userId, Long groupId,
                                               String requestId, String hash) {
        TeamAgentActionGroup existing = groupMapper.selectOne(
            new LambdaQueryWrapper<TeamAgentActionGroup>()
                .eq(TeamAgentActionGroup::getTeamId, teamId)
                .eq(TeamAgentActionGroup::getInitiatorUserId, userId)
                .eq(TeamAgentActionGroup::getClientRequestId, requestId)
                .last("LIMIT 1"));
        if (existing == null) return null;
        if (!Objects.equals(existing.getId(), groupId)
            || !Objects.equals(existing.getRequestHash(), hash)) {
            throw idempotencyReused();
        }
        return groupView(existing, true);
    }

    private boolean isReplay(TeamAgentActionGroup group, String requestId, String hash) {
        if (!requestId.equals(group.getClientRequestId())) return false;
        if (!Objects.equals(hash, group.getRequestHash())) throw idempotencyReused();
        return true;
    }

    private TeamDemand lockActionableTeam(Long teamId, Long userId) {
        TeamDemand team = teamId == null ? null : teamMapper.selectByIdForUpdate(teamId);
        if (team == null || !"TEAMING".equalsIgnoreCase(team.getStatus())) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED,
                "当前团队状态不允许处理该动作");
        }
        try {
            authorization.requireScope(teamId, userId);
        } catch (TeamAgentException e) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED,
                "当前成员身份或权限已变化");
        }
        return team;
    }

    private void requireGroupOwnership(TeamAgentActionGroup group, Long teamId, Long userId) {
        if (group == null || !Objects.equals(group.getTeamId(), teamId)
            || !Objects.equals(group.getInitiatorUserId(), userId)) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_GROUP_NOT_FOUND,
                "动作组不存在");
        }
    }

    private void requirePendingVersion(TeamAgentActionGroup group, Integer expectedVersion) {
        if (!TeamAgentActionGroup.PENDING.equals(group.getStatus())) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_ALREADY_FINALIZED,
                "动作组已经结束");
        }
        if (!Objects.equals(currentVersion(group.getVersion()), expectedVersion)) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_VERSION_CONFLICT,
                "动作组状态已变化，请刷新后重试");
        }
    }

    private void requireCompletedRunAndConversation(TeamAgentActionGroup group) {
        requireActiveConversation(group.getTeamId(), group.getInitiatorUserId(),
            group.getConversationId());
        TeamAgentRun run = group.getRunId() == null ? null
            : runMapper.selectById(group.getRunId());
        if (run == null || !Objects.equals(run.getTeamId(), group.getTeamId())
            || !Objects.equals(run.getUserId(), group.getInitiatorUserId())
            || !Objects.equals(run.getConversationId(), group.getConversationId())
            || !TeamAgentRun.STATUS_COMPLETED.equals(run.getStatus())) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_SELECTION_INVALID,
                "来源运行未成功完成，不能执行动作");
        }
    }

    private TeamAgentConversation requireActiveConversation(Long teamId, Long userId,
                                                            Long conversationId) {
        TeamAgentConversation conversation = conversationId == null ? null
            : conversationMapper.selectById(conversationId);
        if (conversation == null || !Objects.equals(conversation.getTeamId(), teamId)
            || !Objects.equals(conversation.getOwnerUserId(), userId)
            || !TeamAgentConversation.STATUS_ACTIVE.equals(conversation.getStatus())) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_GROUP_NOT_FOUND,
                "动作组来源会话不存在");
        }
        return conversation;
    }

    private TeamAgentActionGroup requireOwnedGroup(Long teamId, Long userId, Long groupId) {
        TeamAgentActionGroup group = groupId == null ? null : groupMapper.selectById(groupId);
        requireGroupOwnership(group, teamId, userId);
        requireActiveConversation(teamId, userId, group.getConversationId());
        return group;
    }

    private boolean conversationVisible(TeamAgentActionGroup group) {
        if (group.getConversationId() == null) return false;
        TeamAgentConversation conversation = conversationMapper.selectById(
            group.getConversationId());
        return conversation != null
            && Objects.equals(conversation.getTeamId(), group.getTeamId())
            && Objects.equals(conversation.getOwnerUserId(), group.getInitiatorUserId())
            && TeamAgentConversation.STATUS_ACTIVE.equals(conversation.getStatus());
    }

    private List<TeamAgentAction> actionsForUpdate(Long groupId) {
        List<TeamAgentAction> rows = actionMapper.selectList(
            new LambdaQueryWrapper<TeamAgentAction>()
                .eq(TeamAgentAction::getGroupId, groupId)
                .orderByAsc(TeamAgentAction::getSeq));
        List<TeamAgentAction> locked = new ArrayList<>();
        for (TeamAgentAction row : rows) {
            TeamAgentAction action = actionMapper.selectByIdForUpdate(row.getId());
            if (action != null) locked.add(action);
        }
        return locked;
    }

    private void validateSelection(List<TeamAgentAction> all,
                                   List<TeamAgentAction> selected) {
        boolean weekly = selected.stream().anyMatch(row ->
            "SEND_WEEKLY_REPORT".equals(row.getActionType()));
        if (weekly && selected.size() != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_SELECTION_INVALID,
                "周报动作必须单独确认");
        }
        Set<String> targets = new HashSet<>();
        for (TeamAgentAction action : selected) {
            if (Set.of("ASSIGN_TASK", "SET_TASK_PRIORITY", "SET_TASK_DEADLINE",
                "SEND_TASK_REMINDER",
                "APPEND_TEAM_DOCUMENT", "UPDATE_TEAM_TABLE_ROWS",
                "LINK_MATERIAL_EVIDENCE", "SET_MATERIAL_REVIEW")
                .contains(action.getActionType())
                && !targets.add(action.getTargetType() + "#" + action.getTargetId())) {
                throw new TeamAgentException(TeamAgentErrorCode.ACTION_SELECTION_INVALID,
                    "同一目标只能选择一项修改动作");
            }
        }
    }

    private void assertCooldown(TeamAgentActionGroup group, TeamAgentAction action) {
        if (!"SEND_TASK_REMINDER".equals(action.getActionType())
            && !"SEND_WEEKLY_REPORT".equals(action.getActionType())) return;
        long windowSeconds = "SEND_TASK_REMINDER".equals(action.getActionType())
            ? 600L : 3600L;
        Date since = Date.from(Instant.now().minusSeconds(windowSeconds));
        List<TeamAgentAction> prior = actionMapper.selectList(
            new LambdaQueryWrapper<TeamAgentAction>()
                .eq(TeamAgentAction::getTeamId, group.getTeamId())
                .eq(TeamAgentAction::getActionType, action.getActionType())
                .eq(TeamAgentAction::getStatus, TeamAgentAction.STATUS_EXECUTED)
                .ge(TeamAgentAction::getExecutedAt, since)
                .orderByDesc(TeamAgentAction::getExecutedAt)
                .last("LIMIT 50"));
        for (TeamAgentAction row : prior) {
            TeamAgentActionGroup priorGroup = groupMapper.selectById(row.getGroupId());
            if (priorGroup == null
                || !Objects.equals(priorGroup.getInitiatorUserId(), group.getInitiatorUserId())) {
                continue;
            }
            if ("SEND_TASK_REMINDER".equals(action.getActionType())
                && Objects.equals(row.getTargetId(), action.getTargetId())) {
                throw new TeamAgentException(TeamAgentErrorCode.AGENT_RATE_LIMITED,
                    "同一任务 10 分钟内不能重复发送 Agent 提醒", null, 60);
            }
            if ("SEND_WEEKLY_REPORT".equals(action.getActionType())
                && Objects.equals(read(row.getAfterJson()).get("reportHash"),
                    read(action.getAfterJson()).get("reportHash"))) {
                throw new TeamAgentException(TeamAgentErrorCode.AGENT_RATE_LIMITED,
                    "相同周报 1 小时内不能重复发送", null, 300);
            }
        }
    }

    private void cancelAction(TeamAgentAction action, Date now) {
        action.setStatus(TeamAgentAction.STATUS_CANCELLED);
        action.setUpdatedAt(now);
        action.setVersion(next(action.getVersion()));
        if (actionMapper.updateById(action) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_VERSION_CONFLICT,
                "动作状态已变化");
        }
    }

    private Map<String, Object> groupView(TeamAgentActionGroup group, boolean includeActions) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("groupId", group.getId());
        row.put("conversationId", group.getConversationId());
        row.put("runId", group.getRunId());
        row.put("status", group.getStatus());
        row.put("decisionType", group.getDecisionType());
        row.put("version", currentVersion(group.getVersion()));
        row.put("expiresAt", TeamAgentTime.iso(group.getExpiresAt()));
        row.put("expiresInSeconds", remaining(group.getExpiresAt()));
        row.put("decidedAt", TeamAgentTime.iso(group.getDecidedAt()));
        row.put("executedAt", TeamAgentTime.iso(group.getExecutedAt()));
        row.put("errorCode", group.getErrorCode());
        row.put("errorMessage", group.getErrorMessage());
        row.put("createdAt", TeamAgentTime.iso(group.getCreatedAt()));
        if (includeActions) {
            List<Map<String, Object>> actions = actionMapper.selectList(
                    new LambdaQueryWrapper<TeamAgentAction>()
                        .eq(TeamAgentAction::getGroupId, group.getId())
                        .orderByAsc(TeamAgentAction::getSeq))
                .stream().map(this::actionView).toList();
            row.put("actions", actions);
        } else {
            row.put("actionCount", actionMapper.selectCount(
                new LambdaQueryWrapper<TeamAgentAction>()
                    .eq(TeamAgentAction::getGroupId, group.getId())));
        }
        row.put("serverTime", TeamAgentTime.iso(Instant.now()));
        return row;
    }

    private Map<String, Object> actionView(TeamAgentAction action) {
        if (action == null) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_NOT_FOUND,
                "动作不存在");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("actionId", action.getId());
        row.put("groupId", action.getGroupId());
        row.put("seq", action.getSeq());
        row.put("schemaVersion", action.getSchemaVersion());
        row.put("actionType", action.getActionType());
        row.put("targetType", action.getTargetType());
        row.put("targetId", action.getTargetId());
        row.put("expectedTargetVersion", action.getExpectedTargetVersion());
        row.put("before", read(action.getBeforeJson()));
        row.put("after", read(action.getAfterJson()));
        row.put("reason", action.getReason());
        row.put("evidence", readList(action.getEvidenceJson()));
        row.put("status", action.getStatus());
        row.put("result", read(action.getResultJson()));
        row.put("undoStatus", action.getUndoStatus());
        row.put("undoDeadline", TeamAgentTime.iso(action.getUndoDeadline()));
        row.put("undoRemainingSeconds", remaining(action.getUndoDeadline()));
        row.put("undoResult", read(action.getUndoResultJson()));
        row.put("undoErrorCode", action.getUndoErrorCode());
        row.put("undoErrorMessage", action.getUndoErrorMessage());
        row.put("errorCode", action.getErrorCode());
        row.put("errorMessage", action.getErrorMessage());
        row.put("version", currentVersion(action.getVersion()));
        row.put("executedAt", TeamAgentTime.iso(action.getExecutedAt()));
        row.put("undoneAt", TeamAgentTime.iso(action.getUndoneAt()));
        return row;
    }

    private void audit(TeamAgentActionGroup group, TeamAgentAction action, Long actorUserId,
                       String requestId, String eventType, String outcome,
                       Map<String, Object> metadata) {
        TeamAgentAuditLog log = new TeamAgentAuditLog();
        log.setTeamId(group.getTeamId());
        log.setConversationId(group.getConversationId());
        log.setRunId(group.getRunId());
        log.setActionGroupId(group.getId());
        log.setActionId(action == null ? null : action.getId());
        log.setActorType("USER");
        log.setActorUserId(actorUserId);
        log.setEventType(eventType);
        log.setOutcome(outcome);
        log.setRequestId(requestId == null ? "system" : requestId);
        log.setMetadataJson(write(metadata));
        log.setCreatedAt(new Date());
        auditMapper.insert(log);
    }

    private String decisionHash(String type, Long groupId, List<Long> selected,
                                Integer expectedVersion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionType", type);
        payload.put("groupId", groupId);
        payload.put("selectedActionIds", selected.stream().sorted().toList());
        payload.put("expectedGroupVersion", expectedVersion);
        return TeamAgentHashes.structureHash("action-decision", payload);
    }

    private String undoHash(Long actionId, Integer expectedVersion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionType", "UNDO");
        payload.put("actionId", actionId);
        payload.put("expectedActionVersion", expectedVersion);
        return TeamAgentHashes.structureHash("action-undo", payload);
    }

    private TeamAgentException idempotencyReused() {
        return new TeamAgentException(TeamAgentErrorCode.IDEMPOTENCY_KEY_REUSED,
            "同一请求标识不能用于不同的动作决策");
    }

    private boolean expired(TeamAgentActionGroup group) {
        return group.getExpiresAt() != null && !group.getExpiresAt().after(new Date());
    }

    private long remaining(Date deadline) {
        if (deadline == null) return 0L;
        return Math.max(0L, Duration.between(Instant.now(), deadline.toInstant()).toSeconds());
    }

    private int currentVersion(Integer version) { return version == null ? 0 : version; }
    private int next(Integer version) { return currentVersion(version) + 1; }

    private Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json,
                new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                "动作审计内容无法保存");
        }
    }

    private String encodeCursor(TeamAgentActionGroup group) {
        String raw = group.getCreatedAt().getTime() + ":" + group.getId();
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor),
                StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 2);
            long time = Long.parseLong(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (time < 0 || id <= 0) throw new IllegalArgumentException();
            return new Cursor(time, id);
        } catch (RuntimeException e) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                "动作组游标不合法");
        }
    }

    private record Cursor(long time, long id) {}
}
