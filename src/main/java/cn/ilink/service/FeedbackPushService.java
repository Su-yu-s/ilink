package cn.ilink.service;

import cn.ilink.entity.Feedback;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * 通过 Server酱（sctapi.ftqq.com）把新反馈推送到开发者微信。
 *
 * <p>全程 best-effort：未配置 SendKey 时静默跳过，推送失败只记日志。
 * 反馈本身已落库并生成了详情页，推送只是通知手段，绝不能因为它失败而让用户提交失败。
 */
@Service
public class FeedbackPushService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackPushService.class);

    private static final String ENDPOINT = "https://sctapi.ftqq.com/%s.send";

    /** 微信推送卡片标题的长度上限（Server酱 约 32 字符会被截断） */
    private static final int TITLE_LIMIT = 32;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    @Value("${app.feedback.serverchan-sendkey:}")
    private String sendKey;

    @Value("${app.feedback.enabled:true}")
    private boolean enabled;

    public FeedbackPushService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /** 是否已配置推送通道 */
    public boolean isConfigured() {
        return enabled && StringUtils.hasText(sendKey);
    }

    /**
     * 推送一条反馈通知。
     *
     * @param feedback  已落库的反馈
     * @param detailUrl 详情页绝对地址（微信内打开，必须可公网访问）
     * @return true 表示推送成功
     */
    public boolean send(Feedback feedback, String detailUrl) {
        if (!isConfigured()) {
            log.debug("未配置 Server酱 SendKey，跳过反馈推送");
            return false;
        }
        if (feedback == null) {
            return false;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("title", buildTitle(feedback));
        form.add("desp", buildBody(feedback, detailUrl));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                String.format(ENDPOINT, sendKey.trim()),
                new HttpEntity<>(form, headers),
                String.class);
            JsonNode root = objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            int code = root.path("code").asInt(-1);
            if (code == 0) {
                return true;
            }
            log.warn("反馈推送被拒绝：code={} message={}", code, root.path("message").asText(""));
            return false;
        } catch (RestClientResponseException e) {
            log.warn("反馈推送失败，HTTP {}：{}", e.getRawStatusCode(),
                scrub(e.getResponseBodyAsString()));
            return false;
        } catch (Exception e) {
            // RestTemplate 的异常 message 里带着完整请求 URL，而 SendKey 就在 URL 路径上，
            // 直接记 e.getMessage() 会把推送凭据写进日志文件。
            log.warn("反馈推送异常（{}）：{}", e.getClass().getSimpleName(), scrub(e.getMessage()));
            return false;
        }
    }

    /** 抹掉日志里可能出现的 SendKey。 */
    String scrub(String message) {
        if (message == null) {
            return "";
        }
        String key = sendKey == null ? "" : sendKey.trim();
        return key.isEmpty() ? message : message.replace(key, "***");
    }

    private String buildTitle(Feedback feedback) {
        String label = FeedbackService.labelOf(feedback.getType());
        String summary = feedback.getDescription() == null ? "" : feedback.getDescription().trim();
        summary = summary.replaceAll("\\s+", " ");
        String title = "【iLink 反馈】" + label + "：" + summary;
        return title.length() <= TITLE_LIMIT ? title : title.substring(0, TITLE_LIMIT);
    }

    /**
     * 推送正文只放文字摘要与详情页链接，不放图片地址：
     * dev 环境下上传地址是相对路径（file.access-url-prefix=/uploads/），在微信里无法解析。
     *
     * <p>正文是 Markdown，而描述与联系方式完全由提交者控制。若不处理，提交者可以塞入
     * 图片语法做打开追踪、或伪造一个指向站外的链接来钓鱼开发者。这里统一包成代码块 /
     * 行内代码，让其中的 Markdown 语法失效（围栏长度按内容动态加长，避免被反引号提前闭合）。
     */
    private String buildBody(Feedback feedback, String detailUrl) {
        StringBuilder body = new StringBuilder();
        body.append("**类型**：").append(FeedbackService.labelOf(feedback.getType())).append("\n\n");
        body.append("**提交者**：")
            .append(feedback.getSubmitterId() == null ? "匿名用户" : "用户 #" + feedback.getSubmitterId())
            .append("\n\n");
        if (StringUtils.hasText(feedback.getContact())) {
            body.append("**联系方式**：").append(inlineCode(feedback.getContact())).append("\n\n");
        }
        int imageCount = FeedbackService.parseImageUrls(feedback.getImageUrls()).size();
        if (imageCount > 0) {
            body.append("**截图**：").append(imageCount).append(" 张\n\n");
        }
        body.append("**描述**\n\n");
        body.append(fencedCode(StringUtils.hasText(feedback.getDescription())
            ? feedback.getDescription() : "（无）"));
        body.append("\n\n---\n\n");
        if (StringUtils.hasText(detailUrl)) {
            body.append("[在浏览器中打开反馈详情](").append(detailUrl).append(")\n");
        }
        return body.toString();
    }

    /** 围栏代码块，围栏比内容里最长的一串反引号更长，保证不会被提前闭合。 */
    static String fencedCode(String text) {
        String fence = "```";
        while (text.contains(fence)) {
            fence = fence + "`";
        }
        return fence + "\n" + text + "\n" + fence;
    }

    /** 行内代码；内容以反引号开头或结尾时按 CommonMark 要求补一个空格。 */
    static String inlineCode(String text) {
        String fence = "`";
        while (text.contains(fence)) {
            fence = fence + "`";
        }
        String pad = text.startsWith("`") || text.endsWith("`") ? " " : "";
        return fence + pad + text + pad + fence;
    }
}
