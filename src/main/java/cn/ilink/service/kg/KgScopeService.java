package cn.ilink.service.kg;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.service.TeamAccessService;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * KG 可见性服务（团队私有子图的核心）：
 * 一次查询拿到用户可见的团队 ID 集合，供各工具在 SQL 层拼接 scope/team_id/user_id 过滤。
 * 可见规则：GLOBAL 全员可见；TEAM 仅成员（队长或已通过的申请者）可见；PRIVATE 仅属主可见。
 */
@Service
public class KgScopeService {

    private final TeamAccessService teamAccessService;
    private final TeamDemandServiceImpl teamDemandService;
    private final TeamApplicationServiceImpl teamApplicationService;

    @Autowired
    public KgScopeService(TeamAccessService teamAccessService,
                          TeamDemandServiceImpl teamDemandService,
                          TeamApplicationServiceImpl teamApplicationService) {
        this.teamAccessService = teamAccessService;
        this.teamDemandService = teamDemandService;
        this.teamApplicationService = teamApplicationService;
    }

    /** 用户可见的团队 ID 列表（队长身份 + 已通过的申请） */
    public List<Long> visibleTeamIds(Long userId) {
        if (userId == null || userId <= 0) {
            return Collections.emptyList();
        }
        List<Long> teamIds = new ArrayList<>();
        teamDemandService.list(new LambdaQueryWrapper<TeamDemand>()
                .eq(TeamDemand::getCreatorId, userId).select(TeamDemand::getId))
            .forEach(team -> teamIds.add(team.getId()));
        teamApplicationService.list(new LambdaQueryWrapper<TeamApplication>()
                .eq(TeamApplication::getUserId, userId)
                .eq(TeamApplication::getStatus, "APPROVED").select(TeamApplication::getTeamId))
            .forEach(app -> teamIds.add(app.getTeamId()));
        return teamIds;
    }

    /**
     * 判断某实体对用户是否可见。
     * GLOBAL 放行；TEAM 要求 team_id 在用户可见团队里；PRIVATE 要求 user_id 匹配。
     */
    public boolean canSee(KgEntity entity, Long userId) {
        if (entity == null) {
            return false;
        }
        if (KgEntity.SCOPE_GLOBAL.equals(entity.getScope())) {
            return true;
        }
        if (userId == null || userId <= 0) {
            return false;
        }
        if (KgEntity.SCOPE_PRIVATE.equals(entity.getScope())) {
            return entity.getUserId() != null && entity.getUserId().equals(userId);
        }
        if (KgEntity.SCOPE_TEAM.equals(entity.getScope())) {
            return entity.getTeamId() != null && teamAccessService.isTeamParticipant(entity.getTeamId(), userId);
        }
        return false;
    }
}
