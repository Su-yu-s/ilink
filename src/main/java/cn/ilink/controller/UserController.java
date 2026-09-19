package cn.ilink.controller;

import cn.ilink.common.BuiltinAvatarCatalog;
import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.ChangePasswordRequest;
import cn.ilink.dto.ProfileRequest;
import cn.ilink.dto.PublicUserProfileVO;
import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.Asset;
import cn.ilink.entity.User;
import cn.ilink.mapper.CommunityPostMapper;
import cn.ilink.mapper.AssetMapper;
import cn.ilink.service.UserService;
import cn.ilink.service.RememberMeService;
import cn.ilink.util.PasswordPolicy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private RememberMeService rememberMeService;

    @Value("${file.access-url-prefix:/uploads/}")
    private String accessUrlPrefix;

    /**
     * 公开用户概览（组队/社区/导师等场景跳转主页，仅展示可公开字段）
     */
    @GetMapping("/public/{userId}")
    @ResponseBody
    public ResponseEntity<Result<?>> getPublicProfile(@PathVariable Long userId) {
        User u = userService.getById(userId);
        if (u == null) {
            return Result.fail(404, "用户不存在").toResponseEntity();
        }
        PublicUserProfileVO vo = new PublicUserProfileVO();
        vo.setId(u.getId());
        vo.setUsername(u.getUsername());
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
        vo.setPublishedAssets(loadPublishedAssets(u.getId()));
        return Result.ok(vo).toResponseEntity();
    }

    /**
     * 用户发布的公开成果（成果展示里的资产）。
     * 只回概要字段：公开主页不需要附件地址与下载统计。
     */
    private List<Map<String, Object>> loadPublishedAssets(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }
        Page<Asset> page = new Page<>(1, 20);
        Page<Asset> result = assetMapper.selectPage(page,
            new LambdaQueryWrapper<Asset>()
                .eq(Asset::getUserId, userId)
                .orderByDesc(Asset::getCreatedAt)
        );
        List<Map<String, Object>> out = new ArrayList<>();
        for (Asset a : result.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("title", a.getTitle());
            m.put("category", a.getCategory());
            m.put("coverUrl", a.getCoverUrl());
            m.put("viewCount", a.getViewCount());
            m.put("createdAt", a.getCreatedAt() == null ? new Date() : a.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> loadPublishedPosts(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }
        Page<CommunityPost> page = new Page<>(1, 20);
        Page<CommunityPost> result = communityPostMapper.selectPage(page,
            new LambdaQueryWrapper<CommunityPost>()
                .eq(CommunityPost::getAuthorId, userId)
                .orderByDesc(CommunityPost::getCreatedAt)
        );
        List<CommunityPost> rows = result.getRecords();
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
            return Result.ok(user).toResponseEntity();
        } else {
            return Result.unauthorized().toResponseEntity();
        }
    }

    /** 当前登录用户可选择的内置头像（仅返回本人角色分组） */
    @GetMapping("/builtin-avatars")
    @ResponseBody
    public ResponseEntity<Result<?>> builtinAvatars(HttpSession session) {
        User current = ControllerUtils.requireUser(session);
        if (current == null) {
            return Result.unauthorized().toResponseEntity();
        }
        return Result.ok(BuiltinAvatarCatalog.listForRole(current.getRole())).toResponseEntity();
    }

    @PostMapping("/profile")
    @ResponseBody
    public ResponseEntity<Result<?>> updateProfile(@RequestBody ProfileRequest profileRequest, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        if (profileRequest.getAvatar() != null
                && !ControllerUtils.isManagedUploadUrl(profileRequest.getAvatar(), accessUrlPrefix)) {
            return Result.badRequest("头像地址仅支持站内上传文件").toResponseEntity();
        }
        // 内置头像只能选择本人角色分组，防止学生选教师头像（反之亦然）
        String requestedAvatar = profileRequest.getAvatar();
        if (requestedAvatar != null && BuiltinAvatarCatalog.isBuiltin(requestedAvatar.trim())
                && !BuiltinAvatarCatalog.belongsToRole(requestedAvatar.trim(), user.getRole())) {
            return Result.badRequest("只能选择与本人身份匹配的内置头像").toResponseEntity();
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

            return Result.ok(user).toResponseEntity();
        } else {
            return Result.fail(500, "更新失败").toResponseEntity();
        }
    }

    @PutMapping("/password")
    @ResponseBody
    public ResponseEntity<Result<?>> changePassword(@RequestBody @Valid ChangePasswordRequest req, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        if (!PasswordPolicy.isValid(req.getNewPassword())) {
            return Result.badRequest(PasswordPolicy.message()).toResponseEntity();
        }
        boolean ok = userService.changePassword(user.getId(), req.getOldPassword(), req.getNewPassword());
        if (!ok) return Result.badRequest("原密码错误").toResponseEntity();
        rememberMeService.revokeAllForUser(user.getId());
        return Result.ok("密码修改成功", null).toResponseEntity();
    }
}
