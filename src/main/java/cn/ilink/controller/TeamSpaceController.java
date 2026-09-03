package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamTaskMapper;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import cn.ilink.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 团队工作空间 API —— 仅供已通过申请的成员访问。
 * 提供空间概览、成员列表、待办任务统计等数据。
 */
@Controller
@RequestMapping("/api/team-space")
public class TeamSpaceController {

    @Autowired
    private TeamDemandServiceImpl teamDemandService;

    @Autowired
    private TeamApplicationServiceImpl teamApplicationService;

    @Autowired
    private TeamTaskMapper teamTaskMapper;

    @Autowired
    private UserService userService;

    /**
     * 检查当前用户是否为团队成员（创建者或通过审批的成员）
     */
    private boolean isTeamMember(Long teamId, Long userId) {
        if (teamId == null || userId == null) return false;
        // 检查是否为创建者
        long creatorCount = teamDemandService.count(
                new LambdaQueryWrapper<TeamDemand>()
                        .eq(TeamDemand::getId, teamId)
                        .eq(TeamDemand::getCreatorId, userId)
        );
        if (creatorCount > 0) return true;
        // 检查是否已通过审批
        long approvedCount = teamApplicationService.count(
                new LambdaQueryWrapper<TeamApplication>()
                        .eq(TeamApplication::getTeamId, teamId)
                        .eq(TeamApplication::getUserId, userId)
                        .eq(TeamApplication::getStatus, "APPROVED")
        );
        return approvedCount > 0;
    }

    /**
     * 检查当前用户是否为团队创建者（队长）
     */
    private boolean isTeamLeader(Long teamId, Long userId) {
        if (teamId == null || userId == null) return false;
        return teamDemandService.count(
                new LambdaQueryWrapper<TeamDemand>()
                        .eq(TeamDemand::getId, teamId)
                        .eq(TeamDemand::getCreatorId, userId)
        ) > 0;
    }

    /**
     * 检查用户是否为团队成员，同时返回团队对象避免重复查询
     * @return 团队对象，如果不是成员则返回 null
     */
    private TeamDemand checkMembershipAndGetTeam(Long teamId, Long userId) {
        if (teamId == null || userId == null) return null;
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) return null;
        // 创建者直接通过
        if (userId.equals(team.getCreatorId())) return team;
        // 检查审批记录
        long approvedCount = teamApplicationService.count(
                new LambdaQueryWrapper<TeamApplication>()
                        .eq(TeamApplication::getTeamId, teamId)
                        .eq(TeamApplication::getUserId, userId)
                        .eq(TeamApplication::getStatus, "APPROVED")
        );
        return approvedCount > 0 ? team : null;
    }

    /**
     * 获取工作空间概览信息
     * GET /api/team-space/{teamId}/info
     */
    @GetMapping("/{teamId}/info")
    @ResponseBody
    public ResponseEntity<Result<?>> getWorkspaceInfo(@PathVariable Long teamId,
                                                       HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        // 一次性检查成员资格并获取团队对象，避免重复查询
        TeamDemand team = checkMembershipAndGetTeam(teamId, user.getId());
        if (team == null) {
            // 区分“不存在”和“无权访问”
            TeamDemand exists = teamDemandService.getById(teamId);
            if (exists == null) {
                return Result.notFound("团队不存在").toResponseEntity();
            }
            return Result.fail(403, "你不是该团队成员，无权访问工作空间").toResponseEntity();
        }

        // 统计成员数（不再重复调用 getById）
        long approvedCount = teamApplicationService.count(
                new LambdaQueryWrapper<TeamApplication>()
                        .eq(TeamApplication::getTeamId, teamId)
                        .eq(TeamApplication::getStatus, "APPROVED")
        );
        long memberCount = approvedCount + 1; // +创建者

        // 一次性查询所有任务，在内存中统计各状态数量
        List<TeamTask> allTasks = teamTaskMapper.selectList(
                new LambdaQueryWrapper<TeamTask>().eq(TeamTask::getTeamId, teamId)
        );
        Map<String, Long> taskStats = new LinkedHashMap<>();
        long todo = 0, inProgress = 0, review = 0, completed = 0;
        for (TeamTask t : allTasks) {
            String s = t.getStatus() == null ? "" : t.getStatus().toLowerCase(Locale.ROOT);
            switch (s) {
                case "pending": case "todo": todo++; break;
                case "in_progress": inProgress++; break;
                case "review": review++; break;
                case "completed": completed++; break;
            }
        }
        taskStats.put("todo", todo);
        taskStats.put("in_progress", inProgress);
        taskStats.put("review", review);
        taskStats.put("completed", completed);

        boolean isLeader = user.getId().equals(team.getCreatorId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teamId", team.getId());
        data.put("teamName", team.getTitle());
        data.put("teamDescription", team.getDescription());
        data.put("status", team.getStatus());
        data.put("statusLabel", statusLabel(team.getStatus()));
        data.put("memberCount", memberCount);
        data.put("pendingTaskCount", todo);
        data.put("taskStats", taskStats);
        data.put("isLeader", isLeader);
        data.put("deadline", team.getDeadline());
        data.put("requiredMemberCount", team.getRequiredMemberCount());

        return Result.ok("获取成功", data).toResponseEntity();
    }

    /**
     * 获取左侧底部概要数据
     * GET /api/team-space/{teamId}/overview
     */
    @GetMapping("/{teamId}/overview")
    @ResponseBody
    public ResponseEntity<Result<?>> getOverview(@PathVariable Long teamId,
                                                  HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamDemand team = checkMembershipAndGetTeam(teamId, user.getId());
        if (team == null) {
            TeamDemand exists = teamDemandService.getById(teamId);
            if (exists == null) return Result.notFound("团队不存在").toResponseEntity();
            return Result.fail(403, "你不是该团队成员，无权访问工作空间").toResponseEntity();
        }

        long approvedCount = teamApplicationService.count(
                new LambdaQueryWrapper<TeamApplication>()
                        .eq(TeamApplication::getTeamId, teamId)
                        .eq(TeamApplication::getStatus, "APPROVED")
        );

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teamId", team.getId());
        data.put("memberCount", approvedCount + 1);
        data.put("requiredMemberCount", team.getRequiredMemberCount());
        data.put("status", team.getStatus());
        data.put("statusLabel", statusLabel(team.getStatus()));
        data.put("deadline", team.getDeadline());
        return Result.ok("获取成功", data).toResponseEntity();
    }

    /**
     * 获取团队成员列表（含在线状态）
     * GET /api/team-space/{teamId}/members
     */
    @GetMapping("/{teamId}/members")
    @ResponseBody
    public ResponseEntity<Result<?>> getMembers(@PathVariable Long teamId,
                                                 HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        TeamDemand team = checkMembershipAndGetTeam(teamId, user.getId());
        if (team == null) {
            TeamDemand exists = teamDemandService.getById(teamId);
            if (exists == null) return Result.notFound("团队不存在").toResponseEntity();
            return Result.fail(403, "你不是该团队成员").toResponseEntity();
        }

        List<Map<String, Object>> members = new ArrayList<>();

        // 队长
        User creator = userService.getById(team.getCreatorId());
        if (creator != null) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("userId", creator.getId());
            view.put("username", creator.getRealName() != null ? creator.getRealName() : creator.getUsername());
            view.put("avatar", normalizeAvatar(creator.getAvatar()));
            view.put("major", creator.getMajor());
            view.put("grade", creator.getGrade());
            view.put("role", "队长");
            view.put("isLeader", true);
            members.add(view);
        }

        // 队员
        List<TeamApplication> approvedApps = teamApplicationService.list(
                new LambdaQueryWrapper<TeamApplication>()
                        .eq(TeamApplication::getTeamId, teamId)
                        .eq(TeamApplication::getStatus, "APPROVED")
        );

        Set<Long> userIds = new HashSet<>();
        for (TeamApplication app : approvedApps) {
            if (app.getUserId() != null && !app.getUserId().equals(team.getCreatorId())) {
                userIds.add(app.getUserId());
            }
        }

        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap()
                : userService.listByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        for (TeamApplication app : approvedApps) {
            if (app.getUserId() == null) continue;
            if (app.getUserId().equals(team.getCreatorId())) continue;
            User u = userMap.get(app.getUserId());
            if (u != null) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("userId", u.getId());
                view.put("username", u.getRealName() != null ? u.getRealName() : u.getUsername());
                view.put("avatar", normalizeAvatar(u.getAvatar()));
                view.put("major", u.getMajor());
                view.put("grade", u.getGrade());
                view.put("role", "队员");
                view.put("isLeader", false);
                view.put("joinedAt", app.getCreatedAt());
                members.add(view);
            }
        }

        return Result.ok("获取成功", members).toResponseEntity();
    }

    /**
     * 获取工作空间统计数据
     * GET /api/team-space/{teamId}/stats
     */
    @GetMapping("/{teamId}/stats")
    @ResponseBody
    public ResponseEntity<Result<?>> getWorkspaceStats(@PathVariable Long teamId,
                                                        HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        TeamDemand team = checkMembershipAndGetTeam(teamId, user.getId());
        if (team == null) {
            TeamDemand exists = teamDemandService.getById(teamId);
            if (exists == null) return Result.notFound("团队不存在").toResponseEntity();
            return Result.fail(403, "你不是该团队成员").toResponseEntity();
        }

        // 一次性查询所有任务，内存统计
        List<TeamTask> allTasks = teamTaskMapper.selectList(
                new LambdaQueryWrapper<TeamTask>().eq(TeamTask::getTeamId, teamId)
        );
        long todo = 0, inProgress = 0, review = 0, completed = 0;
        for (TeamTask t : allTasks) {
            String s = t.getStatus() == null ? "" : t.getStatus().toLowerCase(Locale.ROOT);
            switch (s) {
                case "pending": case "todo": todo++; break;
                case "in_progress": inProgress++; break;
                case "review": review++; break;
                case "completed": completed++; break;
            }
        }

        long approvedCount = teamApplicationService.count(
                new LambdaQueryWrapper<TeamApplication>()
                        .eq(TeamApplication::getTeamId, teamId)
                        .eq(TeamApplication::getStatus, "APPROVED")
        );

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalTasks", allTasks.size());
        stats.put("pendingTasks", todo);
        stats.put("inProgressTasks", inProgress);
        stats.put("reviewTasks", review);
        stats.put("completedTasks", completed);
        stats.put("memberCount", approvedCount + 1);

        return Result.ok("获取成功", stats).toResponseEntity();
    }

    // ==================== 私有辅助方法 ====================

    private long countMembers(Long teamId) {
        long approvedCount = teamApplicationService.count(
                new LambdaQueryWrapper<TeamApplication>()
                        .eq(TeamApplication::getTeamId, teamId)
                        .eq(TeamApplication::getStatus, "APPROVED")
        );
        return approvedCount + 1; // 创建者也算一个成员
    }

    private String normalizeAvatar(String avatar) {
        if (avatar == null || avatar.trim().isEmpty()) return null;
        String u = avatar.trim();
        if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("/uploads/") || u.startsWith("/static/")) return u;
        return "/uploads/" + u;
    }

    private String statusLabel(String status) {
        if ("OPEN".equals(status)) return "招募中";
        if ("TEAMING".equals(status)) return "已组队";
        if ("CLOSED".equals(status)) return "已结束";
        return status != null ? status : "";
    }
}
