package cn.ilink.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QwenTokenCounterTest {
    @Test void matchesPinnedTiktokenReferenceIncludingUnicodeAndWhitespace() throws Exception {
        try (var stream = getClass().getResourceAsStream("/tokenizers/qwen-conformance.json")) {
            var rows = new ObjectMapper().readTree(stream);
            assertEquals(109, rows.size());
            for (var row : rows) {
                assertEquals(row.path("count").asInt(), QwenTokenCounter.count(row.path("text").asText()),
                    row.path("text").asText());
            }
        }
    }
}
