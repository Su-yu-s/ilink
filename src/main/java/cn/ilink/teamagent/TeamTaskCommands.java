package cn.ilink.teamagent;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

public final class TeamTaskCommands {
    private TeamTaskCommands() {}

    public record CreateTask(
        Long teamId,
        Long actorUserId,
        String requestId,
        Long agentActionId,
        String title,
        String description,
        String taskType,
        Integer priority,
        BigDecimal estimatedHours,
        Date deadline,
        Long assignedTo,
        Boolean requiresReview,
        Long parentTaskId
    ) {}

    public record AssignTask(
        Long teamId,
        Long actorUserId,
        Long taskId,
        Integer expectedVersion,
        Long assignedTo,
        String requestId,
        Long agentActionId
    ) {}

    public record SetPriority(
        Long teamId,
        Long actorUserId,
        Long taskId,
        Integer expectedVersion,
        Integer priority,
        String requestId,
        Long agentActionId
    ) {}

    public record SetDeadline(
        Long teamId,
        Long actorUserId,
        Long taskId,
        Integer expectedVersion,
        Date deadline,
        String requestId,
        Long agentActionId
    ) {}

    /**
     * Human-facing edit patch. Null means "leave unchanged" for ordinary
     * scalar fields; the explicit deadline command is used when a nullable
     * field must be cleared.
     */
    public record UpdateTask(
        Long teamId,
        Long actorUserId,
        Long taskId,
        Integer expectedVersion,
        String title,
        String description,
        String taskType,
        BigDecimal estimatedHours,
        Boolean deadlinePresent,
        Date deadline,
        Boolean requiresReview,
        Long assignedTo,
        String requestId
    ) {}

    public record ChangeStatus(
        Long teamId,
        Long actorUserId,
        Long taskId,
        Integer expectedVersion,
        String targetStatus,
        String requestId,
        Long agentActionId
    ) {}

    public record DeleteTask(
        Long teamId,
        Long actorUserId,
        Long taskId,
        Integer expectedVersion,
        String requestId,
        Long agentActionId
    ) {}

    /**
     * Strict compensation used only by Agent undo. Unlike the human delete
     * command it refuses to erase a task that has received any later work.
     */
    public record UndoCreatedTask(
        Long teamId,
        Long actorUserId,
        Long taskId,
        Integer expectedVersion,
        String requestId,
        Long agentActionId
    ) {}

    public record ReleaseMemberTasks(
        Long teamId,
        Long operatorUserId,
        Long targetUserId,
        String membershipOperation,
        String requestId
    ) {}

    /**
     * Team dissolution is the only path that cancels every unfinished task in
     * one command. It still locks the team before any task row, advances each
     * task version and emits the same index revision as an ordinary status
     * change.
     */
    public record CancelOpenTasksForDissolution(
        Long teamId,
        Long ownerUserId,
        String requestId
    ) {}

    public record AddParticipant(
        Long teamId,
        Long actorUserId,
        Long taskId,
        Integer expectedVersion,
        Long participantUserId,
        String role,
        String requestId
    ) {}

    public record RemoveParticipant(
        Long teamId,
        Long actorUserId,
        Long taskId,
        Integer expectedVersion,
        Long participantUserId,
        String requestId
    ) {}

    public record AddComment(
        Long teamId,
        Long actorUserId,
        Long taskId,
        String content,
        Long parentId,
        String requestId
    ) {}

    public record SubmitTask(
        Long teamId,
        Long actorUserId,
        Long taskId,
        String content,
        Object attachments,
        String requestId
    ) {}

    public record ReviewTask(
        Long teamId,
        Long actorUserId,
        Long taskId,
        Long submissionId,
        String action,
        String overallComment,
        List<?> fileComments,
        String requestId
    ) {}

    public record SendReminder(
        Long teamId,
        Long actorUserId,
        Long taskId,
        Long recipientUserId,
        String message,
        String requestId
    ) {}

    public record MutationResult(
        Long taskId,
        Integer version,
        String snapshot,
        String actionMessage
    ) {}
}
