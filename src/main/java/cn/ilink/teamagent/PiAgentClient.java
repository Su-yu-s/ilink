package cn.ilink.teamagent;

import cn.ilink.service.kg.AgenticGraphRagService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用 pi Agent 运行时的 HTTP/SSE 入口。
 *
 * 只负责传输与事件转译：检索、权限、动作仍由 Java 侧工具端点执行。
 * 任何失败都抛异常，由 {@link TeamScopedGraphRagAdapter} 决定是否回退。
 */
@Component
public class PiAgentClient {
    private static final Logger log = LoggerFactory.getLogger(PiAgentClient.class);

    private final TeamAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PiAgentClient(TeamAgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getPiAgent().getConnectTimeoutMs()))
            .build();
    }

    public TeamScopedGraphRagAdapter.Result query(String question, TeamAgentScope scope, Long runId,
                                                  AgenticGraphRagService.AgenticStreamListener listener) {
        TeamAgentProperties.PiAgent config = properties.getPiAgent();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("teamId", scope.teamId());
            payload.put("userId", scope.userId());
            payload.put("runId", runId == null ? 0 : runId);
            payload.put("question", question);
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.getBaseUrl() + "/v1/agent/run"))
                .timeout(Duration.ofSeconds(config.getReadTimeoutSeconds()))
                .header("content-type", "application/json")
                .header("x-ilink-internal-token", config.getToken())
                .POST(HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("pi agent 返回 " + response.statusCode());
            }
            return readStream(response.body(), listener, config.getReadTimeoutSeconds(),
                scope.teamId(), scope.userId());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("pi agent 调用被中断", interrupted);
        } catch (Exception failed) {
            throw new IllegalStateException("pi agent 调用失败：" + failed.getMessage(), failed);
        }
    }

    private TeamScopedGraphRagAdapter.Result readStream(
        InputStream stream, AgenticGraphRagService.AgenticStreamListener listener, int timeoutSeconds,
        Long teamId, Long userId) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        String answer = "";
        int rounds = 0;
        int toolCalls = 0;
        String event = null;
        StringBuilder data = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("pi agent 响应超时");
                }
                if (line.isEmpty()) {
                    if (event != null && data.length() > 0) {
                        JsonNode node = objectMapper.readTree(data.toString());
                        switch (event) {
                            case "tool.started" -> listener.onAction(rounds + 1,
                                node.path("tool").asText("tool"), "");
                            case "progress" -> listener.onObservation(rounds + 1,
                                node.path("safeText").asText(""));
                            case "answer" -> {
                                answer = node.path("answer").asText("");
                                rounds = node.path("rounds").asInt(0);
                                toolCalls = node.path("toolCalls").asInt(0);
                            }
                            case "error" -> throw new IllegalStateException(
                                "pi agent 错误：" + node.path("message").asText("unknown"));
                            default -> { }
                        }
                    }
                    event = null;
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                }
            }
        }
        if (answer.isBlank()) {
            throw new IllegalStateException("pi agent 没有返回答案");
        }
        listener.onFinal(answer, List.of(), List.of());
        log.info("pi agent 完成：team={} user={} rounds={} tools={}",
            teamId, userId, rounds, toolCalls);
        return new TeamScopedGraphRagAdapter.Result(answer, List.of(), rounds, toolCalls, false);
    }
}
