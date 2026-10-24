package cn.ilink.teamagent;

import cn.ilink.entity.TaskParticipant;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.mapper.TaskParticipantMapper;
import cn.ilink.service.TeamMembershipService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Single object-level task authorization used by both human pages and Agent
 * proposals/execution.
 */
@Service
public class TeamTaskPolicy {
    private final TeamMembershipService membershipService;
    private final TaskParticipantMapper participantMapper;

    public TeamTaskPolicy(TeamMembershipService membershipService,
                          TaskParticipantMapper participantMapper) {
        this.membershipService = membershipService;
        this.participantMapper = participantMapper;
    }

    public boolean isActiveMember(TeamDemand team, Long userId) {
        return team != null && userId != null
            && (TeamMembershipService.isOwner(team, userId)
                || membershipService.isApprovedMember(team.getId(), userId));
    }

    public boolean canReadTask(TeamDemand team, TeamTask task, User user) {
        if (team == null || task == null || user == null
            || !Objects.equals(team.getId(), task.getTeamId())
            || !isActiveMember(team, user.getId())
            || "DISSOLVED".equals(team.getStatus())) {
            return false;
        }
        if (canReadAllTasks(team, user)) {
            return true;
        }
        return Objects.equals(task.getAssignedTo(), user.getId())
            || hasActiveParticipant(task, user.getId());
    }

    public boolean canReadAllTasks(TeamDemand team, User user) {
        if (team == null || user == null) return false;
        return TeamMembershipService.isOwner(team, user.getId())
            || membershipService.isMentorMember(team.getId(), user.getId());
    }

    public boolean canOperateTask(TeamDemand team, TeamTask task, User user) {
        if (!taskFeatureEnabled(team) || !canReadTask(team, task, user)) {
            return false;
        }
        return TeamMembershipService.isOwner(team, user.getId())
            || Objects.equals(task.getAssignedTo(), user.getId())
            || hasActiveParticipant(task, user.getId());
    }

    public boolean canChangeTaskStatus(TeamDemand team, TeamTask task, User user,
                                       String targetStatus) {
        if (targetStatus == null || !canOperateTask(team, task, user)) {
            return false;
        }
        String expected = task.getStatus();
        String target = targetStatus.trim().toUpperCase(java.util.Locale.ROOT);
        if (expected == null) return false;
        expected = expected.trim().toUpperCase(java.util.Locale.ROOT);
        return ("PENDING".equals(expected) && "IN_PROGRESS".equals(target))
            || ("IN_PROGRESS".equals(expected) && "REVIEW".equals(target))
            || ("REVIEW".equals(expected)
                && ("COMPLETED".equals(target) || "IN_PROGRESS".equals(target)))
            || (!Boolean.TRUE.equals(task.getRequiresReview())
                && "IN_PROGRESS".equals(expected) && "COMPLETED".equals(target));
    }

    public boolean canSubmitTask(TeamDemand team, TeamTask task, User user) {
        return canReadTask(team, task, user)
            && Objects.equals(task.getAssignedTo(), user.getId())
            && "IN_PROGRESS".equalsIgnoreCase(task.getStatus());
    }

    public boolean canReviewTask(TeamDemand team, TeamTask task, User user) {
        return canReadTask(team, task, user)
            && "REVIEW".equalsIgnoreCase(task.getStatus())
            && (TeamMembershipService.isOwner(team, user.getId())
                || membershipService.isMentorMember(team.getId(), user.getId()));
    }

    public boolean canCreateTask(TeamDemand team, User user, Long assigneeId, TeamTask parent) {
        if (!taskFeatureEnabled(team) || user == null || assigneeId == null
            || !isActiveMember(team, assigneeId)) {
            return false;
        }
        if (TeamMembershipService.isOwner(team, user.getId())) {
            return parent == null || validParent(team, parent, user);
        }
        return parent != null && validParent(team, parent, user)
            && Objects.equals(parent.getAssignedTo(), user.getId())
            && Objects.equals(assigneeId, user.getId());
    }

    public boolean canAssignTask(TeamDemand team, TeamTask task, User user, Long assigneeId) {
        return taskFeatureEnabled(team) && TeamMembershipService.isOwner(team, user.getId())
            && task != null && Objects.equals(task.getTeamId(), team.getId())
            && assigneeId != null && isActiveMember(team, assigneeId);
    }

    public boolean canAdjustTask(TeamDemand team, TeamTask task, User user) {
        return taskFeatureEnabled(team) && TeamMembershipService.isOwner(team, user.getId())
            && task != null && Objects.equals(task.getTeamId(), team.getId());
    }

    public boolean canSendTaskReminder(TeamDemand team, TeamTask task, User user, Long recipientId) {
        return canAdjustTask(team, task, user) && recipientId != null
            && Objects.equals(recipientId, task.getAssignedTo());
    }

    public boolean canDeleteTask(TeamDemand team, TeamTask task, User user) {
        return canAdjustTask(team, task, user);
    }

    public boolean canAddTaskParticipant(TeamDemand team, TeamTask task, User user,
                                         Long participantId) {
        return canAdjustTask(team, task, user) && participantId != null
            && isActiveMember(team, participantId);
    }

    public boolean canRemoveTaskParticipant(TeamDemand team, TeamTask task, User user,
                                            Long participantId) {
        return canAdjustTask(team, task, user) && participantId != null
            && !Objects.equals(participantId, task.getAssignedTo());
    }

    public boolean hasActiveParticipant(TeamTask task, Long userId) {
        if (task == null || userId == null) return false;
        TaskParticipant participant = participantMapper.selectOne(
            new LambdaQueryWrapper<TaskParticipant>()
                .eq(TaskParticipant::getTaskId, task.getId())
                .eq(TaskParticipant::getUserId, userId)
                .eq(TaskParticipant::getStatus, "active")
                .last("LIMIT 1"));
        if (participant == null
            || !Objects.equals(participant.getTaskId(), task.getId())
            || !Objects.equals(participant.getUserId(), userId)
            || !"active".equalsIgnoreCase(participant.getStatus())) {
            return false;
        }
        if ("lead".equalsIgnoreCase(participant.getRole())) {
            return Objects.equals(task.getAssignedTo(), userId);
        }
        if ("owner".equalsIgnoreCase(participant.getRole())) {
            return Objects.equals(task.getCreatedBy(), userId);
        }
        return true;
    }

    private boolean validParent(TeamDemand team, TeamTask parent, User user) {
        return Objects.equals(parent.getTeamId(), team.getId())
            && parent.getParentTaskId() == null
            && ("PENDING".equalsIgnoreCase(parent.getStatus())
                || "IN_PROGRESS".equalsIgnoreCase(parent.getStatus()))
            && canReadTask(team, parent, user);
    }

    private boolean taskFeatureEnabled(TeamDemand team) {
        return team != null && "TEAMING".equals(team.getStatus());
    }
}
