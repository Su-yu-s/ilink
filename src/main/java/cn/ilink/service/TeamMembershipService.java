package cn.ilink.service;

import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamApplicationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 团队角色与状态的统一判定。
 *
 * <p>团队里的「成员」有两类：创建者（存在 team_demand.creator_id，不占 team_application 行）
 * 和普通成员（team_application.status = APPROVED）。所有权限判断都从这里出，
 * 避免同一套规则在多个 Controller 里各写一遍。
 */
@Service
public class TeamMembershipService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    /** 主动退出 */
    public static final String STATUS_LEFT = "LEFT";
    /** 被移出 */
    public static final String STATUS_REMOVED = "REMOVED";
    /** 随团队解散作废 */
    public static final String STATUS_VOID = "VOID";

    /** 三种「曾加入过但已离开」的状态，允许重新申请或被再次邀请 */
    public static final List<String> REJOINABLE = List.of(STATUS_REJECTED, STATUS_LEFT, STATUS_REMOVED);

    public static final String ROLE_MENTOR = "MENTOR";
    public static final String ROLE_STUDENT = "STUDENT";

    private final TeamApplicationMapper teamApplicationMapper;

    public TeamMembershipService(TeamApplicationMapper teamApplicationMapper) {
        this.teamApplicationMapper = teamApplicationMapper;
    }

    /** 团队内角色由注册角色推导：教师即导师，其余为学生 */
    public static String deriveMemberRole(User user) {
        return user != null && "TEACHER".equals(user.getRole()) ? ROLE_MENTOR : ROLE_STUDENT;
    }

    public static boolean isOwner(TeamDemand team, Long userId) {
        return team != null && userId != null && userId.equals(team.getCreatorId());
    }

    /** 已通过审批的普通成员 */
    public boolean isApprovedMember(Long teamId, Long userId) {
        if (teamId == null || userId == null) {
            return false;
        }
        return teamApplicationMapper.selectCount(new LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, teamId)
            .eq(TeamApplication::getUserId, userId)
            .eq(TeamApplication::getStatus, STATUS_APPROVED)) > 0;
    }

    /** 在队的导师（创建者若本身是教师也算，由调用方用 {@link #isOwner} 一并判断） */
    public boolean isMentorMember(Long teamId, Long userId) {
        if (teamId == null || userId == null) {
            return false;
        }
        return teamApplicationMapper.selectCount(new LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, teamId)
            .eq(TeamApplication::getUserId, userId)
            .eq(TeamApplication::getStatus, STATUS_APPROVED)
            .eq(TeamApplication::getMemberRole, ROLE_MENTOR)) > 0;
    }

    /** 能否邀请成员 / 审批入队申请：创建者或已在队的导师 */
    public boolean canManageMembers(TeamDemand team, Long userId) {
        if (isOwner(team, userId)) {
            return true;
        }
        return team != null && isMentorMember(team.getId(), userId);
    }

    /** 批量版导师成员判定：一次查询用户在多个团队中的 APPROVED 导师身份（创建者判定无需查库，调用方自行短路） */
    public Set<Long> mentorTeamIds(Long userId, List<Long> teamIds) {
        if (userId == null || teamIds == null || teamIds.isEmpty()) {
            return Collections.emptySet();
        }
        return teamApplicationMapper.selectList(new LambdaQueryWrapper<TeamApplication>()
                .select(TeamApplication::getTeamId)
                .eq(TeamApplication::getUserId, userId)
                .eq(TeamApplication::getStatus, STATUS_APPROVED)
                .eq(TeamApplication::getMemberRole, ROLE_MENTOR)
                .in(TeamApplication::getTeamId, teamIds))
            .stream().map(TeamApplication::getTeamId).collect(Collectors.toSet());
    }

    /** 能否移除某个成员：创建者可移任何人（除自己），导师只能移学生 */
    public boolean canRemove(TeamDemand team, Long operatorId, Long targetUserId, String targetRole) {
        if (isOwner(team, operatorId)) {
            return !operatorId.equals(targetUserId);
        }
        if (!isMentorMember(team.getId(), operatorId)) {
            return false;
        }
        // 导师之间平级，且移除不了同为导师的人
        return ROLE_STUDENT.equals(targetRole);
    }

    public TeamApplication findRow(Long teamId, Long userId) {
        return teamApplicationMapper.selectOne(new LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, teamId)
            .eq(TeamApplication::getUserId, userId)
            .last("LIMIT 1"));
    }

    /**
     * 队伍是否已满。{@code requiredMemberCount} 是「队伍总人数」口径，
     * 创建者也算一名成员，因此这里用「已通过申请数 + 1」与上限比较，
     * 与工作台展示的 memberCount（approvedCount + 1）保持同一口径。
     * 与 {@code TeamApplicationWorkflowService#isFull} 一致，邀请和审批共用一条上限。
     */
    public boolean isFull(TeamDemand team) {
        Integer required = team == null ? null : team.getRequiredMemberCount();
        if (required == null || required <= 0) {
            return false;
        }
        return approvedCount(team.getId()) + 1 >= required;
    }

    public long approvedCount(Long teamId) {
        return teamApplicationMapper.selectCount(new LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, teamId)
            .eq(TeamApplication::getStatus, STATUS_APPROVED));
    }

    /**
     * 写或改写一行成员记录。
     * 历史遗留的 REJECTED / LEFT / REMOVED 行直接复用（uk_team_user 只允许一行），
     * 不重新 INSERT，否则会撞唯一键。
     */
    public void upsertRow(TeamApplication existing, Long teamId, Long userId, Long initiatorId,
                          String status, String memberRole, String message) {
        Date now = new Date();
        if (existing == null) {
            TeamApplication row = new TeamApplication();
            row.setTeamId(teamId);
            row.setUserId(userId);
            row.setInitiatorId(initiatorId);
            row.setStatus(status);
            row.setMemberRole(memberRole);
            row.setMessage(message);
            row.setCreatedAt(now);
            teamApplicationMapper.insert(row);
            return;
        }
        existing.setInitiatorId(initiatorId);
        existing.setStatus(status);
        existing.setMemberRole(memberRole);
        existing.setMessage(message);
        // 复用历史行时把审批痕迹清掉，否则会留着上一次的拒绝理由
        existing.setReviewerNote(null);
        existing.setReviewedAt(null);
        existing.setCreatedAt(now);
        teamApplicationMapper.updateById(existing);
    }
}
