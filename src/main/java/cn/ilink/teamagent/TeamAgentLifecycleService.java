package cn.ilink.teamagent;

import cn.ilink.entity.TeamAgentAction;
import cn.ilink.entity.TeamAgentActionGroup;
import cn.ilink.entity.TeamAgentAuditLog;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.entity.TeamDemand;
import cn.ilink.mapper.TeamAgentActionGroupMapper;
import cn.ilink.mapper.TeamAgentActionMapper;
import cn.ilink.mapper.TeamAgentAuditLogMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import cn.ilink.mapper.TeamDemandMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Closes Agent-owned state when team access is revoked. It joins the caller's
 * business transaction and always locks the team before action groups or runs,
 * matching the workbench lock order.
 */
@Service
public class TeamAgentLifecycleService {
    private final TeamDemandMapper teamMapper;
    private final TeamAgentActionGroupMapper groupMapper;
    private final TeamAgentActionMapper actionMapper;
    private final TeamAgentAuditLogMapper auditMapper;
    private final TeamAgentRunMapper runMapper;
    private final TeamAgentEventService eventService;
    private final TeamAgentAdmissionController admission;

    public TeamAgentLifecycleService(TeamDemandMapper teamMapper,
                                     TeamAgentActionGroupMapper groupMapper,
                                     TeamAgentActionMapper actionMapper,
                                     TeamAgentAuditLogMapper auditMapper,
                                     TeamAgentRunMapper runMapper,
                                     TeamAgentEventService eventService,
                                     TeamAgentAdmissionController admission) {
        this.teamMapper = teamMapper;
        this.groupMapper = groupMapper;
        this.actionMapper = actionMapper;
        this.auditMapper = auditMapper;
        this.runMapper = runMapper;
        this.eventService = eventService;
        this.admission = admission;
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void onMemberAccessRevoked(Long teamId, Long userId, String reason) {
        TeamDemand team = lockTeam(teamId);
        if (team == null || userId == null) return;
        cancelPendingGroups(teamId, userId, reason);
        expireUndoWindows(teamId, userId, reason);
        stopActiveRuns(teamId, userId, false, reason);
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void onTeamClosed(Long teamId) {
        TeamDemand team = lockTeam(teamId);
        if (team == null) return;
        cancelPendingGroups(teamId, null, "团队已结束，待确认动作已取消");
        expireUndoWindows(teamId, null, "团队已结束，撤销窗口已关闭");
        stopActiveRuns(teamId, null, true, "团队已结束，动作型运行已停止");
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void onTeamDissolved(Long teamId) {
        TeamDemand team = lockTeam(teamId);
        if (team == null) return;
        cancelPendingGroups(teamId, null, "团队已解散，待确认动作已取消");
        expireUndoWindows(teamId, null, "团队已解散，撤销窗口已关闭");
        stopActiveRuns(teamId, null, false, "团队已解散，运行已停止");
    }

    /**
     * Account deletion must not cut through a confirm/undo transaction. A
     * caller can retry after the short-lived decision has reached a terminal
     * state.
     */
    public void requireNoInFlightForAccountDeletion(Long userId) {
        if (userId == null) return;
        List<Long> groupIds = groupMapper.selectList(
                new LambdaQueryWrapper<TeamAgentActionGroup>()
                    .eq(TeamAgentActionGroup::getInitiatorUserId, userId)
                    .select(TeamAgentActionGroup::getId))
            .stream().map(TeamAgentActionGroup::getId).toList();
        long confirming = groupMapper.selectCount(
            new LambdaQueryWrapper<TeamAgentActionGroup>()
                .eq(TeamAgentActionGroup::getInitiatorUserId, userId)
                .eq(TeamAgentActionGroup::getStatus, TeamAgentActionGroup.CONFIRMING));
        if (confirming > 0) {
            throw new TeamAgentException(TeamAgentErrorCode.CONVERSATION_BUSY,
                "仍有正在提交的 Agent 动作，请稍后再试");
        }
        if (!groupIds.isEmpty()) {
            long undoing = actionMapper.selectCount(
                new LambdaQueryWrapper<TeamAgentAction>()
                    .in(TeamAgentAction::getGroupId, groupIds)
                    .eq(TeamAgentAction::getUndoStatus, TeamAgentAction.UNDOING));
            if (undoing > 0) {
                throw new TeamAgentException(TeamAgentErrorCode.CONVERSATION_BUSY,
                    "仍有正在撤销的 Agent 动作，请稍后再试");
            }
        }
    }

    private TeamDemand lockTeam(Long teamId) {
        return teamId == null ? null : teamMapper.selectByIdForUpdate(teamId);
    }

    private void cancelPendingGroups(Long teamId, Long userId, String reason) {
        LambdaQueryWrapper<TeamAgentActionGroup> query =
            new LambdaQueryWrapper<TeamAgentActionGroup>()
                .eq(TeamAgentActionGroup::getTeamId, teamId)
                .eq(TeamAgentActionGroup::getStatus, TeamAgentActionGroup.PENDING)
                .orderByAsc(TeamAgentActionGroup::getId);
        if (userId != null) query.eq(TeamAgentActionGroup::getInitiatorUserId, userId);
        List<TeamAgentActionGroup> candidates = groupMapper.selectList(query);
        Date now = new Date();
        for (TeamAgentActionGroup candidate : candidates) {
            TeamAgentActionGroup group = groupMapper.selectByIdForUpdate(candidate.getId());
            if (group == null || !TeamAgentActionGroup.PENDING.equals(group.getStatus())) {
                continue;
            }
            group.setStatus(TeamAgentActionGroup.CANCELLED);
            group.setDecisionType("SYSTEM_CANCELLED");
            group.setClientRequestId(null);
            group.setRequestHash(null);
            group.setDecidedAt(now);
            group.setUpdatedAt(now);
            group.setVersion(next(group.getVersion()));
            if (groupMapper.updateById(group) != 1) {
                throw new TeamAgentException(TeamAgentErrorCode.ACTION_VERSION_CONFLICT,
                    "动作组状态已变化，请刷新后重试");
            }
            for (TeamAgentAction action : actionMapper.selectList(
                new LambdaQueryWrapper<TeamAgentAction>()
                    .eq(TeamAgentAction::getGroupId, group.getId())
                    .eq(TeamAgentAction::getStatus, TeamAgentAction.STATUS_PENDING)
                    .orderByAsc(TeamAgentAction::getSeq))) {
                TeamAgentAction locked = actionMapper.selectByIdForUpdate(action.getId());
                if (locked != null && TeamAgentAction.STATUS_PENDING.equals(locked.getStatus())) {
                    locked.setStatus(TeamAgentAction.STATUS_CANCELLED);
                    locked.setUpdatedAt(now);
                    locked.setVersion(next(locked.getVersion()));
                    actionMapper.updateById(locked);
                }
            }
            audit(group, null, "ACTION_GROUP_SYSTEM_CANCELLED", reason);
        }
    }

    private void expireUndoWindows(Long teamId, Long userId, String reason) {
        LambdaQueryWrapper<TeamAgentActionGroup> groupQuery =
            new LambdaQueryWrapper<TeamAgentActionGroup>()
                .eq(TeamAgentActionGroup::getTeamId, teamId)
                .orderByAsc(TeamAgentActionGroup::getId);
        if (userId != null) groupQuery.eq(TeamAgentActionGroup::getInitiatorUserId, userId);
        List<Long> groupIds = groupMapper.selectList(groupQuery)
            .stream().map(TeamAgentActionGroup::getId).toList();
        if (groupIds.isEmpty()) return;

        List<TeamAgentAction> candidates = actionMapper.selectList(
            new LambdaQueryWrapper<TeamAgentAction>()
                .in(TeamAgentAction::getGroupId, groupIds)
                .eq(TeamAgentAction::getUndoStatus, TeamAgentAction.UNDO_AVAILABLE)
                .orderByAsc(TeamAgentAction::getGroupId)
                .orderByAsc(TeamAgentAction::getSeq));
        Date now = new Date();
        for (TeamAgentAction candidate : candidates) {
            TeamAgentActionGroup group = groupMapper.selectByIdForUpdate(candidate.getGroupId());
            if (group == null || !Objects.equals(group.getTeamId(), teamId)) continue;
            TeamAgentAction action = actionMapper.selectByIdForUpdate(candidate.getId());
            if (action == null
                || !TeamAgentAction.UNDO_AVAILABLE.equals(action.getUndoStatus())) {
                continue;
            }
            action.setUndoStatus(TeamAgentAction.UNDO_EXPIRED);
            action.setUpdatedAt(now);
            action.setVersion(next(action.getVersion()));
            if (actionMapper.updateById(action) != 1) {
                throw new TeamAgentException(TeamAgentErrorCode.ACTION_VERSION_CONFLICT,
                    "撤销状态已变化，请刷新后重试");
            }
            audit(group, action, "ACTION_UNDO_SYSTEM_EXPIRED", reason);
        }
    }

    private void stopActiveRuns(Long teamId, Long userId, boolean actionOnly, String reason) {
        LambdaQueryWrapper<TeamAgentRun> query = new LambdaQueryWrapper<TeamAgentRun>()
            .eq(TeamAgentRun::getTeamId, teamId)
            .in(TeamAgentRun::getStatus,
                List.of(TeamAgentRun.STATUS_QUEUED, TeamAgentRun.STATUS_RUNNING))
            .orderByAsc(TeamAgentRun::getId);
        if (userId != null) query.eq(TeamAgentRun::getUserId, userId);
        for (TeamAgentRun candidate : runMapper.selectList(query)) {
            if (actionOnly && !isActionRun(candidate)) continue;
            TeamAgentRun run = runMapper.selectByIdForUpdate(candidate.getId());
            if (run == null) continue;
            if (TeamAgentRun.STATUS_QUEUED.equals(run.getStatus())) {
                Date now = new Date();
                run.setStatus(TeamAgentRun.STATUS_CANCELLED);
                run.setCancelRequestId(systemRequestId(reason));
                run.setCancelRequestedAt(now);
                run.setFinishedAt(now);
                run.setElapsedMs(elapsed(run.getStartedAt(), now));
                long seq = eventService.appendLocked(run, "run.cancelled",
                    TeamAgentEventService.payload("message", safeMessage(reason)));
                run.setEventSeq(seq);
                run.setVersion(next(run.getVersion()));
                if (runMapper.updateById(run) != 1) {
                    throw new TeamAgentException(TeamAgentErrorCode.ACTION_VERSION_CONFLICT,
                        "运行状态已变化，请刷新后重试");
                }
                releaseAfterCommit(run);
            } else if (run.getCancelRequestedAt() == null) {
                run.setCancelRequestId(systemRequestId(reason));
                run.setCancelRequestedAt(new Date());
                run.setVersion(next(run.getVersion()));
                if (runMapper.updateById(run) != 1) {
                    throw new TeamAgentException(TeamAgentErrorCode.ACTION_VERSION_CONFLICT,
                        "运行状态已变化，请刷新后重试");
                }
            }
        }
    }

    private boolean isActionRun(TeamAgentRun run) {
        return "ACTION_PLAN".equalsIgnoreCase(run.getRequestedMode())
            || "ACTION_PLAN".equalsIgnoreCase(run.getResolvedRoute());
    }

    private void audit(TeamAgentActionGroup group, TeamAgentAction action,
                       String eventType, String reason) {
        if (group == null) return;
        Date now = new Date();
        TeamAgentAuditLog log = new TeamAgentAuditLog();
        log.setTeamId(group.getTeamId());
        log.setConversationId(group.getConversationId());
        log.setRunId(group.getRunId());
        log.setActionGroupId(group.getId());
        log.setActionId(action == null ? null : action.getId());
        log.setActorType("SYSTEM");
        log.setActorUserId(null);
        log.setEventType(eventType);
        log.setOutcome("SUCCESS");
        log.setRequestId(systemRequestId(reason));
        log.setMetadataJson("{}");
        log.setCreatedAt(now);
        auditMapper.insert(log);
    }

    private void releaseAfterCommit(TeamAgentRun run) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        admission.release(run.getId(), run.getTeamId(), run.getUserId());
                    }
                });
        } else {
            admission.release(run.getId(), run.getTeamId(), run.getUserId());
        }
    }

    private String systemRequestId(String reason) {
        String suffix = reason == null ? "lifecycle" : reason
            .replaceAll("[^A-Za-z0-9_-]", "-");
        return ("system:" + suffix).substring(0,
            Math.min(64, ("system:" + suffix).length()));
    }

    private String safeMessage(String reason) {
        return TeamAgentMarkdownSanitizer.sanitize(
            reason == null ? "运行已停止" : reason, 200);
    }

    private long elapsed(Date started, Date finished) {
        if (started == null || finished == null) return 0L;
        return Math.max(0L, finished.getTime() - started.getTime());
    }

    private int next(Integer version) {
        return version == null ? 1 : version + 1;
    }
}
