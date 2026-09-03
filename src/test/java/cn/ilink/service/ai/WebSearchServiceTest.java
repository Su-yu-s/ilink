package cn.ilink.service.ai;

import cn.ilink.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void searchDisabledReturnsEmpty() {
        AiProperties properties = new AiProperties();
        properties.setSearchEnabled(false);
        WebSearchService service = new WebSearchService(properties);
        assertTrue(service.search("任意查询", 3).isEmpty());
    }
}