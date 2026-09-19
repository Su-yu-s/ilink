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

import java.net.URI;
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

    /**
     * 提问语气词 / 疑问后缀：不携带检索价值，留着会稀释关键词匹配。
     * 实测「deepseek-v4.1-flash 是什么模型」会退化成通用站点结果，去掉后缀后能直接命中官网。
     * 只清「是什么 / 有哪些 / 吗 / 呢」这类纯语气成分，不影响「怎么准备」这类带意图的措辞。
     */
    private static final Pattern QUESTION_NOISE = Pattern.compile(
        "^(请问|想问一下|我想知道|帮我看看|帮我查一下|帮我)[\\s，,]*"
            + "|[\\s，,。？！?!]*(是怎么样的|是什么意思|是什么模型|指的是什么|什么意思|是什么|是啥|有哪些|有什么)[\\s，,。？！?!]*$"
            + "|[\\s，,。？！?!]*(吗|呢|吧)[\\s，,。？！?!]*$"
            + "|[\\s，,。？！?!]+$");

    /** 清洗检索词。清洗后若为空则退回原串，避免搜出毫不相干的东西。 */
    static String refineQuery(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return value;
        }
        String refined = QUESTION_NOISE.matcher(value).replaceAll("").trim();
        return refined.isEmpty() ? value : refined;
    }

    /**
     * 构造搜索请求 URI。
     *
     * <p>必须编码后以 {@link java.net.URI} 传入：若把字符串交给
     * {@code restTemplate.exchange(String, ...)}，它会把该字符串当作 URI 模板再编码一次，
     * {@code %E6%A8%A1} 变成 {@code %25E6%25A8%25A1}，Bing 解码一次后拿到的是字面的百分号串，
     * 中文关键词全部丢失，退化成只按拉丁字母部分检索（表现为「什么都搜不到」）。
     */
    static URI buildSearchUri(String query) {
        String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        return URI.create(String.format(SEARCH_URL, encoded));
    }

    /** 搜索公开网页。未开启 / 失败 / 无结果时返回空列表，不抛异常。 */
    public List<SearchResult> search(String query, int limit) {
        if (!aiProperties.isSearchEnabled() || query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        int capped = Math.max(1, Math.min(limit <= 0 ? 5 : limit, RESULT_HARD_CAP));
        // 先清掉提问语气词再检索，否则「X 是什么模型」这类问法会退化成泛结果
        String effectiveQuery = refineQuery(query);

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.TEXT_HTML));
        headers.set(HttpHeaders.USER_AGENT,
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.8");

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                buildSearchUri(effectiveQuery),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
            List<SearchResult> results = parseHtml(response.getBody(), capped);
            // 命中 0 条时说明页面结构变了或被反爬拦截，必须留下痕迹，否则会静默退化成「没有联网」
            if (results.isEmpty()) {
                log.warn("[联网搜索] 未解析到结果 query={} httpStatus={} bodyBytes={}",
                    effectiveQuery, response.getStatusCodeValue(),
                    response.getBody() == null ? 0 : response.getBody().length());
            }
            return results;
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