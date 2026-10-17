package cn.ilink.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录/注册失败次数限制：同一 key（IP 或账号）连续失败 5 次后锁定 15 分钟。
 * 注册限流：同一 IP 每分钟最多 3 次。
 * 意见反馈限流：同一 IP 每 10 分钟最多 3 条。
 */
@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 15;
    private static final int REGISTER_MAX_PER_MINUTE = 3;
    private static final int PASSWORD_RESET_MAX_PER_15_MINUTES = 3;
    private static final int FEEDBACK_MAX_PER_10_MINUTES = 3;
    /** 同一邮箱 24 小时内最多索取多少个注册验证码 */
    private static final int EMAIL_CODE_MAX_PER_DAY = 10;
    /** 同一 IP 每小时最多索取多少个验证码（防止换邮箱刷） */
    private static final int EMAIL_CODE_MAX_PER_HOUR = 20;

    private final Cache<String, AtomicInteger> attempts = Caffeine.newBuilder()
        .expireAfterWrite(LOCK_MINUTES, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build();

    /** 注册限流：同一 IP 每分钟最多 3 次 */
    private final Cache<String, AtomicInteger> registerAttempts = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build();

    private final Cache<String, AtomicInteger> passwordResetAttempts = Caffeine.newBuilder()
        .expireAfterWrite(15, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build();

    /** 意见反馈限流：同一 IP 每 10 分钟最多 3 条 */
    private final Cache<String, AtomicInteger> feedbackAttempts = Caffeine.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build();

    /** 注册验证码冷却：60 秒后自动过期，key 还在就说明仍在冷却期 */
    private final Cache<String, Boolean> emailCodeCooldown = Caffeine.newBuilder()
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .maximumSize(10_000)
        .build();

    /** 注册验证码邮箱配额：24 小时窗口 */
    private final Cache<String, AtomicInteger> emailCodeQuota = Caffeine.newBuilder()
        .expireAfterWrite(24, TimeUnit.HOURS)
        .maximumSize(10_000)
        .build();

    /** 注册验证码 IP 限流：1 小时窗口 */
    private final Cache<String, AtomicInteger> emailCodeIp = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .maximumSize(10_000)
        .build();

    // ===== 登录限流 =====

    public void loginFailed(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        AtomicInteger count = attempts.asMap().computeIfAbsent(key, k -> new AtomicInteger(0));
        synchronized (count) {
            count.incrementAndGet();
        }
    }

    public void loginSucceeded(String key) {
        if (key != null && !key.isBlank()) {
            attempts.invalidate(key);
        }
    }

    public boolean isBlocked(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        AtomicInteger count = attempts.getIfPresent(key);
        return count != null && count.get() >= MAX_ATTEMPTS;
    }

    public int remainingLockMinutes() {
        return LOCK_MINUTES;
    }

    // ===== 注册限流 =====

    /**
     * 尝试注册，返回 true 表示允许，false 表示频率过高。
     */
    public boolean tryRegister(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return true; // 无法识别IP时放行
        }
        String key = "register:" + clientIp;
        AtomicInteger count = registerAttempts.asMap().computeIfAbsent(key, k -> new AtomicInteger(0));
        synchronized (count) {
            if (count.get() >= REGISTER_MAX_PER_MINUTE) {
                return false;
            }
            count.incrementAndGet();
            return true;
        }
    }

    /** 密码重置邮件限流：同一 IP 每 15 分钟最多 3 次。 */
    public boolean tryPasswordReset(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return true;
        AtomicInteger count = passwordResetAttempts.asMap().computeIfAbsent(
            "password-reset:" + clientIp, key -> new AtomicInteger(0));
        synchronized (count) {
            if (count.get() >= PASSWORD_RESET_MAX_PER_15_MINUTES) return false;
            count.incrementAndGet();
            return true;
        }
    }

    /** 意见反馈限流：同一 IP 每 10 分钟最多 3 条，避免匿名入口被用来刷爆开发者微信。 */
    public boolean tryFeedback(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return true;
        AtomicInteger count = feedbackAttempts.asMap().computeIfAbsent(
            "feedback:" + clientIp, key -> new AtomicInteger(0));
        synchronized (count) {
            if (count.get() >= FEEDBACK_MAX_PER_10_MINUTES) return false;
            count.incrementAndGet();
            return true;
        }
    }

    /** 注册验证码冷却：同一邮箱 60 秒内不可重发（与前端倒计时一致）。 */
    public boolean tryEmailCodeCooldown(String email) {
        if (email == null || email.isBlank()) return true;
        String key = "email-code:" + email;
        if (emailCodeCooldown.getIfPresent(key) != null) {
            return false;
        }
        emailCodeCooldown.put(key, Boolean.TRUE);
        return true;
    }

    /** 注册验证码配额：同一邮箱 24 小时上限，避免被当成骚扰工具。 */
    public boolean tryEmailCodeQuota(String email) {
        if (email == null || email.isBlank()) return true;
        AtomicInteger count = emailCodeQuota.asMap().computeIfAbsent(
            "email-code-quota:" + email, key -> new AtomicInteger(0));
        synchronized (count) {
            if (count.get() >= EMAIL_CODE_MAX_PER_DAY) return false;
            count.incrementAndGet();
            return true;
        }
    }

    /** 注册验证码按 IP 限流：换邮箱也刷不动。 */
    public boolean tryEmailCodeByIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return true;
        AtomicInteger count = emailCodeIp.asMap().computeIfAbsent(
            "email-code-ip:" + clientIp, key -> new AtomicInteger(0));
        synchronized (count) {
            if (count.get() >= EMAIL_CODE_MAX_PER_HOUR) return false;
            count.incrementAndGet();
            return true;
        }
    }
}
