package cn.ilink.controller;

import cn.ilink.security.LoginAttemptService;
import cn.ilink.entity.User;
import cn.ilink.service.EmailVerificationService;
import cn.ilink.service.HomeStatsService;
import cn.ilink.service.UserService;
import cn.ilink.service.RememberMeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private LoginAttemptService loginAttemptService;

    @MockBean
    private HomeStatsService homeStatsService;

    @MockBean
    private RememberMeService rememberMeService;

    @MockBean
    private EmailVerificationService emailVerificationService;

    @Test
    void rootIndexAddsAllHomeStats() throws Exception {
        given(homeStatsService.resolveIndexStats()).willReturn(Map.of(
            "userCount", 14L,
            "teamCount", 2L,
            "assetCount", 1L,
            "teacherCount", 2L
        ));

        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(view().name("index"))
            .andExpect(model().attribute("userCount", 14L))
            .andExpect(model().attribute("teamCount", 2L))
            .andExpect(model().attribute("assetCount", 1L))
            .andExpect(model().attribute("teacherCount", 2L));
    }

    /**
     * 核实报告 P2-2：登录失败必须返回真实 HTTP 401，而不是 HTTP 200 + body.code=401。
     * 网关/监控按状态码统计成功率，SDK 也不应被迫先读 body 才知道失败。
     */
    @Test
    void loginFailureReturnsHttp401() throws Exception {
        given(userService.login(any())).willReturn(null);
        given(loginAttemptService.isBlocked(any())).willReturn(false);

        mockMvc.perform(post("/api/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"nobody@example.com\",\"password\":\"WrongPass123\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    /** 登录锁定同样回 401（与失败路径一致，避免通过状态码区分“锁定”与“密码错”） */
    @Test
    void blockedLoginReturnsHttp401() throws Exception {
        given(loginAttemptService.isBlocked(any())).willReturn(true);
        given(loginAttemptService.remainingLockMinutes()).willReturn(15);

        mockMvc.perform(post("/api/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"13900011111\",\"password\":\"Loadtest123\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void disabledAccountLoginReturnsForbiddenAndDoesNotCreateRememberMeSession() throws Exception {
        User disabled = new User();
        disabled.setId(21L);
        disabled.setStatus("DISABLED");
        given(loginAttemptService.isBlocked(any())).willReturn(false);
        given(userService.login(any())).willReturn(disabled);

        mockMvc.perform(post("/api/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"student@example.edu\",\"password\":\"ValidPass123\",\"rememberMe\":true}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.message").value("账号已被暂停使用，如有疑问请联系管理员"));

        verify(loginAttemptService).loginFailed(any());
        org.mockito.Mockito.verifyNoInteractions(rememberMeService);
    }
}
