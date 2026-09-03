package cn.ilink.service;

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
    private UserRoleService roleService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        teacherService = mock(TeacherApplicationServiceImpl.class);
        projectService = mock(ProjectApplicationServiceImpl.class);
        CacheManager cacheManager = mock(CacheManager.class);
        roleService = new UserRoleService(userService, teacherService, projectService, cacheManager);
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
}
