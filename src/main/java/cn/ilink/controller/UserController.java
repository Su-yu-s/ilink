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
import cn.ilink.service.AccountDeletionService;
import cn.ilink.service.AccountDeletionBlockedException;
import cn.ilink.util.PasswordPolicy;
import cn.ilink.util.DisplayNameResolver;
import cn.ilink.util.I18nMessageResolver;
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

    @Autowired
    private AccountDeletionService accountDeletionService;

    @Autowired(required = false)
    private I18nMessageResolver i18nMessages;

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
            return Result.fail(404, msg("user.profile.notFound", "用户不存在")).toResponseEntity();
        }
        PublicUserProfileVO vo = new PublicUserProfileVO();
        vo.setId(u.getId());
        vo.setDisplayName(DisplayNameResolver.resolve(u));
        vo.setAvatarInitial(DisplayNameResolver.initial(u));
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
            return Result.badRequest(msg("user.profile.avatar.invalid", "头像地址仅支持站内上传文件"))
                .toResponseEntity();
        }
        // 内置头像只能选择本人角色分组，防止学生选教师头像（反之亦然）
        String requestedAvatar = profileRequest.getAvatar();
        if (requestedAvatar != null && BuiltinAvatarCatalog.isBuiltin(requestedAvatar.trim())
                && !BuiltinAvatarCatalog.belongsToRole(requestedAvatar.trim(), user.getRole())) {
            return Result.badRequest(msg("user.profile.avatar.roleMismatch", "只能选择与本人身份匹配的内置头像"))
                .toResponseEntity();
        }

        // 长度/取值校验前置：超长值直接撞列宽会变成 500，这里统一回 400
        // username 已不接受修改（见 UserServiceImpl.updateProfile），因此也不做长度校验
        String lengthError =
            ControllerUtils.checkMaxLength(profileRequest.getEmail(), ControllerUtils.EMAIL_MAX, "邮箱");
        if (lengthError == null) {
            lengthError = ControllerUtils.checkEmailFormat(profileRequest.getEmail());
        }
        if (lengthError == null) {
            lengthError = ControllerUtils.checkMaxLength(profileRequest.getRealName(), ControllerUtils.REAL_NAME_MAX, "姓名");
        }
        if (lengthError == null) {
            lengthError = ControllerUtils.checkMaxLength(profileRequest.getGrade(), ControllerUtils.GRADE_MAX, "年级");
        }
        if (lengthError == null) {
            lengthError = ControllerUtils.checkMaxLength(profileRequest.getMajor(), ControllerUtils.MAJOR_MAX, "专业");
        }
        if (lengthError == null) {
            lengthError = ControllerUtils.checkMaxLength(profileRequest.getSchool(), ControllerUtils.SCHOOL_MAX, "学校");
        }
        if (lengthError == null) {
            lengthError = ControllerUtils.checkMaxLength(profileRequest.getCollege(), ControllerUtils.COLLEGE_MAX, "学院");
        }
        if (lengthError == null) {
            lengthError = ControllerUtils.checkMaxLength(profileRequest.getBio(), ControllerUtils.BIO_MAX, "个人简介");
        }
        if (lengthError == null) {
            lengthError = ControllerUtils.checkGender(profileRequest.getGender());
        }
        if (lengthError != null) {
            return Result.badRequest(lengthError).toResponseEntity();
        }
        profileRequest.setGender(ControllerUtils.normalizeGender(profileRequest.getGender()));
        // 校验通过后再归一化邮箱（去空格 + 小写），保证唯一索引与登录查询口径一致
        if (profileRequest.getEmail() != null) {
            String normalizedEmail = ControllerUtils.normalizeEmail(profileRequest.getEmail());
            profileRequest.setEmail(normalizedEmail == null || normalizedEmail.isEmpty() ? null : normalizedEmail);
        }

        boolean success = userService.updateProfile(user.getId(), profileRequest);
        if (success) {
            // 更新session中的用户信息
            // username 是内部标识，不接受客户端修改，此处刻意不写回会话快照
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
            return Result.fail(500, msg("user.profile.updateFailed", "更新失败")).toResponseEntity();
        }
    }

    @PutMapping("/password")
    @ResponseBody
    public ResponseEntity<Result<?>> changePassword(@RequestBody @Valid ChangePasswordRequest req, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) return Result.unauthorized().toResponseEntity();
        if (!PasswordPolicy.isValid(req.getNewPassword())) {
            return Result.badRequest(msg("auth.forgot.validation.password", PasswordPolicy.message()))
                .toResponseEntity();
        }
        boolean ok = userService.changePassword(user.getId(), req.getOldPassword(), req.getNewPassword());
        if (!ok) return Result.badRequest(msg("user.password.oldIncorrect", "原密码错误")).toResponseEntity();
        rememberMeService.revokeAllForUser(user.getId());
        return Result.ok(msg("user.password.changed", "密码修改成功"), null).toResponseEntity();
    }

    /**
     * 自助注销账号。
     *
     * <p>校验顺序固定为：登录态 → 密码 → 确认文本 → 创建者阻断 → 执行删除。
     * 密码与确认文本都通过后才做创建者检查，是为了不让「能不能注销」这一步
     * 变成探测团队归属的接口。
     *
     * <p>删除完成后当前会话随之失效，前端应跳回首页。
     */
    @PostMapping("/delete-account")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteAccount(@RequestBody Map<String, Object> payload, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        String password = payload == null ? null : str(payload.get("password"));
        String confirmText = payload == null ? null : str(payload.get("confirmText"));
        if (password == null || password.isEmpty()) {
            return Result.badRequest(msg("account.delete.passwordRequired", "请输入当前密码")).toResponseEntity();
        }
        if (!userService.checkPassword(user.getId(), password)) {
            return Result.badRequest(msg("account.delete.passwordIncorrect", "密码错误")).toResponseEntity();
        }
        if (!"注销".equals(confirmText)) {
            return Result.badRequest(msg("account.delete.confirmMismatch", "请输入「注销」二字以确认"))
                .toResponseEntity();
        }
        List<String> blocking = accountDeletionService.findBlockingTeams(user.getId());
        if (!blocking.isEmpty()) {
            return Result.fail(409, msg("account.delete.blockedDetailed",
                "你仍是以下团队的创建者：{0}。请先转让或解散团队后再注销账号。",
                String.join("、", blocking))).toResponseEntity();
        }
        try {
            accountDeletionService.deleteAccount(user.getId());
        } catch (AccountDeletionBlockedException e) {
            return Result.fail(409, msg("account.delete.blockedRace",
                "你创建的团队仍有成员，暂时无法注销。请先转让或解散团队后再试。"))
                .toResponseEntity();
        } catch (Exception e) {
            return Result.fail(500, msg("account.delete.failed", "注销失败，请稍后重试")).toResponseEntity();
        }
        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // 会话可能已被 sessionRegistry.invalidateAll 失效
        }
        return Result.ok(msg("account.delete.success", "账号已注销"), null).toResponseEntity();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String msg(String key, String fallback, Object... arguments) {
        return i18nMessages == null ? fallback : i18nMessages.get(key, fallback, arguments);
    }
}
