package cn.ilink.service.ai;

import cn.ilink.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量联网搜索：抓取 Bing 网页搜索结果并解析出标题/链接/摘要。
 *
 * 仅用于给 AI 答疑补充公开资料上下文；结果可能为空（超时/被反爬/无结果），
 * 上层必须容忍空结果并降级为基于自身知识的回答。
 */
@Service
@Slf4j
public class WebSearchService {

    private static final String SEARCH_URL = "https://www.bing.com/search?q=%s&mkt=zh-CN&setlang=zh-hans";
    private static final int RESULT_HARD_CAP = 8;
    private static final int SNIPPET_LIMIT = 200;

    // b_algo 结果块：<li class="b_algo"> ... <h2><a href="URL">标题</a></h2> ... <p>摘要</p>
    private static final Pattern ALGO_BLOCK = Pattern.compile("<li class=\"b_algo\"[^>]*>(.*?)</li>", Pattern.DOTALL);
    private static final Pattern TITLE_LINK = Pattern.compile("<h2[^>]*><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a></h2>", Pattern.DOTALL);
    private static final Pattern SNIPPET = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private final AiProperties aiProperties;
    private final RestTemplate restTemplate;

    public WebSearchService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        // 搜索只是上下文增强，绝不允许拖垮答疑主链路
        factory.setReadTimeout(8_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /** 搜索结果条目 */
    public static class SearchResult {
        public final String title;
        public final String url;
        public final String snippet;

        public SearchResult(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
    }

    /** 搜索公开网页。未开启 / 失败 / 无结果时返回空列表，不抛异常。 */
    public List<SearchResult> search(String query, int limit) {
        if (!aiProperties.isSearchEnabled() || query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        int capped = Math.max(1, Math.min(limit <= 0 ? 5 : limit, RESULT_HARD_CAP));
        String encoded;
        try {
            encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return Collections.emptyList();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.TEXT_HTML));
        headers.set(HttpHeaders.USER_AGENT,
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.8");

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                String.format(SEARCH_URL, encoded),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
            return parseHtml(response.getBody(), capped);
        } catch (ResourceAccessException e) {
            log.warn("[联网搜索] 搜索超时/连接失败: {}", e.getMessage());
            return Collections.emptyList();
        } catch (RestClientException e) {
            log.warn("[联网搜索] 搜索请求失败: {}", e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("[联网搜索] 搜索异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 解析 Bing HTML，提取最多 limit 条结果。提取逻辑独立以便单元测试。 */
    static List<SearchResult> parseHtml(String html, int limit) {
        if (html == null || html.isEmpty()) {
            return Collections.emptyList();
        }
        List<SearchResult> results = new ArrayList<>();
        Matcher blockMatcher = ALGO_BLOCK.matcher(html);
        while (blockMatcher.find() && results.size() < limit) {
            String block = blockMatcher.group(1);
            Matcher linkMatcher = TITLE_LINK.matcher(block);
            if (!linkMatcher.find()) {
                continue;
            }
            String url = stripQuotes(linkMatcher.group(1));
            String title = cleanText(linkMatcher.group(2), 120);
            if (title.isEmpty() || url.isEmpty()) {
                continue;
            }
            String snippet = "";
            Matcher snippetMatcher = SNIPPET.matcher(block);
            if (snippetMatcher.find()) {
                snippet = cleanText(snippetMatcher.group(1), SNIPPET_LIMIT);
            }
            results.add(new SearchResult(title, url, snippet));
        }
        return results;
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replace("\"", "").replace("'", "");
    }

    /** 去除 HTML 标签与多余空白，并截断到 maxLen */
    static String cleanText(String raw, int maxLen) {
        if (raw == null) {
            return "";
        }
        String text = TAG.matcher(raw).replaceAll("");
        text = text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ");
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() > maxLen) {
            text = text.substring(0, maxLen) + "…";
        }
        return text;
    }
}