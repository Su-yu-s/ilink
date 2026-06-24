package cn.ilink.util;

import cn.ilink.entity.User;

import java.util.LinkedHashMap;
import java.util.Map;

public final class UserPreviewHelper {

    private UserPreviewHelper() {
    }

    /**
     * 列表/详情卡片用：头像、展示名、跳转公开主页所需 id
     */
    public static Map<String, Object> toPreview(User u) {
        if (u == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("realName", u.getRealName());
        m.put("avatar", u.getAvatar());
        m.put("role", u.getRole());
        return m;
    }
}
