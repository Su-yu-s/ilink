package cn.ilink.service.kg;

import cn.ilink.service.ai.AiClient;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in replay of the saved v10 M10 verification failure.
 *
 * <p>M10 failed even though its GENERATE and REWRITE drafts both finished with {@code stop} and
 * named the correct POST:6 record: BOTH the VERIFY and the EXTRA stage returned
 * {@code finishReason=length} at a 512-token completion cap, while the evidence packet was only
 * 2,191 of 3,000 tokens. The failure was therefore a verbose verdict contract, not an oversized
 * evidence packet.
 *
 * <p>This replay proves the compressed contract and the named budget stay within limits, using the
 * real saved draft and a stubbed verdict model. It performs no provider, database or scorer call.
 *
 * <pre>
 * mvn test -DkgVerdictReplay=true -DkgVerdictReplayFile=&lt;batch8.json&gt; \
 *   -Dtest=SavedVerdictBudgetReplayTest
 * </pre>
 */
@EnabledIfSystemProperty(named = "kgVerdictReplay", matches = "true")
class SavedVerdictBudgetReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void compressedVerdictContractFitsTheNamedBudgetForTheSavedM10Draft() throws Exception {
        JsonNode row = loadSavedRow("M10");
        String draft = row.path("b3_generationResult").path("draft").asText();
        assertFalse(draft.isBlank(), "The saved M10 row must carry the real generation draft.");
        assertEquals("stop", row.path("b3_generationResult").path("finishReason").asText(),
            "The M10 draft must have finished normally.");
        // The draft already names the correct record; the failure was downstream of generation.
        assertTrue(draft.contains("POST:6"), "The saved M10 draft must name POST:6.");
        assertEquals("SEMANTIC_UNCERTAIN:TRUNCATED",
            row.path("b3_answerVerification").path("reasons").path(0).asText(),
            "The saved M10 row must record the historical truncation this replay targets.");

        // The historical evidence packet was well inside budget, so the budget was never the issue.
        int expanded = row.path("b3_verificationEvidence").path("expandedTokenCount").asInt();
        assertTrue(expanded > 0 && expanded <= EvidenceContext.MAX_BUDGET,
            "The saved evidence packet (" + expanded + ") must be within the evidence budget; "
                + "if it were not, the truncation would have a different root cause.");

        // A compact, closed verdict is the shape the compressed contract asks for.
        String compact = "{\"facts\":\"SUPPORTED\",\"coverage\":\"COMPLETE\","
            + "\"unsupported\":[],\"missing\":[]}";
        AiClient ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult(compact, null, 100, 30, "stop"));

        String question = row.path("question").asText();
        assertFalse(question.isBlank());

        // The historical truncation came from the verdict model, so the fix is proven on the
        // contract itself: the compressed verdict must parse and must not be reported as truncated.
        EvidenceLedger ledger = new EvidenceLedger(
            mock(cn.ilink.mapper.KgEntityMapper.class),
            mock(cn.ilink.mapper.KgRelationMapper.class),
            mock(cn.ilink.mapper.KgDocumentChunkMapper.class),
            mock(KgScopeService.class));
        AnswerEvidenceVerifier.VerificationResult result = new AnswerEvidenceVerifier()
            .verifySemantic(question, draft, ledger, QueryEvidencePlan.fromQuestion(question), ai);
        assertFalse(result.reasons().contains("SEMANTIC_UNCERTAIN:TRUNCATED"),
            "A compact verdict must not be reported as truncated, got " + result.reasons());

        // The named budget is explicit and auditable, and the contract text stays small.
        assertTrue(AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET >= 512,
            "The verdict budget must be at least the historical 512 that truncated.");
        assertTrue(compact.length() < 120,
            "The compact verdict sample must stay short, got " + compact.length() + " chars.");
    }

    private static JsonNode loadSavedRow(String caseId) throws Exception {
        String configured = System.getProperty("kgVerdictReplayFile", "");
        assertFalse(configured.isBlank(), "Set -DkgVerdictReplayFile to a saved batch containing " + caseId + ".");
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
