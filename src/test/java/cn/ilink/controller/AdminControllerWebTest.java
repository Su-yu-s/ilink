package cn.ilink.controller;

import cn.ilink.common.Result;
import cn.ilink.entity.AdminAuditLog;
import cn.ilink.entity.User;
import cn.ilink.security.LoginAttemptService;
import cn.ilink.service.HomeStatsService;
import cn.ilink.service.UserService;
import cn.ilink.service.impl.AssetServiceImpl;
import cn.ilink.service.impl.CommunityPostServiceImpl;
import cn.ilink.service.impl.ProjectApplicationServiceImpl;
import cn.ilink.service.impl.TeacherApplicationServiceImpl;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminController 权限与基础功能测试。
 * 验证仪表盘和基础CRUD接口的权限拦截和响应结构。
 */
@WebMvcTest(controllers = AdminController.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class AdminControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private TeamDemandServiceImpl teamDemandService;

    @MockBean
    private TeacherApplicationServiceImpl teacherApplicationService;

    @MockBean
    private ProjectApplicationServiceImpl projectApplicationService;

    @MockBean
    private AssetServiceImpl assetService;

    @MockBean
    private CommunityPostServiceImpl communityPostService;

    @MockBean
    private TeamApplicationServiceImpl teamApplicationService;

    @MockBean
    private LoginAttemptService loginAttemptService;

    @MockBean
    private HomeStatsService homeStatsService;

    @MockBean
    private cn.ilink.mapper.CommunityPostLikeMapper communityPostLikeMapper;

    @MockBean
    private cn.ilink.mapper.CommunityPostFavoriteMapper communityPostFavoriteMapper;

    @MockBean
    private cn.ilink.mapper.NotificationMapper notificationMapper;

    @MockBean
    private cn.ilink.mapper.ChatMessageMapper chatMessageMapper;

    @MockBean
    private cn.ilink.mapper.CommunityCommentMapper communityCommentMapper;

    @MockBean
    private cn.ilink.service.NotificationService notificationService;

    @MockBean
    private cn.ilink.service.UserRoleService userRoleService;

    @MockBean
    private cn.ilink.service.AdminDataService adminDataService;

    @MockBean
    private cn.ilink.service.AdminAuditService adminAuditService;

    @MockBean
    private cn.ilink.service.RememberMeService rememberMeService;

    @MockBean
    private cn.ilink.common.UserSessionRegistry userSessionRegistry;

    @Test
    void dashboardReturns403WhenNoUserInSession() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void userListReturns403WhenNoUserInSession() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void teamListReturns403WhenNoUserInSession() throws Exception {
        mockMvc.perform(get("/api/admin/teams"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void teacherListReturns403WhenNoUserInSession() throws Exception {
        mockMvc.perform(get("/api/admin/teachers"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void assetListReturns403WhenNoUserInSession() throws Exception {
        mockMvc.perform(get("/api/admin/assets"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void communityPostListReturns403WhenNoUserInSession() throws Exception {
        mockMvc.perform(get("/api/admin/community-posts"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void auditLogsReturn403WhenNoUserInSession() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void auditLogsReturn403ForNonAdminUser() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs").session(sessionFor(student())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void auditLogsReturnRowsForAdmin() throws Exception {
        User admin = adminUser();
        AdminAuditLog entry = new AdminAuditLog();
        entry.setId(1L);
        entry.setAdminUserId(8L);
        entry.setAction("DELETE");
        entry.setTargetType("TEAM");
        entry.setTargetId("2");
        entry.setDetails("username=x");
        entry.setIpAddress("127.0.0.1");

        Page<AdminAuditLog> page = new Page<>(1, 20);
        page.setRecords(List.of(entry));
        page.setTotal(1);
        given(adminAuditService.listLogs(anyLong(), anyLong(), any(), any(), any())).willReturn(page);
        given(userService.listByIds(java.util.Set.of(8L))).willReturn(List.of(admin));

        mockMvc.perform(get("/api/admin/audit-logs").session(sessionFor(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].adminName").value("闫振宇"))
            .andExpect(jsonPath("$.data[0].action").value("DELETE"))
            .andExpect(jsonPath("$.extra.pagination.total").value(1));
    }

    @Test
    void auditLogsSupportActionAndKeywordFilters() throws Exception {
        User admin = adminUser();
        Page<AdminAuditLog> page = new Page<>(1, 20);
        page.setRecords(Collections.emptyList());
        page.setTotal(0);
        given(adminAuditService.listLogs(anyLong(), anyLong(), any(), any(), any())).willReturn(page);

        mockMvc.perform(get("/api/admin/audit-logs")
                .param("keyword", "TEAM")
                .param("action", "delete")
                .session(sessionFor(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.extra.pagination.total").value(0));
    }

    @Test
    void userListSupportsServerSideSearchForAdmin() throws Exception {
        User admin = adminUser();
        Page<User> page = new Page<>(1, 10);
        page.setRecords(Collections.emptyList());
        page.setTotal(0);
        given(userService.page(any(), any())).willReturn(page);

        mockMvc.perform(get("/api/admin/users")
                .param("keyword", "苏")
                .param("role", "ADMIN")
                .session(sessionFor(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.extra.pagination.total").value(0));
    }

    @Test
    void userListRejectsIllegalRoleFilter() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                .param("role", "HACKER")
                .session(sessionFor(adminUser())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void administratorCannotDisableOwnAccount() throws Exception {
        given(userService.getById(8L)).willReturn(adminUser());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/user/8/status")
                .session(sessionFor(adminUser()))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\",\"reason\":\"test\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        verify(userService, never()).updateById(any(User.class));
        verifyNoInteractions(rememberMeService, userSessionRegistry);
    }

    @Test
    void disablingAnotherAccountRevokesRememberMeAndActiveSessions() throws Exception {
        User target = student();
        given(userService.getById(22L)).willReturn(target);
        given(userService.updateById(any(User.class))).willReturn(true);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/user/22/status")
                .session(sessionFor(adminUser()))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\",\"reason\":\"违反平台规则\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        org.junit.jupiter.api.Assertions.assertEquals("DISABLED", target.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals("违反平台规则", target.getDisabledReason());
        verify(rememberMeService).revokeAllForUser(22L);
        verify(userSessionRegistry).invalidateAll(22L);
    }

    @Test
    void restoringAccountClearsDisableMetadataWithoutRevokingSessions() throws Exception {
        User target = student();
        target.setStatus("DISABLED");
        target.setDisabledReason("违反平台规则");
        target.setDisabledAt(new java.util.Date());
        given(userService.getById(22L)).willReturn(target);
        given(userService.updateById(any(User.class))).willReturn(true);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/user/22/status")
                .session(sessionFor(adminUser()))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        org.junit.jupiter.api.Assertions.assertEquals("ACTIVE", target.getStatus());
        org.junit.jupiter.api.Assertions.assertNull(target.getDisabledReason());
        org.junit.jupiter.api.Assertions.assertNull(target.getDisabledAt());
        org.mockito.Mockito.verifyNoInteractions(rememberMeService, userSessionRegistry);
    }

    private User adminUser() {
        User admin = new User();
        admin.setId(8L);
        admin.setUsername("苏");
        admin.setRealName("闫振宇");
        admin.setRole("ADMIN");
        return admin;
    }

    private User student() {
        User student = new User();
        student.setId(22L);
        student.setUsername("student");
        student.setRole("STUDENT");
        return student;
    }

    private MockHttpSession sessionFor(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", user);
        return session;
    }
}
