package cn.ilink.service;

import cn.ilink.entity.PasswordResetToken;
import cn.ilink.entity.User;
import cn.ilink.mapper.PasswordResetTokenMapper;
import cn.ilink.mapper.UserMapper;
import cn.ilink.security.SecureTokenSupport;
import cn.ilink.util.PasswordPolicy;
import cn.ilink.util.I18nMessageResolver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;

@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final long TOKEN_LIFETIME_MS = 30L * 60L * 1000L;

    private final PasswordResetTokenMapper tokenMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetMailService mailService;
    private final RememberMeService rememberMeService;

    @Autowired(required = false)
    private I18nMessageResolver i18nMessages;

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
        if (user == null) {
            return;
        }
        String recipient = resolveRecipient(user);
        if (recipient == null) {
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
        // 修复 D-P1-5：Resend HTTPS / SMTP 是分钟级外呼，原先在事务内同步发送，
        // 外网抖动期间持续占用 Hikari 连接（池上限 20），并发找回密码即可拖垮全站。
        // 改为事务提交后再发信，发信失败仅记日志（token 已落库，可重新发起请求）。
        String tokenForMail = rawToken;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        mailService.sendResetLink(recipient, tokenForMail);
                    } catch (Exception e) {
                        log.warn("密码重置邮件发送失败（token 已生成），收件人：{}", recipient, e);
                    }
                }
            });
        } else {
            mailService.sendResetLink(recipient, rawToken);
        }
    }

    /**
     * 收件地址只取账号已绑定的邮箱。
     * <p>
     * 早期实现会在账号未绑定邮箱时把重置链接改发到「请求者自行填写」的邮箱，
     * 而 {@code /api/password-reset/confirm} 仅凭邮件里的 token 就能改密，
     * 于是任何人都能用他人的用户名请求重置、用自己的邮箱收链接，进而接管账号。
     * 因此这里不再接受任何调用方提供的地址：账号没绑定邮箱就不发信（由用户先补绑邮箱，
     * 或联系管理员处理），调用方拿不到任何可用于改密的凭证。
     */
    private String resolveRecipient(User user) {
        String bound = user.getEmail();
        if (bound == null || bound.isBlank()) {
            log.warn("账号 {} 未绑定邮箱，密码重置邮件未发送，请先补全邮箱或联系管理员", user.getUsername());
            return null;
        }
        return bound.trim();
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException(msg("password.reset.invalidToken", "重置链接无效或已过期"));
        }
        if (!PasswordPolicy.isValid(newPassword)) {
            throw new IllegalArgumentException(msg("auth.forgot.validation.password", PasswordPolicy.message()));
        }
        PasswordResetToken token = tokenMapper.selectByHashForUpdate(
            SecureTokenSupport.hash(rawToken.trim()));
        Date now = new Date();
        if (token == null || token.getUsedAt() != null || token.getExpiresAt() == null
                || !token.getExpiresAt().after(now)) {
            throw new IllegalArgumentException(msg("password.reset.invalidToken", "重置链接无效或已过期"));
        }
        User user = userMapper.selectById(token.getUserId());
        if (user == null) {
            throw new IllegalArgumentException(msg("password.reset.invalidToken", "重置链接无效或已过期"));
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        if (userMapper.updateById(user) != 1) {
            throw new IllegalStateException(msg("password.reset.updateFailed", "密码更新失败"));
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

    private String msg(String key, String fallback, Object... arguments) {
        return i18nMessages == null ? fallback : i18nMessages.get(key, fallback, arguments);
    }
}
