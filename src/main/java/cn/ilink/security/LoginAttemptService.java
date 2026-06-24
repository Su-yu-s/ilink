package cn.ilink.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录失败次数限制：同一 key（IP 或账号）连续失败 5 次后锁定 15 分钟。
 */
@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 15;

    private final Cache<String, AtomicInteger> attempts = Caffeine.newBuilder()
        .expireAfterWrite(LOCK_MINUTES, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build();

    // C-07: 使用 ConcurrentHashMap 保证并发安全，替代 asMap().computeIfAbsent() 的竞态
    private final ConcurrentHashMap<String, AtomicInteger> lockMap = new ConcurrentHashMap<>();

    public void loginFailed(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        AtomicInteger count = lockMap.computeIfAbsent(key, k -> new AtomicInteger(0));
        synchronized (count) {
            int current = count.incrementAndGet();
            if (current >= MAX_ATTEMPTS) {
                attempts.asMap().put(key, count);
            }
        }
    }

    public void loginSucceeded(String key) {
        if (key != null && !key.isBlank()) {
            attempts.invalidate(key);
            AtomicInteger removed = lockMap.remove(key);
            if (removed != null) {
                synchronized (removed) {
                    removed.set(0);
                }
            }
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
}
