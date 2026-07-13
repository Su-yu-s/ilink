package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.TeacherApplicationRequest;
import cn.ilink.entity.ProjectApplication;
import cn.ilink.entity.TeacherApplication;
import cn.ilink.entity.User;
import cn.ilink.service.impl.ProjectApplicationServiceImpl;
import cn.ilink.service.impl.TeacherApplicationServiceImpl;
import cn.ilink.service.NotificationService;
import cn.ilink.service.UserService;
import cn.ilink.util.UserPreviewHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import static cn.ilink.common.ControllerUtils.safePage;
import static cn.ilink.common.ControllerUtils.safeSize;

@Controller
@RequestMapping("/api/teacher")
public class TeacherController {

    @Autowired
    private TeacherApplicationServiceImpl teacherApplicationService;

    @Autowired
    private ProjectApplicationServiceImpl projectApplicationService;

    @Autowired
    private UserService userService;

    @Autowired
    private NotificationService notificationService;

    @GetMapping("/list")
    @ResponseBody
    public ResponseEntity<Result<?>> listTeachers(@RequestParam(defaultValue = "1") Integer page,
                                                           @RequestParam(defaultValue = "10") Integer size,
                                                           @RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) String major,
                                                           @RequestParam(required = false) String title) {

        LambdaQueryWrapper<TeacherApplication> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TeacherApplication::getStatus, "APPROVED").orderByDesc(TeacherApplication::getCreatedAt);

        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(TeacherApplication::getResearchDirection, kw)
                .or().like(TeacherApplication::getProjects, kw)
                .or().like(TeacherApplication::getProfessionalTitle, kw)
                .or().like(TeacherApplication::getIntroduction, kw));
        }

        if (major != null && !major.trim().isEmpty()) {
            wrapper.like(TeacherApplication::getResearchDirection, major.trim());
        }

        if (title != null && !title.trim().isEmpty()) {
            String t = title.trim();
            wrapper.and(w -> w.like(TeacherApplication::getProfessionalTitle, t)
                .or().like(TeacherApplication::getProjects, t));
        }

        int safePage = safePage(page);
        int safeSize = safeSize(size, 100);
        Page<TeacherApplication> pageReq = new Page<>(safePage, safeSize);
        Page<TeacherApplication> result = teacherApplicationService.page(pageReq, wrapper);
        List<Map<String, Object>> data = enrichTeachersWithUsers(result.getRecords());

        return Result.ok("获取成功", data).withPagination(safePage, safeSize, result.getTotal()).toResponseEntity();
    }

    @GetMapping("/project-application-status")
    @ResponseBody
    public ResponseEntity<Result<?>> projectApplicationStatus(@RequestParam(required = false) Long teacherId,
                                                                        HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        if (teacherId == null) {
            return Result.badRequest("缺少导师ID参数").toResponseEntity();
        }

        ProjectApplication application = projectApplicationService.getOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ProjectApplication>()
                .eq("teacher_id", teacherId)
                .eq("user_id", user.getId())
        );

        if (application != null) {
            return Result.ok("获取成功", Map.of("status", application.getStatus())).toResponseEntity();
        } else {
            return Result.ok("未申请", Map.of("status", "NOT_APPLIED")).toResponseEntity();
        }
    }

    @GetMapping("/{id}")
    @ResponseBody
    @Cacheable(value = "teacherDetail", key = "#id")
    public ResponseEntity<Result<?>> getTeacher(@PathVariable Long id) {
        TeacherApplication teacher = teacherApplicationService.getById(id);
        if (teacher != null && "APPROVED".equals(teacher.getStatus())) {
            return Result.ok("获取成功", teacherToMap(teacher, userService.getById(teacher.getUserId()))).toResponseEntity();
        } else {
            return Result.notFound("导师不存在").toResponseEntity();
        }
    }

    @PostMapping("/apply")
    @ResponseBody
    @Transactional
    public ResponseEntity<Result<?>> applyAsTeacher(@RequestBody TeacherApplicationRequest request, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        // 检查是否已经申请过
        TeacherApplication existingApplication = teacherApplicationService.getOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TeacherApplication>()
                .eq("user_id", user.getId())
        );

        if (existingApplication != null) {
            return Result.badRequest("您已经申请过成为导师").toResponseEntity();
        }

        // 创建申请（唯一索引兜底，捕获并发重复插入）
        try {
            String introduction = cleanField(request.getIntroduction());
            String researchDirection = cleanField(request.getResearchDirection());
            String professionalTitle = cleanField(request.getProfessionalTitle());
            String expertise = cleanField(request.getExpertise());
            String projects = cleanField(request.getProjects());
            if (introduction.isEmpty() || researchDirection.isEmpty() || professionalTitle.isEmpty() || expertise.isEmpty()) {
                return Result.badRequest("请完整填写专业领域、职称、导师简介和研究方向").toResponseEntity();
            }
            if (introduction.length() > 2000 || researchDirection.length() > 500
                || professionalTitle.length() > 100 || expertise.length() > 100 || projects.length() > 3000) {
                return Result.badRequest("导师资料内容过长，请精简后重试").toResponseEntity();
            }
            TeacherApplication application = new TeacherApplication();
            application.setUserId(user.getId());
            application.setIntroduction(introduction);
            application.setResearchDirection(researchDirection);
            application.setProfessionalTitle(professionalTitle);
            application.setProjects(projects.isEmpty() ? null : projects);
            application.setStatus("PENDING");
            application.setCreatedAt(new Date());
            if (!teacherApplicationService.save(application)) {
                return Result.fail(500, "导师申请提交失败，请稍后重试").toResponseEntity();
            }
            if (!expertise.equals(cleanField(user.getMajor()))) {
                user.setMajor(expertise);
                userService.updateById(user);
                session.setAttribute("user", user);
            }
            return Result.ok("申请已提交，请等待管理员审核", null).toResponseEntity();
        } catch (DuplicateKeyException e) {
            return Result.badRequest("您已经申请过成为导师").toResponseEntity();
        }
    }

    @PostMapping("/project-apply")
    @ResponseBody
    @Transactional
    public ResponseEntity<Result<?>> applyForProject(@RequestBody Map<String, Object> request, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        Object rawTeacherId = request.get("teacherId");
        if (rawTeacherId == null) {
            return Result.badRequest("缺少导师ID参数").toResponseEntity();
        }
        Long teacherId = ControllerUtils.parseLongParam(rawTeacherId);
        if (teacherId == null) {
            return Result.badRequest("导师ID格式无效").toResponseEntity();
        }
        String message = request.get("message") != null ? String.valueOf(request.get("message")).trim() : "";
        if (message.length() > 1000) {
            return Result.badRequest("申请说明不能超过1000字").toResponseEntity();
        }

        TeacherApplication teacher = teacherApplicationService.getById(teacherId);
        if (teacher == null || !"APPROVED".equals(teacher.getStatus())) {
            return Result.notFound("导师不存在").toResponseEntity();
        }
        if (teacher.getUserId() != null && teacher.getUserId().equals(user.getId())) {
            return Result.badRequest("不能向自己提交合作申请").toResponseEntity();
        }

        // 检查是否已经申请过
        ProjectApplication existingApplication = projectApplicationService.getOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ProjectApplication>()
                .eq("teacher_id", teacherId)
                .eq("user_id", user.getId())
        );

        if (existingApplication != null) {
            return Result.badRequest("您已经申请过该项目").toResponseEntity();
        }

        // 创建项目申请（唯一索引兜底，捕获并发重复插入）
        try {
            ProjectApplication application = new ProjectApplication();
            application.setTeacherId(teacherId);
            application.setUserId(user.getId());
            application.setStatus("PENDING");
            application.setMessage(message);
            application.setCreatedAt(new Date());
            projectApplicationService.save(application);
            notificationService.create(
                teacher.getUserId(),
                user.getId(),
                "PROJECT_APPLY",
                "收到新的合作申请",
                displayName(user) + " 向您提交了合作申请，请及时处理。",
                teacherId
            );
            return Result.ok("申请已提交，请等待导师审核", null).toResponseEntity();
        } catch (DuplicateKeyException e) {
            return Result.badRequest("您已经申请过该项目").toResponseEntity();
        }
    }

    private Map<String, Object> teacherToMap(TeacherApplication t, User account) {
        Map<String, Object> m = new LinkedHashMap<>();
        String legacyProjects = cleanField(t.getProjects());
        String professionalTitle = cleanField(t.getProfessionalTitle());
        if (professionalTitle.isEmpty()) professionalTitle = legacyProfessionalTitle(legacyProjects);
        String expertise = account == null ? "" : cleanField(account.getMajor());
        if (expertise.isEmpty()) expertise = legacyExpertise(legacyProjects);
        String representativeProjects = isLegacyCredential(legacyProjects) ? "" : legacyProjects;
        m.put("id", t.getId());
        m.put("userId", t.getUserId());
        m.put("introduction", t.getIntroduction());
        m.put("researchDirection", t.getResearchDirection());
        m.put("professionalTitle", professionalTitle);
        m.put("expertise", expertise);
        m.put("institution", account == null ? "" : cleanField(account.getSchool()));
        m.put("department", account == null ? "" : cleanField(account.getCollege()));
        m.put("projects", representativeProjects);
        m.put("status", t.getStatus());
        m.put("createdAt", t.getCreatedAt());
        m.put("userPreview", UserPreviewHelper.toPreview(account));
        return m;
    }

    private List<Map<String, Object>> enrichTeachersWithUsers(List<TeacherApplication> teachers) {
        Set<Long> ids = teachers.stream()
            .map(TeacherApplication::getUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return teachers.stream().map(t -> teacherToMap(t, null)).collect(Collectors.toList());
        }
        Map<Long, User> map = userService.listByIds(ids).stream()
            .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        return teachers.stream().map(t -> teacherToMap(t, map.get(t.getUserId()))).collect(Collectors.toList());
    }

    @GetMapping("/me")
    @ResponseBody
    public ResponseEntity<Result<?>> getMyTeacherProfile(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        TeacherApplication teacher = teacherApplicationService.getOne(
            new LambdaQueryWrapper<TeacherApplication>()
                .eq(TeacherApplication::getUserId, user.getId())
                .eq(TeacherApplication::getStatus, "APPROVED")
        );
        if (teacher == null) return Result.notFound("当前账号尚无已启用的导师档案").toResponseEntity();
        return Result.ok(teacherToMap(teacher, user)).toResponseEntity();
    }

    @PutMapping("/profile")
    @ResponseBody
    @Transactional
    @CacheEvict(value = "teacherDetail", allEntries = true)
    public ResponseEntity<Result<?>> updateMyTeacherProfile(@RequestBody TeacherApplicationRequest request, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        if (!"TEACHER".equals(user.getRole())) return Result.forbidden().toResponseEntity();
        TeacherApplication teacher = teacherApplicationService.getOne(
            new LambdaQueryWrapper<TeacherApplication>()
                .eq(TeacherApplication::getUserId, user.getId())
                .eq(TeacherApplication::getStatus, "APPROVED")
        );
        if (teacher == null) return Result.notFound("导师档案不存在").toResponseEntity();

        String introduction = cleanField(request.getIntroduction());
        String researchDirection = cleanField(request.getResearchDirection());
        String professionalTitle = cleanField(request.getProfessionalTitle());
        String projects = cleanField(request.getProjects());
        if (introduction.length() > 2000 || researchDirection.length() > 500
            || professionalTitle.length() > 100 || projects.length() > 3000) {
            return Result.badRequest("导师资料内容过长，请精简后重试").toResponseEntity();
        }
        teacher.setIntroduction(introduction.isEmpty() ? null : introduction);
        teacher.setResearchDirection(researchDirection.isEmpty() ? null : researchDirection);
        teacher.setProfessionalTitle(professionalTitle.isEmpty() ? null : professionalTitle);
        teacher.setProjects(projects.isEmpty() ? null : projects);
        if (!teacherApplicationService.updateById(teacher)) {
            return Result.fail(500, "导师资料保存失败，请稍后重试").toResponseEntity();
        }
        return Result.ok("导师资料已保存", teacherToMap(teacher, user)).toResponseEntity();
    }

    /** Teacher: get pending project applications */
    @GetMapping("/my/project-applications")
    @ResponseBody
    public ResponseEntity<Result<?>> getMyProjectApplications(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        // Find teacher application for this user
        TeacherApplication teacherApp = teacherApplicationService.getOne(
            new LambdaQueryWrapper<TeacherApplication>()
                .eq(TeacherApplication::getUserId, user.getId())
                .eq(TeacherApplication::getStatus, "APPROVED"));
        if (teacherApp == null) return Result.ok(Collections.emptyList()).toResponseEntity();
        List<ProjectApplication> pendingApps = projectApplicationService.list(
            new LambdaQueryWrapper<ProjectApplication>()
                .eq(ProjectApplication::getTeacherId, teacherApp.getId())
                .eq(ProjectApplication::getStatus, "PENDING")
                .orderByDesc(ProjectApplication::getCreatedAt));
        List<Map<String, Object>> views = new ArrayList<>();
        for (ProjectApplication app : pendingApps) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", app.getId());
            view.put("applicantUserId", app.getUserId());
            view.put("message", app.getMessage());
            view.put("createdAt", app.getCreatedAt());
            User applicant = userService.getById(app.getUserId());
            if (applicant != null) {
                view.put("applicantName", displayName(applicant));
                view.put("applicantAvatar", applicant.getAvatar());
                view.put("applicantPreview", UserPreviewHelper.toPreview(applicant));
            }
            views.add(view);
        }
        return Result.ok(views).toResponseEntity();
    }

    /** Teacher: approve/reject project application */
    @PutMapping("/project-application/{id}/approve")
    @ResponseBody
    @Transactional
    public ResponseEntity<Result<?>> approveProjectApplication(@PathVariable Long id, @RequestBody Map<String, String> body, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        ProjectApplication app = projectApplicationService.getById(id);
        if (app == null) return Result.notFound("申请不存在").toResponseEntity();
        TeacherApplication teacherApp = teacherApplicationService.getById(app.getTeacherId());
        if (teacherApp == null || !teacherApp.getUserId().equals(user.getId()))
            return Result.forbidden().toResponseEntity();
        if (!"PENDING".equals(app.getStatus())) {
            return Result.badRequest("该申请已处理，请刷新列表").toResponseEntity();
        }
        String action = body.getOrDefault("action", "");
        if (!"APPROVED".equals(action) && !"REJECTED".equals(action))
            return Result.badRequest("无效的操作").toResponseEntity();
        app.setStatus(action);
        if (!projectApplicationService.updateById(app)) {
            return Result.fail(500, "申请状态更新失败，请稍后重试").toResponseEntity();
        }
        boolean approved = "APPROVED".equals(action);
        notificationService.create(
            app.getUserId(),
            user.getId(),
            approved ? "PROJECT_APPROVED" : "PROJECT_REJECTED",
            approved ? "导师已通过您的申请" : "导师未通过您的申请",
            displayName(user) + (approved ? " 已通过您的合作申请。" : " 暂未通过您的合作申请。"),
            teacherApp.getId()
        );
        return Result.ok(action.equals("APPROVED") ? "已通过申请" : "已拒绝申请", null).toResponseEntity();
    }

    private String displayName(User user) {
        if (user == null) return "用户";
        if (user.getRealName() != null && !user.getRealName().trim().isEmpty()) {
            return user.getRealName().trim();
        }
        if (user.getUsername() != null && !user.getUsername().trim().isEmpty()) {
            return user.getUsername().trim();
        }
        return "用户";
    }

    private String cleanField(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isLegacyCredential(String value) {
        return value != null && value.matches("^.*（[^（）]+）$");
    }

    private String legacyProfessionalTitle(String value) {
        if (!isLegacyCredential(value)) return "";
        int start = value.lastIndexOf('（');
        int end = value.lastIndexOf('）');
        return start >= 0 && end > start ? value.substring(start + 1, end).trim() : "";
    }

    private String legacyExpertise(String value) {
        if (!isLegacyCredential(value)) return "";
        int start = value.lastIndexOf('（');
        return start > 0 ? value.substring(0, start).trim() : "";
    }
}
