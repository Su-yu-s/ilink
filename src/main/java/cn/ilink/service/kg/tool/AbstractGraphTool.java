package cn.ilink.service.kg.tool;

import cn.ilink.entity.KgEntity;
import cn.ilink.service.kg.KgScopeService;
import cn.ilink.teamagent.TeamAgentScopeContext;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.List;
import java.util.Map;

/** 工具公共基类：参数提取 + 可见性过滤（团队私有子图的统一 WHERE 拼装点） */
public abstract class AbstractGraphTool implements GraphTool {

    protected final KgScopeService scopeService;

    protected AbstractGraphTool(KgScopeService scopeService) {
        this.scopeService = scopeService;
    }

    // ==================== 参数提取 ====================

    protected static String paramString(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    protected static Long paramLong(Map<String, Object> params, String key) {
        String text = paramString(params, key);
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected static int paramInt(Map<String, Object> params, String key, int defaultValue) {
        Long value = paramLong(params, key);
        return value == null ? defaultValue : value.intValue();
    }

    // ==================== 可见性过滤 ====================

    /**
     * 在实体查询上叠加当前用户可见范围（阶段 4 团队子图的核心 WHERE）：
     * GLOBAL 全可见；TEAM 限成员团队；PRIVATE 限属主。
     */
    protected void applyVisibility(QueryWrapper<KgEntity> wrapper, Long userId) {
        List<Long> teamIds = scopeService.visibleTeamIds(userId);
        boolean hasTeams = !teamIds.isEmpty();
        boolean hasUser = userId != null && userId > 0;
        boolean teamAgentScoped = TeamAgentScopeContext.current() != null;
        wrapper.and(w -> {
            w.eq("scope", KgEntity.SCOPE_GLOBAL);
            if (hasTeams) {
                w.or(o -> o.eq("scope", KgEntity.SCOPE_TEAM).in("team_id", teamIds));
            } else {
                w.or(o -> o.eq("scope", KgEntity.SCOPE_TEAM).eq("team_id", -1L));
            }
            if (hasUser && !teamAgentScoped) {
                w.or(o -> o.eq("scope", KgEntity.SCOPE_PRIVATE).eq("user_id", userId));
            } else {
                w.or(o -> o.eq("scope", KgEntity.SCOPE_PRIVATE).eq("user_id", -1L));
            }
        });
    }

    // ==================== 输出结构化错误（不抛异常，Agent 可自行换策略） ====================

    protected static String errorJson(String code, String message) {
        return "{\"error\":\"" + code + "\",\"message\":\"" + escape(message) + "\"}";
    }

    protected static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
