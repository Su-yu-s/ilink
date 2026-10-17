package cn.ilink.service.ai;

import cn.ilink.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
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
        /** 模型的思考过程（推理模型才返回，可能为 null） */
        public final String reasoning;
        public final Integer promptTokens;
        public final Integer completionTokens;
        public final String finishReason;

        public AiChatResult(String content, Integer promptTokens, Integer completionTokens) {
            this(content, null, promptTokens, completionTokens);
        }

        public AiChatResult(String content, String reasoning, Integer promptTokens, Integer completionTokens) {
            this(content, reasoning, promptTokens, completionTokens, null);
        }

        public AiChatResult(String content, String reasoning, Integer promptTokens, Integer completionTokens,
                            String finishReason) {
            this.content = content;
            this.reasoning = reasoning;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.finishReason = finishReason;
        }
    }

    private final HttpClient deadlineClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AiProperties properties;
    private final RestTemplate restTemplate;
    /** 流式专用：读超时更宽松，见构造函数注释 */
    private final RestTemplate streamRestTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiClient(AiProperties properties) {
        this.properties = properties;
        this.restTemplate = buildTemplate(properties.getTimeoutMs());
        // 单块读取超时在流式下必须放宽：推理模型会在「想完了、开始写」这类阶段停顿几十秒，
        // 一次停顿超过 60 秒就会把已经吐了一半的回答判死。真正的卡死由 SSE 超时兜底。
        this.streamRestTemplate = buildTemplate(Math.max(properties.getTimeoutMs(), 120_000));
    }

    private static RestTemplate buildTemplate(int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(Math.max(10_000, readTimeoutMs));
        return new RestTemplate(factory);
    }

    /** 流式增量回调：reasoning 与 content 各自逐块推送。 */
    public interface StreamListener {
        void onReasoning(String delta);

        void onContent(String delta);
    }

    /** 构造请求体（chat 与 streamChat 共用，避免两侧参数漂移）。 */
    ObjectNode buildRequestBody(List<Map<String, String>> messages, int maxTokens) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getModel());
        ArrayNode messagesNode = body.putArray("messages");
        for (Map<String, String> message : messages) {
            ObjectNode node = messagesNode.addObject();
            node.put("role", message.get("role"));
            node.put("content", message.get("content"));
        }
        body.put("max_tokens", maxTokens);
        body.put("temperature", properties.getTemperature());
        // Qwen 3.7 系列默认开思考模式（单次 6~7 秒起步，且思考内容破坏结构化输出格式）。
        // 关闭后 0.5 秒级。该参数为 DashScope 专属，其他 OpenAI 兼容端点若不认识可移除此行。
        body.put("enable_thinking", false);
        return body;
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey().trim());
        return headers;
    }

    private int cappedMaxTokens(int maxTokens) {
        return Math.min(Math.max(maxTokens, 64), properties.getMaxTokens());
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

        ChatRequestBudget budget = ChatRequestBudget.current();
        int outputLimit = cappedMaxTokens(maxTokens);
        if (budget != null) outputLimit = budget.completionLimit(outputLimit);
        ObjectNode body = buildRequestBody(messages, outputLimit);
        ChatRequestBudget.Reservation reservation = budget == null ? null
            : budget.reserve(promptReservation(messages), body.path("max_tokens").asInt());

        Integer promptUsed = null, completionUsed = null;
        boolean successful = false;
        try {
            ResponseEntity<String> response = budget == null ? restTemplate.postForEntity(
                properties.getBaseUrl().trim() + "/chat/completions",
                new HttpEntity<>(body.toString(), buildHeaders()),
                String.class) : postWithDeadline(body, budget);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode usage = root.path("usage");
            promptUsed = usageValue(usage, "prompt_tokens");
            completionUsed = usageValue(usage, "completion_tokens");
            if (reservation != null) reservation.settle(promptUsed, completionUsed);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("AI 响应缺少 choices: {}", response.getStatusCodeValue());
                throw new AiUnavailableException("AI 返回内容为空");
            }
            JsonNode message = choices.get(0).path("message");
            String content = message.path("content").asText("");
            if (content == null || content.trim().isEmpty()) {
                throw new AiUnavailableException("AI 返回内容为空");
            }
            // 推理模型会在 reasoning_content 里给出思考过程；非推理模型该字段缺失，取空即可
            String reasoning = message.path("reasoning_content").asText("");
            successful = true;
            return new AiChatResult(
                content,
                reasoning == null || reasoning.trim().isEmpty() ? null : reasoning.trim(),
                usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null,
                usage.hasNonNull("completion_tokens") ? usage.get("completion_tokens").asInt() : null,
                choices.get(0).path("finish_reason").asText(null));
        } catch (ChatRequestBudget.BudgetStopException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // 超时 / 连接失败
            log.warn("AI 服务连接失败: {}", e.getMessage());
            throw new AiUnavailableException("AI 服务连接超时");
        } catch (RestClientResponseException e) {
            try {
                JsonNode usage = objectMapper.readTree(e.getResponseBodyAsString()).path("usage");
                promptUsed = usageValue(usage, "prompt_tokens");
                completionUsed = usageValue(usage, "completion_tokens");
                if (reservation != null) reservation.settle(promptUsed, completionUsed);
            } catch (ChatRequestBudget.BudgetStopException stop) { throw stop; }
            catch (Exception ignored) { /* close retains the reservation and marks unknown usage */ }
            // 供应商返回 4xx/5xx，不把响应体细节抛给用户
            log.warn("AI 服务返回异常状态 {}: {}", e.getRawStatusCode(), e.getStatusText());
            throw new AiUnavailableException("AI 服务暂时不可用");
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI 调用解析失败: {}", e.getMessage());
            throw new AiUnavailableException("AI 返回内容无法解析");
        } finally {
            if (reservation != null) reservation.close();
            new ChatUsage(promptUsed, completionUsed, successful).publish();
        }
    }

    private static Integer usageValue(JsonNode usage, String key) {
        JsonNode value = usage.path(key);
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0 ? value.intValue() : null;
    }

    /** Conservative scheduling reservation, NOT supplier usage or an exact server tokenizer.
     * Qwen text uses the SDK vocabulary; extra framing allowance must be calibrated per model.
     * Any actual overrun is recorded as a hard defect and blocks further calls. */
    int promptReservation(List<Map<String, String>> messages) {
        long bound = 1024;
        for (Map<String, String> message : messages) {
            bound += 64;
            for (String key : List.of("role", "content"))
                bound += properties.getModel().startsWith("qwen")
                    ? QwenTokenCounter.count(message.getOrDefault(key, ""))
                    : Normalizer.normalize(message.getOrDefault(key, ""), Normalizer.Form.NFC)
                        .getBytes(StandardCharsets.UTF_8).length;
        }
        return (int) Math.min(Integer.MAX_VALUE, bound);
    }

    private ResponseEntity<String> postWithDeadline(ObjectNode body, ChatRequestBudget budget) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl().trim() + "/chat/completions"))
            .timeout(Duration.ofMillis(Math.min(properties.getTimeoutMs(), budget.remainingMillis())))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + properties.getApiKey().trim())
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response = deadlineClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RestClientResponseException("Supplier error", response.statusCode(), "Supplier error",
                null, response.body().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
        return ResponseEntity.status(response.statusCode()).body(response.body());
    }

    /**
     * 流式对话补全：逐块回调 reasoning/content，同时累计完整结果返回。
     * 思考过程与正文分属两个增量流，调用方可以据此「边思考边显示」。
     */
    public AiChatResult streamChat(List<Map<String, String>> messages, int maxTokens, StreamListener listener) {
        if (ChatRequestBudget.current() != null) {
            throw new ChatRequestBudget.BudgetStopException("STREAM_USAGE_UNSUPPORTED");
        }
        if (!properties.isConfigured()) {
            throw new AiUnavailableException("AI 服务未配置");
        }

        ObjectNode body = buildRequestBody(messages, cappedMaxTokens(maxTokens));
        body.put("stream", true);

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();

        try {
            streamRestTemplate.execute(
                // 必须以 URI 传入：execute(String) 会把地址当 URI 模板再编码一次
                URI.create(properties.getBaseUrl().trim() + "/chat/completions"),
                HttpMethod.POST,
                request -> {
                    request.getHeaders().putAll(buildHeaders());
                    request.getHeaders().setAccept(Collections.singletonList(MediaType.TEXT_EVENT_STREAM));
                    request.getBody().write(body.toString().getBytes(StandardCharsets.UTF_8));
                },
                response -> {
                    try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data:")) {
                                continue;
                            }
                            String payload = line.substring("data:".length()).trim();
                            if (payload.isEmpty()) {
                                continue;
                            }
                            if ("[DONE]".equals(payload)) {
                                break;
                            }
                            appendDelta(payload, content, reasoning, listener);
                        }
                    }
                    return null;
                });
        } catch (ResourceAccessException e) {
            log.warn("AI 流式连接失败: {}", e.getMessage());
            throw new AiUnavailableException("AI 服务连接超时");
        } catch (RestClientResponseException e) {
            log.warn("AI 流式返回异常状态 {}: {}", e.getRawStatusCode(), e.getStatusText());
            throw new AiUnavailableException("AI 服务暂时不可用");
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI 流式调用解析失败: {}", e.getMessage());
            throw new AiUnavailableException("AI 返回内容无法解析");
        }

        if (content.length() == 0 && reasoning.length() == 0) {
            throw new AiUnavailableException("AI 返回内容为空");
        }
        return new AiChatResult(content.toString(),
            reasoning.length() == 0 ? null : reasoning.toString().trim(), null, null);
    }

    /** 解析单个 SSE 分片并累加；单块损坏只跳过，不影响整条流。包级可见以便单测。 */
    void appendDelta(String payload, StringBuilder content, StringBuilder reasoning,
                     StreamListener listener) {
        JsonNode delta;
        try {
            delta = objectMapper.readTree(payload).path("choices").path(0).path("delta");
        } catch (Exception e) {
            log.debug("忽略无法解析的流式分片: {}", e.getMessage());
            return;
        }
        String reason = delta.path("reasoning_content").asText("");
        if (reason != null && !reason.isEmpty()) {
            reasoning.append(reason);
            if (listener != null) {
                listener.onReasoning(reason);
            }
        }
        String text = delta.path("content").asText("");
        if (text != null && !text.isEmpty()) {
            content.append(text);
            if (listener != null) {
                listener.onContent(text);
            }
        }
    }
}
