package cn.ilink.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 页面路由契约测试（不启动完整容器与数据库）。
 */
@WebMvcTest(controllers = PageController.class)
// 文案字典改为 /i18n/catalog.js 外链后，页面渲染不再需要 I18nModelAdvice，只保留语言解析配置
@Import({cn.ilink.config.I18nConfig.class})
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
    void unknownHtmlShows404() throws Exception {
        mockMvc.perform(get("/not-a-real-page.html"))
            .andExpect(status().isNotFound())
            .andExpect(view().name("404"));
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
    void englishLocaleRendersLegalAndAuthPagesAndPersistsInCookie() throws Exception {
        MvcResult privacy = mockMvc.perform(get("/privacy.html").param("lang", "en"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<html lang=\"en\"")))
            .andExpect(content().string(containsString("Privacy Policy")))
            .andReturn();

        javax.servlet.http.Cookie language = privacy.getResponse().getCookie("ilink_lang");
        org.junit.jupiter.api.Assertions.assertNotNull(language);
        mockMvc.perform(get("/terms.html").cookie(language))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<html lang=\"en\"")))
            .andExpect(content().string(containsString("Terms of Service")));
        mockMvc.perform(get("/login.html").session(sessionWithCsrf()).cookie(language))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Log in to iLink")))
            .andExpect(content().string(containsString("<html lang=\"en\"")));
        mockMvc.perform(get("/register.html").session(sessionWithCsrf()).cookie(language))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Create an iLink account")))
            .andExpect(content().string(containsString("Enter your password")));
        mockMvc.perform(get("/forgot-password.html").session(sessionWithCsrf()).cookie(language))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Reset your password")));
    }

    /**
     * 业务页只带走「语言元数据 + 一行字典兜底 + 外链字典脚本」，字典本体由 /i18n/catalog.js 提供
     * （见 {@code I18nCatalogControllerWebTest}）。
     *
     * <p>历史版本把两份目录内联进每个页面，单页 HTML 因此多出约 250KB；
     * 这里反向断言字典键不再出现在 HTML 里，防止改回去。页面里的中文字面量仍会由运行时字典翻译。
     */
    @Test
    void englishBusinessPagesReceiveLanguageMetadataAndExternalUiCatalog() throws Exception {
        mockMvc.perform(get("/team-market.html").param("lang", "en"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<html lang=\"en\"")))
            .andExpect(content().string(containsString("window.__I18N__ = window.__I18N__ || {}")))
            .andExpect(content().string(containsString("window.__I18N_SOURCE__ = window.__I18N_SOURCE__ || {}")))
            .andExpect(content().string(containsString("src=\"/i18n/catalog.js\"")))
            .andExpect(content().string(not(containsString("\"ui.market.eyebrow\":"))))
            .andExpect(content().string(not(containsString("\"ui.teamSpace.recruiting\":"))));

        // 不传 lang 时默认中文，页面语言元数据随之回落
        mockMvc.perform(get("/team-market.html"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<html lang=\"zh-CN\"")))
            .andExpect(content().string(containsString("src=\"/i18n/catalog.js\"")));
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
