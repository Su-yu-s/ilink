package cn.ilink.teamagent;

import cn.ilink.service.kg.AgenticGraphRagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** pi Agent 的 SSE 事件转译与失败处理。 */
class PiAgentClientTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void translatesToolProgressAndAnswerEvents() throws Exception {
        startExchange("""
            event: run.started
            data: {"runId":99}

            event: tool.started
            data: {"tool":"kg_search_entities","callId":"c1"}

            event: progress
            data: {"stage":"EVIDENCE","safeText":"第 1 轮证据核验"}

            event: answer
            data: {"answer":"挑战杯","rounds":1,"toolCalls":1}

            event: done
            data: {"ok":true}

            """);
        RecordingListener listener = new RecordingListener();
        TeamScopedGraphRagAdapter.Result result = client().query(
            "问题", scope(), 99L, listener);

        assertEquals("挑战杯", result.answer());
        assertEquals(1, result.rounds());
        assertEquals(1, result.toolCalls());
        assertEquals(List.of("action:kg_search_entities", "observation:第 1 轮证据核验"),
            listener.events);
        assertTrue(listener.finalized);
    }

    @Test
    void errorEventFailsInsteadOfReturningEmptyAnswer() throws Exception {
        startExchange("""
            event: error
            data: {"code":"AGENT_FAILED","message":"上游不可用"}

            """);
        assertThrows(IllegalStateException.class, () ->
            client().query("问题", scope(), 99L, new RecordingListener()));
    }

    @Test
    void non200ResponseFails() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent/run", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        TeamAgentProperties properties = properties();
        properties.getPiAgent().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        PiAgentClient client = new PiAgentClient(properties, new ObjectMapper());
        assertThrows(IllegalStateException.class, () ->
            client.query("问题", scope(), 99L, new RecordingListener()));
    }

    private PiAgentClient client() {
        return new PiAgentClient(properties(), new ObjectMapper());
    }

    private TeamAgentProperties properties() {
        TeamAgentProperties properties = new TeamAgentProperties();
        properties.getPiAgent().setEnabled(true);
        properties.getPiAgent().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getPiAgent().setToken("test-token");
        properties.getPiAgent().setReadTimeoutSeconds(30);
        return properties;
    }

    private void startExchange(String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent/run", exchange -> {
            exchange.getResponseHeaders().add("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
    }

    private static TeamAgentScope scope() {
        return new TeamAgentScope(8L, 1L, "测试团队", "TEAMING", TeamAgentScope.TeamRole.STUDENT,
            TeamAgentScope.TaskReadMode.ALL, null, null, null, null, java.time.Instant.now());
    }

    private static final class RecordingListener
        implements AgenticGraphRagService.AgenticStreamListener {
        private final List<String> events = new ArrayList<>();
        private boolean finalized;

        @Override
        public void onThought(int round, String text) { }

        @Override
        public void onAction(int round, String tool, String paramsJson) {
            events.add("action:" + tool);
        }

        @Override
        public void onObservation(int round, String text) {
            events.add("observation:" + text);
        }

        @Override
        public void onFinal(String answer, Object citations, Object trace) {
            finalized = true;
        }
    }
}
