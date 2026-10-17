package cn.ilink.service;

import cn.ilink.entity.AdminAuditLog;
import cn.ilink.entity.User;
import cn.ilink.mapper.AdminAuditLogMapper;
import cn.ilink.util.ClientIpResolver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

@Service
public class AdminAuditService {
    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    /** 审计日志界面把对象类型显示成中文（用户/队伍/…），搜中文时要换算回库里的枚举值 */
    private static final Map<String, String> TARGET_TYPE_CN = Map.of(
        "用户", "USER", "队伍", "TEAM", "导师", "TEACHER_PROFILE", "成果", "ASSET",
        "帖子", "COMMUNITY_POST", "竞赛", "COMPETITION", "身份", "IDENTITY");

    private final AdminAuditLogMapper mapper;

    public AdminAuditService(AdminAuditLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 分页查询审计日志（管理端「审计日志」页签）。
     *
     * @param keyword     模糊匹配对象类型 / 对象ID / 详情 / IP
     * @param action      精确匹配动作（忽略大小写，如 DELETE / APPROVE）
     * @param adminUserId 精确匹配操作管理员
     */
    @Transactional(readOnly = true)
    public Page<AdminAuditLog> listLogs(long page, long size, String keyword, String action, Long adminUserId) {
        LambdaQueryWrapper<AdminAuditLog> wrapper = new LambdaQueryWrapper<>();
        if (adminUserId != null) {
            wrapper.eq(AdminAuditLog::getAdminUserId, adminUserId);
        }
        if (action != null && !action.isBlank()) {
            wrapper.eq(AdminAuditLog::getAction, action.trim().toUpperCase(Locale.ROOT));
        }
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            // 列表里的对象类型是中文，用户自然照抄着搜；只认库里的英文枚举会让「用户」搜出 0 条
            String typeAlias = TARGET_TYPE_CN.get(kw);
            wrapper.and(w -> w.like(AdminAuditLog::getTargetType, kw)
                .or().like(AdminAuditLog::getTargetId, kw)
                .or().like(AdminAuditLog::getDetails, kw)
                .or().like(AdminAuditLog::getIpAddress, kw)
                .or(typeAlias != null, q -> q.eq(AdminAuditLog::getTargetType, typeAlias)));
        }
        wrapper.orderByDesc(AdminAuditLog::getCreatedAt);
        return mapper.selectPage(new Page<>(page, size), wrapper);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSafely(User admin, String action, String targetType, Object targetId,
                             String details, HttpServletRequest request) {
        if (admin == null || admin.getId() == null || !"ADMIN".equals(admin.getRole())) return;
        try {
            AdminAuditLog entry = new AdminAuditLog();
            entry.setAdminUserId(admin.getId());
            entry.setAction(trim(action, 64));
            entry.setTargetType(trim(targetType, 64));
            entry.setTargetId(trim(targetId == null ? "" : String.valueOf(targetId), 128));
            entry.setDetails(trim(details, 1000));
            entry.setIpAddress(trim(clientIp(request), 64));
            entry.setUserAgent(trim(request == null ? "" : request.getHeader("User-Agent"), 255));
            entry.setCreatedAt(new Date());
            mapper.insert(entry);
        } catch (Exception e) {
            log.error("管理员操作审计写入失败: action={}, targetType={}, targetId={}",
                action, targetType, targetId, e);
        }
    }

    private String clientIp(HttpServletRequest request) {
        return ClientIpResolver.resolve(request);
    }

    private String trim(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }
}
