package cn.ilink.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文案字典接口 {@code GET /i18n/catalog.js} 的契约测试。
 *
 * <p>这个接口是页面体积优化的关键：原先每个 HTML 内联两份目录（当前语言 + 中文原文）约 250KB，
 * 现在只留一个 script 标签。因此这里锁住三件事：
 * <ol>
 *   <li>英文模式同时下发英文目录与中文原文目录（前者显示，后者供运行时对照翻译）；</li>
 *   <li>中文模式默认下发中文；</li>
 *   <li>ETag 命中回 304，且响应带 {@code Vary: Cookie}，避免 CDN / 代理把语言串味。</li>
 * </ol>
 */
@WebMvcTest(controllers = I18nCatalogController.class)
@Import(cn.ilink.config.I18nConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class I18nCatalogControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void englishCatalogCarriesLocalizedAndSourceDictionaries() throws Exception {
        mockMvc.perform(get("/i18n/catalog.js").param("lang", "en"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/javascript;charset=UTF-8"))
            .andExpect(header().string(HttpHeaders.VARY, containsString(HttpHeaders.COOKIE)))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-cache")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
            .andExpect(content().string(containsString("window.__I18N__ = {")))
            .andExpect(content().string(containsString("window.__I18N_SOURCE__ = {")))
            .andExpect(content().string(containsString("document.documentElement.lang")))
            // 当前语言目录：界面实际显示的英文
            .andExpect(content().string(containsString("\"ui.teamSpace.recruiting\":\"This team is still recruiting\"")))
            .andExpect(content().string(containsString("\"ui.search.teamMarket.placeholder\":\"Search titles, descriptions, or skills\"")))
            // 中文原文目录：脚本动态渲染时靠它做「中文 → 英文」对照，英文模式下缺一不可
            .andExpect(content().string(containsString("\"ui.teamSpace.recruiting\":\"团队还在招募中\"")))
            .andExpect(content().string(containsString("\"ui.home.hero.poem\":\"频率相近的人，终会在某个路口相遇。\"")));
    }

    @Test
    void catalogDefaultsToChinese() throws Exception {
        mockMvc.perform(get("/i18n/catalog.js"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"language.current\":\"中文\"")))
            .andExpect(content().string(containsString("\"ui.teamSpace.recruiting\":\"团队还在招募中\"")))
            .andExpect(content().string(not(containsString("This team is still recruiting"))));
    }

    @Test
    void matchingEtagReturns304WithoutBody() throws Exception {
        MvcResult first = mockMvc.perform(get("/i18n/catalog.js").param("lang", "en"))
            .andExpect(status().isOk())
            .andReturn();
        String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
        org.junit.jupiter.api.Assertions.assertNotNull(etag, "首次响应必须带 ETag");

        MvcResult second = mockMvc.perform(get("/i18n/catalog.js").param("lang", "en").header(HttpHeaders.IF_NONE_MATCH, etag))
            .andExpect(status().isNotModified())
            .andExpect(content().string(""))
            .andExpect(header().string(HttpHeaders.ETAG, etag))
            .andExpect(header().string(HttpHeaders.VARY, containsString(HttpHeaders.COOKIE)))
            .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals("", second.getResponse().getContentAsString());

        // 陈旧 ETag 必须回完整字典，否则用户会卡在旧文案上
        mockMvc.perform(get("/i18n/catalog.js").param("lang", "en").header(HttpHeaders.IF_NONE_MATCH, "\"stale\""))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("window.__I18N__ = {")));
    }

    @Test
    void englishAndChineseCatalogsHaveDifferentEtags() throws Exception {
        MvcResult zh = mockMvc.perform(get("/i18n/catalog.js")).andExpect(status().isOk()).andReturn();
        MvcResult en = mockMvc.perform(get("/i18n/catalog.js").param("lang", "en")).andExpect(status().isOk()).andReturn();
        org.junit.jupiter.api.Assertions.assertNotEquals(
            zh.getResponse().getHeader(HttpHeaders.ETAG),
            en.getResponse().getHeader(HttpHeaders.ETAG));
    }
}
