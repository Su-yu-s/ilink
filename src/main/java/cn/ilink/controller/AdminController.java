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
import cn.ilink.service.impl.AssetServiceImpl;
import cn.ilink.service.impl.CommunityPostServiceImpl;
import cn.ilink.service.impl.TeacherApplicationServiceImpl;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import cn.ilink.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import java.util.stream.Collectors;

@Controller
@RequestMapping("/api/admin")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final Set<String> ALLOWED_ROLES = Set.of("STUDENT", "TEACHER", "ADMIN");

    @Autowired
    private UserService userService;

    @Autowired
    private TeamDemandServiceImpl teamDemandService;

    @Autowired
    private TeacherApplicationServiceImpl teacherApplicationService;

    @Autowired
    private AssetServiceImpl assetService;

    @Autowired
    private CommunityPostServiceImpl communityPostService;

    @Autowired
    private TeamApplicationServiceImpl teamApplicationService;

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
            return Result.forbidden().toResponseEntity();
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
            int safeSize = Math.min(Math.max(size, 1), 100);
            int safePage = Math.max(page, 1);
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
            int safeSize = Math.min(Math.max(size, 1), 100);
            int safePage = Math.max(page, 1);
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
            int safeSize = Math.min(Math.max(size, 1), 100);
            int safePage = Math.max(page, 1);
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
            int safeSize = Math.min(Math.max(size, 1), 100);
            int safePage = Math.max(page, 1);
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
            int safeSize = Math.min(Math.max(size, 1), 100);
            int safePage = Math.max(page, 1);
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
    public ResponseEntity<Result<?>> deleteUser(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            // C-05: 级联清理关联数据，避免孤儿记录
            // 1. 组队申请
            teamApplicationService.remove(new LambdaQueryWrapper<cn.ilink.entity.TeamApplication>()
                    .eq(cn.ilink.entity.TeamApplication::getUserId, id));
            // 2. 社区帖子点赞/收藏（先收集受影响帖子，删后回写计数）
            List<CommunityPostLike> likes = communityPostLikeMapper.selectList(
                new LambdaQueryWrapper<CommunityPostLike>().eq(CommunityPostLike::getUserId, id));
            Set<Long> affectedPostIds = likes.stream().map(CommunityPostLike::getPostId).collect(Collectors.toSet());
            communityPostLikeMapper.delete(new LambdaQueryWrapper<CommunityPostLike>()
                    .eq(CommunityPostLike::getUserId, id));

            List<CommunityPostFavorite> favs = communityPostFavoriteMapper.selectList(
                new LambdaQueryWrapper<CommunityPostFavorite>().eq(CommunityPostFavorite::getUserId, id));
            favs.forEach(f -> affectedPostIds.add(f.getPostId()));
            communityPostFavoriteMapper.delete(new LambdaQueryWrapper<CommunityPostFavorite>()
                    .eq(CommunityPostFavorite::getUserId, id));

            // 回写帖子 like_count / favorite_count
            for (Long postId : affectedPostIds) {
                Long likeCount = communityPostLikeMapper.selectCount(
                    new LambdaQueryWrapper<CommunityPostLike>().eq(CommunityPostLike::getPostId, postId));
                Long favCount = communityPostFavoriteMapper.selectCount(
                    new LambdaQueryWrapper<CommunityPostFavorite>().eq(CommunityPostFavorite::getPostId, postId));
                communityPostService.update(new LambdaUpdateWrapper<CommunityPost>()
                    .set(CommunityPost::getLikeCount, likeCount != null ? likeCount.intValue() : 0)
                    .set(CommunityPost::getFavoriteCount, favCount != null ? favCount.intValue() : 0)
                    .eq(CommunityPost::getId, postId));
            }
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
                return Result.ok("删除成功", null).toResponseEntity();
            } else {
                return Result.notFound("用户不存在").toResponseEntity();
            }
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
        HttpSession session
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

            target.setRole(role);
            boolean success = userService.updateById(target);
            if (!success) {
                return Result.fail(500, "更新失败").toResponseEntity();
            }

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
        HttpSession session
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
                return Result.fail(500, "更新失败").toResponseEntity();
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

    @DeleteMapping("/team/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteTeam(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            boolean success = teamDemandService.removeById(id);
            if (success) {
                return Result.ok("删除成功", null).toResponseEntity();
            } else {
                return Result.notFound("团队不存在").toResponseEntity();
            }
        } catch (Exception e) {
            log.error("删除团队失败", e);
            return Result.fail(500, "删除团队失败，请稍后重试").toResponseEntity();
        }
    }

    @PutMapping("/teacher/{id}/approve")
    @ResponseBody
    public ResponseEntity<Result<?>> approveTeacher(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            cn.ilink.entity.TeacherApplication teacher = teacherApplicationService.getById(id);
            if (teacher != null) {
                teacher.setStatus("APPROVED");
                boolean success = teacherApplicationService.updateById(teacher);
                if (success) {
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
    public ResponseEntity<Result<?>> deleteTeacher(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            boolean success = teacherApplicationService.removeById(id);
            if (success) {
                return Result.ok("删除成功", null).toResponseEntity();
            } else {
                return Result.notFound("导师申请不存在").toResponseEntity();
            }
        } catch (Exception e) {
            log.error("删除导师申请失败", e);
            return Result.fail(500, "删除导师申请失败，请稍后重试").toResponseEntity();
        }
    }

    @DeleteMapping("/asset/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteAsset(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            boolean success = assetService.removeById(id);
            if (success) {
                return Result.ok("删除成功", null).toResponseEntity();
            } else {
                return Result.notFound("成果不存在").toResponseEntity();
            }
        } catch (Exception e) {
            log.error("删除成果失败", e);
            return Result.fail(500, "删除成果失败，请稍后重试").toResponseEntity();
        }
    }

    @DeleteMapping("/community-post/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteCommunityPost(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (!ControllerUtils.isAdmin(user)) {
            return Result.forbidden().toResponseEntity();
        }

        try {
            boolean success = communityPostService.removeById(id);
            if (success) {
                return Result.ok("删除成功", null).toResponseEntity();
            } else {
                return Result.notFound("帖子不存在").toResponseEntity();
            }
        } catch (Exception e) {
            log.error("删除帖子失败", e);
            return Result.fail(500, "删除帖子失败，请稍后重试").toResponseEntity();
        }
    }
}
