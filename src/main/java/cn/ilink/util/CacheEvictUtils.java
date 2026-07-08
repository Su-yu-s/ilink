package cn.ilink.util;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * 缓存工具类，提供手动淘汰通知未读数缓存的能力。
 */
@Component
public class CacheEvictUtils {

    private static CacheManager cacheManager;

    public CacheEvictUtils(CacheManager cacheManager) {
        CacheEvictUtils.cacheManager = cacheManager;
    }

    /**
     * 淘汰指定用户的未读数缓存。
     */
    public static void evictUnreadCount(Long userId) {
        if (cacheManager == null) return;
        Cache cache = cacheManager.getCache("unreadCount");
        if (cache != null) {
            cache.evict(userId);
        }
    }
}
