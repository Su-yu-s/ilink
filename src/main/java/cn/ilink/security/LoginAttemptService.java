package cn.ilink.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录/注册失败次数限制：同一 key（IP 或账号）连续失败 5 次后锁定 15 分钟。
 * 注册限流：同一 IP 每分钟最多 3 次。
 */
@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 15;
    private static final int REGISTER_MAX_PER_MINUTE = 3;

    private final Cache<String, AtomicInteger> attempts = Caffeine.newBuilder()
        .expireAfterWrite(LOCK_MINUTES, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build();

    /** 注册限流：同一 IP 每分钟最多 3 次 */
    private final Cache<String, AtomicInteger> registerAttempts = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
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
}
