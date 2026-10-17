package cn.ilink.util;

import cn.ilink.entity.User;

import java.util.LinkedHashMap;
import java.util.Map;

public final class UserPreviewHelper {

    private UserPreviewHelper() {
    }

    /**
     * 列表/详情卡片用：头像、展示名、跳转公开主页所需 id。
     *
     * <p>不下发 {@code username}：它是内部标识，历史账号可能是手机号或学号，
     * 一旦下发就会经导师墙、团队详情、成果详情等公开页面泄露。
     * 展示名由 {@link DisplayNameResolver} 统一解析后以 {@code displayName} 下发。
     */
    public static Map<String, Object> toPreview(User u) {
        if (u == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("realName", u.getRealName());
        m.put("displayName", DisplayNameResolver.resolve(u));
        m.put("avatarInitial", DisplayNameResolver.initial(u));
        m.put("avatar", u.getAvatar());
        m.put("role", u.getRole());
        return m;
    }
}
