package cn.ilink.controller;

import cn.ilink.common.Result;
import cn.ilink.security.LoginAttemptService;
import cn.ilink.service.HomeStatsService;
import cn.ilink.service.UserService;
import cn.ilink.service.impl.AssetServiceImpl;
import cn.ilink.service.impl.CommunityPostServiceImpl;
import cn.ilink.service.impl.TeacherApplicationServiceImpl;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
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
}
