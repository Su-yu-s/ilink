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

    /**
     * 头像等资源地址白名单校验：只允许站内受管上传目录。
     * 允许相对地址 {@code /uploads/...}；当配置了完整 URL 前缀时，
     * 只允许与该前缀同源（同 host:port）的绝对地址。其余一律拒绝，
     * 防止外链跟踪、data:/javascript: 等非受管资源写入用户资料。
     *
     * @param value           待校验地址；null 或空字符串视为清空，允许
     * @param accessUrlPrefix 受管上传访问前缀（如 {@code /uploads/} 或完整 URL）
     * @return 是否允许写入
     */
    public static boolean isManagedUploadUrl(String value, String accessUrlPrefix) {
        if (value == null || value.trim().isEmpty()) {
            return true;
        }
        String v = value.trim();
        if (v.startsWith("/uploads/")) {
            return true;
        }
        String prefix = accessUrlPrefix == null ? "" : accessUrlPrefix.trim();
        if (prefix.isEmpty()) {
            return false;
        }
        try {
            java.net.URI uri = new java.net.URI(v);
            java.net.URI prefixUri = new java.net.URI(prefix);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return false;
            }
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            if (uri.getPath() == null || !uri.getPath().startsWith("/uploads/")) {
                return false;
            }
            String authority = prefixUri.getAuthority();
            return authority != null && authority.equalsIgnoreCase(uri.getAuthority());
        } catch (Exception e) {
            return false;
        }
    }
}
