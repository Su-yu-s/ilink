package cn.ilink.service.ai;

import cn.ilink.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchServiceTest {

    private static final String SAMPLE_HTML =
        "<html><body><ol id=\"b_results\">"
            + "<li class=\"b_algo\">"
            + "<h2><a href=\"https://example.com/notice\">大赛官网报名公告</a></h2>"
            + "<div class=\"b_caption\"><p>报名时间 3&#8209;5 月，需组队 3&#8209;5 人。&lt;强调&gt;</p></div>"
            + "</li>"
            + "<li class=\"b_algo\">"
            + "<h2><a href=\"/relative/link\">无域名链接</a></h2>"
            + "<div class=\"b_caption\"><p>相对地址结果</p></div>"
            + "</li>"
            + "<li class=\"b_algo\">"
            + "<h2><a href=\"https://example.com/empty\">空摘要</a></h2>"
            + "</li>"
            + "</ol></body></html>";

    @Test
    void buildSearchUriEncodesChineseExactlyOnce() {
        String uri = WebSearchService.buildSearchUri("数学建模 报名").toString();

        // 中文按 UTF-8 编码一次
        assertTrue(uri.contains("%E6%95%B0%E5%AD%A6%E5%BB%BA%E6%A8%A1"), "中文未按 UTF-8 编码: " + uri);
        // 空格编码为 +（表单式查询串）
        assertTrue(uri.contains("+"), "空格未编码: " + uri);
        // 绝不能出现二次编码的 %25：一旦出现，Bing 收到的是字面的百分号串，中文关键词全丢，
        // 表现为「什么都搜不到」（这是真实踩过的坑：RestTemplate.exchange(String) 会再编码一次）
        assertFalse(uri.contains("%25"), "出现二次编码，中文会被当字面量检索: " + uri);
    }

    @Test
    void buildSearchUriKeepsAsciiQueryReadable() {
        String uri = WebSearchService.buildSearchUri("icpc 2026").toString();
        assertTrue(uri.contains("q=icpc+2026"), uri);
    }

    @Test
    void parseHtmlExtractsResults() {
        List<WebSearchService.SearchResult> results = WebSearchService.parseHtml(SAMPLE_HTML, 5);
        assertEquals(3, results.size());
        WebSearchService.SearchResult first = results.get(0);
        assertEquals("大赛官网报名公告", first.title);
        assertEquals("https://example.com/notice", first.url);
        assertTrue(first.snippet.contains("报名时间"));
        assertTrue(first.snippet.contains("需组队"));
        // HTML 实体与标签被清洗
        assertTrue(first.snippet.contains("强调"));
    }

    @Test
    void parseHtmlRespectsLimit() {
        List<WebSearchService.SearchResult> results = WebSearchService.parseHtml(SAMPLE_HTML, 1);
        assertEquals(1, results.size());
        assertEquals("大赛官网报名公告", results.get(0).title);
    }

    @Test
    void parseHtmlHandlesNullAndEmpty() {
        assertTrue(WebSearchService.parseHtml(null, 5).isEmpty());
        assertTrue(WebSearchService.parseHtml("", 5).isEmpty());
        assertTrue(WebSearchService.parseHtml("<html></html>", 5).isEmpty());
    }

    @Test
    void cleanTextStripsTagsAndTruncates() {
        String out = WebSearchService.cleanText("<p>很长的摘要清理</p><b>加粗</b>", 1000);
        assertEquals("很长的摘要清理加粗", out);
        String truncated = WebSearchService.cleanText("1234567890", 4);
        assertEquals("1234…", truncated);
    }

    @Test
    void refineQueryStripsQuestionParticles() {
        // 实测：带上「是什么模型」后缀，Bing 会退化成通用站点结果；去掉后直接命中官网
        assertEquals("deepseek-v4.1-flash", WebSearchService.refineQuery("deepseek-v4.1-flash 是什么模型"));
        assertEquals("怎么报名", WebSearchService.refineQuery("请问 怎么报名"));
        assertEquals("数学建模论文格式要求", WebSearchService.refineQuery("数学建模论文格式要求有哪些"));
        assertEquals("挑战杯什么时候开始", WebSearchService.refineQuery("我想知道挑战杯什么时候开始"));
        assertEquals("这道题怎么做", WebSearchService.refineQuery("这道题怎么做呢？"));
    }

    @Test
    void refineQueryKeepsIntentBearingWording() {
        // 「怎么准备」这类措辞携带检索意图，不能当语气词清掉；只允许顺带掉一个句末问号
        assertEquals("数学建模怎么备赛", WebSearchService.refineQuery("数学建模怎么备赛？"));
        assertEquals("互联网+ 报名流程", WebSearchService.refineQuery("互联网+ 报名流程"));
    }

    @Test
    void refineQueryNeverReturnsEmpty() {
        // 清洗后为空必须退回原串，否则会搜出毫不相干的东西
        assertEquals("是什么", WebSearchService.refineQuery("是什么"));
        assertEquals("吗", WebSearchService.refineQuery("吗"));
        assertEquals("", WebSearchService.refineQuery("   "));
        assertEquals("", WebSearchService.refineQuery(null));
    }

    @Test
    void searchDisabledReturnsEmpty() {
        AiProperties properties = new AiProperties();
        properties.setSearchEnabled(false);
        WebSearchService service = new WebSearchService(properties);
        assertTrue(service.search("任意查询", 3).isEmpty());
    }
}