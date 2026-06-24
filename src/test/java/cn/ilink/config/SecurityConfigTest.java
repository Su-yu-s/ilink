package cn.ilink.config;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring Security 规则与 CSRF 契约测试。
 * 需要完整 Spring 上下文（含数据库），仅在 CI 环境或本地 MySQL 可用时运行。
 * 本地开发无 MySQL 时自动跳过，不影响日常编译。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Disabled("需要完整数据库上下文，本地开发时跳过。CI/MySQL 可用后请删除此行。")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousCannotAccessUserProfile() throws Exception {
        mockMvc.perform(get("/api/user/profile"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void anonymousCanBrowseCommunityPosts() throws Exception {
        mockMvc.perform(get("/api/community/posts"))
            .andExpect(status().isOk());
    }

    @Test
    void postWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post("/api/community/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"t\",\"content\":\"c\",\"category\":\"general\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void postWithCsrfStillRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/community/posts")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"t\",\"content\":\"c\",\"category\":\"general\"}"))
            .andExpect(status().is3xxRedirection());
    }
}
