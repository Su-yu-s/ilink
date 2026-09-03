package cn.ilink.service;

import cn.ilink.entity.PasswordResetToken;
import cn.ilink.entity.User;
import cn.ilink.mapper.PasswordResetTokenMapper;
import cn.ilink.mapper.UserMapper;
import cn.ilink.security.SecureTokenSupport;
import cn.ilink.util.PasswordPolicy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
public class PasswordResetService {
    private static final long TOKEN_LIFETIME_MS = 30L * 60L * 1000L;

    private final PasswordResetTokenMapper tokenMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetMailService mailService;
    private final RememberMeService rememberMeService;

    public PasswordResetService(PasswordResetTokenMapper tokenMapper, UserMapper userMapper,
                                PasswordEncoder passwordEncoder, PasswordResetMailService mailService,
                                RememberMeService rememberMeService) {
        this.tokenMapper = tokenMapper;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.rememberMeService = rememberMeService;
    }

    @Transactional
    public void requestReset(String account, String requestIp) {
        User user = findAccount(account);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        tokenMapper.delete(new LambdaQueryWrapper<PasswordResetToken>()
            .eq(PasswordResetToken::getUserId, user.getId()));
        String rawToken = SecureTokenSupport.randomToken(32);
        Date now = new Date();
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(user.getId());
        token.setTokenHash(SecureTokenSupport.hash(rawToken));
        token.setExpiresAt(new Date(now.getTime() + TOKEN_LIFETIME_MS));
        token.setRequestIp(trimToLength(requestIp, 64));
        token.setCreatedAt(now);
        tokenMapper.insert(token);
        mailService.sendResetLink(user, rawToken);
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("重置链接无效或已过期");
        }
        if (!PasswordPolicy.isValid(newPassword)) {
            throw new IllegalArgumentException(PasswordPolicy.message());
        }
        PasswordResetToken token = tokenMapper.selectByHashForUpdate(
            SecureTokenSupport.hash(rawToken.trim()));
        Date now = new Date();
        if (token == null || token.getUsedAt() != null || token.getExpiresAt() == null
                || !token.getExpiresAt().after(now)) {
            throw new IllegalArgumentException("重置链接无效或已过期");
        }
        User user = userMapper.selectById(token.getUserId());
        if (user == null) {
            throw new IllegalArgumentException("重置链接无效或已过期");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        if (userMapper.updateById(user) != 1) {
            throw new IllegalStateException("密码更新失败");
        }
        token.setUsedAt(now);
        tokenMapper.updateById(token);
        rememberMeService.revokeAllForUser(user.getId());
    }

    @Scheduled(cron = "0 23 3 * * *")
    public void cleanupExpiredTokens() {
        tokenMapper.delete(new LambdaUpdateWrapper<PasswordResetToken>()
            .lt(PasswordResetToken::getExpiresAt, new Date()));
    }

    private User findAccount(String account) {
        if (account == null || account.isBlank()) return null;
        String value = account.trim();
        if (value.contains("@")) return userMapper.findByEmail(value);
        if (value.matches("^1[3-9]\\d{9}$")) return userMapper.findByPhoneNumber(value);
        if (value.matches("^\\d{5,15}$")) return userMapper.findByStudentId(value);
        return userMapper.findByUsername(value);
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
