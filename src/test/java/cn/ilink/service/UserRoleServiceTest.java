package cn.ilink.service;

import cn.ilink.common.UserSessionRegistry;
import cn.ilink.entity.TeacherApplication;
import cn.ilink.entity.User;
import cn.ilink.service.impl.ProjectApplicationServiceImpl;
import cn.ilink.service.impl.TeacherApplicationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserRoleServiceTest {

    private UserService userService;
    private TeacherApplicationServiceImpl teacherService;
    private ProjectApplicationServiceImpl projectService;
    private RememberMeService rememberMeService;
    private UserSessionRegistry sessionRegistry;
    private UserRoleService roleService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        teacherService = mock(TeacherApplicationServiceImpl.class);
        projectService = mock(ProjectApplicationServiceImpl.class);
        CacheManager cacheManager = mock(CacheManager.class);
        rememberMeService = mock(RememberMeService.class);
        sessionRegistry = mock(UserSessionRegistry.class);
        roleService = new UserRoleService(userService, teacherService, projectService, cacheManager,
            rememberMeService, sessionRegistry);
    }

    @Test
    void promotingStudentCreatesNonPublicTeacherProfile() {
        User user = user("STUDENT");
        when(userService.getById(7L)).thenReturn(user);
        when(userService.updateById(user)).thenReturn(true);
        when(teacherService.getOne(any())).thenReturn(null);
        when(teacherService.save(any(TeacherApplication.class))).thenReturn(true);

        User updated = roleService.changeRole(7L, "teacher");

        assertEquals("TEACHER", updated.getRole());
        verify(teacherService).save(org.mockito.ArgumentMatchers.argThat(
            profile -> "INCOMPLETE".equals(profile.getStatus()) && Long.valueOf(7L).equals(profile.getUserId())));
    }

    @Test
    void downgradingTeacherRevokesProfileAndPendingApplications() {
        User user = user("TEACHER");
        TeacherApplication profile = new TeacherApplication();
        profile.setId(12L);
        profile.setUserId(7L);
        profile.setStatus("APPROVED");
        when(userService.getById(7L)).thenReturn(user);
        when(userService.updateById(user)).thenReturn(true);
        when(teacherService.getOne(any())).thenReturn(profile);
        when(teacherService.updateById(profile)).thenReturn(true);

        User updated = roleService.changeRole(7L, "STUDENT");

        assertEquals("STUDENT", updated.getRole());
        assertEquals("REVOKED", profile.getStatus());
        verify(projectService).update(any());
    }

    private User user(String role) {
        User user = new User();
        user.setId(7L);
        user.setUsername("user7");
        user.setRole(role);
        return user;
    }

    /**
     * 回归报告 N-01：管理员在后台编辑自己时，必须保留发起请求的会话
     * （否则 controller 随后的会话同步会撞上已失效会话 → 500 且数据已生效）。
     * 常规路径（编辑别人）仍然吊销该用户全部会话与 remember-me。
     */
    @Test
    void selfEditKeepsOwnSessionAndOtherSessionsStillRevoked() {
        User user = user("ADMIN");
        when(userService.updateById(user)).thenReturn(true);
        when(teacherService.getOne(any())).thenReturn(null);

        roleService.updateUserAndRole(user, "session-keep-me");

        verify(sessionRegistry).invalidateAllExcept(7L, "session-keep-me");
        verify(sessionRegistry, org.mockito.Mockito.never()).invalidateAll(7L);
        // 自己改自己不必吊销自己的 remember-me（否则会话过期后直接被登出）
        verify(rememberMeService, org.mockito.Mockito.never()).revokeAllForUser(7L);
    }

    @Test
    void editingOthersRevokesAllSessionsAndRememberMe() {
        User user = user("STUDENT");
        when(userService.updateById(user)).thenReturn(true);
        when(teacherService.getOne(any())).thenReturn(null);

        roleService.updateUserAndRole(user);

        verify(rememberMeService).revokeAllForUser(7L);
        verify(sessionRegistry).invalidateAll(7L);
    }
}
