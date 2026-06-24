package cn.ilink.util;

import java.util.regex.Pattern;

/**
 * 密码策略：8-32 位，至少包含一个大写字母、一个小写字母、一个数字。
 * C-32: 加强密码要求，防止弱密码。
 */
public final class PasswordPolicy {

    private static final Pattern POLICY = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,32}$");

    private PasswordPolicy() {
    }

    public static boolean isValid(String password) {
        return password != null && POLICY.matcher(password).matches();
    }

    public static String message() {
        return "密码须为 8-32 位，且同时包含大写字母、小写字母和数字";
    }
}
