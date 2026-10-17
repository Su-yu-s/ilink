package cn.ilink.service.kg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in replay of the saved v10 E20 draft against the production citation masker.
 *
 * <p>The 100-question v10 run judged E20 as {@code UNKNOWN_ENTITY:#171717} because a Markdown
 * table cell held a CSS colour literal. The masker was fixed afterwards but the fix landed after
 * that run's {@code sourceHash}, so the fix has never been proven against the real saved draft.
 * This replay closes that gap without any model, database or scorer call.
 *
 * <p>Run with:
 * <pre>
 * mvn test -DkgColorReplay=true -DkgColorReplayFile=&lt;batch file with E20&gt; \
 *   -Dtest=SavedColorCitationReplayTest
 * </pre>
 */
@EnabledIfSystemProperty(named = "kgColorReplay", matches = "true")
class SavedColorCitationReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Production entity-reference shape used by AnswerEvidenceVerifier for the UNKNOWN_ENTITY check. */
    private static final java.util.regex.Pattern ENTITY_REF =
        java.util.regex.Pattern.compile("(?i)(?<![\\p{L}\\p{N}])#(\\d{1,9})(?![\\p{L}\\p{N}])");

    /** Chunk references are written with a literal {@code c} prefix: {@code c#262}. */
    private static final java.util.regex.Pattern CHUNK_REF =
        java.util.regex.Pattern.compile("(?i)c#(\\d{1,9})(?![\\p{L}\\p{N}])");

    @Test
    void savedE20TableColoursAreMaskedButRealChunkReferencesSurvive() throws Exception {
        JsonNode row = loadSavedRow("E20");

        // The historical UNKNOWN_ENTITY:#171717 came from the GENERATE draft, not the final
        // clarification text: the draft is a Markdown table whose value cells are CSS colours.
        String answer = row.path("b3_generationResult").path("draft").asText();
        assertFalse(answer.isBlank(), "The saved E20 row must carry the real generation draft.");
        assertEquals("stop", row.path("b3_generationResult").path("finishReason").asText(),
            "The draft must have finished normally; a truncated draft is not a valid citation control.");

        assertTrue(answer.contains("#171717"), "The saved E20 draft must still contain the literal #171717.");
        assertTrue(answer.contains("#fafafa"), "The saved E20 draft must still contain the literal #fafafa.");

        String masked = CitationText.forReferences(answer);

        // 1. Colour literals are masked in both plain and table-cell positions.
        assertFalse(masked.contains("#171717"), "Table colour #171717 must be masked.");
        assertFalse(masked.contains("#fafafa"), "Table colour #fafafa must be masked.");

        // 2. The mask is offset-preserving: same length, so downstream coordinates stay valid.
        assertEquals(answer.length(), masked.length(), "Masking must preserve length and offsets.");

        // 3. Real evidence references in the same table row must survive and still be extractable.
        List<String> chunkRefs = new ArrayList<>();
        var chunkMatcher = CHUNK_REF.matcher(masked);
        while (chunkMatcher.find()) {
            chunkRefs.add(chunkMatcher.group(1));
        }
        assertTrue(chunkRefs.contains("262"), "Real chunk reference c#262 must survive masking, got " + chunkRefs);
        assertTrue(chunkRefs.contains("341"), "Real chunk reference c#341 must survive masking, got " + chunkRefs);

        // 4. The historical failure must be gone: no colour literal may surface as an entity id.
        List<String> entityRefs = new ArrayList<>();
        var matcher = ENTITY_REF.matcher(masked);
        while (matcher.find()) {
            entityRefs.add(matcher.group(1));
        }
        assertFalse(entityRefs.contains("171717"), "#171717 must never be treated as an entity reference.");
        assertFalse(entityRefs.contains("fafafa"), "#fafafa must never be treated as an entity reference.");
        assertTrue(entityRefs.isEmpty(),
            "The masked draft must expose no bare numeric entity reference at all, got " + entityRefs);
    }

    private static JsonNode loadSavedRow(String caseId) throws Exception {
        String configured = System.getProperty("kgColorReplayFile", "");
        assertFalse(configured.isBlank(), "Set -DkgColorReplayFile to a saved batch containing " + caseId + ".");
        Path source = Path.of(configured).toAbsolutePath().normalize();
        assertFalse(source.toString().replace('\\', '/').toLowerCase(java.util.Locale.ROOT)
            .contains("/holdout-v9-custody/private"), "Sealed holdout files must not be read.");
        assertTrue(Files.isRegularFile(source), "Saved batch does not exist: " + source);
        JsonNode root = JSON.readTree(Files.readAllBytes(source.toRealPath()));
        assertTrue(root.isArray(), "The saved evaluation batch must be an array.");
        List<JsonNode> matches = new ArrayList<>();
        root.forEach(row -> {
            if (caseId.equals(row.path("id").asText())) {
                matches.add(row);
            }
        });
        assertEquals(1, matches.size(), "The saved batch must contain exactly one " + caseId + " row.");
        return matches.get(0);
    }
}
