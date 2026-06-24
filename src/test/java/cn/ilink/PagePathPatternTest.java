package cn.ilink;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 /{page}.html 是否能匹配 /community-article.html（避免 page 解析错误导致 redirect:/）
 */
class PagePathPatternTest {

    @Test
    void pageVariableExtractsNameWithoutHtmlSuffix() {
        PathPatternParser parser = new PathPatternParser();
        PathPattern pattern = parser.parse("/{page}.html");
        PathContainer path = PathContainer.parsePath("/community-article.html");
        PathPattern.PathMatchInfo info = pattern.matchAndExtract(path);
        assertNotNull(info, "pattern should match /community-article.html");
        assertEquals("community-article", info.getUriVariables().get("page"));
    }
}
