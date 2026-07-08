package cn.ilink.common;

import cn.ilink.entity.User;

import javax.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Controller 公共工具类
 * 提供用户获取、参数解析、权限判断等通用方法
 */
public final class ControllerUtils {

    private ControllerUtils() {
        // 工具类禁止实例化
    }

    /**
     * 从 Spring Security SecurityContext 获取当前登录用户。
     * 优先从 SecurityContextHolder 读取，fallback 到 HttpSession（向后兼容）。
     *
     * @param session HTTP 会话（用于 fallback）
     * @return 当前用户，未登录返回 null
     */
    public static User requireUser(HttpSession session) {
        // 主路径：从 Spring Security 上下文获取
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof User) {
            return (User) auth.getPrincipal();
        }
        // Fallback：从 HttpSession 获取（兼容登录时手动设置的 session attribute）
        if (session != null) {
            Object user = session.getAttribute("user");
            if (user instanceof User) {
                return (User) user;
            }
        }
        return null;
    }

    /**
     * 安全解析 Long 参数
     * 支持数字类型和字符串类型的参数
     *
     * @param raw 原始参数值
     * @return 解析后的 Long 值，解析失败返回 null
     */
    public static Long parseLongParam(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        try {
            return Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 安全解析 Integer 参数
     *
     * @param raw       原始参数值
     * @param defaultVal 默认值
     * @return 解析后的 Integer 值，解析失败返回默认值
     */
    public static int parseIntParam(Object raw, int defaultVal) {
        if (raw == null) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    /**
     * 判断当前用户是否为管理员
     *
     * @param user 用户对象
     * @return 是否为管理员
     */
    public static boolean isAdmin(User user) {
        return user != null && "ADMIN".equals(user.getRole());
    }

    /**
     * 判断当前用户是否为教师
     *
     * @param user 用户对象
     * @return 是否为教师
     */
    public static boolean isTeacher(User user) {
        return user != null && "TEACHER".equals(user.getRole());
    }

    /** 统一分页参数安全处理：page≥1 */
    public static int safePage(int page) {
        return Math.max(page, 1);
    }

    /** 统一分页参数安全处理：1≤size≤max */
    public static int safeSize(int size, int max) {
        return Math.min(Math.max(size, 1), max);
    }
}
