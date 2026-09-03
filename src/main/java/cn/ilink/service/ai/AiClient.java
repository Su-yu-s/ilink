package cn.ilink.service.ai;

import cn.ilink.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 Chat Completions 客户端（Agnes）。
 * 仅服务端持有 Key；超时与 max_tokens 受控，失败抛出统一异常由上层降级。
 */
@Service
@Slf4j
public class AiClient {

    /** 对外统一异常：配置缺失 / 供应商故障 / 响应异常 */
    public static class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message) {
            super(message);
        }
    }

    public static class AiChatResult {
        public final String content;
        public final Integer promptTokens;
        public final Integer completionTokens;

        public AiChatResult(String content, Integer promptTokens, Integer completionTokens) {
            this.content = content;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }
    }

    private final AiProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiClient(AiProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(Math.max(10_000, properties.getTimeoutMs()));
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 发起一次对话补全。
     *
     * @param messages  消息列表，元素含 role/content
     * @param maxTokens 生成 token 上限（会被配置的硬上限截断）
     */
    public AiChatResult chat(List<Map<String, String>> messages, int maxTokens) {
        if (!properties.isConfigured()) {
            throw new AiUnavailableException("AI 服务未配置");
        }
        int cappedMaxTokens = Math.min(Math.max(maxTokens, 64), properties.getMaxTokens());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey().trim());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getModel());
        ArrayNode messagesNode = body.putArray("messages");
        for (Map<String, String> message : messages) {
            ObjectNode node = messagesNode.addObject();
            node.put("role", message.get("role"));
            node.put("content", message.get("content"));
        }
        body.put("max_tokens", cappedMaxTokens);
        body.put("temperature", 0.3);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                properties.getBaseUrl().trim() + "/chat/completions",
                new HttpEntity<>(body.toString(), headers),
                String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("AI 响应缺少 choices: {}", response.getStatusCodeValue());
                throw new AiUnavailableException("AI 返回内容为空");
            }
            String content = choices.get(0).path("message").path("content").asText("");
            if (content == null || content.trim().isEmpty()) {
                throw new AiUnavailableException("AI 返回内容为空");
            }
            JsonNode usage = root.path("usage");
            return new AiChatResult(
                content,
                usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null,
                usage.hasNonNull("completion_tokens") ? usage.get("completion_tokens").asInt() : null);
        } catch (ResourceAccessException e) {
            // 超时 / 连接失败
            log.warn("AI 服务连接失败: {}", e.getMessage());
            throw new AiUnavailableException("AI 服务连接超时");
        } catch (RestClientResponseException e) {
            // 供应商返回 4xx/5xx，不把响应体细节抛给用户
            log.warn("AI 服务返回异常状态 {}: {}", e.getRawStatusCode(), e.getStatusText());
            throw new AiUnavailableException("AI 服务暂时不可用");
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI 调用解析失败: {}", e.getMessage());
            throw new AiUnavailableException("AI 返回内容无法解析");
        }
    }
}
