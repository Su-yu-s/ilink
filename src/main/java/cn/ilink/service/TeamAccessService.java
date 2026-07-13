package cn.ilink.service;

import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/**
 * Resolves whether a user may access a team's private collaboration data.
 * A team creator and an approved applicant are both team participants.
 */
@Service
public class TeamAccessService {

    private final TeamApplicationServiceImpl teamApplicationService;
    private final TeamDemandServiceImpl teamDemandService;

    public TeamAccessService(TeamApplicationServiceImpl teamApplicationService,
                             TeamDemandServiceImpl teamDemandService) {
        this.teamApplicationService = teamApplicationService;
        this.teamDemandService = teamDemandService;
    }

    public boolean isTeamParticipant(Long teamId, Long userId) {
        if (teamId == null || teamId <= 0 || userId == null || userId <= 0) {
            return false;
        }

        boolean isCreator = teamDemandService.count(new LambdaQueryWrapper<TeamDemand>()
            .eq(TeamDemand::getId, teamId)
            .eq(TeamDemand::getCreatorId, userId)) > 0;
        if (isCreator) {
            return true;
        }

        return teamApplicationService.count(new LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, teamId)
            .eq(TeamApplication::getUserId, userId)
            .eq(TeamApplication::getStatus, "APPROVED")) > 0;
    }
}
