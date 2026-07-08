package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.TeacherApplicationRequest;
import cn.ilink.entity.ProjectApplication;
import cn.ilink.entity.TeacherApplication;
import cn.ilink.entity.User;
import cn.ilink.service.impl.ProjectApplicationServiceImpl;
import cn.ilink.service.impl.TeacherApplicationServiceImpl;
import cn.ilink.service.UserService;
import cn.ilink.util.UserPreviewHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
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

@Controller
@RequestMapping("/api/teacher")
public class TeacherController {

    @Autowired
    private TeacherApplicationServiceImpl teacherApplicationService;

    @Autowired
    private ProjectApplicationServiceImpl projectApplicationService;

    @Autowired
    private UserService userService;

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
                .or().like(TeacherApplication::getIntroduction, kw));
        }

        if (major != null && !major.trim().isEmpty()) {
            wrapper.like(TeacherApplication::getResearchDirection, major.trim());
        }

        if (title != null && !title.trim().isEmpty()) {
            // 当前表结构无职称字段，暂按简介/项目信息进行文本匹配兼容前端筛选参数
            String t = title.trim();
            wrapper.and(w -> w.like(TeacherApplication::getIntroduction, t)
                .or().like(TeacherApplication::getProjects, t));
        }

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
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
        if (teacher != null) {
            return Result.ok("获取成功", teacherToMap(teacher, userService.getById(teacher.getUserId()))).toResponseEntity();
        } else {
            return Result.notFound("导师不存在").toResponseEntity();
        }
    }

    @PostMapping("/apply")
    @ResponseBody
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
            TeacherApplication application = new TeacherApplication();
            application.setUserId(user.getId());
            application.setIntroduction(request.getIntroduction());
            application.setResearchDirection(request.getResearchDirection());
            application.setProjects(request.getProjects());
            application.setStatus("PENDING");
            application.setCreatedAt(new Date());
            teacherApplicationService.save(application);
            return Result.ok("申请已提交，请等待管理员审核", null).toResponseEntity();
        } catch (DuplicateKeyException e) {
            return Result.badRequest("您已经申请过成为导师").toResponseEntity();
        }
    }

    @PostMapping("/project-apply")
    @ResponseBody
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
        String message = request.get("message") != null ? String.valueOf(request.get("message")) : null;

        TeacherApplication teacher = teacherApplicationService.getById(teacherId);
        if (teacher == null) {
            return Result.notFound("导师不存在").toResponseEntity();
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
            return Result.ok("申请已提交，请等待导师审核", null).toResponseEntity();
        } catch (DuplicateKeyException e) {
            return Result.badRequest("您已经申请过该项目").toResponseEntity();
        }
    }

    private Map<String, Object> teacherToMap(TeacherApplication t, User account) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("userId", t.getUserId());
        m.put("introduction", t.getIntroduction());
        m.put("researchDirection", t.getResearchDirection());
        m.put("projects", t.getProjects());
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
            view.put("message", app.getMessage());
            view.put("createdAt", app.getCreatedAt());
            User applicant = userService.getById(app.getUserId());
            if (applicant != null) {
                view.put("applicantName", applicant.getRealName() != null ? applicant.getRealName() : applicant.getUsername());
                view.put("applicantAvatar", applicant.getAvatar());
            }
            views.add(view);
        }
        return Result.ok(views).toResponseEntity();
    }

    /** Teacher: approve/reject project application */
    @PutMapping("/project-application/{id}/approve")
    @ResponseBody
    public ResponseEntity<Result<?>> approveProjectApplication(@PathVariable Long id, @RequestBody Map<String, String> body, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        ProjectApplication app = projectApplicationService.getById(id);
        if (app == null) return Result.notFound("申请不存在").toResponseEntity();
        TeacherApplication teacherApp = teacherApplicationService.getById(app.getTeacherId());
        if (teacherApp == null || !teacherApp.getUserId().equals(user.getId()))
            return Result.forbidden().toResponseEntity();
        String action = body.getOrDefault("action", "");
        if (!"APPROVED".equals(action) && !"REJECTED".equals(action))
            return Result.badRequest("无效的操作").toResponseEntity();
        app.setStatus(action);
        projectApplicationService.updateById(app);
        return Result.ok(action.equals("APPROVED") ? "已通过申请" : "已拒绝申请", null).toResponseEntity();
    }
}
