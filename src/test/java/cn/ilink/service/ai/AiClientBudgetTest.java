package cn.ilink.service.ai;

import cn.ilink.config.AiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AiClientBudgetTest {
    private static final List<Map<String, String>> MESSAGES = List.of(Map.of("role", "user", "content", "测试"));

    @Test void accountsUsageEvenWhenChoicesAreMissingAndBlocksUnknownUsageRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int n = requests.incrementAndGet();
            byte[] body = (n == 1 ? "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}"
                : "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AiProperties properties = new AiProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setApiKey("test-key");
            properties.setModel("qwen3.7-flash");
            var client = new AiClient(properties);
            var budget = new ChatRequestBudget();
            try (var scope = budget.bind(); var stage = ChatRequestBudget.stage(ChatRequestBudget.Stage.REACT)) {
                assertThrows(AiClient.AiUnavailableException.class, () -> client.chat(MESSAGES, 64));
                assertEquals(12, budget.snapshot().get("supplierTokensKnown"));
                assertThrows(ChatRequestBudget.BudgetStopException.class, () -> client.chat(MESSAGES, 64));
                assertThrows(ChatRequestBudget.BudgetStopException.class, () -> client.chat(MESSAGES, 64));
                assertEquals(2, requests.get());
                assertEquals(1, budget.snapshot().get("unknownUsageRequests"));
            }
        } finally { server.stop(0); }
    }
}
