package cn.ilink.service;

import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 团队邀请与成员进出。
 *
 * <p>邀请和申请复用同一张 team_application：{@code initiator_id} 等于 {@code user_id} 是本人申请，
 * 不等则是团队邀请。状态的唯一真相在这张表，通知只负责告知，不参与判定。
 */
@Service
@Slf4j
public class TeamInviteService {

    private final TeamDemandServiceImpl teamDemandService;
    private final TeamApplicationServiceImpl teamApplicationService;
    private final TeamApplicationMapper teamApplicationMapper;
    private final TeamDemandMapper teamDemandMapper;
    private final TeamMembershipService membershipService;
    private final TeamTaskService teamTaskService;
    private final NotificationService notificationService;
    private final UserService userService;

    public TeamInviteService(TeamDemandServiceImpl teamDemandService,
                             TeamApplicationServiceImpl teamApplicationService,
                             TeamApplicationMapper teamApplicationMapper,
                             TeamDemandMapper teamDemandMapper,
                             TeamMembershipService membershipService,
                             TeamTaskService teamTaskService,
                             NotificationService notificationService,
                             UserService userService) {
        this.teamDemandService = teamDemandService;
        this.teamApplicationService = teamApplicationService;
        this.teamApplicationMapper = teamApplicationMapper;
        this.teamDemandMapper = teamDemandMapper;
        this.membershipService = membershipService;
        this.teamTaskService = teamTaskService;
        this.notificationService = notificationService;
        this.userService = userService;
    }

    /** 单个用户的邀请结果 */
    public static class InviteOutcome {
        public final List<Long> invited = new ArrayList<>();
        public final List<Map<String, Object>> skipped = new ArrayList<>();

        void skip(Long userId, String reason) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", userId);
            row.put("reason", reason);
            skipped.add(row);
        }
    }

    /** 邀请一批用户加入团队。返回成功与跳过两拨，跳过会带上原因供前端提示。 */
    @Transactional(rollbackFor = Exception.class)
    public InviteOutcome invite(Long teamId, Long inviterId, List<Long> rawUserIds) {
        TeamDemand team = requireTeam(teamId);
        if (!membershipService.canManageMembers(team, inviterId)) {
            throw new TeamMembershipException(403, "只有创建者或导师可以邀请成员");
        }
        requireActive(team, "该团队已停止招募，无法再邀请成员");

        InviteOutcome outcome = new InviteOutcome();
        Set<Long> targets = new LinkedHashSet<>();
        if (rawUserIds != null) {
            for (Long id : rawUserIds) {
                if (id != null) {
                    targets.add(id);
                }
            }
        }
        if (targets.isEmpty()) {
            throw new TeamMembershipException(400, "请先选择要邀请的成员");
        }
        targets.remove(inviterId);
        if (targets.isEmpty()) {
            throw new TeamMembershipException(400, "不能邀请自己");
        }

        Map<Long, User> users = loadUsers(targets);
        Map<Long, TeamApplication> existingRows = loadRows(teamId, targets);
        User inviter = userService.getById(inviterId);
        String inviterName = displayName(inviter);
        String teamTitle = displayName(team.getTitle());
        // 邀请是即时发通知的批量操作，先算一次容量，避免循环里反复查
        boolean full = membershipService.isFull(team);

        for (Long userId : targets) {
            User target = users.get(userId);
            if (target == null) {
                outcome.skip(userId, "USER_NOT_FOUND");
                continue;
            }
            TeamApplication row = existingRows.get(userId);
            if (row != null && TeamMembershipService.STATUS_APPROVED.equals(row.getStatus())) {
                outcome.skip(userId, "ALREADY_MEMBER");
                continue;
            }
            if (row != null && TeamMembershipService.STATUS_PENDING.equals(row.getStatus())) {
                outcome.skip(userId, "ALREADY_INVITED");
                continue;
            }
            if (full) {
                outcome.skip(userId, "TEAM_FULL");
                continue;
            }
            membershipService.upsertRow(row, teamId, userId, inviterId,
                TeamMembershipService.STATUS_PENDING,
                TeamMembershipService.deriveMemberRole(target), null);
            notificationService.create(userId, inviterId, "TEAM_INVITE", "团队邀请",
                inviterName + " 邀请你加入「" + teamTitle + "」", teamId);
            outcome.invited.add(userId);
        }
        return outcome;
    }

    /**
     * 被邀请人响应邀请。
     * 以「影响行数」为准做幂等：重复点击只会命中一次 PENDING，第二次返回已处理。
     */
    @Transactional(rollbackFor = Exception.class)
    public TeamApplication respond(Long applicationId, Long userId, boolean accept) {
        TeamApplication row = teamApplicationMapper.selectByIdForUpdate(applicationId);
        if (row == null) {
            throw new TeamMembershipException(404, "邀请不存在");
        }
        if (!userId.equals(row.getUserId())) {
            throw new TeamMembershipException(403, "无权处理该邀请");
        }
        if (row.getInitiatorId() == null || row.getInitiatorId().equals(row.getUserId())) {
            throw new TeamMembershipException(400, "这是一条入队申请，请等待队长审批");
        }
        if (!TeamMembershipService.STATUS_PENDING.equals(row.getStatus())) {
            throw new TeamMembershipException(400, "该邀请已被处理");
        }

        Long teamId = row.getTeamId();
        TeamDemand team = teamDemandMapper.selectByIdForUpdate(teamId);
        if (team == null) {
            throw new TeamMembershipException(404, "团队不存在");
        }

        Date now = new Date();
        if (!accept) {
            row.setStatus(TeamMembershipService.STATUS_REJECTED);
            row.setReviewedAt(now);
            if (teamApplicationMapper.updateById(row) != 1) {
                throw new IllegalStateException("邀请状态更新失败");
            }
            notificationService.markInviteHandled(userId, teamId);
            return row;
        }

        // 同意：容量以落库这一刻为准，邀请发出后队伍可能已经被别人填满
        if (membershipService.isFull(team)) {
            throw new TeamMembershipException(400, "队伍已满，无法加入");
        }
        row.setStatus(TeamMembershipService.STATUS_APPROVED);
        row.setReviewedAt(now);
        if (teamApplicationMapper.updateById(row) != 1) {
            throw new IllegalStateException("邀请状态更新失败");
        }
        if ("OPEN".equals(team.getStatus()) && membershipService.isFull(team)) {
            team.setStatus("TEAMING");
            team.setUpdatedAt(now);
            teamDemandMapper.updateById(team);
        }
        notificationService.markInviteHandled(userId, teamId);
        return row;
    }

    /** 移除成员；传入本人即为主动退出。 */
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long teamId, Long operatorId, Long targetUserId) {
        TeamDemand team = requireTeam(teamId);
        boolean self = Objects.equals(operatorId, targetUserId);
        if (self && TeamMembershipService.isOwner(team, operatorId)) {
            throw new TeamMembershipException(400, "你是团队创建者，请先转让创建者或解散团队");
        }

        TeamApplication row = membershipService.findRow(teamId, targetUserId);
        if (row == null || !TeamMembershipService.STATUS_APPROVED.equals(row.getStatus())) {
            throw new TeamMembershipException(404, "该成员不在团队中");
        }
        if (!self && !membershipService.canRemove(team, operatorId, targetUserId, row.getMemberRole())) {
            throw new TeamMembershipException(403, "无权移除该成员");
        }

        row.setStatus(self ? TeamMembershipService.STATUS_LEFT : TeamMembershipService.STATUS_REMOVED);
        row.setReviewedAt(new Date());
        if (teamApplicationMapper.updateById(row) != 1) {
            throw new IllegalStateException("成员状态更新失败");
        }
        // 人走了，未完成的任务不能还挂在他名下。
        // 这里用字符串列名的 UpdateWrapper：方法级 lambda（TeamTask::getXxx）需要 MyBatis 的
        // TableInfo 缓存，单测里没有完整上下文会直接抛异常，写成列名才能被单测覆盖。
        teamTaskService.update(new UpdateWrapper<TeamTask>()
            .eq("team_id", teamId)
            .eq("assigned_to", targetUserId)
            .set("assigned_to", null)
            .set("updated_at", new Date()));

        if (!self) {
            notificationService.create(targetUserId, operatorId, "TEAM_MEMBER_REMOVED", "已离开团队",
                "你已被移出团队「" + displayName(team.getTitle()) + "」", teamId);
        }
    }

    /** 供团队卡片与弹窗使用：某团队里处于待确认的邀请数（发起人不是本人的 PENDING 行） */
    public long pendingInviteCount(Long teamId) {
        return teamApplicationMapper.selectCount(new LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, teamId)
            .eq(TeamApplication::getStatus, TeamMembershipService.STATUS_PENDING)
            // 邀请与申请的区别是 initiator_id 是否等于 user_id，只能落到 SQL 里比
            .apply("initiator_id IS NOT NULL AND initiator_id <> user_id"));
    }

    private TeamDemand requireTeam(Long teamId) {
        TeamDemand team = teamId == null ? null : teamDemandService.getById(teamId);
        if (team == null) {
            throw new TeamMembershipException(404, "团队不存在");
        }
        return team;
    }

    /** 已解散的团队不再接受邀请或申请 */
    private void requireActive(TeamDemand team, String message) {
        if (!"OPEN".equals(team.getStatus()) && !"TEAMING".equals(team.getStatus())) {
            throw new TeamMembershipException(400, message);
        }
    }

    private Map<Long, User> loadUsers(Set<Long> userIds) {
        Map<Long, User> map = new HashMap<>();
        if (userIds.isEmpty()) {
            return map;
        }
        for (User user : userService.listByIds(userIds)) {
            map.put(user.getId(), user);
        }
        return map;
    }

    private Map<Long, TeamApplication> loadRows(Long teamId, Set<Long> userIds) {
        Map<Long, TeamApplication> map = new HashMap<>();
        if (userIds.isEmpty()) {
            return map;
        }
        for (TeamApplication row : teamApplicationService.list(new LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, teamId)
            .in(TeamApplication::getUserId, userIds))) {
            map.put(row.getUserId(), row);
        }
        return map;
    }

    static String displayName(User user) {
        if (user == null) {
            return "某位同学";
        }
        if (user.getRealName() != null && !user.getRealName().trim().isEmpty()) {
            return user.getRealName().trim();
        }
        return user.getUsername() == null ? "某位同学" : user.getUsername();
    }

    static String displayName(String fallback) {
        return fallback == null || fallback.trim().isEmpty() ? "未命名团队" : fallback.trim();
    }
}
