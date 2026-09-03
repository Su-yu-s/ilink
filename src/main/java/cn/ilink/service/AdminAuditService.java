package cn.ilink.service;

import cn.ilink.entity.AdminAuditLog;
import cn.ilink.entity.User;
import cn.ilink.mapper.AdminAuditLogMapper;
import cn.ilink.util.ClientIpResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;

@Service
public class AdminAuditService {
    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);
    private final AdminAuditLogMapper mapper;

    public AdminAuditService(AdminAuditLogMapper mapper) {
        this.mapper = mapper;
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
