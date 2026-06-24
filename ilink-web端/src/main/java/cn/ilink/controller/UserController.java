package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.ChangePasswordRequest;
import cn.ilink.dto.ProfileRequest;
import cn.ilink.dto.PublicUserProfileVO;
import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.User;
import cn.ilink.mapper.CommunityPostMapper;
import cn.ilink.service.UserService;
import cn.ilink.util.PasswordPolicy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private CommunityPostMapper communityPostMapper;

    /**
     * 公开用户概览（组队/社区/导师等场景跳转主页，仅展示可公开字段）
     */
    @GetMapping("/public/{userId}")
    @ResponseBody
    public ResponseEntity<Result<?>> getPublicProfile(@PathVariable Long userId) {
        User u = userService.getById(userId);
        if (u == null) {
            return ResponseEntity.ok(Result.fail(404, "用户不存在"));
        }
        PublicUserProfileVO vo = new PublicUserProfileVO();
        vo.setId(u.getId());
        vo.setUsername(u.getUsername());
        vo.setRealName(u.getRealName());
        vo.setAvatar(u.getAvatar());
        vo.setRole(u.getRole());
        vo.setGrade(u.getGrade());
        vo.setMajor(u.getMajor());
        vo.setSchool(u.getSchool());
        vo.setCollege(u.getCollege());
        vo.setBio(u.getBio());
        vo.setCreatedAt(u.getCreatedAt());
        vo.setHonors(u.getHonors());
        vo.setPublishedPosts(loadPublishedPosts(u.getId()));
        return ResponseEntity.ok(Result.ok(vo));
    }

    private List<Map<String, Object>> loadPublishedPosts(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }
        List<CommunityPost> rows = communityPostMapper.selectList(
            new LambdaQueryWrapper<CommunityPost>()
                .eq(CommunityPost::getAuthorId, userId)
                .orderByDesc(CommunityPost::getCreatedAt)
                .last("LIMIT 20")
        );
        List<Map<String, Object>> out = new ArrayList<>();
        for (CommunityPost p : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("title", p.getTitle());
            m.put("category", p.getCategory());
            m.put("createdAt", p.getCreatedAt() == null ? new Date() : p.getCreatedAt());
            m.put("excerpt", excerpt(p.getContent(), 110));
            out.add(m);
        }
        return out;
    }

    private String excerpt(String html, int maxLen) {
        String text = html == null ? "" : html.replaceAll("<[^>]*>", " ");
        text = text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s{2,}", " ").trim();
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLen - 1)) + "…";
    }

    @GetMapping("/profile")
    @ResponseBody
    public ResponseEntity<Result<?>> getProfile(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user != null) {
            return ResponseEntity.ok(Result.ok(user));
        } else {
            return ResponseEntity.ok(Result.unauthorized());
        }
    }

    @PostMapping("/profile")
    @ResponseBody
    public ResponseEntity<Result<?>> updateProfile(@RequestBody ProfileRequest profileRequest, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }

        boolean success = userService.updateProfile(user.getId(), profileRequest);
        if (success) {
            // 更新session中的用户信息
            if (profileRequest.getUsername() != null) {
                user.setUsername(profileRequest.getUsername().trim());
            }
            if (profileRequest.getEmail() != null) {
                user.setEmail(profileRequest.getEmail());
            }
            if (profileRequest.getRealName() != null) {
                user.setRealName(profileRequest.getRealName());
            }
            if (profileRequest.getAvatar() != null) {
                user.setAvatar(profileRequest.getAvatar());
            }
            if (profileRequest.getGender() != null) {
                String g = profileRequest.getGender().trim();
                user.setGender(g.isEmpty() ? null : g);
            }
            if (profileRequest.getGrade() != null) {
                String v = profileRequest.getGrade().trim();
                user.setGrade(v.isEmpty() ? null : v);
            }
            if (profileRequest.getMajor() != null) {
                String v = profileRequest.getMajor().trim();
                user.setMajor(v.isEmpty() ? null : v);
            }
            if (profileRequest.getSchool() != null) {
                String v = profileRequest.getSchool().trim();
                user.setSchool(v.isEmpty() ? null : v);
            }
            if (profileRequest.getCollege() != null) {
                String v = profileRequest.getCollege().trim();
                user.setCollege(v.isEmpty() ? null : v);
            }
            if (profileRequest.getBio() != null) {
                String v = profileRequest.getBio().trim();
                user.setBio(v.isEmpty() ? null : v);
            }
            if (profileRequest.getHonors() != null) {
                user.setHonors(profileRequest.getHonors());
            }
            session.setAttribute("user", user);

            return ResponseEntity.ok(Result.ok(user));
        } else {
            return ResponseEntity.ok(Result.fail(500, "更新失败"));
        }
    }

    @PutMapping("/password")
    @ResponseBody
    public ResponseEntity<Result<Void>> changePassword(@RequestBody @Valid ChangePasswordRequest req, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return ResponseEntity.ok(Result.unauthorized());
        if (!PasswordPolicy.isValid(req.getNewPassword())) {
            return ResponseEntity.ok(Result.badRequest(PasswordPolicy.message()));
        }
        boolean ok = userService.changePassword(user.getId(), req.getOldPassword(), req.getNewPassword());
        if (!ok) return ResponseEntity.ok(Result.badRequest("原密码错误"));
        return ResponseEntity.ok(Result.ok("密码修改成功", null));
    }
}
