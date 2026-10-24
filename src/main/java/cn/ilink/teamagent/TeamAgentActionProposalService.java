package cn.ilink.teamagent;

import cn.ilink.collab.TeamCollabException;
import cn.ilink.collab.TeamCollaborationAgentCommands;
import cn.ilink.entity.TeamAgentAction;
import cn.ilink.entity.TeamAgentActionGroup;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamAgentActionGroupMapper;
import cn.ilink.mapper.TeamAgentActionMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.mapper.TeamTaskMapper;
import cn.ilink.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Converts untrusted model drafts into server-owned action payloads. Existing
 * values and optimistic versions always come from the database.
 */
@Service
public class TeamAgentActionProposalService {
    private static final int ACTION_TTL_SECONDS = 600;
    private static final int UNDO_TTL_SECONDS = 600;
    private static final Set<String> UNDOABLE = Set.of(
        "CREATE_TASK", "CREATE_SUBTASK", "ASSIGN_TASK",
        "SET_TASK_PRIORITY", "SET_TASK_DEADLINE",
        "CREATE_TEAM_DOCUMENT", "APPEND_TEAM_DOCUMENT", "CREATE_TEAM_TABLE",
        "UPDATE_TEAM_TABLE_ROWS", "CREATE_MATERIAL_REQUIREMENT",
        "LINK_MATERIAL_EVIDENCE", "SET_MATERIAL_REVIEW");

    private final TeamDemandMapper teamMapper;
    private final TeamTaskMapper taskMapper;
    private final UserService userService;
    private final TeamTaskPolicy taskPolicy;
    private final TeamAgentActionGroupMapper groupMapper;
    private final TeamAgentActionMapper actionMapper;
    private final TeamAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final TeamCollaborationAgentCommands collaborationCommands;

    public TeamAgentActionProposalService(TeamDemandMapper teamMapper,
                                          TeamTaskMapper taskMapper,
                                          UserService userService,
                                          TeamTaskPolicy taskPolicy,
                                          TeamAgentActionGroupMapper groupMapper,
                                          TeamAgentActionMapper actionMapper,
                                          TeamAgentProperties properties,
                                          ObjectMapper objectMapper,
                                          TeamCollaborationAgentCommands collaborationCommands) {
        this.teamMapper = teamMapper;
        this.taskMapper = taskMapper;
        this.userService = userService;
        this.taskPolicy = taskPolicy;
        this.groupMapper = groupMapper;
        this.actionMapper = actionMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.collaborationCommands = collaborationCommands;
    }

    public List<EnrichedDraft> prepare(TeamAgentScope scope,
                                      List<TeamAgentActionDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) return List.of();
        if (!properties.isActionsEnabled()) {
            return List.of();
        }
        boolean taskActionsAllowed = scope.actionsEnabled();
        boolean collabActionsAllowed = scope.collaborationActionsEnabled();
        if (!taskActionsAllowed && !collabActionsAllowed) {
            return List.of();
        }
        TeamDemand team = requireTeam(scope.teamId());
        User actor = userService.getById(scope.userId());
        if (actor == null) {
            throw new TeamAgentException(TeamAgentErrorCode.AUTH_REQUIRED, "登录状态已失效");
        }
        List<EnrichedDraft> rows = new ArrayList<>();
        for (TeamAgentActionDraft draft : drafts) {
            if ("SEND_WEEKLY_REPORT".equals(draft.actionType())) {
                if (!taskActionsAllowed) continue;
                rows.add(new EnrichedDraft(draft.schemaVersion(), draft.actionType(),
                    "TEAM_CHAT", scope.teamId(), null,
                    Map.of("teamId", scope.teamId()), draft.after(), draft.reason(),
                    draft.evidence()));
                continue;
            }
            if (collaborationCommands.supports(draft.actionType())) {
                if (!collabActionsAllowed) continue;
                rows.add(collaborationDraft(scope, draft));
                continue;
            }
            if (!taskActionsAllowed) continue;
            TeamTask target = draft.targetId() == null ? null : taskMapper.selectById(draft.targetId());
            if (target == null && !"CREATE_TASK".equals(draft.actionType())
                && !"CREATE_SUBTASK".equals(draft.actionType())) {
                throw invalid("动作目标不存在");
            }
            if (target != null && !Objects.equals(target.getTeamId(), team.getId())) {
                throw invalid("动作目标不属于当前团队");
            }
            if (target != null && !taskPolicy.canReadTask(team, target, actor)) {
                throw invalid("动作目标超出当前用户的任务读取范围");
            }
            Map<String, Object> before = target == null ? Map.of() : before(target);
            Integer expectedVersion = target == null ? null : target.getVersion();
            validatePermission(team, target, actor, draft);
            rows.add(new EnrichedDraft(draft.schemaVersion(), draft.actionType(),
                draft.targetType(), draft.targetId(), expectedVersion,
                before, draft.after(), draft.reason(), draft.evidence()));
        }
        return List.copyOf(rows);
    }

    /**
     * Must be called inside the successful run-terminal transaction.
     */
    public List<Long> publishLocked(TeamAgentRun run, Long responseMessageId,
                                    List<EnrichedDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) return List.of();
        Date now = new Date();
        TeamAgentActionGroup group = new TeamAgentActionGroup();
        group.setTeamId(run.getTeamId());
        group.setConversationId(run.getConversationId());
        group.setRunId(run.getId());
        group.setInitiatorUserId(run.getUserId());
        group.setStatus(TeamAgentActionGroup.PENDING);
        group.setExpiresAt(Date.from(Instant.now().plusSeconds(ACTION_TTL_SECONDS)));
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        group.setVersion(0);
        if (groupMapper.insert(group) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                "动作组保存失败");
        }
        int seq = 1;
        for (EnrichedDraft draft : drafts) {
            TeamAgentAction action = new TeamAgentAction();
            action.setGroupId(group.getId());
            action.setTeamId(run.getTeamId());
            action.setSeq(seq++);
            action.setSchemaVersion(draft.schemaVersion());
            action.setActionType(draft.actionType());
            action.setTargetType(draft.targetType());
            action.setTargetId(draft.targetId());
            action.setExpectedTargetVersion(draft.expectedTargetVersion());
            action.setBeforeJson(write(draft.before()));
            action.setAfterJson(write(draft.after()));
            action.setReason(draft.reason());
            action.setEvidenceJson(write(draft.evidence()));
            action.setStatus(TeamAgentAction.STATUS_PENDING);
            action.setUndoStatus(TeamAgentAction.UNDO_NOT_SUPPORTED);
            action.setCreatedAt(now);
            action.setUpdatedAt(now);
            action.setVersion(0);
            if (actionMapper.insert(action) != 1) {
                throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                    "动作保存失败");
            }
        }
        return List.of(group.getId());
    }

    public boolean supportsUndo(String actionType) {
        return UNDOABLE.contains(actionType);
    }

    public Date undoDeadline(Date executedAt) {
        return Date.from((executedAt == null ? Instant.now() : executedAt.toInstant())
            .plusSeconds(UNDO_TTL_SECONDS));
    }

    private void validatePermission(TeamDemand team, TeamTask target, User actor,
                                    TeamAgentActionDraft draft) {
        switch (draft.actionType()) {
            case "CREATE_TASK" -> {
                Long assignee = longValue(draft.after().get("assignedTo"));
                if (!taskPolicy.canCreateTask(team, actor, assignee, null)) {
                    throw invalid("当前角色不能创建该任务");
                }
            }
            case "CREATE_SUBTASK" -> {
                TeamTask parent = taskMapper.selectById(longValue(draft.after().get("parentTaskId")));
                if (parent == null || !Objects.equals(parent.getTeamId(), team.getId())) {
                    throw invalid("父任务不存在或不属于当前团队");
                }
                Long assignee = longValue(draft.after().get("assignedTo"));
                if (!taskPolicy.canCreateTask(team, actor, assignee, parent)) {
                    throw invalid("当前角色不能创建该子任务");
                }
            }
            case "ASSIGN_TASK" -> {
                Long assignee = longValue(draft.after().get("assignedTo"));
                if (!taskPolicy.canAssignTask(team, target, actor, assignee)) {
                    throw invalid("当前角色不能改派该任务");
                }
            }
            case "SET_TASK_PRIORITY", "SET_TASK_DEADLINE" -> {
                if (!taskPolicy.canAdjustTask(team, target, actor)) {
                    throw invalid("当前角色不能调整该任务");
                }
            }
            case "SEND_TASK_REMINDER" -> {
                if (!taskPolicy.canSendTaskReminder(team, target, actor, target.getAssignedTo())) {
                    throw invalid("当前角色不能提醒该任务负责人");
                }
            }
            case "SEND_WEEKLY_REPORT" -> {
                if (!taskPolicy.isActiveMember(team, actor.getId())) {
                    throw invalid("当前用户不是有效团队成员");
                }
            }
            default -> throw invalid("动作类型不在白名单内");
        }
    }

    /**
     * Collaboration targets are re-read from the database here, so the version
     * fence and the `before` snapshot shown on the confirmation card are always
     * server-owned. Permission failures become a model-visible rejection rather
     * than a silent drop.
     */
    private EnrichedDraft collaborationDraft(TeamAgentScope scope, TeamAgentActionDraft draft) {
        try {
            TeamCollaborationAgentCommands.Prepared prepared = collaborationCommands.prepare(
                scope, draft.actionType(), draft.targetId(), draft.after());
            return new EnrichedDraft(draft.schemaVersion(), draft.actionType(),
                prepared.targetType(), prepared.targetId(), prepared.expectedTargetVersion(),
                prepared.before(), prepared.after(), draft.reason(), draft.evidence());
        } catch (TeamCollabException e) {
            throw new TeamAgentException(TeamAgentErrorCode.MODEL_OUTPUT_INVALID,
                e.getMessage() == null ? "协作资产动作不可执行" : e.getMessage());
        }
    }

    private TeamDemand requireTeam(Long teamId) {
        TeamDemand team = teamMapper.selectById(teamId);
        if (team == null || !"TEAMING".equalsIgnoreCase(team.getStatus())) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED,
                "当前团队状态不允许生成任务动作");
        }
        return team;
    }

    private Map<String, Object> before(TeamTask task) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("taskTitle", task.getTaskTitle());
        before.put("status", task.getStatus());
        before.put("priority", task.getPriority());
        before.put("deadline", TeamAgentTime.iso(task.getDeadline()));
        before.put("assignedTo", task.getAssignedTo());
        before.put("version", task.getVersion());
        return before;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                "动作内容无法保存");
        }
    }

    private TeamAgentException invalid(String message) {
        return new TeamAgentException(TeamAgentErrorCode.MODEL_OUTPUT_INVALID, message);
    }

    public record EnrichedDraft(String schemaVersion, String actionType, String targetType,
                                Long targetId, Integer expectedTargetVersion,
                                Map<String, Object> before, Map<String, Object> after,
                                String reason, List<Map<String, Object>> evidence) {}
}
