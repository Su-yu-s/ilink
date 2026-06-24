package cn.ilink.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 页面路由契约测试（不启动完整容器与数据库）。
 */
@WebMvcTest(controllers = PageController.class)
@AutoConfigureMockMvc(addFilters = false)
class PageControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    private CookieCsrfTokenRepository csrfRepo;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    private MockHttpSession sessionWithCsrf() throws Exception {
        MockHttpSession session = new MockHttpSession();
        org.springframework.mock.web.MockHttpServletRequest req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setSession(session);
        CsrfToken token = csrfRepo.generateToken(req);
        session.setAttribute("_csrf", token);
        return session;
    }

    @Test
    void unknownHtmlRedirectsToRoot() throws Exception {
        mockMvc.perform(get("/not-a-real-page.html"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"));
    }

    @Test
    void galleryHtmlResolvedAsSystemModule() throws Exception {
        mockMvc.perform(get("/gallery.html"))
            .andExpect(status().isOk())
            .andExpect(view().name("gallery"));
    }

    @Test
    void communityArticlePathReturnsTemplate() throws Exception {
        mockMvc.perform(get("/community/article/42"))
            .andExpect(status().isOk())
            .andExpect(view().name("community-article"));
    }

    @Test
    void indexHtmlResolvedByGenericMapping() throws Exception {
        mockMvc.perform(get("/index.html"))
            .andExpect(status().isOk())
            .andExpect(view().name("index"));
    }

    @Test
    void profileEditAndHonorsHtmlResolved() throws Exception {
        MockHttpSession session = sessionWithCsrf();
        mockMvc.perform(get("/profile-edit.html").session(session))
            .andExpect(status().isOk())
            .andExpect(view().name("profile-edit"));
        mockMvc.perform(get("/profile-honors.html").session(session))
            .andExpect(status().isOk())
            .andExpect(view().name("profile-honors"));
    }

    @Test
    void profilePostsAndArticleEditHtmlResolved() throws Exception {
        mockMvc.perform(get("/profile-posts.html"))
            .andExpect(status().isOk())
            .andExpect(view().name("profile-posts"));
        mockMvc.perform(get("/profile-favorites.html"))
            .andExpect(status().isOk())
            .andExpect(view().name("profile-favorites"));
        mockMvc.perform(get("/profile-article-edit.html"))
            .andExpect(status().isOk())
            .andExpect(view().name("profile-article-edit"));
    }
}
