package cn.ilink.teamagent;

import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.Competition;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.CompetitionMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Rebuilds team Agent authorization from durable database state on every
 * request/tool checkpoint. Admin status grants no implicit team access.
 */
@Service
public class TeamAgentAuthorization {
    private final TeamDemandMapper teamDemandMapper;
    private final TeamApplicationMapper teamApplicationMapper;
    private final CompetitionMapper competitionMapper;
    private final UserService userService;

    public TeamAgentAuthorization(TeamDemandMapper teamDemandMapper,
                                  TeamApplicationMapper teamApplicationMapper,
                                  CompetitionMapper competitionMapper,
                                  UserService userService) {
        this.teamDemandMapper = teamDemandMapper;
        this.teamApplicationMapper = teamApplicationMapper;
        this.competitionMapper = competitionMapper;
        this.userService = userService;
    }

    public TeamAgentScope requireScope(Long teamId, Long userId) {
        if (teamId == null || teamId <= 0 || userId == null || userId <= 0) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID, "团队或用户标识无效");
        }
        TeamDemand team = teamDemandMapper.selectById(teamId);
        if (team == null || "DISSOLVED".equals(team.getStatus())) {
            throw new TeamAgentException(TeamAgentErrorCode.TEAM_ACCESS_DENIED, "团队不存在或不可访问");
        }

        TeamAgentScope.TeamRole role;
        if (userId.equals(team.getCreatorId())) {
            role = TeamAgentScope.TeamRole.LEADER;
        } else {
            TeamApplication membership = teamApplicationMapper.selectOne(
                new LambdaQueryWrapper<TeamApplication>()
                    .eq(TeamApplication::getTeamId, teamId)
                    .eq(TeamApplication::getUserId, userId)
                    .eq(TeamApplication::getStatus, "APPROVED")
                    .last("LIMIT 1"));
            if (membership == null) {
                throw new TeamAgentException(TeamAgentErrorCode.TEAM_ACCESS_DENIED,
                    "你不是该团队的当前成员");
            }
            role = "MENTOR".equals(membership.getMemberRole())
                ? TeamAgentScope.TeamRole.MENTOR : TeamAgentScope.TeamRole.STUDENT;
        }

        String catalogName = null;
        if (team.getCompetitionId() != null) {
            Competition competition = competitionMapper.selectById(team.getCompetitionId());
            if (competition == null) {
                throw new TeamAgentException(TeamAgentErrorCode.TEAM_ACCESS_DENIED,
                    "团队绑定的竞赛目录已失效，请联系队长更新竞赛信息");
            }
            catalogName = competition.getName();
        }
        TeamAgentScope.TaskReadMode readMode =
            role == TeamAgentScope.TeamRole.LEADER || role == TeamAgentScope.TeamRole.MENTOR
                ? TeamAgentScope.TaskReadMode.ALL : TeamAgentScope.TaskReadMode.RELATION_ONLY;
        return new TeamAgentScope(userId, teamId, team.getTitle(), team.getStatus(), role,
            readMode, team.getCompetitionId(), catalogName, team.getCustomCompetition(),
            team.getDeadline(), Instant.now());
    }

    public User requireUser(Long userId) {
        User user = userId == null ? null : userService.getById(userId);
        if (user == null) {
            throw new TeamAgentException(TeamAgentErrorCode.AUTH_REQUIRED, "登录状态已失效");
        }
        return user;
    }
}
