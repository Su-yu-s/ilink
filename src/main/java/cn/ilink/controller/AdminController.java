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
import cn.ilink.vo.AdminDashboardVO;
import static cn.ilink.common.ControllerUtils.safePage;
import static cn.ilink.common.ControllerUtils.safeSize;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
                           AdminAuditService adminAuditService) {
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
    public ResponseEntity<Result<?>> getUserList(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            int safeSize = safeSize(size, 100);
            int safePage = safePage(page);
            Page<User> result = userService.page(new Page<>(safePage, safeSize));
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
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            int safeSize = safeSize(size, 100);
            int safePage = safePage(page);
            Page<cn.ilink.entity.TeamDemand> result = teamDemandService.page(new Page<>(safePage, safeSize));
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
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            int safeSize = safeSize(size, 100);
            int safePage = safePage(page);
            Page<cn.ilink.entity.TeacherApplication> result = teacherApplicationService.page(new Page<>(safePage, safeSize));
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
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            int safeSize = safeSize(size, 100);
            int safePage = safePage(page);
            Page<cn.ilink.entity.Asset> result = assetService.page(new Page<>(safePage, safeSize));
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
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }
        try {
            int safeSize = safeSize(size, 100);
            int safePage = safePage(page);
            Page<CommunityPost> result = communityPostService.page(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<CommunityPost>().orderByDesc(CommunityPost::getCreatedAt)
            );
            return Result.ok("获取成功", result.getRecords()).withPagination(safePage, safeSize, result.getTotal()).toResponseEntity();
        } catch (Exception e) {
            log.error("获取社区帖子失败", e);
            return Result.fail(500, "获取社区帖子失败，请稍后重试").toResponseEntity();
        }
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
                : String.valueOf(payload.get("role")).trim().toUpperCase();
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

            String username = str(payload.get("username"));
            String role = str(payload.get("role")).toUpperCase();
            if (username.isEmpty()) {
                return Result.badRequest("用户名不能为空").toResponseEntity();
            }
            if (!ALLOWED_ROLES.contains(role)) {
                return Result.badRequest("角色非法，仅支持 STUDENT / TEACHER / ADMIN").toResponseEntity();
            }

            User current = ControllerUtils.requireUser(session);
            if (current != null && current.getId() != null && current.getId().equals(id) && !"ADMIN".equals(role)) {
                return Result.badRequest("不能修改当前登录管理员自己的身份").toResponseEntity();
            }

            target.setUsername(username);
            target.setRole(role);
            target.setEmail(emptyToNull(str(payload.get("email"))));
            target.setRealName(emptyToNull(str(payload.get("realName"))));
            target.setGender(emptyToNull(str(payload.get("gender"))));
            target.setGrade(emptyToNull(str(payload.get("grade"))));
            target.setMajor(emptyToNull(str(payload.get("major"))));
            target.setSchool(emptyToNull(str(payload.get("school"))));
            target.setCollege(emptyToNull(str(payload.get("college"))));
            target.setPhoneNumber(emptyToNull(str(payload.get("phoneNumber"))));
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

            Object sid = payload.get("studentId");
            if (sid == null || String.valueOf(sid).trim().isEmpty()) {
                target.setStudentId(null);
            } else if (sid instanceof Number) {
                target.setStudentId(((Number) sid).longValue());
            } else {
                target.setStudentId(Long.parseLong(String.valueOf(sid).trim()));
            }

            target = userRoleService.updateUserAndRole(target);
            adminAuditService.recordSafely(user, "UPDATE", "USER", id,
                "username=" + username + ", role=" + role, request);

            // 若编辑的是当前登录管理员本人，同步会话数据
            if (current != null && current.getId() != null && current.getId().equals(target.getId())) {
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

            String status = str(payload.get("status")).toUpperCase();
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
                team.setRequiredMemberCount(Integer.parseInt(String.valueOf(countObj).trim()));
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
                                                HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            adminDataService.deleteTeam(id);
            adminAuditService.recordSafely(user, "DELETE", "TEAM", id, "", request);
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

            String status = str(payload.get("status")).toUpperCase();
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
                teacher.setStatus("APPROVED");
                boolean success = teacherApplicationService.updateById(teacher);
                if (success) {
                    User teacherUser = userService.getById(teacher.getUserId());
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
                                                 HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            adminDataService.deleteAsset(id);
            adminAuditService.recordSafely(user, "DELETE", "ASSET", id, "", request);
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

    @DeleteMapping("/community-post/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteCommunityPost(@PathVariable Long id, HttpSession session,
                                                         HttpServletRequest request) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            adminDataService.deletePost(id);
            adminAuditService.recordSafely(user, "DELETE", "COMMUNITY_POST", id, "", request);
            return Result.ok("删除成功", null).toResponseEntity();
        } catch (java.util.NoSuchElementException e) {
            return Result.notFound(e.getMessage()).toResponseEntity();
        } catch (Exception e) {
            log.error("删除帖子失败", e);
            return Result.fail(500, "删除帖子失败，请稍后重试").toResponseEntity();
        }
    }
}
