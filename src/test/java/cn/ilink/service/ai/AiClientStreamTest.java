package cn.ilink.service.ai;

import cn.ilink.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式分片解析。SSE 的 reasoning / content 是两个独立增量流，
 * 拼错一处就会出现「思考文字混进答案」或「答案丢字」。
 */
class AiClientStreamTest {

    private final AiClient client = new AiClient(new AiProperties());

    private static class Collector implements AiClient.StreamListener {
        final List<String> reasoning = new ArrayList<>();
        final List<String> content = new ArrayList<>();

        @Override
        public void onReasoning(String delta) {
            reasoning.add(delta);
        }

        @Override
        public void onContent(String delta) {
            content.add(delta);
        }
    }

    @Test
    void appendDeltaSplitsReasoningFromContent() {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Collector collector = new Collector();

        client.appendDelta(
            "{\"choices\":[{\"delta\":{\"reasoning_content\":\"先看\"}}]}", content, reasoning, collector);
        client.appendDelta(
            "{\"choices\":[{\"delta\":{\"reasoning_content\":\"资料。\"}}]}", content, reasoning, collector);
        client.appendDelta(
            "{\"choices\":[{\"delta\":{\"content\":\"# 结论\"}}]}", content, reasoning, collector);
        client.appendDelta(
            "{\"choices\":[{\"delta\":{\"content\":\"如下。\"}}]}", content, reasoning, collector);

        assertEquals("先看资料。", reasoning.toString());
        assertEquals("# 结论如下。", content.toString());
        // 增量必须原样回调，前端要靠它「边思考边输出」
        assertEquals(List.of("先看", "资料。"), collector.reasoning);
        assertEquals(List.of("# 结论", "如下。"), collector.content);
    }

    @Test
    void appendDeltaIgnoresRoleOnlyAndEmptyChunks() {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Collector collector = new Collector();

        client.appendDelta("{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
            content, reasoning, collector);

        assertEquals("", content.toString());
        assertEquals("", reasoning.toString());
        assertTrue(collector.content.isEmpty());
        assertTrue(collector.reasoning.isEmpty());
    }

    @Test
    void appendDeltaSurvivesBrokenChunk() {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Collector collector = new Collector();

        // 半截 JSON：跳过即可，不能把整条流带崩
        client.appendDelta("{\"choices\":[{\"delta\":{\"content\":\"截", content, reasoning, collector);
        client.appendDelta("{\"choices\":[{\"delta\":{\"content\":\"完整\"}}]}", content, reasoning, collector);

        assertEquals("完整", content.toString());
    }

    @Test
    void appendDeltaToleratesMissingChoices() {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();

        client.appendDelta("{\"usage\":{\"prompt_tokens\":1}}", content, reasoning, new Collector());

        assertEquals("", content.toString());
        assertEquals("", reasoning.toString());
    }
}
