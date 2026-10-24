package cn.ilink.teamagent;

import cn.ilink.config.AiProperties;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.entity.TeamDemand;
import cn.ilink.mapper.TeamAgentRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TeamAgentContextService {
    private final TeamAgentAuthorization authorization;
    private final TeamAgentMetricsService metricsService;
    private final TeamAgentConversationService conversationService;
    private final TeamAgentRunMapper runMapper;
    private final TeamAgentProperties properties;
    private final AiProperties aiProperties;

    public TeamAgentContextService(TeamAgentAuthorization authorization,
                                   TeamAgentMetricsService metricsService,
                                   TeamAgentConversationService conversationService,
                                   TeamAgentRunMapper runMapper,
                                   TeamAgentProperties properties,
                                   AiProperties aiProperties) {
        this.authorization = authorization;
        this.metricsService = metricsService;
        this.conversationService = conversationService;
        this.runMapper = runMapper;
        this.properties = properties;
        this.aiProperties = aiProperties;
    }

    public Map<String, Object> bootstrap(Long teamId, Long userId) {
        requireEnabled();
        TeamAgentScope scope = authorization.requireScope(teamId, userId);
        TeamAgentMetricsService.Snapshot snapshot = metricsService.snapshot(scope);
        TeamDemand team = snapshot.team();

        Map<String, Object> teamData = new LinkedHashMap<>();
        teamData.put("id", team.getId());
        teamData.put("name", team.getTitle());
        teamData.put("status", team.getStatus());
        teamData.put("role", scope.role().name());
        teamData.put("mentorCount", snapshot.mentorCount());
        teamData.put("memberCount", snapshot.memberCount());

        Map<String, Object> competition = new LinkedHashMap<>();
        competition.put("competitionId", scope.competitionId());
        competition.put("name", scope.catalogCompetitionName());
        competition.put("customCompetition",
            scope.catalogCompetitionName() == null ? scope.customCompetition() : null);
        competition.put("resolution", scope.competitionId() != null ? "CATALOG"
            : scope.customCompetition() != null ? "CUSTOM" : "UNBOUND");

        boolean teamReadable = !"DISSOLVED".equalsIgnoreCase(team.getStatus());
        boolean canPropose = properties.isActionsEnabled() && scope.actionsEnabled();
        Map<String, Object> capabilities = new LinkedHashMap<>();
        boolean aiConfigured = aiProperties.isConfigured();
        boolean canProposeCollaboration =
            properties.isActionsEnabled() && scope.collaborationActionsEnabled();
        capabilities.put("canAskKnowledge", aiConfigured);
        capabilities.put("canPlanActions", aiConfigured && canPropose);
        capabilities.put("canReadCollaborationAssets", teamReadable);
        capabilities.put("canProposeCollaborationActions", aiConfigured && canProposeCollaboration);
        capabilities.put("canReadAllTasks", scope.canReadAllTasks());
        capabilities.put("canProposeTaskActions", canPropose);
        List<String> reasons = new java.util.ArrayList<>();
        if ("CLOSED".equalsIgnoreCase(team.getStatus())) reasons.add("TEAM_CLOSED");
        if (!scope.isLeader()) reasons.add("LEADER_REQUIRED_FOR_TASK_ACTIONS");
        if (!properties.isActionsEnabled()) reasons.add("ACTIONS_DISABLED");
        if (!aiConfigured) reasons.add("AI_NOT_CONFIGURED");
        if (!scope.collaborationActionsEnabled()) reasons.add("TEAM_READ_ONLY_FOR_COLLABORATION");
        capabilities.put("disabledReasons", List.copyOf(reasons));
        capabilities.put("readOnly", !canPropose);
        capabilities.put("collaborationReadOnly", !scope.collaborationActionsEnabled());

        TeamAgentRun active = runMapper.selectOne(new LambdaQueryWrapper<TeamAgentRun>()
            .eq(TeamAgentRun::getTeamId, teamId)
            .eq(TeamAgentRun::getUserId, userId)
            .in(TeamAgentRun::getStatus,
                List.of(TeamAgentRun.STATUS_QUEUED, TeamAgentRun.STATUS_RUNNING))
            .orderByDesc(TeamAgentRun::getId)
            .last("LIMIT 1"));
        Map<String, Object> conversations = conversationService.list(teamId, userId, 1, null);
        Object recent = conversations.get("items") instanceof List<?> rows && !rows.isEmpty()
            ? rows.get(0) : null;

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("messageMaxChars", 500);
        limits.put("answerMaxChars", 12000);
        limits.put("modelCallsMax", 12);
        limits.put("modelTokensMax", 20000);
        limits.put("concurrentRun", 1);
        limits.put("actionProposalTtlSeconds", 600);
        limits.put("maxActionsPerGroup", TeamAgentActionSchema.MAX_ACTIONS);
        limits.put("maxCreateActionsPerGroup", TeamAgentActionSchema.MAX_CREATES);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("team", teamData);
        result.put("competition", competition);
        result.put("capabilities", capabilities);
        result.put("counts", snapshot.counts());
        result.put("knowledgeFreshness", snapshot.freshness().toMap());
        result.put("activeRunId", active == null ? null : active.getId());
        result.put("recentConversation", recent);
        result.put("limits", limits);
        result.put("serverTime", TeamAgentTime.iso(Instant.now()));
        result.put("readable", teamReadable);
        return result;
    }

    public TeamAgentScope scope(Long teamId, Long userId) {
        requireEnabled();
        return authorization.requireScope(teamId, userId);
    }

    public void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new TeamAgentException(TeamAgentErrorCode.AGENT_ACTIONS_DISABLED,
                "团队 Agent 工作台当前未开放");
        }
    }
}
