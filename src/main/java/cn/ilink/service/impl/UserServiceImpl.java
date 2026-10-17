package cn.ilink.service.impl;

import cn.ilink.common.BuiltinAvatarCatalog;
import cn.ilink.entity.User;
import cn.ilink.mapper.UserMapper;
import cn.ilink.dto.LoginRequest;
import cn.ilink.dto.RegisterRequest;
import cn.ilink.dto.ProfileRequest;
import cn.ilink.entity.TeacherApplication;
import cn.ilink.service.impl.TeacherApplicationServiceImpl;
import cn.ilink.service.UserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Date;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TeacherApplicationServiceImpl teacherApplicationService;

    /**
     * username 随机字符集：剔除易混的 0 / 1 / i / l / o，共 31 个字符。
     * 8 位随机串的空间为 31^8 ≈ 8.5×10^11，远超校园规模下的碰撞概率。
     */
    private static final char[] USERNAME_ALPHABET =
        "23456789abcdefghjkmnpqrstuvwxyz".toCharArray();
    private static final int USERNAME_RANDOM_LENGTH = 8;
    private static final int USERNAME_MAX_ATTEMPTS = 5;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 生成内部用户名：{@code u} + 8 位随机字符。
     *
     * <p>username 只作内部标识与登录凭据载体，对用户不可见、不参与展示，
     * 因此不携带任何身份信息（历史实现用手机号或 {@code s}+学号，会把注册凭据泄露到公开接口）。
     * 连续冲突超过 {@value #USERNAME_MAX_ATTEMPTS} 次视为异常情况，返回 null 由调用方失败退出。
     */
    private String generateUsername() {
        for (int attempt = 0; attempt < USERNAME_MAX_ATTEMPTS; attempt++) {
            StringBuilder sb = new StringBuilder(USERNAME_RANDOM_LENGTH + 1);
            sb.append('u');
            for (int i = 0; i < USERNAME_RANDOM_LENGTH; i++) {
                sb.append(USERNAME_ALPHABET[secureRandom.nextInt(USERNAME_ALPHABET.length)]);
            }
            String candidate = sb.toString();
            if (userMapper.findByUsername(candidate) == null) {
                return candidate;
            }
            log.warn("用户名生成冲突，重试第 {} 次", attempt + 1);
        }
        return null;
    }

    private boolean matchesPassword(User user, String rawPassword) {
        if (user == null || rawPassword == null) {
            return false;
        }
        String stored = user.getPassword();
        if (stored == null) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, stored);
    }

    @Override
    @Transactional
    public boolean register(RegisterRequest registerRequest) {
        if (registerRequest.getPhoneNumber() != null && !registerRequest.getPhoneNumber().trim().isEmpty()) {
            User existingUserByPhone = userMapper.findByPhoneNumber(registerRequest.getPhoneNumber());
            if (existingUserByPhone != null) {
                log.debug("注册失败：手机号已存在");
                return false;
            }
        }

        if (registerRequest.getStudentId() != null) {
            User existingUserByStudentId = userMapper.findByStudentId(registerRequest.getStudentId().toString());
            if (existingUserByStudentId != null) {
                log.debug("注册失败：学号/工号已存在");
                return false;
            }
        }

        // username 一律随机生成：不再用手机号/学号推导，避免注册凭据经公开接口泄露。
        // 请求体里即便带了 username 也一律忽略。
        String username = generateUsername();
        if (username == null) {
            log.error("用户名生成失败：连续 {} 次冲突", USERNAME_MAX_ATTEMPTS);
            return false;
        }

        User user = new User();
        user.setUsername(username);
        user.setPhoneNumber(registerRequest.getPhoneNumber());
        user.setStudentId(registerRequest.getStudentId());
        user.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        user.setEmail(registerRequest.getEmail());
        String resolvedRole = registerRequest.getRole() != null ? registerRequest.getRole() : "STUDENT";
        user.setRole(resolvedRole);
        // 注册即按角色随机分配一个内置头像
        user.setAvatar(BuiltinAvatarCatalog.randomForRole(resolvedRole));
        // real_name 留空，由用户在个人资料页自行填写；显示名走 DisplayNameResolver 的角色占位。
        // 历史上这里填的是注册标识（手机号/学号），使同一个凭据经 realName 再次泄露到公开页面。
        user.setGender(registerRequest.getGender());

        user.setCreatedAt(new Date());
        if (!save(user)) {
            return false;
        }

        if ("TEACHER".equals(user.getRole())) {
            TeacherApplication mentorProfile = new TeacherApplication();
            mentorProfile.setUserId(user.getId());
            mentorProfile.setStatus("INCOMPLETE");
            mentorProfile.setCreatedAt(new Date());
            if (!teacherApplicationService.save(mentorProfile)) {
                throw new IllegalStateException("教师账号创建成功，但导师档案创建失败");
            }
        }
        return true;
    }

    @Override
    public User ensureAvatarAssigned(User user) {
        if (user == null) {
            return null;
        }
        // 已有头像（含历史随机分配与用户自定义上传）一律不动，保证只补一次
        if (user.getAvatar() != null && !user.getAvatar().isBlank()) {
            return user;
        }
        String avatar = BuiltinAvatarCatalog.randomForRole(user.getRole());
        if (avatar == null) {
            return user;
        }
        User patch = new User();
        patch.setId(user.getId());
        patch.setAvatar(avatar);
        if (userMapper.updateById(patch) > 0) {
            user.setAvatar(avatar);
        }
        return user;
    }

    @Override
    public User login(LoginRequest loginRequest) {
        User user = null;

        if (loginRequest.getPhoneNumber() != null && !loginRequest.getPhoneNumber().isEmpty()) {
            user = userMapper.findByPhoneNumber(loginRequest.getPhoneNumber());
        }

        if (user == null && loginRequest.getStudentId() != null && !loginRequest.getStudentId().isEmpty()) {
            user = userMapper.findByStudentId(loginRequest.getStudentId());
        }

        if (user == null && loginRequest.getIdentifier() != null && !loginRequest.getIdentifier().isEmpty()) {
            String id = loginRequest.getIdentifier().trim();
            if (id.contains("@")) {
                user = userMapper.findByEmail(id);
            }
            if (user == null) {
                user = userMapper.findByUsername(id);
            }
        }

        if (user != null && matchesPassword(user, loginRequest.getPassword())) {
            // 被禁用的账号即便密码正确也不放行。返回 user 由调用方判断状态，
            // 这样 AuthController 能区分「密码错误」和「账号已暂停」两种提示。
            return user;
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateProfile(Long userId, ProfileRequest profileRequest) {
        User user = getById(userId);
        if (user == null) {
            return false;
        }

        // username 为内部标识，不接受任何修改：它是随机生成的，且不用于展示。
        // 允许修改会重新打开「把 username 改成手机号」的泄露路径，因此直接忽略请求中的该字段。

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

        boolean updated = updateById(user);
        if (updated && "TEACHER".equals(user.getRole())) {
            synchronizeTeacherProfileStatus(user);
        }
        return updated;
    }

    private void synchronizeTeacherProfileStatus(User user) {
        TeacherApplication profile = teacherApplicationService.getOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TeacherApplication>()
                .eq(TeacherApplication::getUserId, user.getId()));
        if (profile == null || "REVOKED".equals(profile.getStatus()) || "PENDING".equals(profile.getStatus())) {
            return;
        }
        String desiredStatus = isTeacherProfileComplete(user, profile) ? "APPROVED" : "INCOMPLETE";
        if (!desiredStatus.equals(profile.getStatus())) {
            profile.setStatus(desiredStatus);
            if (!teacherApplicationService.updateById(profile)) {
                throw new IllegalStateException("导师档案状态同步失败");
            }
        }
    }

    private boolean isTeacherProfileComplete(User user, TeacherApplication profile) {
        return hasText(user.getRealName())
            && hasText(user.getSchool())
            && hasText(user.getMajor())
            && hasText(profile.getProfessionalTitle())
            && hasText(profile.getResearchDirection())
            && hasText(profile.getIntroduction());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @Override
    public boolean changePassword(Long userId, String oldPassword, String newPassword) {
        User user = getById(userId);
        if (user == null) return false;
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) return false;
        user.setPassword(passwordEncoder.encode(newPassword));
        updateById(user);
        return true;
    }

    @Override
    public boolean checkPassword(Long userId, String rawPassword) {
        if (userId == null) {
            return false;
        }
        return matchesPassword(getById(userId), rawPassword);
    }
}
