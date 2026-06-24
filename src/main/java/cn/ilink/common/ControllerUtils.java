package cn.ilink.common;

import cn.ilink.entity.User;

import javax.servlet.http.HttpSession;

/**
 * Controller 公共工具类
 * 提供用户获取、参数解析、权限判断等通用方法
 */
public final class ControllerUtils {

    private ControllerUtils() {
        // 工具类禁止实例化
    }

    /**
     * 从 Session 获取当前登录用户
     *
     * @param session HTTP 会话
     * @return 当前用户，未登录返回 null
     */
    public static User requireUser(HttpSession session) {
        if (session == null) {
            return null;
        }
        return (User) session.getAttribute("user");
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
}
