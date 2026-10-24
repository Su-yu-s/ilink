package cn.ilink.teamagent;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.TaskComment;
import cn.ilink.entity.TaskParticipant;
import cn.ilink.entity.TaskReview;
import cn.ilink.entity.TaskSubmission;
import cn.ilink.entity.TeamAgentAuditLog;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamAgentAuditLogMapper;
import cn.ilink.mapper.TaskCommentMapper;
import cn.ilink.mapper.TaskParticipantMapper;
import cn.ilink.mapper.TaskReviewMapper;
import cn.ilink.mapper.TaskSubmissionMapper;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.mapper.TeamTaskMapper;
import cn.ilink.service.NotificationService;
import cn.ilink.service.UserService;
import cn.ilink.service.kg.KgIndexService;
import cn.ilink.util.DisplayNameResolver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The only write path for team tasks. It binds versioned task changes,
 * participant transitions, notifications and source-index revisions to one
 * database transaction.
 */
@Service
public class TeamTaskCommandService {
    private static final String TEAMING = "TEAMING";
    private static final java.util.Set<String> TYPES = java.util.Set.of(
        "DEVELOPMENT", "DESIGN", "TESTING", "DOCUMENTATION", "OTHER");

    private final TeamDemandMapper teamDemandMapper;
    private final TeamTaskMapper taskMapper;
    private final TaskParticipantMapper participantMapper;
    private final TaskCommentMapper commentMapper;
    private final TaskSubmissionMapper submissionMapper;
    private final TaskReviewMapper reviewMapper;
    private final TeamApplicationMapper teamApplicationMapper;
    private final UserService userService;
    private final NotificationService notificationService;
    private final KgIndexService indexService;
    private final TeamAgentAuditLogMapper auditMapper;
    private final TeamTaskPolicy policy;
    private final TeamTaskSnapshotSerializer snapshotSerializer;
    private final ObjectMapper objectMapper;

    public TeamTaskCommandService(TeamDemandMapper teamDemandMapper,
                                  TeamTaskMapper taskMapper,
                                  TaskParticipantMapper participantMapper,
                                  TaskCommentMapper commentMapper,
                                  TaskSubmissionMapper submissionMapper,
                                  TaskReviewMapper reviewMapper,
                                  TeamApplicationMapper teamApplicationMapper,
                                  UserService userService,
                                  NotificationService notificationService,
                                  KgIndexService indexService,
                                  TeamAgentAuditLogMapper auditMapper,
                                  TeamTaskPolicy policy,
                                  TeamTaskSnapshotSerializer snapshotSerializer,
                                  ObjectMapper objectMapper) {
        this.teamDemandMapper = teamDemandMapper;
        this.taskMapper = taskMapper;
        this.participantMapper = participantMapper;
        this.commentMapper = commentMapper;
        this.submissionMapper = submissionMapper;
        this.reviewMapper = reviewMapper;
        this.teamApplicationMapper = teamApplicationMapper;
        this.userService = userService;
        this.notificationService = notificationService;
        this.indexService = indexService;
        this.auditMapper = auditMapper;
        this.policy = policy;
        this.snapshotSerializer = snapshotSerializer;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public TeamTaskCommands.MutationResult createTask(TeamTaskCommands.CreateTask command) {
        TeamDemand team = requireTeamingTeam(command.teamId());
        User actor = requireActor(command.actorUserId());
        TeamTask parent = command.parentTaskId() == null ? null : requireTask(command.parentTaskId());
        if (parent != null && !Objects.equals(parent.getTeamId(), team.getId())) {
            fail(TeamAgentErrorCode.ACTION_SELECTION_INVALID, "父任务不属于当前团队");
        }
        if (!policy.canCreateTask(team, actor, command.assignedTo(), parent)) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "当前角色不能创建该任务");
        }
        validateCreate(command);

        Date now = new Date();
        TeamTask task = new TeamTask();
        task.setTeamId(team.getId());
        task.setTaskTitle(normalizeRequired(command.title(), 200, "任务名称"));
        task.setTaskDescription(normalizeOptional(command.description(), 4000, "任务描述"));
        task.setTaskType(normalizeType(command.taskType()));
        task.setPriority(command.priority() == null ? 2 : command.priority());
        task.setStatus("PENDING");
        task.setEstimatedHours(command.estimatedHours());
        task.setDeadline(command.deadline());
        task.setAssignedTo(command.assignedTo());
        task.setCreatedBy(actor.getId());
        task.setRequiresReview(command.requiresReview() == null || command.requiresReview());
        task.setParentTaskId(parent == null ? null : parent.getId());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setVersion(0);
        if (taskMapper.insert(task) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED, "任务创建失败");
        }

        if (!Objects.equals(actor.getId(), command.assignedTo())) {
            upsertLead(task.getId(), command.assignedTo(), now);
        }
        upsertOwner(task.getId(), actor.getId(), now);
        notifyAssigned(team, task, actor, command.assignedTo(), "新任务指派",
            DisplayNameResolver.resolve(actor) + " 给你分配了任务「" + task.getTaskTitle() + "」");
        reindex(task);
        audit(task, actor, "TASK_CREATED", command.requestId(),
            commandMetadata(command.agentActionId(), Map.of(
                "status", task.getStatus(),
                "assignedTo", Objects.toString(task.getAssignedTo(), ""),
                "parentTaskId", Objects.toString(task.getParentTaskId(), ""))));
        return result(task, parent == null ? "任务已创建" : "子任务已创建");
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public TeamTaskCommands.MutationResult assignTask(TeamTaskCommands.AssignTask command) {
        TeamDemand team = requireTeamingTeam(command.teamId());
        TeamTask task = lockTask(command.taskId(), team.getId());
        User actor = requireActor(command.actorUserId());
        assertVersion(task, command.expectedVersion());
        if (!policy.canAssignTask(team, task, actor, command.assignedTo())) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "当前角色不能改派任务");
        }
        if (Objects.equals(task.getAssignedTo(), command.assignedTo())) {
            return result(task, "任务负责人未变化");
        }

        Date now = new Date();
        Long previousAssignee = task.getAssignedTo();
        int updated = taskMapper.update(null, new UpdateWrapper<TeamTask>()
            .eq("id", task.getId())
            .eq("version", task.getVersion())
            .set("assigned_to", command.assignedTo())
            .set("updated_at", now)
            .setSql("version = version + 1"));
        if (updated != 1) versionConflict();
        retireLead(task.getId(), previousAssignee, now);
        upsertLead(task.getId(), command.assignedTo(), now);
        task = taskMapper.selectById(task.getId());
        notifyAssigned(team, task, actor, command.assignedTo(), "任务已改派",
            DisplayNameResolver.resolve(actor) + " 将任务「" + task.getTaskTitle() + "」改派给你");
        reindex(task);
        audit(task, actor, "TASK_ASSIGNED", command.requestId(),
            commandMetadata(command.agentActionId(), Map.of(
                "previousAssignee", Objects.toString(previousAssignee, ""),
                "assignedTo", Objects.toString(command.assignedTo(), ""))));
        return result(task, "任务负责人已更新");
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public TeamTaskCommands.MutationResult setPriority(TeamTaskCommands.SetPriority command) {
        TeamDemand team = requireTeamingTeam(command.teamId());
        TeamTask task = lockTask(command.taskId(), team.getId());
        User actor = requireActor(command.actorUserId());
        assertVersion(task, command.expectedVersion());
        if (!policy.canAdjustTask(team, task, actor)) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "当前角色不能调整任务优先级");
        }
        validatePriority(command.priority());
        if (Objects.equals(task.getPriority(), command.priority())) {
            return result(task, "任务优先级未变化");
        }
        updateVersionedTask(task, new UpdateWrapper<TeamTask>()
            .set("priority", command.priority()));
        task = taskMapper.selectById(task.getId());
        reindex(task);
        audit(task, actor, "TASK_PRIORITY_SET", command.requestId(),
            commandMetadata(command.agentActionId(),
                Map.of("priority", command.priority())));
        return result(task, "任务优先级已更新");
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public TeamTaskCommands.MutationResult setDeadline(TeamTaskCommands.SetDeadline command) {
        TeamDemand team = requireTeamingTeam(command.teamId());
        TeamTask task = lockTask(command.taskId(), team.getId());
        User actor = requireActor(command.actorUserId());
        assertVersion(task, command.expectedVersion());
        if (!policy.canAdjustTask(team, task, actor)) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "当前角色不能调整任务截止日期");
        }
        if (command.deadline() != null && command.deadline().before(new Date())) {
            fail(TeamAgentErrorCode.ACTION_SELECTION_INVALID, "新截止日期不能早于当前时间");
        }
        updateVersionedTask(task, new UpdateWrapper<TeamTask>().set("deadline", command.deadline()));
        task = taskMapper.selectById(task.getId());
        reindex(task);
        audit(task, actor, "TASK_DEADLINE_SET", command.requestId(),
            commandMetadata(command.agentActionId(),
                Map.of("deadline", command.deadline() == null ? "" : command.deadline().toInstant().toString())));
        return result(task, command.deadline() == null ? "任务截止日期已清空" : "任务截止日期已更新");
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public TeamTaskCommands.MutationResult updateTask(TeamTaskCommands.UpdateTask command) {
        TeamDemand team = requireTeamingTeam(command.teamId());
        TeamTask task = lockTask(command.taskId(), team.getId());
        User actor = requireActor(command.actorUserId());
        assertVersion(task, command.expectedVersion());
        if (!policy.canAdjustTask(team, task, actor)) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "当前角色不能编辑任务");
        }

        UpdateWrapper<TeamTask> update = new UpdateWrapper<>();
        if (command.title() != null) {
            update.set("task_title", normalizeRequired(command.title(), 200, "任务名称"));
        }
        if (command.description() != null) {
            update.set("task_description",
                normalizeOptional(command.description(), 4000, "任务描述"));
        }
        if (command.taskType() != null) {
            update.set("task_type", normalizeType(command.taskType()));
        }
        if (command.estimatedHours() != null) {
            validateEstimatedHours(command.estimatedHours());
            update.set("estimated_hours", command.estimatedHours());
        }
        if (Boolean.TRUE.equals(command.deadlinePresent())) {
            if (command.deadline() != null && command.deadline().before(new Date())) {
                fail(TeamAgentErrorCode.REQUEST_INVALID, "截止日期不能早于当前时间");
            }
            update.set("deadline", command.deadline());
        }
        if (command.requiresReview() != null) {
            update.set("requires_review", command.requiresReview());
        }

        Long previousAssignee = task.getAssignedTo();
        boolean reassigned = command.assignedTo() != null
            && !Objects.equals(command.assignedTo(), previousAssignee);
        if (reassigned) {
            if (!policy.canAssignTask(team, task, actor, command.assignedTo())) {
                fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED,
                    "当前角色不能将该任务改派给此成员");
            }
            update.set("assigned_to", command.assignedTo());
        }

        // Always advance the version for a successful explicit edit, including
        // identical values, so a stale concurrent editor is still rejected.
        updateVersionedTask(task, update);
        Date now = new Date();
        if (reassigned) {
            retireLead(task.getId(), previousAssignee, now);
            upsertLead(task.getId(), command.assignedTo(), now);
            notifyAssigned(team, taskMapper.selectById(task.getId()), actor,
                command.assignedTo(), "任务已改派",
                DisplayNameResolver.resolve(actor) + " 将任务「" + task.getTaskTitle() + "」改派给你");
        }
        task = taskMapper.selectById(task.getId());
        reindex(task);
        audit(task, actor, "TASK_UPDATED", command.requestId(),
            Map.of("assignedTo", Objects.toString(task.getAssignedTo(), "")));
        return result(task, "任务已更新");
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public TeamTaskCommands.MutationResult changeStatus(TeamTaskCommands.ChangeStatus command) {
        TeamDemand team = requireTeamingTeam(command.teamId());
        TeamTask task = lockTask(command.taskId(), team.getId());
        User actor = requireActor(command.actorUserId());
        assertVersion(task, command.expectedVersion());
        if (!policy.canChangeTaskStatus(team, task, actor, command.targetStatus())) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "当前角色不能推进到该任务状态");
        }
        String target = command.targetStatus().trim().toUpperCase(Locale.ROOT);
        Date now = new Date();
        UpdateWrapper<TeamTask> update = new UpdateWrapper<TeamTask>()
            .set("status", target)
            .set("completed_at", "COMPLETED".equals(target) ? now : null);
        updateVersionedTask(task, update);
        task = taskMapper.selectById(task.getId());
        reindex(task);
        audit(task, actor, "TASK_STATUS_CHANGED", command.requestId(),
            commandMetadata(command.agentActionId(),
                Map.of("targetStatus", target)));
        return result(task, "任务状态已更新");
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public TeamTaskCommands.MutationResult deleteTask(TeamTaskCommands.DeleteTask command) {
        TeamDemand team = requireTeamingTeam(command.teamId());
        TeamTask task = lockTask(command.taskId(), team.getId());
        User actor = requireActor(command.actorUserId());
        assertVersion(task, command.expectedVersion());
        if (!policy.canDeleteTask(team, task, actor)) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "当前角色不能删除任务");
        }
        long childCount = taskMapper.selectCount(new LambdaQueryWrapper<TeamTask>()
            .eq(TeamTask::getParentTaskId, task.getId()));
        if (childCount > 0) {
            fail(TeamAgentErrorCode.ACTION_SELECTION_INVALID, "请先处理该任务的子任务");
        }
        int deleted = taskMapper.delete(new LambdaQueryWrapper<TeamTask>()
            .eq(TeamTask::getId, task.getId())
            .eq(TeamTask::getVersion, task.getVersion()));
        if (deleted != 1) versionConflict();
        participantMapper.delete(new LambdaQueryWrapper<TaskParticipant>()
            .eq(TaskParticipant::getTaskId, task.getId()));
        commentMapper.delete(new LambdaQueryWrapper<TaskComment>()
            .eq(TaskComment::getTaskId, task.getId()));
        submissionMapper.delete(new LambdaQueryWrapper<TaskSubmission>()
            .eq(TaskSubmission::getTaskId, task.getId()));
        reviewMapper.delete(new LambdaQueryWrapper<TaskReview>()
            .eq(TaskReview::getTaskId, task.getId()));
        indexService.enqueueDelete("TASK", task.getId(), KgEntity.SCOPE_TEAM,
            team.getId(), actor.getId());
        audit(task, actor, "TASK_DELETED", command.requestId(),
            commandMetadata(command.agentActionId(),
                Map.of("previousStatus", Objects.toString(task.getStatus(), ""))));
        return new TeamTaskCommands.MutationResult(task.getId(), null, null, "任务已删除");
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public TeamTaskCommands.MutationResult undoCreatedTask(
        TeamTaskCommands.UndoCreatedTask command) {
        TeamDemand team = requireTeamingTeam(command.teamId());
        TeamTask task = lockTask(command.taskId(), team.getId());
        User actor = requireActor(command.actorUserId());
        assertVersion(task, command.expectedVersion());
        if (!policy.canDeleteTask(team, task, actor)) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "当前角色不能撤销该任务创建");
        }
        if (!"PENDING".equalsIgnoreCase(task.getStatus())) {
            fail(TeamAgentErrorCode.UNDO_CONFLICT, "任务状态已变化，不能撤销创建");
        }
        long childCount = taskMapper.selectCount(new LambdaQueryWrapper<TeamTask>()
            .eq(TeamTask::getParentTaskId, task.getId()));
        long commentCount = commentMapper.selectCount(new LambdaQueryWrapper<TaskComment>()
            .eq(TaskComment::getTaskId, task.getId()));
        long submissionCount = submissionMapper.selectCount(
            new LambdaQueryWrapper<TaskSubmission>()
                .eq(TaskSubmission::getTaskId, task.getId()));
        long reviewCount = reviewMapper.selectCount(new LambdaQueryWrapper<TaskReview>()
            .eq(TaskReview::getTaskId, task.getId()));
        if (childCount + commentCount + submissionCount + reviewCount > 0) {
            fail(TeamAgentErrorCode.UNDO_CONFLICT,
                "任务已被继续使用，不能自动撤销创建");
        }
        List<TaskParticipant> participants = participantMapper.selectList(
            new LambdaQueryWrapper<TaskParticipant>()
                .eq(TaskParticipant::getTaskId, task.getId())
                .orderByAsc(TaskParticipant::getUserId));
        boolean participantsUnchanged = participants.size()
            == (Objects.equals(task.getCreatedBy(), task.getAssignedTo()) ? 1 : 2);
        if (participantsUnchanged) {
            for (TaskParticipant participant : participants) {
                boolean owner = Objects.equals(participant.getUserId(), task.getCreatedBy())
                    && "owner".equalsIgnoreCase(participant.getRole());
                boolean lead = Objects.equals(participant.getUserId(), task.getAssignedTo())
                    && !Objects.equals(task.getCreatedBy(), task.getAssignedTo())
                    && "lead".equalsIgnoreCase(participant.getRole());
                if (!owner && !lead) {
                    participantsUnchanged = false;
                    break;
                }
                if (!"active".equalsIgnoreCase(participant.getStatus())
                    || participant.getLeftAt() != null) {
                    participantsUnchanged = false;
                    break;
                }
            }
        }
        if (!participantsUnchanged) {
            fail(TeamAgentErrorCode.UNDO_CONFLICT,
                "任务参与者已发生变化，不能自动撤销创建");
        }
        int deleted = taskMapper.delete(new LambdaQueryWrapper<TeamTask>()
            .eq(TeamTask::getId, task.getId())
            .eq(TeamTask::getVersion, task.getVersion()));
        if (deleted != 1) versionConflict();
        participantMapper.delete(new LambdaQueryWrapper<TaskParticipant>()
            .eq(TaskParticipant::getTaskId, task.getId()));
        indexService.enqueueDelete("TASK", task.getId(), KgEntity.SCOPE_TEAM,
            team.getId(), actor.getId());
        audit(task, actor, "TASK_CREATE_UNDONE", command.requestId(),
            commandMetadata(command.agentActionId(),
                Map.of("previousStatus", Objects.toString(task.getStatus(), ""))));
        return new TeamTaskCommands.MutationResult(
            task.getId(), null, null, "已撤销任务创建");
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public int releaseMemberTasks(TeamTaskCommands.ReleaseMemberTasks command) {
        TeamDemand team = command.teamId() == null ? null
            : teamDemandMapper.selectByIdForUpdate(command.teamId());
        if (team == null) fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "团队不存在");
        User operator = requireActor(command.operatorUserId());
        if (!Objects.equals(operator.getId(), command.targetUserId())
            && !Objects.equals(team.getCreatorId(), operator.getId())) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "无权释放该成员的任务");
        }
        Date now = new Date();
        List<TeamTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<TeamTask>()
            .eq(TeamTask::getTeamId, team.getId())
            .eq(TeamTask::getAssignedTo, command.targetUserId())
            .in(TeamTask::getStatus, List.of("PENDING", "IN_PROGRESS", "REVIEW"))
            .orderByAsc(TeamTask::getId));
        int changed = 0;
        for (TeamTask task : tasks) {
            int updated = taskMapper.update(null, new UpdateWrapper<TeamTask>()
                .eq("id", task.getId())
                .eq("version", task.getVersion())
                .set("assigned_to", null)
                .set("updated_at", now)
                .setSql("version = version + 1"));
            if (updated != 1) versionConflict();
            changed++;
            TeamTask updatedTask = taskMapper.selectById(task.getId());
            reindex(updatedTask);
            audit(updatedTask, operator, "TASK_UNASSIGNED", command.requestId(),
                Map.of("previousAssignee", command.targetUserId(),
                    "membershipOperation",
                    command.membershipOperation() == null ? "" : command.membershipOperation()));
        }
        participantMapper.update(null, new UpdateWrapper<TaskParticipant>()
            .eq("user_id", command.targetUserId())
            .eq("status", "active")
            .inSql("task_id", "SELECT id FROM team_tasks WHERE team_id = " + team.getId())
            .set("status", "left")
            .set("left_at", now));
        if (changed > 0 && team.getCreatorId() != null) {
            notificationService.create(team.getCreatorId(), operator.getId(), "TASK_UNASSIGNED",
                "任务待改派",
                "有 " + changed + " 个未结束任务因成员离开而需要重新指派",
                team.getId());
        }
        return changed;
    }

    /**
     * Cancels every unfinished task before the team status becomes DISSOLVED.
     * The outer ownership transaction holds the team lock, and this command
     * keeps the same lock order before taking task locks.
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public int cancelOpenTasksForDissolution(
        TeamTaskCommands.CancelOpenTasksForDissolution command) {
        TeamDemand team = command.teamId() == null ? null
            : teamDemandMapper.selectByIdForUpdate(command.teamId());
        if (team == null) fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "团队不存在");
        if (!Objects.equals(team.getCreatorId(), command.ownerUserId())) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "只有团队创建者可以解散团队");
        }
        if (!"OPEN".equals(team.getStatus()) && !TEAMING.equals(team.getStatus())) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "该团队已经结束或已解散");
        }
        User actor = requireActor(command.ownerUserId());
        List<TeamTask> tasks = taskMapper.selectOpenByTeamIdForUpdate(team.getId());
        int changed = 0;
        Map<Long, Integer> affectedByAssignee = new LinkedHashMap<>();
        for (TeamTask task : tasks) {
            String status = task.getStatus() == null ? ""
                : task.getStatus().trim().toUpperCase(Locale.ROOT);
            if (!java.util.Set.of("PENDING", "IN_PROGRESS", "REVIEW").contains(status)) {
                continue;
            }
            updateVersionedTask(task, new UpdateWrapper<TeamTask>()
                .set("status", "CANCELLED")
                .set("completed_at", null));
            TeamTask updated = taskMapper.selectById(task.getId());
            reindex(updated);
            audit(updated, actor, "TASK_CANCELLED_BY_TEAM_DISSOLUTION",
                command.requestId(),
                Map.of("previousStatus", status, "reason", "TEAM_DISSOLVED"));
            if (task.getAssignedTo() != null
                && !Objects.equals(task.getAssignedTo(), actor.getId())) {
                affectedByAssignee.merge(task.getAssignedTo(), 1, Integer::sum);
            }
            changed++;
        }
        for (Map.Entry<Long, Integer> entry : affectedByAssignee.entrySet()) {
            String teamTitle = team.getTitle() == null || team.getTitle().trim().isEmpty()
                ? "未命名团队" : team.getTitle().trim();
            notificationService.create(entry.getKey(), actor.getId(), "TASK_CANCELLED",
                "未结束任务已取消",
                "团队「" + teamTitle
                    + "」已解散，你负责的 " + entry.getValue() + " 个未结束任务已取消",
                team.getId());
        }
        return changed;
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public TeamTaskCommands.MutationResult addParticipant(TeamTaskCommands.AddParticipant command) {
        TeamDemand team = requireTeamingTeam(command.teamId());
        TeamTask task = lockTask(command.taskId(), team.getId());
        User actor = requireActor(command.actorUserId());
        assertVersion(task, command.expectedVersion());
        if (!policy.canAddTaskParticipant(team, task, actor, command.participantUserId())) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "当前角色不能添加该参与者");
        }
        TaskParticipant existing = participantMapper.selectOne(
            new LambdaQueryWrapper<TaskParticipant>()
                .eq(TaskParticipant::getTaskId, task.getId())
                .eq(TaskParticipant::getUserId, command.participantUserId())
                .last("LIMIT 1"));
        Date now = new Date();
        if (existing == null) {
            TaskParticipant participant = new TaskParticipant();
            participant.setTaskId(task.getId());
            participant.setUserId(command.participantUserId());
            participant.setRole(normalizeParticipantRole(command.role()));
            participant.setStatus("active");
            participant.setJoinedAt(now);
            participantMapper.insert(participant);
        } else if (!"active".equalsIgnoreCase(existing.getStatus())) {
            existing.setRole(normalizeParticipantRole(command.role()));
            existing.setStatus("active");
            existing.setLeftAt(null);
            existing.setJoinedAt(now);
            participantMapper.updateById(existing);
        } else {
            return result(task, "参与者已在任务中");
        }
        bumpVersion(task);
        task = taskMapper.selectById(task.getId());
        reindex(task);
        audit(task, actor, "TASK_PARTICIPANT_ADDED", command.requestId(),
            Map.of("participantUserId", command.participantUserId(),
                "role", command.role() == null ? "" : command.role()));
        return result(task, "参与者已添加");
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public TeamTaskCommands.MutationResult removeParticipant(
        TeamTaskCommands.RemoveParticipant command) {
        TeamDemand team = requireTeamingTeam(command.teamId());
        TeamTask task = lockTask(command.taskId(), team.getId());
        User actor = requireActor(command.actorUserId());
        assertVersion(task, command.expectedVersion());
        if (!policy.canRemoveTaskParticipant(team, task, actor, command.participantUserId())) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "当前角色不能移除该参与者");
        }
        Date now = new Date();
        participantMapper.update(null, new UpdateWrapper<TaskParticipant>()
            .eq("task_id", task.getId())
            .eq("user_id", command.participantUserId())
            .eq("status", "active")
            .set("status", "left")
            .set("left_at", now));
        bumpVersion(task);
        task = taskMapper.selectById(task.getId());
        reindex(task);
        audit(task, actor, "TASK_PARTICIPANT_REMOVED", command.requestId(),
            Map.of("participantUserId", command.participantUserId()));
        return result(task, "参与者已移除");
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public TeamTaskCommands.MutationResult addComment(TeamTaskCommands.AddComment command) {
        TeamDemand team = requireTeamingTeam(command.teamId());
        TeamTask task = lockTask(command.taskId(), team.getId());
        User actor = requireActor(command.actorUserId());
        if (!policy.canReadTask(team, task, actor)) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "当前角色不能评论该任务");
        }
        String content = normalizeRequired(command.content(), 4000, "评论内容");
        if (command.parentId() != null) {
            TaskComment parent = commentMapper.selectById(command.parentId());
            if (parent == null || !Objects.equals(parent.getTaskId(), task.getId())
                || Integer.valueOf(1).equals(parent.getIsDeleted())) {
                fail(TeamAgentErrorCode.ACTION_SELECTION_INVALID, "回复的评论不存在");
            }
        }
        Date now = new Date();
        TaskComment comment = new TaskComment();
        comment.setTaskId(task.getId());
        comment.setUserId(actor.getId());
        comment.setContent(content);
        comment.setParentId(command.parentId());
        comment.setCommentType(command.parentId() == null ? "comment" : "reply");
        comment.setLikeCount(0);
        comment.setIsDeleted(0);
        comment.setCreatedAt(now);
        comment.setUpdatedAt(now);
        if (commentMapper.insert(comment) != 1) {
            fail(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED, "评论保存失败");
        }
        audit(task, actor, "TASK_COMMENT_ADDED", command.requestId(),
            Map.of("commentId", comment.getId(),
                "isReply", command.parentId() != null));
        return result(task, "评论已发布");
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public TeamTaskCommands.MutationResult submitTask(TeamTaskCommands.SubmitTask command) {
        TeamDemand team = requireTeamingTeam(command.teamId());
        TeamTask task = lockTask(command.taskId(), team.getId());
        User actor = requireActor(command.actorUserId());
        if (!policy.canSubmitTask(team, task, actor)) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "仅当前任务负责人可在进行中提交");
        }
        String content = normalizeOptional(command.content(), 8000, "提交说明");
        String attachments = serializeJson(command.attachments(), "附件");

        Date now = new Date();
        TaskSubmission submission = new TaskSubmission();
        submission.setTaskId(task.getId());
        submission.setSubmitterId(actor.getId());
        submission.setContent(content == null ? "" : content);
        submission.setAttachments(attachments);
        submission.setCreatedAt(now);
        if (submissionMapper.insert(submission) != 1) {
            fail(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED, "任务提交保存失败");
        }
        if (content != null) {
            TaskComment comment = new TaskComment();
            comment.setTaskId(task.getId());
            comment.setUserId(actor.getId());
            comment.setContent(content);
            comment.setCommentType("comment");
            comment.setLikeCount(0);
            comment.setIsDeleted(0);
            comment.setCreatedAt(now);
            comment.setUpdatedAt(now);
            commentMapper.insert(comment);
        }
        updateVersionedTask(task, new UpdateWrapper<TeamTask>()
            .set("status", "REVIEW")
            .set("completed_at", null));
        task = taskMapper.selectById(task.getId());
        reindex(task);
        notifyTaskAudience(team, task, actor, "TASK_SUBMITTED", "任务已提交",
            DisplayNameResolver.resolve(actor) + " 提交了任务「" + task.getTaskTitle() + "」，请审核");
        audit(task, actor, "TASK_SUBMITTED", command.requestId(),
            Map.of("submissionId", submission.getId(),
                "targetStatus", "REVIEW"));
        return result(task, "任务已提交，等待审核");
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public TeamTaskCommands.MutationResult reviewTask(TeamTaskCommands.ReviewTask command) {
        TeamDemand team = requireTeamingTeam(command.teamId());
        TeamTask task = lockTask(command.taskId(), team.getId());
        User actor = requireActor(command.actorUserId());
        String action = normalizeReviewAction(command.action());
        if (!policy.canReviewTask(team, task, actor)
            && !("COMMENT".equals(action) && policy.canReadAllTasks(team, actor))) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "只有队长或导师可以审核该任务");
        }
        Long submissionId = command.submissionId();
        if (submissionId != null) {
            TaskSubmission submission = submissionMapper.selectById(submissionId);
            if (submission == null || !Objects.equals(submission.getTaskId(), task.getId())) {
                fail(TeamAgentErrorCode.ACTION_SELECTION_INVALID, "提交记录不属于当前任务");
            }
        }
        String overall = normalizeOptional(command.overallComment(), 4000, "整体点评");
        String fileComments = serializeJson(command.fileComments(), "文件点评");
        if ("RETURNED".equals(action) && overall == null && !hasFileComment(command.fileComments())) {
            fail(TeamAgentErrorCode.REQUEST_INVALID, "驳回时至少填写一条点评");
        }

        if (!"COMMENT".equals(action)) {
            String target = "COMPLETED".equals(action) ? "COMPLETED" : "IN_PROGRESS";
            updateVersionedTask(task, new UpdateWrapper<TeamTask>()
                .set("status", target)
                .set("completed_at", "COMPLETED".equals(target) ? new Date() : null));
            task = taskMapper.selectById(task.getId());
            reindex(task);
        }

        TaskReview review = new TaskReview();
        review.setTaskId(task.getId());
        review.setSubmissionId(submissionId);
        review.setReviewerId(actor.getId());
        review.setVerdict("COMMENT".equals(action) ? "COMMENT"
            : ("COMPLETED".equals(action) ? "PASS" : "REJECT"));
        review.setOverallComment(overall);
        review.setFileComments(fileComments);
        review.setCreatedAt(new Date());
        if (reviewMapper.insert(review) != 1) {
            fail(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED, "审核记录保存失败");
        }

        TaskSubmission latest = submissionId == null
            ? submissionMapper.selectOne(new LambdaQueryWrapper<TaskSubmission>()
                .eq(TaskSubmission::getTaskId, task.getId())
                .orderByDesc(TaskSubmission::getCreatedAt)
                .last("LIMIT 1"))
            : submissionMapper.selectById(submissionId);
        Long recipient = latest == null ? task.getAssignedTo() : latest.getSubmitterId();
        if (recipient != null && !Objects.equals(recipient, actor.getId())) {
            String title = "COMPLETED".equals(action) ? "任务已通过"
                : "RETURNED".equals(action) ? "任务被驳回" : "收到新的批阅点评";
            String suffix = overall == null ? "" : "：" + truncate(overall, 80);
            notificationService.create(recipient, actor.getId(), "TASK_REVIEWED", title,
                DisplayNameResolver.resolve(actor) + " " + title + "「" + task.getTaskTitle() + "」"
                    + suffix, task.getId());
        }
        audit(task, actor, "TASK_REVIEWED", command.requestId(),
            Map.of("action", action,
                "submissionId", Objects.toString(submissionId, "")));
        return result(task, "COMMENT".equals(action) ? "点评已保存"
            : "COMPLETED".equals(action) ? "任务已确认完成" : "已退回修改");
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public TeamTaskCommands.MutationResult sendReminder(TeamTaskCommands.SendReminder command) {
        TeamDemand team = requireTeamingTeam(command.teamId());
        TeamTask task = lockTask(command.taskId(), team.getId());
        User actor = requireActor(command.actorUserId());
        Long recipient = command.recipientUserId() == null
            ? task.getAssignedTo() : command.recipientUserId();
        if (!policy.canSendTaskReminder(team, task, actor, recipient)) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "当前角色不能提醒该负责人");
        }
        String detail = normalizeOptional(command.message(), 500, "提醒内容");
        notificationService.create(recipient, actor.getId(), "TASK_REMINDER", "任务提醒",
            DisplayNameResolver.resolve(actor) + " 提醒你处理任务「" + task.getTaskTitle() + "」"
                + (detail == null ? "" : "：" + detail), task.getId());
        audit(task, actor, "TASK_REMINDER_SENT", command.requestId(),
            Map.of("recipientUserId", recipient));
        return result(task, "提醒已发送");
    }

    private void updateVersionedTask(TeamTask task, UpdateWrapper<TeamTask> values) {
        Date now = new Date();
        int updated = taskMapper.update(null, values
            .eq("id", task.getId())
            .eq("version", task.getVersion())
            .set("updated_at", now)
            .setSql("version = version + 1"));
        if (updated != 1) versionConflict();
    }

    private void bumpVersion(TeamTask task) {
        updateVersionedTask(task, new UpdateWrapper<>());
    }

    private void retireLead(Long taskId, Long userId, Date now) {
        if (userId == null) return;
        TaskParticipant lead = participantMapper.selectOne(new LambdaQueryWrapper<TaskParticipant>()
            .eq(TaskParticipant::getTaskId, taskId)
            .eq(TaskParticipant::getUserId, userId)
            .last("LIMIT 1"));
        if (lead != null && "lead".equalsIgnoreCase(lead.getRole())) {
            lead.setStatus("left");
            lead.setLeftAt(now);
            participantMapper.updateById(lead);
        }
    }

    private void upsertLead(Long taskId, Long userId, Date now) {
        upsertParticipant(taskId, userId, "lead", now);
    }

    private void upsertOwner(Long taskId, Long userId, Date now) {
        upsertParticipant(taskId, userId, "owner", now);
    }

    private void upsertParticipant(Long taskId, Long userId, String role, Date now) {
        if (userId == null) return;
        TaskParticipant existing = participantMapper.selectOne(
            new LambdaQueryWrapper<TaskParticipant>()
                .eq(TaskParticipant::getTaskId, taskId)
                .eq(TaskParticipant::getUserId, userId)
                .last("LIMIT 1"));
        if (existing == null) {
            TaskParticipant participant = new TaskParticipant();
            participant.setTaskId(taskId);
            participant.setUserId(userId);
            participant.setRole(role);
            participant.setStatus("active");
            participant.setJoinedAt(now);
            participantMapper.insert(participant);
        } else {
            existing.setRole(role);
            existing.setStatus("active");
            existing.setLeftAt(null);
            participantMapper.updateById(existing);
        }
    }

    private void reindex(TeamTask task) {
        if (task == null) return;
        indexService.enqueueUpsert("TASK", task.getId(), task.getTaskTitle(),
            snapshotSerializer.serialize(task), KgEntity.SCOPE_TEAM, task.getTeamId(),
            task.getAssignedTo() == null ? task.getCreatedBy() : task.getAssignedTo());
    }

    private void audit(TeamTask task, User actor, String eventType, String requestId,
                       Map<String, Object> metadata) {
        if (task == null) return;
        TeamAgentAuditLog log = new TeamAgentAuditLog();
        log.setTeamId(task.getTeamId());
        log.setActorType(actor == null ? "SYSTEM" : "USER");
        log.setActorUserId(actor == null ? null : actor.getId());
        log.setEventType(eventType);
        log.setOutcome("SUCCESS");
        log.setRequestId(normalizeAuditRequestId(requestId));
        try {
            log.setMetadataJson(objectMapper.writeValueAsString(
                metadata == null ? Map.of() : metadata));
        } catch (JsonProcessingException e) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                "任务审计记录生成失败");
        }
        log.setCreatedAt(new Date());
        if (auditMapper.insert(log) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                "任务审计记录保存失败");
        }
    }

    private Map<String, Object> commandMetadata(Long agentActionId,
                                                Map<String, Object> facts) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "TEAM_TASK_COMMAND");
        if (agentActionId != null) {
            metadata.put("agentActionId", agentActionId);
        }
        if (facts != null) {
            metadata.putAll(facts);
        }
        return metadata;
    }

    private String normalizeAuditRequestId(String requestId) {
        String normalized = requestId == null ? "" : requestId.trim();
        if (normalized.isEmpty()) {
            return "task-" + UUID.randomUUID();
        }
        return normalized.length() <= 64
            ? normalized : TeamAgentHashes.contentHash("task-audit-request", normalized);
    }

    private void notifyAssigned(TeamDemand team, TeamTask task, User actor, Long assigneeId,
                                String title, String content) {
        if (assigneeId == null || Objects.equals(assigneeId, actor.getId())) return;
        notificationService.create(assigneeId, actor.getId(), "TASK_ASSIGNED", title, content,
            task.getId());
    }

    private TeamDemand requireTeamingTeam(Long teamId) {
        TeamDemand team = teamId == null ? null : teamDemandMapper.selectByIdForUpdate(teamId);
        if (team == null) fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "团队不存在");
        if (!TEAMING.equals(team.getStatus())) {
            fail(TeamAgentErrorCode.ACTION_PERMISSION_CHANGED, "当前团队状态不允许修改任务");
        }
        return team;
    }

    private User requireActor(Long userId) {
        User user = userId == null ? null : userService.getById(userId);
        if (user == null) fail(TeamAgentErrorCode.AUTH_REQUIRED, "登录状态已失效");
        return user;
    }

    private TeamTask requireTask(Long taskId) {
        TeamTask task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null) fail(TeamAgentErrorCode.ACTION_SELECTION_INVALID, "任务不存在");
        return task;
    }

    private TeamTask lockTask(Long taskId, Long teamId) {
        TeamTask task = taskId == null ? null : taskMapper.selectByIdForUpdate(taskId);
        if (task == null || !Objects.equals(task.getTeamId(), teamId)) {
            fail(TeamAgentErrorCode.ACTION_SELECTION_INVALID, "任务不存在");
        }
        return task;
    }

    private void assertVersion(TeamTask task, Integer expectedVersion) {
        if (expectedVersion == null) return;
        int current = task.getVersion() == null ? 0 : task.getVersion();
        if (current != expectedVersion) versionConflict();
    }

    private void validateCreate(TeamTaskCommands.CreateTask command) {
        normalizeRequired(command.title(), 200, "任务名称");
        normalizeOptional(command.description(), 4000, "任务描述");
        normalizeType(command.taskType());
        validatePriority(command.priority() == null ? 2 : command.priority());
        validateEstimatedHours(command.estimatedHours());
        if (command.deadline() != null && command.deadline().before(new Date())) {
            fail(TeamAgentErrorCode.REQUEST_INVALID, "截止日期不能早于当前时间");
        }
    }

    private void validatePriority(Integer priority) {
        if (priority == null || priority < 1 || priority > 4) {
            fail(TeamAgentErrorCode.REQUEST_INVALID, "优先级需在 1-4 之间");
        }
    }

    private void validateEstimatedHours(BigDecimal hours) {
        if (hours != null && (hours.compareTo(BigDecimal.ZERO) < 0
            || hours.compareTo(new BigDecimal("999")) > 0)) {
            fail(TeamAgentErrorCode.REQUEST_INVALID, "预估工时需在 0-999 之间");
        }
    }

    private String normalizeReviewAction(String value) {
        String action = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("COMPLETED", "RETURNED", "COMMENT").contains(action)) {
            fail(TeamAgentErrorCode.REQUEST_INVALID, "无效的审核操作");
        }
        return action;
    }

    private boolean hasFileComment(Object fileComments) {
        if (!(fileComments instanceof List)) return false;
        for (Object item : (List<?>) fileComments) {
            if (item instanceof java.util.Map) {
                Object comment = ((java.util.Map<?, ?>) item).get("comment");
                if (comment != null && !String.valueOf(comment).isBlank()) return true;
            }
        }
        return false;
    }

    private String serializeJson(Object value, String label) {
        if (value == null) return null;
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? null : text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            fail(TeamAgentErrorCode.REQUEST_INVALID, label + "格式不合法");
            return null;
        }
    }

    private void notifyTaskAudience(TeamDemand team, TeamTask task, User actor,
                                    String type, String title, String content) {
        java.util.LinkedHashSet<Long> recipients = new java.util.LinkedHashSet<>();
        if (team.getCreatorId() != null) recipients.add(team.getCreatorId());
        teamApplicationMapper.selectList(new LambdaQueryWrapper<TeamApplication>()
                .eq(TeamApplication::getTeamId, team.getId())
                .eq(TeamApplication::getStatus, "APPROVED")
                .eq(TeamApplication::getMemberRole, "MENTOR"))
            .stream()
            .map(TeamApplication::getUserId)
            .filter(Objects::nonNull)
            .forEach(recipients::add);
        recipients.remove(actor.getId());
        for (Long recipient : recipients) {
            notificationService.create(recipient, actor.getId(), type, title, content, task.getId());
        }
    }

    private String normalizeRequired(String value, int max, String label) {
        String normalized = TeamAgentHashes.normalizeText(value);
        if (normalized == null || normalized.trim().isEmpty()) {
            fail(TeamAgentErrorCode.REQUEST_INVALID, label + "不能为空");
        }
        normalized = normalized.trim();
        if (normalized.length() > max) {
            fail(TeamAgentErrorCode.REQUEST_INVALID, label + "过长");
        }
        return normalized;
    }

    private String normalizeOptional(String value, int max, String label) {
        String normalized = TeamAgentHashes.normalizeText(value);
        if (normalized != null) normalized = normalized.trim();
        if (normalized != null && normalized.length() > max) {
            fail(TeamAgentErrorCode.REQUEST_INVALID, label + "过长");
        }
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }

    private String normalizeType(String value) {
        String type = value == null ? "OTHER" : value.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            fail(TeamAgentErrorCode.REQUEST_INVALID, "任务类型不合法");
        }
        return type;
    }

    private String normalizeParticipantRole(String role) {
        String value = role == null ? "member" : role.trim().toLowerCase(Locale.ROOT);
        return java.util.Set.of("owner", "lead", "member", "reviewer").contains(value)
            ? value : "member";
    }

    private TeamTaskCommands.MutationResult result(TeamTask task, String message) {
        return new TeamTaskCommands.MutationResult(task.getId(), task.getVersion(),
            snapshotSerializer.serialize(task), message);
    }

    private static void fail(TeamAgentErrorCode code, String message) {
        throw new TeamAgentException(code, message);
    }

    private static void versionConflict() {
        throw new TeamAgentException(TeamAgentErrorCode.ACTION_VERSION_CONFLICT,
            "任务已被修改，请刷新后重试");
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max) + "…";
    }
}
