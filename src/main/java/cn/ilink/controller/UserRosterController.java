package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.ProjectApplication;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeacherApplication;
import cn.ilink.entity.User;
import cn.ilink.service.TeamMembershipService;
import cn.ilink.service.UserService;
import cn.ilink.service.impl.ProjectApplicationServiceImpl;
import cn.ilink.service.impl.TeacherApplicationServiceImpl;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 邀请弹窗用的人员检索与师生通讯录。
 *
 * <p>隐私边界：这两个接口只返回姓名、头像、专业、年级、注册角色，**不返回手机号、邮箱、学号**。
 * 学号只用于匹配（搜学号能搜到人），不出现在响应里。
 */
@Controller
@RequestMapping("/api/user")
public class UserRosterController {

    private static final int SEARCH_LIMIT_DEFAULT = 20;
    private static final int SEARCH_LIMIT_MAX = 50;

    private final UserService userService;
    private final ProjectApplicationServiceImpl projectApplicationService;
    private final TeamApplicationServiceImpl teamApplicationService;
    private final TeacherApplicationServiceImpl teacherApplicationService;

    public UserRosterController(UserService userService,
                                ProjectApplicationServiceImpl projectApplicationService,
                                TeamApplicationServiceImpl teamApplicationService,
                                TeacherApplicationServiceImpl teacherApplicationService) {
        this.userService = userService;
        this.projectApplicationService = projectApplicationService;
        this.teamApplicationService = teamApplicationService;
        this.teacherApplicationService = teacherApplicationService;
    }

    /** 按姓名 / 用户名 / 学号搜索用户。 */
    @GetMapping("/search")
    @ResponseBody
    public ResponseEntity<Result<?>> search(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Long teamId,
                                            @RequestParam(required = false) Integer limit,
                                            HttpSession session) {
        User me = ControllerUtils.requireUser(session);
        if (me == null) {
            return Result.unauthorized().toResponseEntity();
        }
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return Result.ok(Collections.emptyList()).toResponseEntity();
        }
        int capped = Math.min(Math.max(limit == null ? SEARCH_LIMIT_DEFAULT : limit, 1), SEARCH_LIMIT_MAX);

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
            .and(w -> w.like(User::getRealName, kw)
                .or().like(User::getUsername, kw)
                .or().apply("CAST(student_id AS CHAR) LIKE {0}", kw + "%"))
            .ne(User::getId, me.getId())
            // 管理员不参与组队，不进搜索结果
            .ne(User::getRole, "ADMIN")
            .orderByAsc(User::getId)
            .last("LIMIT " + capped);
        List<User> users = userService.list(wrapper);
        return Result.ok(buildRows(users, teamId)).toResponseEntity();
    }

    /**
     * 师生通讯录：{@code side=students} 取「我的学生」，{@code side=mentors} 取「我的导师」。
     * 关系来源是既有的 project_application（status = APPROVED）。
     */
    @GetMapping("/roster")
    @ResponseBody
    public ResponseEntity<Result<?>> roster(@RequestParam(defaultValue = "students") String side,
                                            @RequestParam(required = false) Long teamId,
                                            HttpSession session) {
        User me = ControllerUtils.requireUser(session);
        if (me == null) {
            return Result.unauthorized().toResponseEntity();
        }
        boolean wantStudents = !"mentors".equalsIgnoreCase(side == null ? "" : side.trim());

        LambdaQueryWrapper<ProjectApplication> wrapper = new LambdaQueryWrapper<ProjectApplication>()
            .eq(ProjectApplication::getStatus, "APPROVED");
        if (wantStudents) {
            wrapper.eq(ProjectApplication::getTeacherId, me.getId());
        } else {
            wrapper.eq(ProjectApplication::getUserId, me.getId());
        }
        List<ProjectApplication> links = projectApplicationService.list(wrapper);
        if (links.isEmpty()) {
            return Result.ok(Collections.emptyList()).toResponseEntity();
        }

        Set<Long> ids = links.stream()
            .map(link -> wantStudents ? link.getUserId() : link.getTeacherId())
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList()).toResponseEntity();
        }
        Map<Long, User> userMap = userService.listByIds(ids).stream()
            .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        // 保持「先建立关系的人排在前面」
        List<User> ordered = ids.stream().map(userMap::get).filter(Objects::nonNull).collect(Collectors.toList());
        return Result.ok(buildRows(ordered, teamId)).toResponseEntity();
    }

    /**
     * 组装返回行，并针对 teamId 批量标注 joined / invited。
     * 放在这里一次算完，前端就不必为每个结果单独查一次，也能直接置灰。
     */
    private List<Map<String, Object>> buildRows(List<User> users, Long teamId) {
        if (users == null || users.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, String> statusByUser = new HashMap<>();
        if (teamId != null) {
            Set<Long> ids = users.stream().map(User::getId).collect(Collectors.toSet());
            for (TeamApplication row : teamApplicationService.list(new LambdaQueryWrapper<TeamApplication>()
                .eq(TeamApplication::getTeamId, teamId)
                .in(TeamApplication::getUserId, ids))) {
                statusByUser.put(row.getUserId(), row.getStatus());
            }
        }
        // 导师卡片要显示职称与研究方向，这两项在 teacher_application 上，按需批量补齐
        Map<Long, TeacherApplication> teacherProfiles = loadTeacherProfiles(users);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (User user : users) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", user.getId());
            row.put("realName", displayName(user));
            row.put("avatar", user.getAvatar());
            row.put("major", user.getMajor());
            row.put("grade", user.getGrade());
            row.put("role", user.getRole());
            TeacherApplication profile = teacherProfiles.get(user.getId());
            if (profile != null) {
                row.put("professionalTitle", profile.getProfessionalTitle());
                row.put("researchDirection", profile.getResearchDirection());
            }
            String status = statusByUser.get(user.getId());
            row.put("joined", TeamMembershipService.STATUS_APPROVED.equals(status));
            row.put("invited", TeamMembershipService.STATUS_PENDING.equals(status));
            rows.add(row);
        }
        return rows;
    }

    /** 只查教师角色的档案，学生不必多这一跳 */
    private Map<Long, TeacherApplication> loadTeacherProfiles(List<User> users) {
        Set<Long> teacherIds = users.stream()
            .filter(u -> "TEACHER".equals(u.getRole()))
            .map(User::getId)
            .collect(Collectors.toSet());
        if (teacherIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return teacherApplicationService.list(new LambdaQueryWrapper<TeacherApplication>()
                .in(TeacherApplication::getUserId, teacherIds)
                .eq(TeacherApplication::getStatus, "APPROVED"))
            .stream()
            .collect(Collectors.toMap(TeacherApplication::getUserId, t -> t, (a, b) -> a));
    }

    private static String displayName(User user) {
        if (user.getRealName() != null && !user.getRealName().trim().isEmpty()) {
            return user.getRealName().trim();
        }
        return user.getUsername() == null ? "未命名用户" : user.getUsername();
    }
}
