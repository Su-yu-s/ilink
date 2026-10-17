package cn.ilink.util;

import cn.ilink.entity.User;

/**
 * 用户显示名的唯一来源。
 *
 * <p>规则：优先使用用户自己填写的姓名；未填写时按角色给出中性占位（「同学」「老师」「管理员」）。
 *
 * <p><b>任何情况下都不回退到 {@code username}。</b>username 是内部标识，新注册账号由随机串生成，
 * 历史账号则可能是手机号或学号——把它当展示名会把注册凭据暴露给所有访客。
 * 历史上散落在各处的 {@code realName != null ? realName : username} 全部由本类取代。
 */
public final class DisplayNameResolver {

    /** 未填写姓名时的角色占位文案 */
    public static final String PLACEHOLDER_STUDENT = "同学";
    public static final String PLACEHOLDER_TEACHER = "老师";
    public static final String PLACEHOLDER_ADMIN = "管理员";
    public static final String PLACEHOLDER_UNKNOWN = "用户";

    private DisplayNameResolver() {
    }

    /** 按角色解析显示名；{@code user} 为 null 时返回通用占位。 */
    public static String resolve(User user) {
        if (user == null) {
            return PLACEHOLDER_UNKNOWN;
        }
        String realName = trimToNull(user.getRealName());
        if (realName != null) {
            return realName;
        }
        return placeholderForRole(user.getRole());
    }

    /** 显示名的首字，用于头像占位。始终非空。 */
    public static String initial(User user) {
        String name = resolve(user);
        return name.isEmpty() ? PLACEHOLDER_UNKNOWN.substring(0, 1) : name.substring(0, 1);
    }

    /** 按角色取占位文案；未知角色回退为通用占位。 */
    public static String placeholderForRole(String role) {
        if (role == null) {
            return PLACEHOLDER_UNKNOWN;
        }
        switch (role) {
            case "STUDENT":
                return PLACEHOLDER_STUDENT;
            case "TEACHER":
                return PLACEHOLDER_TEACHER;
            case "ADMIN":
                return PLACEHOLDER_ADMIN;
            default:
                return PLACEHOLDER_UNKNOWN;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
