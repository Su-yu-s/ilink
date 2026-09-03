package cn.ilink.service;

import cn.ilink.entity.AdminAuditLog;
import cn.ilink.entity.User;
import cn.ilink.mapper.AdminAuditLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AdminAuditServiceTest {

    @Test
    void recordsAdminActionWithForwardedClientIp() {
        AdminAuditLogMapper mapper = mock(AdminAuditLogMapper.class);
        AdminAuditService service = new AdminAuditService(mapper);
        User admin = new User();
        admin.setId(3L);
        admin.setRole("ADMIN");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        request.addHeader("User-Agent", "audit-test");
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);

        service.recordSafely(admin, "DELETE", "TEAM", 12L, "reason=test", request);

        verify(mapper).insert(captor.capture());
        assertEquals(3L, captor.getValue().getAdminUserId());
        assertEquals("203.0.113.9", captor.getValue().getIpAddress());
        assertEquals("12", captor.getValue().getTargetId());
    }

    @Test
    void ignoresNonAdminActor() {
        AdminAuditLogMapper mapper = mock(AdminAuditLogMapper.class);
        AdminAuditService service = new AdminAuditService(mapper);
        User student = new User();
        student.setId(4L);
        student.setRole("STUDENT");

        service.recordSafely(student, "DELETE", "TEAM", 12L, "", null);

        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }
}
