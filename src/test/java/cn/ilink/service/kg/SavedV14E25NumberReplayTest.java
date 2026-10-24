package cn.ilink.service.kg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zero-model replay of v14 E25. The generator added px to viewport numbers that are bare in the
 * cited source. The exception remains limited to a source viewport pair and never becomes general
 * unit normalization.
 */
@EnabledIfSystemProperty(named = "kgV14E25Replay", matches = "true")
class SavedV14E25NumberReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void savedDraftAndExpandedSeparatorFormsPassWithoutWeakeningUnrelatedNumbers() throws Exception {
        Path source = requiredPath("kgV14E25ReplayFile");
        byte[] before = Files.readAllBytes(source);
        JsonNode row = onlyCase(source, "E25");
        String question = row.path("question").asText();
        String draft = row.path("b3_generationResult").path("draft").asText();
        assertFalse(draft.isBlank());
        assertEquals("stop", row.path("b3_generationResult").path("finishReason").asText());
        assertTrue(draft.contains("1440px / 1024px"));

        EvidenceLedger ledger = SavedV13FoundationReplayTest.replayLedger(row);
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion(question);
        AnswerEvidenceVerifier verifier = new AnswerEvidenceVerifier();

        var saved = verifier.verify(question, draft, ledger, plan);
        assertTrue(saved.supported(), saved.reasons().toString());
        assertFalse(saved.reasons().contains("UNSUPPORTED_EXACT_FACT:1440px"));
        assertFalse(saved.reasons().contains("UNSUPPORTED_EXACT_FACT:1024px"));

        for (String pair : List.of("1440×1024", "1440*1024", "1440 x 1024", "1440/1024 px")) {
            String answer = "桌面端视口为" + pair + "，固定高度72px；移动端375px，响应式高度56px。c#264";
            var result = verifier.verify(question, answer, ledger, plan);
            assertTrue(result.supported(), pair + " -> " + result.reasons());
        }

        for (String answer : List.of(
                "桌面端视口为1440px / 999px。c#264",
                "桌面端视口为1441px / 1024px。c#264",
                "桌面端视口为1440em。c#264")) {
            var result = verifier.verify(question, answer, ledger, plan);
            assertFalse(result.supported(), answer);
            assertTrue(result.reasons().toString().contains("UNSUPPORTED_VIEWPORT"),
                answer + " -> " + result.reasons());
        }
        var bare = verifier.verify(question, "页面展示了1440，c#264", ledger, plan);
        assertFalse(bare.supported());
        assertTrue(bare.reasons().contains("UNSUPPORTED_VIEWPORT_NUMBER:1440"), bare.reasons().toString());

        assertArrayEquals(before, Files.readAllBytes(source), "The saved input batch must not change");
    }

    private static JsonNode onlyCase(Path source, String id) throws Exception {
        JsonNode root = JSON.readTree(Files.readAllBytes(source));
        assertTrue(root.isArray());
        List<JsonNode> matches = java.util.stream.StreamSupport.stream(root.spliterator(), false)
            .filter(row -> id.equals(row.path("id").asText())).toList();
        assertEquals(1, matches.size());
        return matches.get(0);
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property, "");
        assertFalse(value.isBlank(), "Set -D" + property + " to the saved v14 batch2.json");
        Path path = Path.of(value).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(path), "Saved batch does not exist: " + path);
        return path;
    }
}
