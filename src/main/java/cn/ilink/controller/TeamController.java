package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.TeamDemandRequest;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.service.TeamApplicationService;
import cn.ilink.service.TeamDemandService;
import cn.ilink.service.UserService;
import cn.ilink.util.UserPreviewHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/api/team")
public class TeamController {

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_TEAMING = "TEAMING";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final Pattern LEGACY_MEMBER_COUNT_PATTERN =
        Pattern.compile("[（(]\\s*所需人数\\s*[：:]\\s*(\\d+)\\s*[）)]");
    private static final Pattern LEGACY_DEADLINE_PATTERN =
        Pattern.compile("[（(]\\s*截止日期\\s*[：:]\\s*([0-9]{4}-[0-9]{2}-[0-9]{2}|[^）)]+)\\s*[）)]");

    @Autowired
    private TeamDemandService teamDemandService;

    @Autowired
    private TeamApplicationService teamApplicationService;

    @Autowired
    private UserService userService;

    @GetMapping("/list")
    @ResponseBody
    public ResponseEntity<Result<?>> listTeams(@RequestParam(defaultValue = "1") Integer page,
                                                @RequestParam(defaultValue = "10") Integer size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String category,
                                                @RequestParam(required = false) String status) {

        LambdaQueryWrapper<TeamDemand> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(TeamDemand::getCreatedAt);

        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(TeamDemand::getTitle, kw)
                .or().like(TeamDemand::getDescription, kw)
                .or().like(TeamDemand::getRequiredSkills, kw));
        }

        if (status != null && !status.trim().isEmpty()) {
            String normalizedStatus = "招募中".equals(status) ? "OPEN"
                : ("已完成".equals(status) ? "CLOSED" : status.trim());
            wrapper.eq(TeamDemand::getStatus, normalizedStatus);
        }

        // category: 如果能映射到 competitionId，则做精确过滤；否则回退到文本匹配（兼容旧数据/历史数据）
        if (category != null && !category.trim().isEmpty()) {
            String cat = category.trim();
            Integer mappedCompetitionId = mapCategoryToCompetitionId(cat);
            if (mappedCompetitionId != null) {
                wrapper.eq(TeamDemand::getCompetitionId, mappedCompetitionId);
            } else {
                wrapper.and(w -> w.like(TeamDemand::getTitle, cat)
                    .or().like(TeamDemand::getDescription, cat)
                    .or().like(TeamDemand::getRequiredSkills, cat));
            }
        }

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<TeamDemand> pageReq = new Page<>(safePage, safeSize);
        Page<TeamDemand> result = teamDemandService.page(pageReq, wrapper);
        List<Map<String, Object>> data = enrichTeamsWithCreators(result.getRecords());

        return ResponseEntity.ok(Result.ok("获取成功", data).withPagination(safePage, safeSize, result.getTotal()));
    }

    /** 当前用户发布的组队需求 */
    @GetMapping("/my/published")
    @ResponseBody
    public ResponseEntity<Result<?>> myPublishedTeams(@RequestParam(required = false) String status,
                                                       @RequestParam(required = false, defaultValue = "createdDesc") String sort,
                                                       HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        LambdaQueryWrapper<TeamDemand> wrapper = new LambdaQueryWrapper<TeamDemand>()
            .eq(TeamDemand::getCreatorId, user.getId());
        if (status != null && !status.trim().isEmpty() && !"ALL".equalsIgnoreCase(status.trim())) {
            wrapper.eq(TeamDemand::getStatus, normalizeTeamStatus(status));
        }
        applyMyTeamSort(wrapper, sort);
        List<TeamDemand> teams = teamDemandService.list(wrapper);
        List<Map<String, Object>> rows = enrichTeamsWithCreators(teams);
        if ("applicantsDesc".equalsIgnoreCase(sort)) {
            rows.sort(Comparator.comparingLong((Map<String, Object> row) -> toLong(row.get("applicationCount"))).reversed());
        }
        List<Map<String, Object>> list = rows;
        return ResponseEntity.ok(Result.ok("获取成功", list));
    }

    /** 当前用户发起的组队申请（含队伍标题） */
    @GetMapping("/my/applications")
    @ResponseBody
    public ResponseEntity<Result<?>> myTeamApplications(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        List<TeamApplication> apps = teamApplicationService.list(
            new LambdaQueryWrapper<TeamApplication>()
                .eq(TeamApplication::getUserId, user.getId())
                .orderByDesc(TeamApplication::getCreatedAt));
        Set<Long> teamIds = apps.stream().map(TeamApplication::getTeamId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, TeamDemand> teamMap = teamIds.isEmpty() ? Collections.emptyMap()
            : teamDemandService.listByIds(teamIds).stream()
                .collect(Collectors.toMap(TeamDemand::getId, t -> t, (a, b) -> a));
        List<Map<String, Object>> rows = apps.stream().map((a) -> {
            TeamDemand t = teamMap.get(a.getTeamId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("teamId", a.getTeamId());
            m.put("teamTitle", t != null ? t.getTitle() : "（组队已删除）");
            m.put("status", a.getStatus());
            m.put("createdAt", a.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(Result.ok("获取成功", rows));
    }

    private Integer mapCategoryToCompetitionId(String category) {
        if (category == null) return null;
        switch (category) {
            case "技术开发":
                return 1;
            case "创意设计":
                return 2;
            case "市场营销":
                return 3;
            case "学术研究":
                return 4;
            default:
                return null;
        }
    }

    @GetMapping("/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> getTeam(@PathVariable Long id, HttpSession session) {
        TeamDemand team = teamDemandService.getById(id);
        if (team != null) {
            return ResponseEntity.ok(Result.ok("获取成功", teamDemandToMap(team, loadCreator(team.getCreatorId()))));
        } else {
            return ResponseEntity.ok(Result.notFound("组队需求不存在"));
        }
    }

    @PostMapping
    @ResponseBody
    @CacheEvict(value = "teamDetail", allEntries = true)
    public ResponseEntity<Result<?>> createTeam(@RequestBody TeamDemandRequest request, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user != null) {
            String validationError = validateTeamDemandRequest(request);
            if (validationError != null) {
                return ResponseEntity.ok(Result.badRequest(validationError));
            }
            TeamDemand team = new TeamDemand();
            team.setTitle(request.getTitle().trim());
            team.setDescription(request.getDescription().trim());
            team.setCompetitionId(request.getCompetitionId());
            team.setRequiredSkills(trimToNull(request.getRequiredSkills()));
            team.setRequiredMemberCount(request.getRequiredMemberCount());
            team.setDeadline(request.getDeadline());
            team.setStatus(STATUS_OPEN);
            team.setCreatorId(user.getId());
            team.setCreatedAt(new Date());
            team.setUpdatedAt(new Date());

            boolean success = teamDemandService.save(team);
            if (success) {
                return ResponseEntity.ok(Result.ok("发布成功", team));
            } else {
                return ResponseEntity.ok(Result.fail("发布失败"));
            }
        } else {
            return ResponseEntity.ok(Result.unauthorized());
        }
    }

    @PostMapping("/join")
    @ResponseBody
    public ResponseEntity<Result<?>> joinTeam(@RequestBody Map<String, Object> request, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }

        Object rawTeamId = request.get("teamId");
        if (rawTeamId == null) {
            return ResponseEntity.ok(Result.badRequest("缺少团队ID参数"));
        }
        Long teamId = ControllerUtils.parseLongParam(rawTeamId);
        if (teamId == null) {
            return ResponseEntity.ok(Result.badRequest("团队ID格式无效"));
        }

        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            return ResponseEntity.ok(Result.notFound("团队不存在"));
        }

        // 检查团队状态，仅 OPEN 状态允许申请加入
        if (!STATUS_OPEN.equals(team.getStatus())) {
            return ResponseEntity.ok(Result.badRequest("该团队已停止招募"));
        }

        // 检查是否已经是团队创建者
        if (team.getCreatorId() != null && team.getCreatorId().equals(user.getId())) {
            return ResponseEntity.ok(Result.badRequest("您是该团队的创建者，无需申请加入"));
        }

        // 检查是否已经申请过
        TeamApplication existingApplication = teamApplicationService.getOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TeamApplication>()
                .eq("team_id", teamId)
                .eq("user_id", user.getId())
        );

        if (existingApplication != null) {
            return ResponseEntity.ok(Result.badRequest("您已经申请过该团队"));
        }

        // 创建申请记录
        TeamApplication application = new TeamApplication();
        application.setTeamId(teamId);
        application.setUserId(user.getId());
        application.setStatus("PENDING");
        application.setMessage("");
        application.setCreatedAt(new Date());

        boolean success = teamApplicationService.save(application);
        if (success) {
            return ResponseEntity.ok(Result.ok("申请已提交，请等待团队创建者审核"));
        } else {
            return ResponseEntity.ok(Result.fail("申请提交失败"));
        }
    }

    @PutMapping("/{id}")
    @ResponseBody
    @CacheEvict(value = "teamDetail", key = "#id")
    public ResponseEntity<Result<?>> updateTeam(@PathVariable Long id,
                                                 @RequestBody TeamDemandRequest request,
                                                 HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        TeamDemand team = teamDemandService.getById(id);

        if (user != null && team != null && team.getCreatorId() != null && team.getCreatorId().equals(user.getId())) {
            if (!STATUS_OPEN.equals(team.getStatus())) {
                return ResponseEntity.ok(Result.badRequest("只有招募中的组队需求可以编辑"));
            }
            String validationError = validateTeamDemandRequest(request);
            if (validationError != null) {
                return ResponseEntity.ok(Result.badRequest(validationError));
            }
            team.setTitle(request.getTitle().trim());
            team.setDescription(request.getDescription().trim());
            team.setCompetitionId(request.getCompetitionId());
            team.setRequiredSkills(trimToNull(request.getRequiredSkills()));
            team.setRequiredMemberCount(request.getRequiredMemberCount());
            team.setDeadline(request.getDeadline());
            team.setUpdatedAt(new Date());

            boolean success = teamDemandService.updateById(team);
            if (success) {
                return ResponseEntity.ok(Result.ok("更新成功", team));
            } else {
                return ResponseEntity.ok(Result.fail("更新失败"));
            }
        } else if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        } else if (team == null) {
            return ResponseEntity.ok(Result.notFound("组队需求不存在"));
        } else {
            return ResponseEntity.ok(Result.forbidden());
        }
    }

    @PutMapping("/{id}/status")
    @ResponseBody
    @CacheEvict(value = "teamDetail", key = "#id")
    public ResponseEntity<Result<?>> updateTeamStatus(@PathVariable Long id,
                                                       @RequestBody Map<String, String> request,
                                                       HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        TeamDemand team = teamDemandService.getById(id);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        if (team == null) {
            return ResponseEntity.ok(Result.notFound("组队需求不存在"));
        }
        if (team.getCreatorId() == null || !team.getCreatorId().equals(user.getId())) {
            return ResponseEntity.ok(Result.forbidden());
        }
        String targetStatus = normalizeTeamStatus(request == null ? null : request.get("status"));
        if (!isValidStatusTransition(team.getStatus(), targetStatus)) {
            return ResponseEntity.ok(Result.badRequest("当前状态不允许执行该操作"));
        }
        team.setStatus(targetStatus);
        team.setUpdatedAt(new Date());
        boolean success = teamDemandService.updateById(team);
        if (!success) {
            return ResponseEntity.ok(Result.fail("状态更新失败"));
        }
        return ResponseEntity.ok(Result.ok("状态更新成功", teamDemandToMap(team, loadCreator(team.getCreatorId()))));
    }

    @DeleteMapping("/{id}")
    @ResponseBody
    @CacheEvict(value = "teamDetail", key = "#id")
    public ResponseEntity<Result<?>> deleteTeam(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        TeamDemand team = teamDemandService.getById(id);

        if (user != null && team != null && team.getCreatorId() != null && team.getCreatorId().equals(user.getId())) {
            if (!STATUS_OPEN.equals(team.getStatus())) {
                return ResponseEntity.ok(Result.badRequest("只有招募中的组队需求可以删除"));
            }
            if (countApplications(id) > 0) {
                return ResponseEntity.ok(Result.badRequest("已有报名记录，不能删除"));
            }
            boolean success = teamDemandService.removeById(id);
            if (success) {
                return ResponseEntity.ok(Result.ok("删除成功"));
            } else {
                return ResponseEntity.ok(Result.fail("删除失败"));
            }
        } else if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        } else if (team == null) {
            return ResponseEntity.ok(Result.notFound("组队需求不存在"));
        } else {
            return ResponseEntity.ok(Result.forbidden());
        }
    }

    @GetMapping("/application-status")
    @ResponseBody
    public ResponseEntity<Result<?>> getApplicationStatus(@RequestParam(required = false) Long teamId,
                                                            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        if (teamId == null) {
            return ResponseEntity.ok(Result.badRequest("缺少团队ID参数"));
        }

        // 查询申请状态
        TeamApplication application = teamApplicationService.getOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TeamApplication>()
                .eq("team_id", teamId)
                .eq("user_id", user.getId())
        );

        if (application != null) {
            return ResponseEntity.ok(Result.ok("获取成功", Map.of("status", application.getStatus())));
        } else {
            return ResponseEntity.ok(Result.ok("未申请", Map.of("status", "NOT_APPLIED")));
        }
    }

    private User loadCreator(Long creatorId) {
        if (creatorId == null) {
            return null;
        }
        return userService.getById(creatorId);
    }

    private Map<Long, User> loadCreators(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return userService.listByIds(ids).stream()
            .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    private Map<String, Object> teamDemandToMap(TeamDemand t, User creator) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("title", t.getTitle());
        m.put("description", t.getDescription());
        m.put("competitionId", t.getCompetitionId());
        m.put("requiredSkills", t.getRequiredSkills());
        m.put("requiredMemberCount", resolveRequiredMemberCount(t));
        m.put("deadline", resolveDeadline(t));
        m.put("status", t.getStatus());
        m.put("statusLabel", statusLabel(t.getStatus()));
        m.put("creatorId", t.getCreatorId());
        m.put("createdAt", t.getCreatedAt());
        m.put("updatedAt", t.getUpdatedAt());
        m.put("creatorPreview", UserPreviewHelper.toPreview(creator));
        long applicationCount = countApplications(t.getId());
        long approvedMemberCount = countApprovedMembers(t.getId());
        m.put("applicationCount", applicationCount);
        m.put("approvedMemberCount", approvedMemberCount);
        m.put("currentMemberCount", approvedMemberCount + 1);
        m.put("isFull", isTeamFull(t));
        m.put("canEdit", STATUS_OPEN.equals(t.getStatus()));
        m.put("canDelete", STATUS_OPEN.equals(t.getStatus()) && applicationCount == 0);
        m.put("canMoveToTeaming", STATUS_OPEN.equals(t.getStatus()));
        m.put("canClose", STATUS_OPEN.equals(t.getStatus()) || STATUS_TEAMING.equals(t.getStatus()));
        m.put("members", buildTeamMemberViews(t, creator));
        return m;
    }

    private void applyMyTeamSort(LambdaQueryWrapper<TeamDemand> wrapper, String sort) {
        String value = sort == null ? "createdDesc" : sort.trim();
        if ("createdAsc".equalsIgnoreCase(value)) {
            wrapper.orderByAsc(TeamDemand::getCreatedAt);
        } else if ("deadlineAsc".equalsIgnoreCase(value)) {
            wrapper.orderByAsc(TeamDemand::getDeadline).orderByDesc(TeamDemand::getCreatedAt);
        } else if ("statusAsc".equalsIgnoreCase(value)) {
            wrapper.orderByAsc(TeamDemand::getStatus).orderByDesc(TeamDemand::getCreatedAt);
        } else {
            wrapper.orderByDesc(TeamDemand::getCreatedAt);
        }
    }

    private String normalizeTeamStatus(String status) {
        if (status == null) {
            return "";
        }
        String value = status.trim();
        if (value.isEmpty()) {
            return "";
        }
        if ("招募中".equals(value)) {
            return STATUS_OPEN;
        }
        if ("组队中".equals(value)) {
            return STATUS_TEAMING;
        }
        if ("已结束".equals(value)) {
            return STATUS_CLOSED;
        }
        return value.toUpperCase();
    }

    private String statusLabel(String status) {
        if (STATUS_OPEN.equals(status)) {
            return "招募中";
        }
        if (STATUS_TEAMING.equals(status)) {
            return "组队中";
        }
        if (STATUS_CLOSED.equals(status)) {
            return "已结束";
        }
        return status == null ? "" : status;
    }

    private boolean isValidStatusTransition(String currentStatus, String targetStatus) {
        if (STATUS_TEAMING.equals(targetStatus)) {
            return STATUS_OPEN.equals(currentStatus);
        }
        if (STATUS_CLOSED.equals(targetStatus)) {
            return STATUS_OPEN.equals(currentStatus) || STATUS_TEAMING.equals(currentStatus);
        }
        return false;
    }

    private String validateTeamDemandRequest(TeamDemandRequest request) {
        if (request == null) {
            return "请求不能为空";
        }
        if (request.getTitle() == null || request.getTitle().trim().isEmpty()) {
            return "标题不能为空";
        }
        if (request.getDescription() == null || request.getDescription().trim().isEmpty()) {
            return "内容描述不能为空";
        }
        Integer count = request.getRequiredMemberCount();
        if (count != null && (count < 1 || count > 50)) {
            return "需求人数需在 1-50 之间";
        }
        return null;
    }

    private Integer resolveRequiredMemberCount(TeamDemand team) {
        if (team.getRequiredMemberCount() != null) {
            return team.getRequiredMemberCount();
        }
        Matcher matcher = LEGACY_MEMBER_COUNT_PATTERN.matcher(team.getDescription() == null ? "" : team.getDescription());
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object resolveDeadline(TeamDemand team) {
        if (team.getDeadline() != null) {
            return team.getDeadline();
        }
        Matcher matcher = LEGACY_DEADLINE_PATTERN.matcher(team.getDescription() == null ? "" : team.getDescription());
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private long countApplications(Long teamId) {
        if (teamId == null) {
            return 0;
        }
        return teamApplicationService.count(new LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, teamId));
    }

    private long countApprovedMembers(Long teamId) {
        if (teamId == null) {
            return 0;
        }
        return teamApplicationService.count(new LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, teamId)
            .eq(TeamApplication::getStatus, "APPROVED"));
    }

    private boolean isTeamFull(TeamDemand team) {
        Integer requiredCount = resolveRequiredMemberCount(team);
        return requiredCount != null && requiredCount > 0 && countApprovedMembers(team.getId()) >= requiredCount;
    }

    private List<Map<String, Object>> buildTeamMemberViews(TeamDemand team, User creator) {
        List<Map<String, Object>> views = new ArrayList<>();
        if (creator != null) {
            views.add(userToMemberView(creator, "队长", team.getCreatedAt()));
        }
        List<TeamApplication> members = teamApplicationService.list(
            new LambdaQueryWrapper<TeamApplication>()
                .eq(TeamApplication::getTeamId, team.getId())
                .eq(TeamApplication::getStatus, "APPROVED"));
        Set<Long> userIds = members.stream()
            .map(TeamApplication::getUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap()
            : userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        for (TeamApplication member : members) {
            User user = userMap.get(member.getUserId());
            if (user != null) {
                views.add(userToMemberView(user, "队员", member.getCreatedAt()));
            }
        }
        return views;
    }

    private Map<String, Object> userToMemberView(User user, String role, Date joinedAt) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userId", user.getId());
        view.put("username", user.getRealName() != null ? user.getRealName() : user.getUsername());
        view.put("avatar", user.getAvatar());
        view.put("major", user.getMajor());
        view.put("role", role);
        view.put("joinedAt", joinedAt);
        return view;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<Map<String, Object>> enrichTeamsWithCreators(List<TeamDemand> teams) {
        Set<Long> ids = teams.stream()
            .map(TeamDemand::getCreatorId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, User> map = loadCreators(ids);
        return teams.stream()
            .map(t -> teamDemandToMap(t, map.get(t.getCreatorId())))
            .collect(Collectors.toList());
    }

    /** Get pending applications for teams I created */
    @GetMapping("/my/pending-applications")
    @ResponseBody
    public ResponseEntity<Result<List<Map<String, Object>>>> getPendingApplications(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return ResponseEntity.ok(Result.unauthorized());
        // Find all team demands created by this user
        List<TeamDemand> myTeams = teamDemandService.list(
            new LambdaQueryWrapper<TeamDemand>().eq(TeamDemand::getCreatorId, user.getId()));
        if (myTeams.isEmpty()) return ResponseEntity.ok(Result.ok(Collections.emptyList()));
        List<Long> teamIds = myTeams.stream().map(TeamDemand::getId).collect(Collectors.toList());
        // Find pending applications for those teams
        List<TeamApplication> pendingApps = teamApplicationService.list(
            new LambdaQueryWrapper<TeamApplication>()
                .in(TeamApplication::getTeamId, teamIds)
                .eq(TeamApplication::getStatus, "PENDING")
                .orderByDesc(TeamApplication::getCreatedAt));
        // Build response with user info
        List<Map<String, Object>> views = new ArrayList<>();
        for (TeamApplication app : pendingApps) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", app.getId());
            view.put("teamId", app.getTeamId());
            view.put("message", app.getMessage());
            view.put("createdAt", app.getCreatedAt());
            // Find team name
            TeamDemand team = teamDemandService.getById(app.getTeamId());
            if (team != null) view.put("teamName", team.getTitle());
            // Find applicant info
            User applicant = userService.getById(app.getUserId());
            if (applicant != null) {
                view.put("applicantName", applicant.getRealName() != null ? applicant.getRealName() : applicant.getUsername());
                view.put("applicantAvatar", applicant.getAvatar());
            }
            views.add(view);
        }
        return ResponseEntity.ok(Result.ok(views));
    }

    /** Approve or reject a team application */
    @PutMapping("/application/{id}/approve")
    @ResponseBody
    public ResponseEntity<Result<Void>> approveApplication(@PathVariable Long id, @RequestBody Map<String, String> body, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return ResponseEntity.ok(Result.unauthorized());
        TeamApplication app = teamApplicationService.getById(id);
        if (app == null) return ResponseEntity.ok(Result.notFound("申请不存在"));
        TeamDemand team = teamDemandService.getById(app.getTeamId());
        if (team == null || team.getCreatorId() == null || !team.getCreatorId().equals(user.getId()))
            return ResponseEntity.ok(Result.forbidden());
        String action = body.getOrDefault("action", "");
        if (!"APPROVED".equals(action) && !"REJECTED".equals(action))
            return ResponseEntity.ok(Result.badRequest("无效的操作"));
        app.setStatus(action);
        teamApplicationService.updateById(app);
        if ("APPROVED".equals(action) && STATUS_OPEN.equals(team.getStatus()) && isTeamFull(team)) {
            team.setStatus(STATUS_TEAMING);
            team.setUpdatedAt(new Date());
            teamDemandService.updateById(team);
        }
        return ResponseEntity.ok(Result.ok(action.equals("APPROVED") ? "已通过申请" : "已拒绝申请", null));
    }

    /** Get team members (approved applications) */
    @GetMapping("/{id}/members")
    @ResponseBody
    public ResponseEntity<Result<List<Map<String, Object>>>> getTeamMembers(@PathVariable Long id) {
        TeamDemand team = teamDemandService.getById(id);
        if (team == null) {
            return ResponseEntity.ok(Result.notFound("组队需求不存在"));
        }
        List<TeamApplication> members = teamApplicationService.list(
            new LambdaQueryWrapper<TeamApplication>()
                .eq(TeamApplication::getTeamId, id)
                .eq(TeamApplication::getStatus, "APPROVED"));
        Set<Long> userIds = members.stream().map(TeamApplication::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap()
            : userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        List<Map<String, Object>> views = new ArrayList<>();
        User creator = loadCreator(team.getCreatorId());
        if (creator != null) {
            views.add(userToMemberView(creator, "队长", team.getCreatedAt()));
        }
        for (TeamApplication m : members) {
            User u = userMap.get(m.getUserId());
            if (u != null) {
                views.add(userToMemberView(u, "队员", m.getCreatedAt()));
            }
        }
        return ResponseEntity.ok(Result.ok(views));
    }
}
