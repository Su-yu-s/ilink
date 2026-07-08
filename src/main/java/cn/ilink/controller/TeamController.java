package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.TeamDemandRequest;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.entity.UserSkill;
import cn.ilink.mapper.UserSkillMapper;
import cn.ilink.service.NotificationService;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import cn.ilink.service.UserService;
import cn.ilink.util.UserPreviewHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static cn.ilink.common.ControllerUtils.safePage;
import static cn.ilink.common.ControllerUtils.safeSize;

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
    private TeamDemandServiceImpl teamDemandService;

    @Autowired
    private TeamApplicationServiceImpl teamApplicationService;

    @Autowired
    private UserService userService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserSkillMapper userSkillMapper;

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

        int safePage = safePage(page);
        int safeSize = safeSize(size, 100);
        Page<TeamDemand> pageReq = new Page<>(safePage, safeSize);
        Page<TeamDemand> result = teamDemandService.page(pageReq, wrapper);
        List<Map<String, Object>> data = enrichTeamsWithCreators(result.getRecords());

        return Result.ok("获取成功", data).withPagination(safePage, safeSize, result.getTotal()).toResponseEntity();
    }

    /** 当前用户发布的组队需求 */
    @GetMapping("/my/published")
    @ResponseBody
    public ResponseEntity<Result<?>> myPublishedTeams(@RequestParam(required = false) String status,
                                                       @RequestParam(required = false, defaultValue = "createdDesc") String sort,
                                                       HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
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
        return Result.ok("获取成功", list).toResponseEntity();
    }

    /** 当前用户发起的组队申请（含队伍标题） */
    @GetMapping("/my/applications")
    @ResponseBody
    public ResponseEntity<Result<?>> myTeamApplications(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
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
        return Result.ok("获取成功", rows).toResponseEntity();
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
            return Result.ok("获取成功", teamDemandToMap(team, loadCreator(team.getCreatorId()))).toResponseEntity();
        } else {
            return Result.notFound("组队需求不存在").toResponseEntity();
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
                return Result.badRequest(validationError).toResponseEntity();
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
                return Result.ok("发布成功", team).toResponseEntity();
            } else {
                return Result.fail("发布失败").toResponseEntity();
            }
        } else {
            return Result.unauthorized().toResponseEntity();
        }
    }

    @PostMapping("/join")
    @ResponseBody
    public ResponseEntity<Result<?>> joinTeam(@RequestBody Map<String, Object> request, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        Object rawTeamId = request.get("teamId");
        if (rawTeamId == null) {
            return Result.badRequest("缺少团队ID参数").toResponseEntity();
        }
        Long teamId = ControllerUtils.parseLongParam(rawTeamId);
        if (teamId == null) {
            return Result.badRequest("团队ID格式无效").toResponseEntity();
        }

        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            return Result.notFound("团队不存在").toResponseEntity();
        }

        // 检查团队状态，仅 OPEN 状态允许申请加入
        if (!STATUS_OPEN.equals(team.getStatus())) {
            return Result.badRequest("该团队已停止招募").toResponseEntity();
        }

        // 检查是否已经是团队创建者
        if (team.getCreatorId() != null && team.getCreatorId().equals(user.getId())) {
            return Result.badRequest("您是该团队的创建者，无需申请加入").toResponseEntity();
        }

        // 检查是否已经申请过
        TeamApplication existingApplication = teamApplicationService.getOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TeamApplication>()
                .eq("team_id", teamId)
                .eq("user_id", user.getId())
        );

        if (existingApplication != null) {
            return Result.badRequest("您已经申请过该团队").toResponseEntity();
        }

        // 创建申请记录（唯一索引兜底，捕获并发重复插入）
        try {
            TeamApplication application = new TeamApplication();
            application.setTeamId(teamId);
            application.setUserId(user.getId());
            application.setStatus("PENDING");
            application.setMessage(request.containsKey("message") ? request.get("message").toString() : "");
            application.setCreatedAt(new Date());
            teamApplicationService.save(application);

            // 给队长发通知：有人申请加入
            String applicantName = user.getRealName() != null ? user.getRealName() : user.getUsername();
            notificationService.create(
                team.getCreatorId(),
                user.getId(),
                "TEAM_APPLY",
                "组队申请",
                applicantName + " 申请加入你的队伍「" + team.getTitle() + "」",
                teamId);

            return Result.ok("申请已提交，请等待团队创建者审核").toResponseEntity();
        } catch (DuplicateKeyException e) {
            return Result.badRequest("您已经申请过该团队").toResponseEntity();
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
                return Result.badRequest("只有招募中的组队需求可以编辑").toResponseEntity();
            }
            String validationError = validateTeamDemandRequest(request);
            if (validationError != null) {
                return Result.badRequest(validationError).toResponseEntity();
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
                return Result.ok("更新成功", team).toResponseEntity();
            } else {
                return Result.fail("更新失败").toResponseEntity();
            }
        } else if (user == null) {
            return Result.unauthorized().toResponseEntity();
        } else if (team == null) {
            return Result.notFound("组队需求不存在").toResponseEntity();
        } else {
            return Result.forbidden().toResponseEntity();
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
            return Result.unauthorized().toResponseEntity();
        }
        if (team == null) {
            return Result.notFound("组队需求不存在").toResponseEntity();
        }
        if (team.getCreatorId() == null || !team.getCreatorId().equals(user.getId())) {
            return Result.forbidden().toResponseEntity();
        }
        String targetStatus = normalizeTeamStatus(request == null ? null : request.get("status"));
        if (!isValidStatusTransition(team.getStatus(), targetStatus)) {
            return Result.badRequest("当前状态不允许执行该操作").toResponseEntity();
        }
        team.setStatus(targetStatus);
        team.setUpdatedAt(new Date());
        boolean success = teamDemandService.updateById(team);
        if (!success) {
            return Result.fail("状态更新失败").toResponseEntity();
        }
        return Result.ok("状态更新成功", teamDemandToMap(team, loadCreator(team.getCreatorId()))).toResponseEntity();
    }

    @DeleteMapping("/{id}")
    @ResponseBody
    @CacheEvict(value = "teamDetail", key = "#id")
    public ResponseEntity<Result<?>> deleteTeam(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        TeamDemand team = teamDemandService.getById(id);

        if (user != null && team != null && team.getCreatorId() != null && team.getCreatorId().equals(user.getId())) {
            if (!STATUS_OPEN.equals(team.getStatus())) {
                return Result.badRequest("只有招募中的组队需求可以删除").toResponseEntity();
            }
            if (countApplications(id) > 0) {
                return Result.badRequest("已有报名记录，不能删除").toResponseEntity();
            }
            boolean success = teamDemandService.removeById(id);
            if (success) {
                return Result.ok("删除成功").toResponseEntity();
            } else {
                return Result.fail("删除失败").toResponseEntity();
            }
        } else if (user == null) {
            return Result.unauthorized().toResponseEntity();
        } else if (team == null) {
            return Result.notFound("组队需求不存在").toResponseEntity();
        } else {
            return Result.forbidden().toResponseEntity();
        }
    }

    @GetMapping("/application-status")
    @ResponseBody
    public ResponseEntity<Result<?>> getApplicationStatus(@RequestParam(required = false) Long teamId,
                                                            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        if (teamId == null) {
            return Result.badRequest("缺少团队ID参数").toResponseEntity();
        }

        // 查询申请状态
        TeamApplication application = teamApplicationService.getOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TeamApplication>()
                .eq("team_id", teamId)
                .eq("user_id", user.getId())
        );

        if (application != null) {
            return Result.ok("获取成功", Map.of("status", application.getStatus())).toResponseEntity();
        } else {
            return Result.ok("未申请", Map.of("status", "NOT_APPLIED")).toResponseEntity();
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
        if ("组队中".equals(value) || "已组队".equals(value)) {
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
            return "已组队";
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

    /** 当前用户已加入/创建的团队列表（作为队员或队长） */
    @GetMapping("/my/joined")
    @ResponseBody
    public ResponseEntity<Result<?>> myJoinedTeams(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        // 1. 查找当前用户所有 APPROVED 的申请（作为队员）
        List<TeamApplication> approvedApps = teamApplicationService.list(
            new LambdaQueryWrapper<TeamApplication>()
                .eq(TeamApplication::getUserId, user.getId())
                .eq(TeamApplication::getStatus, "APPROVED")
                .orderByDesc(TeamApplication::getCreatedAt));
        // 2. 查找当前用户创建的所有团队（作为队长）
        List<TeamDemand> myCreatedTeams = teamDemandService.list(
            new LambdaQueryWrapper<TeamDemand>()
                .eq(TeamDemand::getCreatorId, user.getId())
                .orderByDesc(TeamDemand::getCreatedAt));

        // 合并去重
        Set<Long> teamIds = new HashSet<>();
        if (!approvedApps.isEmpty()) {
            teamIds.addAll(approvedApps.stream().map(TeamApplication::getTeamId).filter(Objects::nonNull).collect(Collectors.toSet()));
        }
        if (!myCreatedTeams.isEmpty()) {
            teamIds.addAll(myCreatedTeams.stream().map(TeamDemand::getId).collect(Collectors.toSet()));
        }
        if (teamIds.isEmpty()) {
            return Result.ok("获取成功", Collections.emptyList()).toResponseEntity();
        }
        Map<Long, TeamDemand> teamMap = teamDemandService.listByIds(teamIds).stream()
            .collect(Collectors.toMap(TeamDemand::getId, t -> t, (a, b) -> a));

        // 构建结果：先放创建的团队（队长），再放加入的团队（队员）
        List<Map<String, Object>> rows = new ArrayList<>();
        // 队长创建的团队
        for (TeamDemand td : myCreatedTeams) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("teamId", td.getId());
            m.put("teamTitle", td.getTitle());
            m.put("status", td.getStatus());
            m.put("joinedAt", td.getCreatedAt());
            m.put("isCreator", true);
            rows.add(m);
        }
        // 加入的团队（排除已作为队长创建的）
        Set<Long> createdTeamIds = myCreatedTeams.stream().map(TeamDemand::getId).collect(Collectors.toSet());
        for (TeamApplication app : approvedApps) {
            if (createdTeamIds.contains(app.getTeamId())) continue;
            TeamDemand team = teamMap.get(app.getTeamId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("teamId", app.getTeamId());
            m.put("teamTitle", team != null ? team.getTitle() : "（组队已删除）");
            m.put("status", team != null ? team.getStatus() : null);
            m.put("joinedAt", app.getCreatedAt());
            m.put("isCreator", false);
            rows.add(m);
        }
        return Result.ok("获取成功", rows).toResponseEntity();
    }

    /** Get pending applications for teams I created */
    @GetMapping("/my/pending-applications")
    @ResponseBody
    public ResponseEntity<Result<?>> getPendingApplications(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        // Find all team demands created by this user
        List<TeamDemand> myTeams = teamDemandService.list(
            new LambdaQueryWrapper<TeamDemand>().eq(TeamDemand::getCreatorId, user.getId()));
        if (myTeams.isEmpty()) return Result.ok(Collections.emptyList()).toResponseEntity();
        List<Long> teamIds = myTeams.stream().map(TeamDemand::getId).collect(Collectors.toList());
        // Find pending applications for those teams
        List<TeamApplication> pendingApps = teamApplicationService.list(
            new LambdaQueryWrapper<TeamApplication>()
                .in(TeamApplication::getTeamId, teamIds)
                .eq(TeamApplication::getStatus, "PENDING")
                .orderByDesc(TeamApplication::getCreatedAt));
        // Build response with user info（批量查询，避免 N+1）
        List<Long> userIds = pendingApps.stream().map(TeamApplication::getUserId).distinct().collect(Collectors.toList());

        Map<Long, TeamDemand> teamMap = new HashMap<>();
        if (!teamIds.isEmpty()) {
            for (TeamDemand td : teamDemandService.listByIds(teamIds)) {
                teamMap.put(td.getId(), td);
            }
        }
        Map<Long, User> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (User u : userService.listByIds(userIds)) {
                u.setPassword(null);
                userMap.put(u.getId(), u);
            }
        }

        // 批量查询申请人技能
        Map<Long, List<Map<String, Object>>> skillsMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            List<UserSkill> allSkills = userSkillMapper.selectList(
                new LambdaQueryWrapper<UserSkill>().in(UserSkill::getUserId, userIds));
            for (UserSkill s : allSkills) {
                skillsMap.computeIfAbsent(s.getUserId(), k -> new ArrayList<>()).add(skillToView(s));
            }
        }

        List<Map<String, Object>> views = new ArrayList<>();
        for (TeamApplication app : pendingApps) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", app.getId());
            view.put("teamId", app.getTeamId());
            view.put("message", app.getMessage());
            view.put("createdAt", app.getCreatedAt());
            // 内存查表
            TeamDemand team = teamMap.get(app.getTeamId());
            if (team != null) view.put("teamName", team.getTitle());
            User applicant = userMap.get(app.getUserId());
            if (applicant != null) {
                view.put("applicantName", applicant.getRealName() != null ? applicant.getRealName() : applicant.getUsername());
                view.put("applicantAvatar", applicant.getAvatar());
                view.put("applicantMajor", applicant.getMajor());
                view.put("applicantGrade", applicant.getGrade());
                view.put("applicantSchool", applicant.getSchool());
                view.put("applicantUserId", applicant.getId());
            }
            view.put("skills", skillsMap.getOrDefault(app.getUserId(), Collections.emptyList()));
            views.add(view);
        }
        return Result.ok(views).toResponseEntity();
    }

    /** 将技能实体转为前端视图 */
    private Map<String, Object> skillToView(UserSkill s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getSkillName());
        m.put("level", s.getSkillLevel());
        m.put("category", s.getSkillCategory());
        return m;
    }

    /** Approve or reject a team application */
    @PutMapping("/application/{id}/approve")
    @ResponseBody
    public ResponseEntity<Result<?>> approveApplication(@PathVariable Long id, @RequestBody Map<String, String> body, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        TeamApplication app = teamApplicationService.getById(id);
        if (app == null) return Result.notFound("申请不存在").toResponseEntity();
        TeamDemand team = teamDemandService.getById(app.getTeamId());
        if (team == null || team.getCreatorId() == null || !team.getCreatorId().equals(user.getId()))
            return Result.forbidden().toResponseEntity();
        if (!"PENDING".equals(app.getStatus()))
            return Result.badRequest("该申请已被处理").toResponseEntity();
        String action = body.getOrDefault("action", "");
        if (!"APPROVED".equals(action) && !"REJECTED".equals(action))
            return Result.badRequest("无效的操作").toResponseEntity();
        String note = body.get("note");
        if ("REJECTED".equals(action) && (note == null || note.trim().length() < 10))
            return Result.badRequest("拒绝理由至少填写 10 个字").toResponseEntity();
        if (note != null && note.length() > 500)
            return Result.badRequest("备注不能超过 500 字").toResponseEntity();

        // 检查队伍是否已满
        if ("APPROVED".equals(action) && isTeamFull(team))
            return Result.badRequest("队伍已满，无法通过更多申请").toResponseEntity();

        // 更新申请状态
        app.setStatus(action);
        app.setReviewerNote(note != null ? note.trim() : null);
        app.setReviewedAt(new Date());
        teamApplicationService.updateById(app);

        // 满员自动切换状态
        if ("APPROVED".equals(action) && STATUS_OPEN.equals(team.getStatus()) && isTeamFull(team)) {
            team.setStatus(STATUS_TEAMING);
            team.setUpdatedAt(new Date());
            teamDemandService.updateById(team);
        }

        // 通知申请人
        String teamTitle = team.getTitle() != null ? team.getTitle() : "未知队伍";
        if ("APPROVED".equals(action)) {
            String content = "你已成功加入队伍「" + teamTitle + "」";
            if (note != null && !note.trim().isEmpty()) {
                content += "\n队长留言：" + note.trim();
            }
            notificationService.create(
                app.getUserId(),
                user.getId(),
                "TEAM_APPROVED",
                "申请通过",
                content,
                app.getTeamId());
        } else {
            String content = "你的申请未被通过「" + teamTitle + "」";
            if (note != null && !note.trim().isEmpty()) {
                content += "\n队长留言：" + note.trim();
            }
            notificationService.create(
                app.getUserId(),
                user.getId(),
                "TEAM_REJECTED",
                "申请未通过",
                content,
                app.getTeamId());
        }

        return Result.ok(action.equals("APPROVED") ? "已通过申请" : "已拒绝申请", null).toResponseEntity();
    }

    /** Get team members (approved applications) */
    @GetMapping("/{id}/members")
    @ResponseBody
    public ResponseEntity<Result<?>> getTeamMembers(@PathVariable Long id) {
        TeamDemand team = teamDemandService.getById(id);
        if (team == null) {
            return Result.notFound("组队需求不存在").toResponseEntity();
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
        return Result.ok(views).toResponseEntity();
    }
}
