package cn.ilink.teamagent;

import java.time.Instant;
import java.util.Date;

/** Server-derived authorization scope. No field is accepted from model tool input. */
public final class TeamAgentScope {
    public enum TeamRole { LEADER, MENTOR, STUDENT }
    public enum TaskReadMode { ALL, RELATION_ONLY }

    private final Long userId;
    private final Long teamId;
    private final String teamName;
    private final String teamStatus;
    private final TeamRole role;
    private final TaskReadMode taskReadMode;
    private final Integer competitionId;
    private final String catalogCompetitionName;
    private final String customCompetition;
    private final Date teamDeadline;
    private final Instant generatedAt;

    public TeamAgentScope(Long userId, Long teamId, String teamName, String teamStatus,
                          TeamRole role, TaskReadMode taskReadMode, Integer competitionId,
                          String catalogCompetitionName, String customCompetition,
                          Date teamDeadline, Instant generatedAt) {
        this.userId = userId;
        this.teamId = teamId;
        this.teamName = teamName;
        this.teamStatus = teamStatus;
        this.role = role;
        this.taskReadMode = taskReadMode;
        this.competitionId = competitionId;
        this.catalogCompetitionName = catalogCompetitionName;
        this.customCompetition = customCompetition;
        this.teamDeadline = teamDeadline;
        this.generatedAt = generatedAt;
    }

    public Long userId() { return userId; }
    public Long teamId() { return teamId; }
    public String teamName() { return teamName; }
    public String teamStatus() { return teamStatus; }
    public TeamRole role() { return role; }
    public TaskReadMode taskReadMode() { return taskReadMode; }
    public Integer competitionId() { return competitionId; }
    public String catalogCompetitionName() { return catalogCompetitionName; }
    public String customCompetition() { return customCompetition; }
    public Date teamDeadline() { return teamDeadline == null ? null : new Date(teamDeadline.getTime()); }
    public Instant generatedAt() { return generatedAt; }
    public boolean privateGraphAllowed() { return false; }
    public boolean canReadAllTasks() { return taskReadMode == TaskReadMode.ALL; }
    public boolean isLeader() { return role == TeamRole.LEADER; }
    public boolean isMentor() { return role == TeamRole.MENTOR; }
    public boolean actionsEnabled() { return isLeader() && "TEAMING".equals(teamStatus); }

    /**
     * Collaboration assets are editable by every active member while the team is
     * TEAMING, so a student may confirm a document, table or material suggestion
     * even though only the leader may confirm task actions. Per-asset permission
     * is still enforced by TeamCollaborationAuthorization at prepare and execute
     * time; this only decides whether such a proposal may exist at all.
     */
    public boolean collaborationActionsEnabled() { return "TEAMING".equals(teamStatus); }
}
