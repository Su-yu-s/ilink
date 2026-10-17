package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.Asset;
import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.ProjectApplication;
import cn.ilink.entity.TeacherApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.service.impl.AssetServiceImpl;
import cn.ilink.service.impl.CommunityPostServiceImpl;
import cn.ilink.service.impl.ProjectApplicationServiceImpl;
import cn.ilink.service.impl.TeacherApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import cn.ilink.service.UserService;
import cn.ilink.service.UserRoleService;
import cn.ilink.service.AdminDataService;
import cn.ilink.service.AdminAuditService;
import cn.ilink.service.NotificationService;
import cn.ilink.service.RememberMeService;
import cn.ilink.util.DisplayNameResolver;
import cn.ilink.vo.AdminDashboardVO;
import static cn.ilink.common.ControllerUtils.safePage;
import static cn.ilink.common.ControllerUtils.safeSize;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpServletRequest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/api/admin")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final Set<String> ALLOWED_ROLES = Set.of("STUDENT", "TEACHER", "ADMIN");

    private final UserService userService;
    private final TeamDemandServiceImpl teamDemandService;
    private final TeacherApplicationServiceImpl teacherApplicationService;
    private final ProjectApplicationServiceImpl projectApplicationService;
    private final AssetServiceImpl assetService;
    private final CommunityPostServiceImpl communityPostService;
    private final NotificationService notificationService;
    private final UserRoleService userRoleService;
    private final AdminDataService adminDataService;
    private final AdminAuditService adminAuditService;
    private final RememberMeService rememberMeService;
    private final cn.ilink.common.UserSessionRegistry sessionRegistry;

    @Value("${file.access-url-prefix:/uploads/}")
    private String accessUrlPrefix;

    public AdminController(UserService userService,
                           TeamDemandServiceImpl teamDemandService,
                           TeacherApplicationServiceImpl teacherApplicationService,
                           ProjectApplicationServiceImpl projectApplicationService,
                           AssetServiceImpl assetService,
                           CommunityPostServiceImpl communityPostService,
                           NotificationService notificationService,
                           UserRoleService userRoleService,
                           AdminDataService adminDataService,
                           AdminAuditService adminAuditService,
                           RememberMeService rememberMeService,
                           cn.ilink.common.UserSessionRegistry sessionRegistry) {
        this.userService = userService;
        this.teamDemandService = teamDemandService;
        this.teacherApplicationService = teacherApplicationService;
        this.projectApplicationService = projectApplicationService;
        this.assetService = assetService;
        this.communityPostService = communityPostService;
        this.notificationService = notificationService;
        this.userRoleService = userRoleService;
        this.adminDataService = adminDataService;
        this.adminAuditService = adminAuditService;
        this.rememberMeService = rememberMeService;
        this.sessionRegistry = sessionRegistry;
    }

    @GetMapping("/dashboard")
    @ResponseBody
    public ResponseEntity<Result<?>> getDashboardData(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            // 获取统计数据
            AdminDashboardVO data = new AdminDashboardVO();
            data.setUserCount(userService.count());
            data.setTeamCount(teamDemandService.count());
            data.setTeacherCount(teacherApplicationService.count());
            data.setAssetCount(assetService.count());
            data.setPostCount(communityPostService.count());

            return Result.ok("获取成功", data).toResponseEntity();
        } catch (Exception e) {
            log.error("获取仪表盘数据失败", e);
            return Result.fail(500, "获取数据失败，请稍后重试").toResponseEntity();
        }
    }

    @GetMapping("/users")
    @ResponseBody
    public ResponseEntity<Result<?>> getUserList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            int safeSize = safeSize(size, 100);
            int safePage = safePage(page);
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            if (role != null && !role.isBlank()) {
                String r = role.trim().toUpperCase(java.util.Locale.ROOT);
                if (!ALLOWED_ROLES.contains(r)) {
                    return Result.badRequest("角色非法，仅支持 STUDENT / TEACHER / ADMIN").toResponseEntity();
                }
                wrapper.eq(User::getRole, r);
            }
            applyKeyword(wrapper, keyword, User::getUsername, User::getRealName, User::getEmail,
                User::getPhoneNumber, User::getStudentId, User::getSchool, User::getMajor, User::getCollege);
            wrapper.orderByAsc(User::getId);
            Page<User> result = userService.page(new Page<>(safePage, safeSize), wrapper);
            return Result.ok("获取成功", result.getRecords()).withPagination(safePage, safeSize, result.getTotal()).toResponseEntity();
        } catch (Exception e) {
            log.error("获取用户列表失败", e);
            return Result.fail(500, "获取用户列表失败，请稍后重试").toResponseEntity();
        }
    }

    @GetMapping("/teams")
    @ResponseBody
    public ResponseEntity<Result<?>> getTeamList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            int safeSize = safeSize(size, 100);
            int safePage = safePage(page);
            LambdaQueryWrapper<TeamDemand> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isBlank()) {
                wrapper.eq(TeamDemand::getStatus, status.trim().toUpperCase(java.util.Locale.ROOT));
            }
            applyKeyword(wrapper, keyword, TeamDemand::getTitle, TeamDemand::getDescription,
                TeamDemand::getRequiredSkills, TeamDemand::getCreatorId);
            wrapper.orderByDesc(TeamDemand::getCreatedAt);
            Page<TeamDemand> result = teamDemandService.page(new Page<>(safePage, safeSize), wrapper);
            return Result.ok("获取成功", result.getRecords()).withPagination(safePage, safeSize, result.getTotal()).toResponseEntity();
        } catch (Exception e) {
            log.error("获取团队列表失败", e);
            return Result.fail(500, "获取团队列表失败，请稍后重试").toResponseEntity();
        }
    }

    @GetMapping("/teachers")
    @ResponseBody
    public ResponseEntity<Result<?>> getTeacherList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            int safeSize = safeSize(size, 100);
            int safePage = safePage(page);
            LambdaQueryWrapper<TeacherApplication> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isBlank()) {
                wrapper.eq(TeacherApplication::getStatus, status.trim().toUpperCase(java.util.Locale.ROOT));
            }
            applyKeyword(wrapper, keyword, TeacherApplication::getIntroduction,
                TeacherApplication::getResearchDirection, TeacherApplication::getProfessionalTitle,
                TeacherApplication::getProjects, TeacherApplication::getUserId);
            wrapper.orderByDesc(TeacherApplication::getCreatedAt);
            Page<TeacherApplication> result = teacherApplicationService.page(new Page<>(safePage, safeSize), wrapper);
            return Result.ok("获取成功", result.getRecords()).withPagination(safePage, safeSize, result.getTotal()).toResponseEntity();
        } catch (Exception e) {
            log.error("获取导师列表失败", e);
            return Result.fail(500, "获取导师列表失败，请稍后重试").toResponseEntity();
        }
    }

    @GetMapping("/assets")
    @ResponseBody
    public ResponseEntity<Result<?>> getAssetList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            int safeSize = safeSize(size, 100);
            int safePage = safePage(page);
            LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<>();
            if (category != null && !category.isBlank()) {
                wrapper.eq(Asset::getCategory, category.trim());
            }
            applyKeyword(wrapper, keyword, Asset::getTitle, Asset::getDescription, Asset::getUserId);
            wrapper.orderByDesc(Asset::getCreatedAt);
            Page<Asset> result = assetService.page(new Page<>(safePage, safeSize), wrapper);
            return Result.ok("获取成功", result.getRecords()).withPagination(safePage, safeSize, result.getTotal()).toResponseEntity();
        } catch (Exception e) {
            log.error("获取成果列表失败", e);
            return Result.fail(500, "获取成果列表失败，请稍后重试").toResponseEntity();
        }
    }

    @GetMapping("/community-posts")
    @ResponseBody
    public ResponseEntity<Result<?>> getCommunityPostList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            int safeSize = safeSize(size, 100);
            int safePage = safePage(page);
            LambdaQueryWrapper<CommunityPost> wrapper = new LambdaQueryWrapper<>();
            if (category != null && !category.isBlank()) {
                wrapper.eq(CommunityPost::getCategory, category.trim());
            }
            applyKeyword(wrapper, keyword, CommunityPost::getTitle, CommunityPost::getContent,
                CommunityPost::getAuthorId);
            wrapper.orderByDesc(CommunityPost::getCreatedAt);
            Page<CommunityPost> result = communityPostService.page(new Page<>(safePage, safeSize), wrapper);
            return Result.ok("获取成功", result.getRecords()).withPagination(safePage, safeSize, result.getTotal()).toResponseEntity();
        } catch (Exception e) {
            log.error("获取社区帖子失败", e);
            return Result.fail(500, "获取社区帖子失败，请稍后重试").toResponseEntity();
        }
    }

    /**
     * 审计日志分页查询（A6：此前只写不读，管理员无法自查操作记录）。
     */
    @GetMapping("/audit-logs")
    @ResponseBody
    public ResponseEntity<Result<?>> getAuditLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long adminUserId,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            int safeSize = safeSize(size, 100);
            int safePage = safePage(page);
            Page<cn.ilink.entity.AdminAuditLog> result =
                adminAuditService.listLogs(safePage, safeSize, keyword, action, adminUserId);
            java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
            long total = 0;
            if (result != null) {
                total = result.getTotal();
                if (result.getRecords() != null && !result.getRecords().isEmpty()) {
                    java.util.Set<Long> adminIds = new java.util.HashSet<>();
                    for (cn.ilink.entity.AdminAuditLog entry : result.getRecords()) {
                        if (entry.getAdminUserId() != null) {
                            adminIds.add(entry.getAdminUserId());
                        }
                    }
                    java.util.Map<Long, User> admins = adminIds.isEmpty()
                        ? java.util.Map.of()
                        : userService.listByIds(adminIds).stream()
                            .collect(java.util.stream.Collectors.toMap(User::getId, u -> u, (a, b) -> a));
                    for (cn.ilink.entity.AdminAuditLog entry : result.getRecords()) {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("id", entry.getId());
                        row.put("adminUserId", entry.getAdminUserId());
                        User admin = admins.get(entry.getAdminUserId());
                        String adminName = admin == null
                            ? "用户#" + entry.getAdminUserId()
                            : DisplayNameResolver.resolve(admin);
                        row.put("adminName", adminName);
                        row.put("action", entry.getAction());
                        row.put("targetType", entry.getTargetType());
                        row.put("targetId", entry.getTargetId());
                        row.put("details", entry.getDetails());
                        row.put("ipAddress", entry.getIpAddress());
                        row.put("createdAt", entry.getCreatedAt());
                        rows.add(row);
                    }
                }
            }
            return Result.ok("获取成功", rows).withPagination(safePage, safeSize, total).toResponseEntity();
        } catch (Exception e) {
            log.error("获取审计日志失败", e);
            return Result.fail(500, "获取审计日志失败，请稍后重试").toResponseEntity();
        }
    }

    /**
     * 对指定列做关键词 OR LIKE 模糊匹配；空关键词不追加条件。
     * 列的取舍与各列表页的搜索提示文案一致。
     */
    @SafeVarargs
    private <T> void applyKeyword(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<T> wrapper,
                                  String keyword,
                                  com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?>... columns) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty() || columns.length == 0) {
            return;
        }
        wrapper.and(w -> {
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) {
                    w.or();
                }
                w.like(columns[i], kw);
            }
        });
    }

    @DeleteMapping("/user/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteUser(@PathVariable Long id, HttpSession session,
                                                HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            adminDataService.deleteUser(id);
            adminAuditService.recordSafely(user, "DELETE", "USER", id, "", request);
            return Result.ok("删除成功", null).toResponseEntity();
        } catch (java.util.NoSuchElementException e) {
            return Result.notFound(e.getMessage()).toResponseEntity();
        } catch (Exception e) {
            log.error("删除用户失败", e);
            return Result.fail(500, "删除用户失败，请稍后重试").toResponseEntity();
        }
    }

    /**
     * 暂停 / 恢复账号使用。
     *
     * <p>禁用是整体禁用（无法登录），不做「仅禁言」这类部分限制——用户协议中的措辞
     * 已同步改为「暂停账号使用」。禁用后立即失效该用户全部在线会话与 remember-me 令牌，
     * 否则对方还能靠旧会话继续操作。
     */
    @PutMapping("/user/{id}/status")
    @ResponseBody
    public ResponseEntity<Result<?>> updateUserStatus(
        @PathVariable Long id,
        @RequestBody Map<String, Object> payload,
        HttpSession session,
        HttpServletRequest request
    ) {
        User operator = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(operator)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            User target = userService.getById(id);
            if (target == null) {
                return Result.notFound("用户不存在").toResponseEntity();
            }
            if (operator.getId() != null && operator.getId().equals(id)) {
                return Result.badRequest("不能修改当前登录管理员自己的账号状态").toResponseEntity();
            }
            String requested = str(payload == null ? null : payload.get("status")).toUpperCase(Locale.ROOT);
            boolean disable;
            if ("DISABLED".equals(requested)) {
                disable = true;
            } else if ("ACTIVE".equals(requested)) {
                disable = false;
            } else {
                return Result.badRequest("账号状态非法，仅支持 ACTIVE / DISABLED").toResponseEntity();
            }
            String reason = str(payload == null ? null : payload.get("reason"));
            if (disable && reason.isEmpty()) {
                return Result.badRequest("暂停账号时必须填写原因").toResponseEntity();
            }
            if (reason.length() > 255) {
                return Result.badRequest("原因不能超过 255 个字").toResponseEntity();
            }

            target.setStatus(disable ? "DISABLED" : "ACTIVE");
            target.setDisabledReason(disable ? reason : null);
            target.setDisabledAt(disable ? new Date() : null);
            if (!userService.updateById(target)) {
                return Result.fail(500, "更新账号状态失败").toResponseEntity();
            }

            if (disable) {
                try {
                    rememberMeService.revokeAllForUser(id);
                } catch (Exception e) {
                    log.warn("暂停账号后吊销 remember-me 令牌失败：userId={}", id, e);
                }
                sessionRegistry.invalidateAll(id);
            }
            adminAuditService.recordSafely(operator, disable ? "DISABLE_USER" : "ENABLE_USER", "USER", id,
                disable ? "reason=" + reason : "", request);
            return Result.ok(disable ? "账号已暂停使用" : "账号已恢复使用", null).toResponseEntity();
        } catch (Exception e) {
            log.error("更新账号状态失败", e);
            return Result.fail(500, "更新账号状态失败，请稍后重试").toResponseEntity();
        }
    }

    @PutMapping("/user/{id}/role")
    @ResponseBody
    public ResponseEntity<Result<?>> updateUserRole(
        @PathVariable Long id,
        @RequestBody Map<String, Object> payload,
        HttpSession session,
        HttpServletRequest request
    ) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            User target = userService.getById(id);
            if (target == null) {
                return Result.notFound("用户不存在").toResponseEntity();
            }

            String role = payload == null || payload.get("role") == null
                ? ""
                : String.valueOf(payload.get("role")).trim().toUpperCase(Locale.ROOT);
            if (!ALLOWED_ROLES.contains(role)) {
                return Result.badRequest("角色非法，仅支持 STUDENT / TEACHER / ADMIN").toResponseEntity();
            }

            User current = ControllerUtils.requireUser(session);
            // 防止管理员把自己降级导致后台失控
            if (current != null && current.getId() != null && current.getId().equals(id) && !"ADMIN".equals(role)) {
                return Result.badRequest("不能修改当前登录管理员自己的身份").toResponseEntity();
            }

            target = userRoleService.changeRole(id, role);
            adminAuditService.recordSafely(user, "CHANGE_ROLE", "USER", id, "role=" + role, request);

            return Result.ok("身份更新成功", Map.of("id", target.getId(), "role", target.getRole())).toResponseEntity();
        } catch (Exception e) {
            log.error("更新身份失败", e);
            return Result.fail(500, "更新身份失败，请稍后重试").toResponseEntity();
        }
    }

    @PutMapping("/user/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> updateUser(
        @PathVariable Long id,
        @RequestBody Map<String, Object> payload,
        HttpSession session,
        HttpServletRequest request
    ) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            User target = userService.getById(id);
            if (target == null) {
                return Result.notFound("用户不存在").toResponseEntity();
            }

            // 部分更新语义（N-02）：只处理 payload 里出现的字段，缺失的字段保持现状。
            // 此前缺 username/role 会被前置校验拦成 400「用户名不能为空」「角色非法」，
            // 逼得前端必须"先 GET 再整体回传"，也让字段级校验的测试被前置错误掩盖。
            // username 是内部标识（历史值可能是手机号或学号），管理端也不再接受修改：
            // 直接忽略 payload 里的 username，始终沿用库中现值。
            String username = target.getUsername();
            String role = target.getRole();
            if (payload.containsKey("role")) {
                String requestedRole = str(payload.get("role")).toUpperCase(Locale.ROOT);
                if (!ALLOWED_ROLES.contains(requestedRole)) {
                    return Result.badRequest("角色非法，仅支持 STUDENT / TEACHER / ADMIN").toResponseEntity();
                }
                role = requestedRole;
            }

            User current = ControllerUtils.requireUser(session);
            if (current != null && current.getId() != null && current.getId().equals(id) && !"ADMIN".equals(role)) {
                return Result.badRequest("不能修改当前登录管理员自己的身份").toResponseEntity();
            }

            // 与 H3 同源：管理端改资料同样要先做长度/取值校验，避免超长值撞列宽变成 500
            String fieldError =
                ControllerUtils.checkMaxLength(username, ControllerUtils.USERNAME_MAX, "用户名");
            if (fieldError == null) {
                fieldError = ControllerUtils.checkMaxLength(str(payload.get("email")), ControllerUtils.EMAIL_MAX, "邮箱");
            }
            if (fieldError == null) {
                fieldError = ControllerUtils.checkMaxLength(str(payload.get("realName")), ControllerUtils.REAL_NAME_MAX, "姓名");
            }
            if (fieldError == null) {
                fieldError = ControllerUtils.checkMaxLength(str(payload.get("grade")), ControllerUtils.GRADE_MAX, "年级");
            }
            if (fieldError == null) {
                fieldError = ControllerUtils.checkMaxLength(str(payload.get("major")), ControllerUtils.MAJOR_MAX, "专业");
            }
            if (fieldError == null) {
                fieldError = ControllerUtils.checkMaxLength(str(payload.get("school")), ControllerUtils.SCHOOL_MAX, "学校");
            }
            if (fieldError == null) {
                fieldError = ControllerUtils.checkMaxLength(str(payload.get("college")), ControllerUtils.COLLEGE_MAX, "学院");
            }
            if (fieldError == null) {
                fieldError = ControllerUtils.checkMaxLength(str(payload.get("bio")), ControllerUtils.BIO_MAX, "个人简介");
            }
            if (fieldError == null) {
                fieldError = ControllerUtils.checkGender(str(payload.get("gender")));
            }
            if (fieldError != null) {
                return Result.badRequest(fieldError).toResponseEntity();
            }

            target.setUsername(username);
            target.setRole(role);
            // 同 N-02：未出现在 payload 里的字段不再被清空，保持数据库现有值
            if (payload.containsKey("email")) {
                target.setEmail(emptyToNull(str(payload.get("email"))));
            }
            if (payload.containsKey("realName")) {
                target.setRealName(emptyToNull(str(payload.get("realName"))));
            }
            if (payload.containsKey("gender")) {
                target.setGender(ControllerUtils.normalizeGender(emptyToNull(str(payload.get("gender")))));
            }
            if (payload.containsKey("grade")) {
                target.setGrade(emptyToNull(str(payload.get("grade"))));
            }
            if (payload.containsKey("major")) {
                target.setMajor(emptyToNull(str(payload.get("major"))));
            }
            if (payload.containsKey("school")) {
                target.setSchool(emptyToNull(str(payload.get("school"))));
            }
            if (payload.containsKey("college")) {
                target.setCollege(emptyToNull(str(payload.get("college"))));
            }
            if (payload.containsKey("phoneNumber")) {
                target.setPhoneNumber(emptyToNull(str(payload.get("phoneNumber"))));
            }
            if (payload.containsKey("avatar")) {
                String avatar = emptyToNull(str(payload.get("avatar")));
                if (avatar != null && !ControllerUtils.isManagedUploadUrl(avatar, accessUrlPrefix)) {
                    return Result.badRequest("头像地址仅支持站内上传文件").toResponseEntity();
                }
                target.setAvatar(avatar);
            }
            if (payload.containsKey("honors")) {
                target.setHonors(emptyToNull(str(payload.get("honors"))));
            }

            // 学号同样只在显式提交时改动：空串/空值视为清空，缺字段保持原值
            if (payload.containsKey("studentId")) {
                Object sid = payload.get("studentId");
                if (sid == null || String.valueOf(sid).trim().isEmpty()) {
                    target.setStudentId(null);
                } else if (sid instanceof Number) {
                    target.setStudentId(((Number) sid).longValue());
                } else {
                    target.setStudentId(Long.parseLong(String.valueOf(sid).trim()));
                }
            }

            // 编辑的是当前登录管理员本人时，保留当前会话（避免"接口 500 + 自己被踢下线"，见 N-01），
            // 只吊销该账号在其它设备上的会话
            boolean editingSelf = current != null && current.getId() != null && current.getId().equals(target.getId());
            target = editingSelf
                ? userRoleService.updateUserAndRole(target, session.getId())
                : userRoleService.updateUserAndRole(target);
            adminAuditService.recordSafely(user, "UPDATE", "USER", id,
                "username=" + username + ", role=" + role, request);

            // 若编辑的是当前登录管理员本人，同步会话数据（会话此时已被保留，不会再抛 invalidated）
            if (editingSelf) {
                current.setUsername(target.getUsername());
                current.setRole(target.getRole());
                current.setEmail(target.getEmail());
                current.setRealName(target.getRealName());
                current.setAvatar(target.getAvatar());
                current.setGender(target.getGender());
                current.setGrade(target.getGrade());
                current.setMajor(target.getMajor());
                current.setSchool(target.getSchool());
                current.setCollege(target.getCollege());
                current.setPhoneNumber(target.getPhoneNumber());
                current.setStudentId(target.getStudentId());
                current.setHonors(target.getHonors());
                session.setAttribute("user", current);
            }

            return Result.ok("用户信息更新成功", target).toResponseEntity();
        } catch (NumberFormatException e) {
            return Result.badRequest("学号/工号必须是数字").toResponseEntity();
        } catch (Exception e) {
            log.error("更新用户失败", e);
            return Result.fail(500, "更新用户失败，请稍后重试").toResponseEntity();
        }
    }

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private Date parseFlexibleDate(String text) {
        String v = text == null ? "" : text.trim();
        if (v.isEmpty()) return null;
        for (String pattern : new String[]{"yyyy-MM-dd", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss"}) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(pattern);
                f.setLenient(false);
                return f.parse(v);
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        return null;
    }

    private boolean teacherProfileComplete(User teacherUser, TeacherApplication profile) {
        return teacherUser != null
            && hasText(teacherUser.getRealName())
            && hasText(teacherUser.getSchool())
            && hasText(teacherUser.getMajor())
            && profile != null
            && hasText(profile.getProfessionalTitle())
            && hasText(profile.getResearchDirection())
            && hasText(profile.getIntroduction());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @PutMapping("/team/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> updateTeam(@PathVariable Long id,
                                                @RequestBody Map<String, Object> payload,
                                                HttpSession session,
                                                HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            TeamDemand team = teamDemandService.getById(id);
            if (team == null) {
                return Result.notFound("组队需求不存在").toResponseEntity();
            }

            String title = str(payload.get("title"));
            if (title.isEmpty()) {
                return Result.badRequest("标题不能为空").toResponseEntity();
            }
            if (title.length() > 200) {
                return Result.badRequest("标题不能超过200字").toResponseEntity();
            }

            String status = str(payload.get("status")).toUpperCase(Locale.ROOT);
            if (status.isEmpty()) {
                status = team.getStatus();
            }
            if (!Set.of("OPEN", "TEAMING", "CLOSED").contains(status)) {
                return Result.badRequest("状态非法，仅支持 OPEN / TEAMING / CLOSED").toResponseEntity();
            }

            team.setTitle(title);
            team.setDescription(emptyToNull(str(payload.get("description"))));
            team.setRequiredSkills(emptyToNull(str(payload.get("requiredSkills"))));

            Object countObj = payload.get("requiredMemberCount");
            if (countObj == null || String.valueOf(countObj).trim().isEmpty()) {
                team.setRequiredMemberCount(null);
            } else {
                // 修复 D-P3-3：管理端原样 Integer.parseInt 且无范围校验，误填 0/负数会让
                // isFull 永远返回"未满"，与用户端 1-50 口径分裂（TeamController#validateTeamDemandRequest）
                int count = Integer.parseInt(String.valueOf(countObj).trim());
                if (count < 1 || count > 50) {
                    return Result.badRequest("需求人数需在 1-50 之间").toResponseEntity();
                }
                team.setRequiredMemberCount(count);
            }

            Object compObj = payload.get("competitionId");
            if (compObj == null || String.valueOf(compObj).trim().isEmpty()) {
                team.setCompetitionId(null);
            } else {
                team.setCompetitionId(Integer.parseInt(String.valueOf(compObj).trim()));
            }

            Object dlObj = payload.get("deadline");
            if (dlObj == null || String.valueOf(dlObj).trim().isEmpty()) {
                team.setDeadline(null);
            } else {
                Date deadline = parseFlexibleDate(String.valueOf(dlObj).trim());
                if (deadline == null) {
                    return Result.badRequest("截止日期格式无效（应为 yyyy-MM-dd）").toResponseEntity();
                }
                team.setDeadline(deadline);
            }

            team.setStatus(status);
            team.setUpdatedAt(new Date());
            if (!teamDemandService.updateById(team)) {
                return Result.fail(500, "组队需求更新失败，请稍后重试").toResponseEntity();
            }
            adminAuditService.recordSafely(user, "UPDATE", "TEAM", id,
                "title=" + title + ", status=" + status, request);
            return Result.ok("组队需求更新成功", team).toResponseEntity();
        } catch (NumberFormatException e) {
            return Result.badRequest("人数/竞赛ID必须是数字").toResponseEntity();
        } catch (Exception e) {
            log.error("更新组队需求失败", e);
            return Result.fail(500, "更新组队需求失败，请稍后重试").toResponseEntity();
        }
    }

    @DeleteMapping("/team/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteTeam(@PathVariable Long id, HttpSession session,
                                                HttpServletRequest request,
                                                @RequestParam(required = false) String reason) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        String normalizedReason = normalizeDeleteReason(reason);
        if (normalizedReason == null) {
            return Result.badRequest("请填写删除原因").toResponseEntity();
        }

        try {
            adminDataService.deleteTeam(id, normalizedReason);
            adminAuditService.recordSafely(user, "DELETE", "TEAM", id, "reason=" + normalizedReason, request);
            return Result.ok("删除成功", null).toResponseEntity();
        } catch (java.util.NoSuchElementException e) {
            return Result.notFound(e.getMessage()).toResponseEntity();
        } catch (Exception e) {
            log.error("删除团队失败", e);
            return Result.fail(500, "删除团队失败，请稍后重试").toResponseEntity();
        }
    }

    @PutMapping("/teacher/{id}")
    @ResponseBody
    @Transactional
    @CacheEvict(value = "teacherDetail", key = "#id")
    public ResponseEntity<Result<?>> updateTeacher(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> payload,
                                                   HttpSession session,
                                                   HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            TeacherApplication teacher = teacherApplicationService.getById(id);
            if (teacher == null || "REVOKED".equals(teacher.getStatus())) {
                return Result.notFound("导师申请不存在").toResponseEntity();
            }

            String introduction = emptyToNull(str(payload.get("introduction")));
            String researchDirection = emptyToNull(str(payload.get("researchDirection")));
            String professionalTitle = emptyToNull(str(payload.get("professionalTitle")));
            String projects = emptyToNull(str(payload.get("projects")));

            if ((introduction != null && introduction.length() > 2000)
                || (researchDirection != null && researchDirection.length() > 500)
                || (professionalTitle != null && professionalTitle.length() > 100)
                || (projects != null && projects.length() > 3000)) {
                return Result.badRequest("导师资料内容过长，请精简后重试").toResponseEntity();
            }

            String status = str(payload.get("status")).toUpperCase(Locale.ROOT);
            if (status.isEmpty()) {
                status = teacher.getStatus();
            }
            if (!Set.of("PENDING", "INCOMPLETE", "APPROVED", "REJECTED").contains(status)) {
                return Result.badRequest("状态非法，仅支持 PENDING / INCOMPLETE / APPROVED / REJECTED").toResponseEntity();
            }

            teacher.setIntroduction(introduction);
            teacher.setResearchDirection(researchDirection);
            teacher.setProfessionalTitle(professionalTitle);
            teacher.setProjects(projects);

            if ("APPROVED".equals(status)) {
                User teacherUser = teacher.getUserId() == null ? null : userService.getById(teacher.getUserId());
                if (!teacherProfileComplete(teacherUser, teacher)) {
                    return Result.badRequest("导师资料不完整，暂不能设为已批准（需姓名、学校、专业、职称、研究方向、简介齐全）").toResponseEntity();
                }
            }

            teacher.setStatus(status);
            if (!teacherApplicationService.updateById(teacher)) {
                return Result.fail(500, "导师资料更新失败，请稍后重试").toResponseEntity();
            }

            if ("APPROVED".equals(status) && teacher.getUserId() != null) {
                User teacherUser = userService.getById(teacher.getUserId());
                if (teacherUser != null && !"ADMIN".equals(teacherUser.getRole()) && !"TEACHER".equals(teacherUser.getRole())) {
                    userRoleService.changeRole(teacherUser.getId(), "TEACHER");
                }
                notificationService.create(
                    teacher.getUserId(),
                    user.getId(),
                    "TEACHER_APPROVED",
                    "导师认证已通过",
                    "您的导师认证已审核通过，现在可以接收学生合作申请。",
                    teacher.getId()
                );
            }
            adminAuditService.recordSafely(user, "UPDATE", "TEACHER_PROFILE", id,
                "status=" + status, request);
            return Result.ok("导师资料更新成功", teacher).toResponseEntity();
        } catch (Exception e) {
            log.error("更新导师资料失败", e);
            return Result.fail(500, "更新导师资料失败，请稍后重试").toResponseEntity();
        }
    }

    @PutMapping("/teacher/{id}/approve")
    @ResponseBody
    @Transactional
    @CacheEvict(value = "teacherDetail", key = "#id")
    public ResponseEntity<Result<?>> approveTeacher(@PathVariable Long id, HttpSession session,
                                                    HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            cn.ilink.entity.TeacherApplication teacher = teacherApplicationService.getById(id);
            if (teacher != null) {
                if ("APPROVED".equals(teacher.getStatus())) {
                    return Result.badRequest("该导师申请已通过").toResponseEntity();
                }
                // 与 PUT /admin/teacher/{id} 设 status=APPROVED 走同一套完整性校验，
                // 否则可以绕过校验把空白档案直接推上公开导师墙。
                User teacherUser = teacher.getUserId() == null ? null : userService.getById(teacher.getUserId());
                if (!teacherProfileComplete(teacherUser, teacher)) {
                    return Result.badRequest("导师资料不完整，暂不能批准（需姓名、学校、专业、职称、研究方向、简介齐全）").toResponseEntity();
                }
                teacher.setStatus("APPROVED");
                boolean success = teacherApplicationService.updateById(teacher);
                if (success) {
                    if (teacherUser != null && !"ADMIN".equals(teacherUser.getRole()) && !"TEACHER".equals(teacherUser.getRole())) {
                        userRoleService.changeRole(teacherUser.getId(), "TEACHER");
                    }
                    notificationService.create(
                        teacher.getUserId(),
                        user.getId(),
                        "TEACHER_APPROVED",
                        "导师认证已通过",
                        "您的导师认证申请已审核通过，现在可以接收学生合作申请。",
                        teacher.getId()
                    );
                    adminAuditService.recordSafely(user, "APPROVE", "TEACHER_PROFILE", id, "", request);
                    return Result.ok("审批通过", null).toResponseEntity();
                } else {
                    return Result.fail(500, "审批失败").toResponseEntity();
                }
            } else {
                return Result.notFound("导师申请不存在").toResponseEntity();
            }
        } catch (Exception e) {
            log.error("审批失败", e);
            return Result.fail(500, "审批失败，请稍后重试").toResponseEntity();
        }
    }

    @DeleteMapping("/teacher/{id}")
    @ResponseBody
    @Transactional
    @CacheEvict(value = "teacherDetail", key = "#id")
    public ResponseEntity<Result<?>> deleteTeacher(@PathVariable Long id, HttpSession session,
                                                   HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            cn.ilink.entity.TeacherApplication teacher = teacherApplicationService.getById(id);
            if (teacher == null) {
                return Result.notFound("导师申请不存在").toResponseEntity();
            }
            projectApplicationService.remove(
                new LambdaQueryWrapper<ProjectApplication>().eq(ProjectApplication::getTeacherId, id)
            );
            boolean success = teacherApplicationService.removeById(id);
            if (success) {
                User teacherUser = userService.getById(teacher.getUserId());
                if (teacherUser != null && "TEACHER".equals(teacherUser.getRole())) {
                    userRoleService.changeRole(teacherUser.getId(), "STUDENT");
                }
                notificationService.create(
                    teacher.getUserId(),
                    user.getId(),
                    "TEACHER_REJECTED",
                    "导师认证未通过",
                    "您的导师认证申请未通过或已被撤销，如有疑问请联系管理员。",
                    null
                );
                adminAuditService.recordSafely(user, "REVOKE", "TEACHER_PROFILE", id, "", request);
                return Result.ok("删除成功", null).toResponseEntity();
            } else {
                return Result.notFound("导师申请不存在").toResponseEntity();
            }
        } catch (Exception e) {
            log.error("删除导师申请失败", e);
            return Result.fail(500, "删除导师申请失败，请稍后重试").toResponseEntity();
        }
    }

    @PutMapping("/asset/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> updateAsset(@PathVariable Long id,
                                                 @RequestBody Map<String, Object> payload,
                                                 HttpSession session,
                                                 HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            Asset asset = assetService.getById(id);
            if (asset == null) {
                return Result.notFound("成果不存在").toResponseEntity();
            }

            String title = str(payload.get("title"));
            if (title.isEmpty()) {
                return Result.badRequest("标题不能为空").toResponseEntity();
            }
            if (title.length() > 200) {
                return Result.badRequest("标题不能超过200字").toResponseEntity();
            }

            String description = emptyToNull(str(payload.get("description")));
            // 正文以 <!--md:base64--> 格式存储，base64 编码后体积膨胀约 1/3，限额需按编码后长度放宽
            if (description != null && description.length() > 60000) {
                return Result.badRequest("成果描述内容过长").toResponseEntity();
            }

            asset.setTitle(title);
            asset.setDescription(description);
            String category = str(payload.get("category"));
            if (!category.isEmpty()) {
                if (category.length() > 100) {
                    return Result.badRequest("分类不能超过100字").toResponseEntity();
                }
                asset.setCategory(category);
            }
            if (!assetService.updateById(asset)) {
                return Result.fail(500, "成果更新失败，请稍后重试").toResponseEntity();
            }
            adminAuditService.recordSafely(user, "UPDATE", "ASSET", id,
                "title=" + title, request);
            return Result.ok("成果更新成功", asset).toResponseEntity();
        } catch (Exception e) {
            log.error("更新成果失败", e);
            return Result.fail(500, "更新成果失败，请稍后重试").toResponseEntity();
        }
    }

    @DeleteMapping("/asset/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteAsset(@PathVariable Long id, HttpSession session,
                                                 HttpServletRequest request,
                                                 @RequestParam(required = false) String reason) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        String normalizedReason = normalizeDeleteReason(reason);
        if (normalizedReason == null) {
            return Result.badRequest("请填写删除原因").toResponseEntity();
        }

        try {
            adminDataService.deleteAsset(id, normalizedReason);
            adminAuditService.recordSafely(user, "DELETE", "ASSET", id, "reason=" + normalizedReason, request);
            return Result.ok("删除成功", null).toResponseEntity();
        } catch (java.util.NoSuchElementException e) {
            return Result.notFound(e.getMessage()).toResponseEntity();
        } catch (Exception e) {
            log.error("删除成果失败", e);
            return Result.fail(500, "删除成果失败，请稍后重试").toResponseEntity();
        }
    }

    @PutMapping("/community-post/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> updateCommunityPost(@PathVariable Long id,
                                                         @RequestBody Map<String, Object> payload,
                                                         HttpSession session,
                                                         HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            CommunityPost post = communityPostService.getById(id);
            if (post == null) {
                return Result.notFound("帖子不存在").toResponseEntity();
            }

            String title = str(payload.get("title"));
            if (title.isEmpty()) {
                return Result.badRequest("标题不能为空").toResponseEntity();
            }
            if (title.length() > 200) {
                return Result.badRequest("标题不能超过200字").toResponseEntity();
            }

            String category = str(payload.get("category"));
            if (category.isEmpty()) {
                category = post.getCategory();
            }
            if (!Set.of("general", "tech", "competition", "resource").contains(category)) {
                return Result.badRequest("分区非法，仅支持 general / tech / competition / resource").toResponseEntity();
            }

            String content = str(payload.get("content"));
            // 正文以 <!--md:base64--> 格式存储，限额与 CommunityController.CONTENT_MAX 保持一致
            if (content.length() > 200000) {
                return Result.badRequest("帖子内容过长").toResponseEntity();
            }

            post.setTitle(title);
            post.setCategory(category);
            post.setContent(emptyToNull(content));
            if (!communityPostService.updateById(post)) {
                return Result.fail(500, "帖子更新失败，请稍后重试").toResponseEntity();
            }
            adminAuditService.recordSafely(user, "UPDATE", "COMMUNITY_POST", id,
                "title=" + title, request);
            return Result.ok("帖子更新成功", post).toResponseEntity();
        } catch (Exception e) {
            log.error("更新帖子失败", e);
            return Result.fail(500, "更新帖子失败，请稍后重试").toResponseEntity();
        }
    }

    /** 置顶/取消置顶（只更新 is_pinned 单列，避免整行回写） */
    @PutMapping("/community-post/{id}/pinned")
    @ResponseBody
    public ResponseEntity<Result<?>> setCommunityPostPinned(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> payload,
                                                            HttpSession session,
                                                            HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            CommunityPost post = communityPostService.getById(id);
            if (post == null) {
                return Result.notFound("帖子不存在").toResponseEntity();
            }
            // 严格校验：非法/缺失的 pinned 不能静默当成 false，否则管理员会以为改成功
            Object pinnedRaw = payload.get("pinned");
            if (!(pinnedRaw instanceof Boolean)) {
                return Result.badRequest("pinned 参数必须为布尔值 true 或 false").toResponseEntity();
            }
            boolean pinned = (Boolean) pinnedRaw;
            communityPostService.update(new LambdaUpdateWrapper<CommunityPost>()
                .set(CommunityPost::getIsPinned, pinned ? 1 : 0)
                .eq(CommunityPost::getId, id));
            adminAuditService.recordSafely(user, pinned ? "PIN" : "UNPIN", "COMMUNITY_POST", id,
                "pinned=" + pinned, request);
            return Result.ok(pinned ? "已置顶" : "已取消置顶", null).toResponseEntity();
        } catch (Exception e) {
            log.error("设置帖子置顶失败", e);
            return Result.fail(500, "设置置顶失败，请稍后重试").toResponseEntity();
        }
    }

    /** 成果置顶/取消置顶（只更新 is_pinned 单列，避免整行回写） */
    @PutMapping("/asset/{id}/pinned")
    @ResponseBody
    public ResponseEntity<Result<?>> setAssetPinned(@PathVariable Long id,
                                                    @RequestBody Map<String, Object> payload,
                                                    HttpSession session,
                                                    HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            Asset asset = assetService.getById(id);
            if (asset == null) {
                return Result.notFound("成果不存在").toResponseEntity();
            }
            // 严格校验：非法/缺失的 pinned 不能静默当成 false，否则管理员会以为改成功
            Object pinnedRaw = payload.get("pinned");
            if (!(pinnedRaw instanceof Boolean)) {
                return Result.badRequest("pinned 参数必须为布尔值 true 或 false").toResponseEntity();
            }
            boolean pinned = (Boolean) pinnedRaw;
            assetService.update(new LambdaUpdateWrapper<Asset>()
                .set(Asset::getIsPinned, pinned ? 1 : 0)
                .eq(Asset::getId, id));
            adminAuditService.recordSafely(user, pinned ? "PIN" : "UNPIN", "ASSET", id,
                "pinned=" + pinned, request);
            return Result.ok(pinned ? "已置顶" : "已取消置顶", null).toResponseEntity();
        } catch (Exception e) {
            log.error("设置成果置顶失败", e);
            return Result.fail(500, "设置置顶失败，请稍后重试").toResponseEntity();
        }
    }

    @DeleteMapping("/community-post/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteCommunityPost(@PathVariable Long id, HttpSession session,
                                                         HttpServletRequest request,
                                                         @RequestParam(required = false) String reason) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        String normalizedReason = normalizeDeleteReason(reason);
        if (normalizedReason == null) {
            return Result.badRequest("请填写删除原因").toResponseEntity();
        }

        try {
            adminDataService.deletePost(id, normalizedReason);
            adminAuditService.recordSafely(user, "DELETE", "COMMUNITY_POST", id, "reason=" + normalizedReason, request);
            return Result.ok("删除成功", null).toResponseEntity();
        } catch (java.util.NoSuchElementException e) {
            return Result.notFound(e.getMessage()).toResponseEntity();
        } catch (Exception e) {
            log.error("删除帖子失败", e);
            return Result.fail(500, "删除帖子失败，请稍后重试").toResponseEntity();
        }
    }

    /** 删除原因规范化：缺失/空白返回 null，超 200 字返回空串（视为非法） */
    private String normalizeDeleteReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 200) {
            return "";
        }
        return trimmed;
    }
}
