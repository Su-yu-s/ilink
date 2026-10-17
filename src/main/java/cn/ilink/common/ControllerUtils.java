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
            User principal = (User) auth.getPrincipal();
            // 纵深防御：账号被暂停时，会话本应已被 UserSessionRegistry.invalidateAll 清掉。
            // 这里再按快照里的状态兜一次，避免漏网会话继续操作。
            return principal.isDisabled() ? null : principal;
        }
        // Fallback：从 HttpSession 获取（兼容登录时手动设置的 session attribute）
        if (session != null) {
            Object user = session.getAttribute("user");
            if (user instanceof User) {
                User sessionUser = (User) user;
                return sessionUser.isDisabled() ? null : sessionUser;
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

    // ------------------------------------------------------------------
    // 字段长度 / 取值校验
    // 口径与 db/migration（V0_5/V12 的 user 表，V1/V10 的任务表等）保持一致，
    // 本地库已按 Flyway 重建，线上（阿里云）也是 Flyway 建库，两侧一致。
    // 校验放在入口可以让超长/非法值返回 400，而不是撞到列宽后变成 500。
    // ------------------------------------------------------------------

    public static final int USERNAME_MAX = 100;
    public static final int REAL_NAME_MAX = 100;
    public static final int EMAIL_MAX = 100;
    public static final int GRADE_MAX = 20;
    public static final int MAJOR_MAX = 100;
    public static final int SCHOOL_MAX = 100;
    public static final int COLLEGE_MAX = 100;
    public static final int BIO_MAX = 300;
    public static final int TEAM_TITLE_MAX = 200;
    public static final int TASK_TITLE_MAX = 200;
    public static final int TASK_TYPE_MAX = 50;
    public static final int MILESTONE_NAME_MAX = 200;
    public static final int SKILL_NAME_MAX = 64;

    private static final java.util.Set<String> GENDERS =
        java.util.Set.of("MALE", "FEMALE", "OTHER");

    /**
     * 邮箱格式正则。<b>全站唯一口径</b>：注册发码、注册、资料更新、管理端改资料都必须用它，
     * 避免出现"注册严格、改资料放行"的两套标准（核实报告 P1-1）。
     * 允许中文等本地部分字符，域名要求至少一个点且顶级域 ≥2 位字母。
     */
    private static final java.util.regex.Pattern EMAIL_PATTERN =
        java.util.regex.Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /** 邮箱归一化：去首尾空白并转小写，保证"同一邮箱"在冷却键/唯一索引上一致 */
    public static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 邮箱格式判定（不做归一化，调用方先用 {@link #normalizeEmail}） */
    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * 邮箱格式校验。null 视为不修改（可选字段），空串放行（表示清空邮箱）。
     *
     * @return 校验通过返回 null，否则返回可直接回给前端的提示
     */
    public static String checkEmailFormat(String email) {
        if (email == null) {
            return null;
        }
        String value = email.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            return "邮箱格式不正确";
        }
        return null;
    }

    /**
     * 从 {@code Map<String,Object>} 请求体里取字符串字段。
     *
     * <p>此前社区评论用 {@code String.valueOf(body.get("content"))} 取内容，导致传对象/数字/布尔也能
     * "评论成功"，并把 {@code {a=1}}、{@code 12345} 这类脏值落库（核实报告 P2-1）。
     *
     * @return 字符串值（未 trim）；字段缺失返回 null；类型不是字符串抛 {@link IllegalArgumentException}
     *         （由 GlobalExceptionHandler 转 400，提示里带字段名）
     */
    public static String requireStringField(java.util.Map<String, Object> body, String field, String label) {
        if (body == null) {
            return null;
        }
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(label + "格式不正确，请提交字符串");
        }
        return (String) value;
    }

    /**
     * 长度校验。null 视为不修改（可选字段），空串也放行（调用方自行决定是否清空）。
     *
     * @return 校验通过返回 null，否则返回可直接回给前端的提示
     */
    public static String checkMaxLength(String value, int max, String label) {
        if (value == null) {
            return null;
        }
        if (value.trim().length() > max) {
            return label + "长度不能超过 " + max + " 个字符";
        }
        return null;
    }

    /** 性别取值校验：只接受 MALE / FEMALE / OTHER（忽略大小写与首尾空格），空值放行 */
    public static String checkGender(String gender) {
        if (gender == null) {
            return null;
        }
        String value = gender.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!GENDERS.contains(value.toUpperCase(java.util.Locale.ROOT))) {
            return "性别取值非法，仅支持 MALE / FEMALE / OTHER";
        }
        return null;
    }

    /** 归一化性别：统一大写，空串转为 null */
    public static String normalizeGender(String gender) {
        if (gender == null) {
            return null;
        }
        String value = gender.trim();
        return value.isEmpty() ? null : value.toUpperCase(java.util.Locale.ROOT);
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
        // 内置头像为随包发布的静态资源，路径固定且在清单内，允许写入资料
        if (BuiltinAvatarCatalog.isBuiltin(v)) {
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
