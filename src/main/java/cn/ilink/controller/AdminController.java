package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.CommunityPostFavorite;
import cn.ilink.entity.CommunityPostLike;
import cn.ilink.entity.Notification;
import cn.ilink.entity.User;
import cn.ilink.mapper.ChatMessageMapper;
import cn.ilink.mapper.CommunityCommentMapper;
import cn.ilink.mapper.CommunityPostFavoriteMapper;
import cn.ilink.mapper.CommunityPostLikeMapper;
import cn.ilink.mapper.NotificationMapper;
import cn.ilink.service.AssetService;
import cn.ilink.service.CommunityPostService;
import cn.ilink.service.TeacherApplicationService;
import cn.ilink.service.TeamApplicationService;
import cn.ilink.service.TeamDemandService;
import cn.ilink.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/api/admin")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final Set<String> ALLOWED_ROLES = Set.of("STUDENT", "TEACHER", "ADMIN");

    @Autowired
    private UserService userService;

    @Autowired
    private TeamDemandService teamDemandService;

    @Autowired
    private TeacherApplicationService teacherApplicationService;

    @Autowired
    private AssetService assetService;

    @Autowired
    private CommunityPostService communityPostService;

    @Autowired
    private TeamApplicationService teamApplicationService;

    @Autowired
    private CommunityPostLikeMapper communityPostLikeMapper;

    @Autowired
    private CommunityPostFavoriteMapper communityPostFavoriteMapper;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private CommunityCommentMapper communityCommentMapper;

    @GetMapping("/dashboard")
    @ResponseBody
    public ResponseEntity<Result<?>> getDashboardData(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return ResponseEntity.ok(Result.forbidden());
        }

        try {
            // 获取统计数据
            long userCount = userService.count();
            long teamCount = teamDemandService.count();
            long teacherCount = teacherApplicationService.count();
            long assetCount = assetService.count();
            long postCount = communityPostService.count();

            Map<String, Object> data = new HashMap<>();
            data.put("userCount", userCount);
            data.put("teamCount", teamCount);
            data.put("teacherCount", teacherCount);
            data.put("assetCount", assetCount);
            data.put("postCount", postCount);

            return ResponseEntity.ok(Result.ok("获取成功", data));
        } catch (Exception e) {
            log.error("获取仪表盘数据失败", e);
            return ResponseEntity.ok(Result.fail(500, "获取数据失败，请稍后重试"));
        }
    }

    @GetMapping("/users")
    @ResponseBody
    public ResponseEntity<Result<?>> getUserList(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return ResponseEntity.ok(Result.forbidden());
        }

        try {
            List<User> users = userService.list();
            return ResponseEntity.ok(Result.ok("获取成功", users));
        } catch (Exception e) {
            log.error("获取用户列表失败", e);
            return ResponseEntity.ok(Result.fail(500, "获取用户列表失败，请稍后重试"));
        }
    }

    @GetMapping("/teams")
    @ResponseBody
    public ResponseEntity<Result<?>> getTeamList(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return ResponseEntity.ok(Result.forbidden());
        }

        try {
            List<cn.ilink.entity.TeamDemand> teams = teamDemandService.list();
            return ResponseEntity.ok(Result.ok("获取成功", teams));
        } catch (Exception e) {
            log.error("获取团队列表失败", e);
            return ResponseEntity.ok(Result.fail(500, "获取团队列表失败，请稍后重试"));
        }
    }

    @GetMapping("/teachers")
    @ResponseBody
    public ResponseEntity<Result<?>> getTeacherList(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return ResponseEntity.ok(Result.forbidden());
        }

        try {
            List<cn.ilink.entity.TeacherApplication> teachers = teacherApplicationService.list();
            return ResponseEntity.ok(Result.ok("获取成功", teachers));
        } catch (Exception e) {
            log.error("获取导师列表失败", e);
            return ResponseEntity.ok(Result.fail(500, "获取导师列表失败，请稍后重试"));
        }
    }

    @GetMapping("/assets")
    @ResponseBody
    public ResponseEntity<Result<?>> getAssetList(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return ResponseEntity.ok(Result.forbidden());
        }

        try {
            List<cn.ilink.entity.Asset> assets = assetService.list();
            return ResponseEntity.ok(Result.ok("获取成功", assets));
        } catch (Exception e) {
            log.error("获取成果列表失败", e);
            return ResponseEntity.ok(Result.fail(500, "获取成果列表失败，请稍后重试"));
        }
    }

    @GetMapping("/community-posts")
    @ResponseBody
    public ResponseEntity<Result<?>> getCommunityPostList(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return ResponseEntity.ok(Result.forbidden());
        }

        try {
            List<CommunityPost> posts = communityPostService.list(
                new LambdaQueryWrapper<CommunityPost>().orderByDesc(CommunityPost::getCreatedAt)
            );
            return ResponseEntity.ok(Result.ok("获取成功", posts));
        } catch (Exception e) {
            log.error("获取社区帖子失败", e);
            return ResponseEntity.ok(Result.fail(500, "获取社区帖子失败，请稍后重试"));
        }
    }

    @DeleteMapping("/user/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteUser(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return ResponseEntity.ok(Result.forbidden());
        }

        try {
            // C-05: 级联清理关联数据，避免孤儿记录
            // 1. 组队申请
            teamApplicationService.remove(new LambdaQueryWrapper<cn.ilink.entity.TeamApplication>()
                    .eq(cn.ilink.entity.TeamApplication::getUserId, id));
            // 2. 社区帖子点赞/收藏
            communityPostLikeMapper.delete(new LambdaQueryWrapper<CommunityPostLike>()
                    .eq(CommunityPostLike::getUserId, id));
            communityPostFavoriteMapper.delete(new LambdaQueryWrapper<CommunityPostFavorite>()
                    .eq(CommunityPostFavorite::getUserId, id));
            // 3. 通知
            notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                    .eq(Notification::getUserId, id));
            // 4. 聊天消息
            chatMessageMapper.delete(new LambdaQueryWrapper<cn.ilink.entity.ChatMessage>()
                    .eq(cn.ilink.entity.ChatMessage::getSenderId, id));
            // 5. 社区帖子（含评论）
            List<CommunityPost> posts = communityPostService.list(
                new LambdaQueryWrapper<CommunityPost>().eq(CommunityPost::getAuthorId, id));
            for (CommunityPost p : posts) {
                communityPostService.removeById(p.getId());
            }
            // 6. 社区评论（所有用户的评论也一并清理）
            communityCommentMapper.delete(new LambdaQueryWrapper<cn.ilink.entity.CommunityComment>()
                    .eq(cn.ilink.entity.CommunityComment::getUserId, id));

            boolean success = userService.removeById(id);
            if (success) {
                return ResponseEntity.ok(Result.ok("删除成功", null));
            } else {
                return ResponseEntity.ok(Result.notFound("用户不存在"));
            }
        } catch (Exception e) {
            log.error("删除用户失败", e);
            return ResponseEntity.ok(Result.fail(500, "删除用户失败，请稍后重试"));
        }
    }

    @PutMapping("/user/{id}/role")
    @ResponseBody
    public ResponseEntity<Result<?>> updateUserRole(
        @PathVariable Long id,
        @RequestBody Map<String, Object> payload,
        HttpSession session
    ) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return ResponseEntity.ok(Result.forbidden());
        }

        try {
            User target = userService.getById(id);
            if (target == null) {
                return ResponseEntity.ok(Result.notFound("用户不存在"));
            }

            String role = payload == null || payload.get("role") == null
                ? ""
                : String.valueOf(payload.get("role")).trim().toUpperCase();
            if (!ALLOWED_ROLES.contains(role)) {
                return ResponseEntity.ok(Result.badRequest("角色非法，仅支持 STUDENT / TEACHER / ADMIN"));
            }

            User current = ControllerUtils.requireUser(session);
            // 防止管理员把自己降级导致后台失控
            if (current != null && current.getId() != null && current.getId().equals(id) && !"ADMIN".equals(role)) {
                return ResponseEntity.ok(Result.badRequest("不能修改当前登录管理员自己的身份"));
            }

            target.setRole(role);
            boolean success = userService.updateById(target);
            if (!success) {
                return ResponseEntity.ok(Result.fail(500, "更新失败"));
            }

            return ResponseEntity.ok(Result.ok("身份更新成功", Map.of("id", target.getId(), "role", target.getRole())));
        } catch (Exception e) {
            log.error("更新身份失败", e);
            return ResponseEntity.ok(Result.fail(500, "更新身份失败，请稍后重试"));
        }
    }

    @PutMapping("/user/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> updateUser(
        @PathVariable Long id,
        @RequestBody Map<String, Object> payload,
        HttpSession session
    ) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return ResponseEntity.ok(Result.forbidden());
        }

        try {
            User target = userService.getById(id);
            if (target == null) {
                return ResponseEntity.ok(Result.notFound("用户不存在"));
            }

            String username = str(payload.get("username"));
            String role = str(payload.get("role")).toUpperCase();
            if (username.isEmpty()) {
                return ResponseEntity.ok(Result.badRequest("用户名不能为空"));
            }
            if (!ALLOWED_ROLES.contains(role)) {
                return ResponseEntity.ok(Result.badRequest("角色非法，仅支持 STUDENT / TEACHER / ADMIN"));
            }

            User current = ControllerUtils.requireUser(session);
            if (current != null && current.getId() != null && current.getId().equals(id) && !"ADMIN".equals(role)) {
                return ResponseEntity.ok(Result.badRequest("不能修改当前登录管理员自己的身份"));
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
                target.setAvatar(emptyToNull(str(payload.get("avatar"))));
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

            boolean success = userService.updateById(target);
            if (!success) {
                return ResponseEntity.ok(Result.fail(500, "更新失败"));
            }

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

            return ResponseEntity.ok(Result.ok("用户信息更新成功", target));
        } catch (NumberFormatException e) {
            return ResponseEntity.ok(Result.badRequest("学号/工号必须是数字"));
        } catch (Exception e) {
            log.error("更新用户失败", e);
            return ResponseEntity.ok(Result.fail(500, "更新用户失败，请稍后重试"));
        }
    }

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    @DeleteMapping("/team/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteTeam(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return ResponseEntity.ok(Result.forbidden());
        }

        try {
            boolean success = teamDemandService.removeById(id);
            if (success) {
                return ResponseEntity.ok(Result.ok("删除成功", null));
            } else {
                return ResponseEntity.ok(Result.notFound("团队不存在"));
            }
        } catch (Exception e) {
            log.error("删除团队失败", e);
            return ResponseEntity.ok(Result.fail(500, "删除团队失败，请稍后重试"));
        }
    }

    @PutMapping("/teacher/{id}/approve")
    @ResponseBody
    public ResponseEntity<Result<?>> approveTeacher(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return ResponseEntity.ok(Result.forbidden());
        }

        try {
            cn.ilink.entity.TeacherApplication teacher = teacherApplicationService.getById(id);
            if (teacher != null) {
                teacher.setStatus("APPROVED");
                boolean success = teacherApplicationService.updateById(teacher);
                if (success) {
                    return ResponseEntity.ok(Result.ok("审批通过", null));
                } else {
                    return ResponseEntity.ok(Result.fail(500, "审批失败"));
                }
            } else {
                return ResponseEntity.ok(Result.notFound("导师申请不存在"));
            }
        } catch (Exception e) {
            log.error("审批失败", e);
            return ResponseEntity.ok(Result.fail(500, "审批失败，请稍后重试"));
        }
    }

    @DeleteMapping("/teacher/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteTeacher(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return ResponseEntity.ok(Result.forbidden());
        }

        try {
            boolean success = teacherApplicationService.removeById(id);
            if (success) {
                return ResponseEntity.ok(Result.ok("删除成功", null));
            } else {
                return ResponseEntity.ok(Result.notFound("导师申请不存在"));
            }
        } catch (Exception e) {
            log.error("删除导师申请失败", e);
            return ResponseEntity.ok(Result.fail(500, "删除导师申请失败，请稍后重试"));
        }
    }

    @DeleteMapping("/asset/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteAsset(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return ResponseEntity.ok(Result.forbidden());
        }

        try {
            boolean success = assetService.removeById(id);
            if (success) {
                return ResponseEntity.ok(Result.ok("删除成功", null));
            } else {
                return ResponseEntity.ok(Result.notFound("成果不存在"));
            }
        } catch (Exception e) {
            log.error("删除成果失败", e);
            return ResponseEntity.ok(Result.fail(500, "删除成果失败，请稍后重试"));
        }
    }

    @DeleteMapping("/community-post/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteCommunityPost(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return ResponseEntity.ok(Result.forbidden());
        }

        try {
            boolean success = communityPostService.removeById(id);
            if (success) {
                return ResponseEntity.ok(Result.ok("删除成功", null));
            } else {
                return ResponseEntity.ok(Result.notFound("帖子不存在"));
            }
        } catch (Exception e) {
            log.error("删除帖子失败", e);
            return ResponseEntity.ok(Result.fail(500, "删除帖子失败，请稍后重试"));
        }
    }
}
