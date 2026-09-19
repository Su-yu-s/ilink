package cn.ilink.service;

import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamDemandMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 团队所有权的转移与终结。
 *
 * <p>创建者不能直接退出团队——他必须先转让或解散。这两条路径都只开放给当前创建者，
 * 不随成员角色放宽：导师再多也不等于团队所有者。
 *
 * <p>所有写操作都以「带条件的 UPDATE + 影响行数」判定，避免并发下出现两个创建者，
 * 或解散之后又被人改回 TEAMING。
 *
 * <p>这些 UPDATE 用字符串列名而不是 TeamDemand::getXxx：方法级 lambda 在 set() 时就要
 * 解析成列名（需要 MyBatis 的 TableInfo 缓存），单测里没有完整上下文会直接抛异常；
 * 查询用的 lambda 是延迟到拼 SQL 才解析，所以那边不受影响。
 */
@Service
@Slf4j
public class TeamOwnershipService {

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_TEAMING = "TEAMING";
    private static final String STATUS_DISSOLVED = "DISSOLVED";

    private final TeamDemandMapper teamDemandMapper;
    private final TeamApplicationMapper teamApplicationMapper;
    private final TeamMembershipService membershipService;
    private final TeamTaskService teamTaskService;
    private final NotificationService notificationService;
    private final UserService userService;

    public TeamOwnershipService(TeamDemandMapper teamDemandMapper,
                                TeamApplicationMapper teamApplicationMapper,
                                TeamMembershipService membershipService,
                                TeamTaskService teamTaskService,
                                NotificationService notificationService,
                                UserService userService) {
        this.teamDemandMapper = teamDemandMapper;
        this.teamApplicationMapper = teamApplicationMapper;
        this.membershipService = membershipService;
        this.teamTaskService = teamTaskService;
        this.notificationService = notificationService;
        this.userService = userService;
    }

    /** 转让创建者。原创建者随即变成普通成员，新任创建者不再占用成员行。 */
    @Transactional(rollbackFor = Exception.class)
    public void transfer(Long teamId, Long ownerId, Long newOwnerId) {
        TeamDemand team = teamDemandMapper.selectByIdForUpdate(teamId);
        if (team == null) {
            throw new TeamMembershipException(404, "团队不存在");
        }
        requireOwner(team, ownerId);
        requireActive(team);
        if (newOwnerId == null || Objects.equals(newOwnerId, ownerId)) {
            throw new TeamMembershipException(400, "请选择一位在队成员作为新创建者");
        }

        TeamApplication newOwnerRow = membershipService.findRow(teamId, newOwnerId);
        if (newOwnerRow == null || !TeamMembershipService.STATUS_APPROVED.equals(newOwnerRow.getStatus())) {
            throw new TeamMembershipException(400, "只能把团队转让给已在队的成员");
        }
        User newOwner = userService.getById(newOwnerId);
        if (newOwner == null) {
            throw new TeamMembershipException(404, "该用户不存在");
        }

        Date now = new Date();
        // 以带条件的 UPDATE 确认「我此刻仍是创建者且团队未终结」，防并发双转让
        int changed = teamDemandMapper.update(null, new UpdateWrapper<TeamDemand>()
            .eq("id", teamId)
            .eq("creator_id", ownerId)
            .in("status", STATUS_OPEN, STATUS_TEAMING)
            .set("creator_id", newOwnerId)
            .set("updated_at", now));
        if (changed != 1) {
            throw new TeamMembershipException(400, "转让失败，你可能已不是该团队的创建者");
        }

        // 新任创建者不再作为普通成员，否则成员列表里会出现两次
        teamApplicationMapper.deleteById(newOwnerRow.getId());

        // 原创建者落回普通成员，保留在队协作能力
        TeamApplication ownerRow = membershipService.findRow(teamId, ownerId);
        membershipService.upsertRow(ownerRow, teamId, ownerId, ownerId,
            TeamMembershipService.STATUS_APPROVED,
            TeamMembershipService.deriveMemberRole(userService.getById(ownerId)), null);

        String teamTitle = TeamInviteService.displayName(team.getTitle());
        String newOwnerName = TeamInviteService.displayName(newOwner);
        notificationService.create(newOwnerId, ownerId, "TEAM_OWNER_TRANSFERRED", "你已成为团队创建者",
            "你已接任团队「" + teamTitle + "」的创建者", teamId);
        notifyMembersExcept(teamId, newOwnerId, ownerId, "TEAM_OWNER_TRANSFERRED", "团队创建者变更",
            TeamInviteService.displayName(userService.getById(ownerId)) + " 已将团队「" + teamTitle + "」转让给 " + newOwnerName);
    }

    /** 解散团队。软删：只改状态与成员/任务状态，协作历史全部保留。 */
    @Transactional(rollbackFor = Exception.class)
    public int dissolve(Long teamId, Long ownerId) {
        TeamDemand team = teamDemandMapper.selectByIdForUpdate(teamId);
        if (team == null) {
            throw new TeamMembershipException(404, "团队不存在");
        }
        requireOwner(team, ownerId);
        if (!STATUS_OPEN.equals(team.getStatus()) && !STATUS_TEAMING.equals(team.getStatus())) {
            throw new TeamMembershipException(400, "该团队已经结束或已解散");
        }

        Date now = new Date();
        int changed = teamDemandMapper.update(null, new UpdateWrapper<TeamDemand>()
            .eq("id", teamId)
            .eq("creator_id", ownerId)
            .in("status", STATUS_OPEN, STATUS_TEAMING)
            .set("status", STATUS_DISSOLVED)
            .set("updated_at", now));
        if (changed != 1) {
            throw new TeamMembershipException(400, "解散失败，团队状态已变化");
        }

        // 待确认的邀请一并作废，不能再有人同意进来
        List<TeamApplication> rows = teamApplicationMapper.selectList(
            new LambdaQueryWrapper<TeamApplication>().eq(TeamApplication::getTeamId, teamId));
        teamApplicationMapper.update(null, new UpdateWrapper<TeamApplication>()
            .eq("team_id", teamId)
            .in("status", TeamMembershipService.STATUS_PENDING, TeamMembershipService.STATUS_APPROVED)
            .set("status", TeamMembershipService.STATUS_VOID)
            .set("reviewed_at", now));

        // 未完结的任务就地取消，别留一堆永远做不完的待办。
        // 用字符串列名而非方法级 lambda：lambda 需要 MyBatis 的 TableInfo 缓存，
        // 单测里没有完整上下文会抛异常，这样写才能被单测覆盖。
        teamTaskService.update(new UpdateWrapper<TeamTask>()
            .eq("team_id", teamId)
            .notIn("status", "COMPLETED", "CANCELLED")
            .set("status", "CANCELLED")
            .set("updated_at", now));

        String teamTitle = TeamInviteService.displayName(team.getTitle());
        int notified = 0;
        for (TeamApplication row : rows) {
            if (Objects.equals(row.getUserId(), ownerId)) {
                continue;
            }
            if (TeamMembershipService.STATUS_PENDING.equals(row.getStatus())) {
                // 邀请作废：把通知卡片上的按钮撤掉，不要再让人点到「同意加入」
                notificationService.markInviteHandled(row.getUserId(), teamId);
                continue;
            }
            if (TeamMembershipService.STATUS_APPROVED.equals(row.getStatus())) {
                notificationService.create(row.getUserId(), ownerId, "TEAM_DISSOLVED", "团队已解散",
                    "团队「" + teamTitle + "」已被创建者解散", teamId);
                notified++;
            }
        }
        log.info("团队 {} 已解散，通知 {} 名成员", teamId, notified);
        return notified;
    }

    private void requireOwner(TeamDemand team, Long ownerId) {
        if (!TeamMembershipService.isOwner(team, ownerId)) {
            throw new TeamMembershipException(403, "只有团队创建者可以执行该操作");
        }
    }

    private void requireActive(TeamDemand team) {
        if (!STATUS_OPEN.equals(team.getStatus()) && !STATUS_TEAMING.equals(team.getStatus())) {
            throw new TeamMembershipException(400, "该团队已经结束或已解散");
        }
    }

    private void notifyMembersExcept(Long teamId, Long excludeA, Long excludeB,
                                     String type, String title, String content) {
        for (TeamApplication row : teamApplicationMapper.selectList(
            new LambdaQueryWrapper<TeamApplication>()
                .eq(TeamApplication::getTeamId, teamId)
                .eq(TeamApplication::getStatus, TeamMembershipService.STATUS_APPROVED))) {
            Long userId = row.getUserId();
            if (userId == null || userId.equals(excludeA) || userId.equals(excludeB)) {
                continue;
            }
            notificationService.create(userId, excludeB, type, title, content, teamId);
        }
    }
}
