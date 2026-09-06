package cn.ilink.common;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 内置头像清单（唯一数据源）。
 *
 * <p>素材位于 {@code static/images/avatars/student|teacher/}，按角色分两组，
 * 每组 6 个（男女各半）。注册时按角色随机分配，个人中心仅可选择本角色头像。</p>
 */
public final class BuiltinAvatarCatalog {

    /** 内置头像静态资源根路径 */
    public static final String BASE_PATH = "/images/avatars/";

    private static final List<String> STUDENT_AVATARS = List.of(
            BASE_PATH + "student/s1.jpg",
            BASE_PATH + "student/s2.jpg",
            BASE_PATH + "student/s3.jpg",
            BASE_PATH + "student/s4.jpg",
            BASE_PATH + "student/s5.jpg",
            BASE_PATH + "student/s6.jpg"
    );

    private static final List<String> TEACHER_AVATARS = List.of(
            BASE_PATH + "teacher/t1.jpg",
            BASE_PATH + "teacher/t2.jpg",
            BASE_PATH + "teacher/t3.jpg",
            BASE_PATH + "teacher/t4.jpg",
            BASE_PATH + "teacher/t5.jpg",
            BASE_PATH + "teacher/t6.jpg"
    );

    private static final Map<String, List<String>> AVATARS_BY_ROLE = Map.of(
            "STUDENT", STUDENT_AVATARS,
            "TEACHER", TEACHER_AVATARS
    );

    private BuiltinAvatarCatalog() {
    }

    /**
     * 按角色返回可选内置头像（不可变列表）；非学生/教师角色返回空列表。
     */
    public static List<String> listForRole(String role) {
        if (role == null) {
            return List.of();
        }
        return AVATARS_BY_ROLE.getOrDefault(role.trim().toUpperCase(), List.of());
    }

    /**
     * 按角色随机返回一个内置头像路径；该角色没有内置头像时返回 null。
     */
    public static String randomForRole(String role) {
        List<String> avatars = listForRole(role);
        if (avatars.isEmpty()) {
            return null;
        }
        return avatars.get(ThreadLocalRandom.current().nextInt(avatars.size()));
    }

    /**
     * 判断路径是否为清单内的内置头像（防止伪造任意 /images/ 路径写入）。
     */
    public static boolean isBuiltin(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return STUDENT_AVATARS.contains(v) || TEACHER_AVATARS.contains(v);
    }

    /**
     * 判断内置头像是否归属于指定角色（用于防止学生选择教师头像、反之亦然）。
     * 非内置头像路径一律返回 false。
     */
    public static boolean belongsToRole(String value, String role) {
        if (value == null || role == null) {
            return false;
        }
        return listForRole(role).contains(value.trim());
    }
}
