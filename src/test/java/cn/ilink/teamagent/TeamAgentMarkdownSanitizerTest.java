package cn.ilink.teamagent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamAgentMarkdownSanitizerTest {

    @Test
    void removesExecutableMarkupImagesAndRawLinks() {
        String raw = """
            ## 结论
            <script>alert(1)</script>
            ![封面](javascript:alert(1))
            请查看[竞赛规则](https://evil.example/collect?token=secret)。
            <iframe src="https://evil.example"></iframe>
            """;

        String safe = TeamAgentMarkdownSanitizer.sanitize(raw, 1000);

        assertFalse(safe.contains("<script"));
        assertFalse(safe.contains("<iframe"));
        assertFalse(safe.contains("javascript:"));
        assertFalse(safe.contains("evil.example"));
        assertTrue(safe.contains("竞赛规则"));
    }

    @Test
    void enforcesLengthAndNewlineBudget() {
        String safe = TeamAgentMarkdownSanitizer.sanitize("A\n\n\n\n\nB" + "x".repeat(30), 16);

        assertTrue(safe.length() <= 16);
        assertTrue(safe.endsWith("…"));
        assertFalse(safe.contains("\n\n\n\n"));
    }

    @Test
    void structuredCitationsAreWhitelistedAndNormalized() {
        List<Map<String, Object>> result = TeamAgentMarkdownSanitizer.sanitizeCitations(List.of(
            Map.of("kind", "TASK", "id", 12, "label", "<b>接口联调</b>",
                "generatedAt", "2026-10-23T01:02:03Z"),
            Map.of("kind", "PRIVATE_GRAPH", "id", 99, "label", "不应展示"),
            Map.of("kind", "TEAM_METRIC", "id", 20,
                "generatedAt", "not-an-instant")
        ));

        assertEquals(2, result.size());
        assertEquals("TASK", result.get(0).get("kind"));
        assertEquals("接口联调", result.get(0).get("label"));
        assertEquals("TEAM_METRIC", result.get(1).get("kind"));
        assertFalse(result.get(1).containsKey("generatedAt"));
    }
}
