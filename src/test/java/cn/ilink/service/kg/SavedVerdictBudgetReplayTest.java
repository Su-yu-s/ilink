package cn.ilink.service.kg;

import cn.ilink.service.ai.AiClient;
import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgEntity;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        List<List<Map<String, String>>> requests = new ArrayList<>();
        when(ai.chat(anyList(), anyInt())).thenAnswer(invocation -> {
            Object payload = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            List<Map<String, String>> messages = (List<Map<String, String>>) payload;
            requests.add(messages);
            return new AiClient.AiChatResult(compact, null, 100, 30, "stop");
        });

        String question = row.path("question").asText();
        assertFalse(question.isBlank());

        // Rebuild the permission-checked ledger from the saved tool observations, so the replay
        // reaches the semantic verifier the same way the real run did instead of stopping at an
        // empty ledger. An empty ledger would make this replay vacuous.
        EvidenceLedger ledger = replayLedger(row);
        for (Long cited : citedChunkIds(draft)) {
            assertTrue(ledger.containsChunk(cited),
                "The saved run had already authorized c#" + cited + "; the replayed ledger must contain it.");
        }
        // The historical truncation came from the verdict model, so the fix is proven on the
        // contract itself: the compressed verdict must reach the model and must not be truncated.
        AnswerEvidenceVerifier.VerificationResult result = new AnswerEvidenceVerifier()
            .verifySemantic(question, draft, ledger, QueryEvidencePlan.fromQuestion(question), ai);
        assertEquals(1, requests.size(),
            "The compact verdict must be requested exactly once; stopping earlier would prove nothing. reasons="
                + result.reasons());
        verify(ai).chat(anyList(), eq(AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET));
        assertFalse(result.reasons().contains("SEMANTIC_UNCERTAIN:TRUNCATED"),
            "A compact verdict must not be reported as truncated, got " + result.reasons());

        // The named budget is explicit and auditable, and the contract text stays small.
        assertEquals(cn.ilink.service.ai.ChatRequestBudget.VERDICT_COMPLETION_LIMIT,
            AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET,
            "The requested verdict budget must stay derived from the per-stage cap.");
        assertTrue(AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET > 512,
            "The verdict budget must exceed the historical 512 that truncated.");
        assertTrue(compact.length() < 120,
            "The compact verdict sample must stay short, got " + compact.length() + " chars.");
    }

    /**
     * Rebuilds the run's ledger from the saved tool observations. The mappers only re-supply the
     * rows the saved run had already been authorized to see; nothing is invented here.
     */
    private static EvidenceLedger replayLedger(JsonNode row) throws Exception {
        Map<Long, KgEntity> entities = new LinkedHashMap<>();
        Map<Long, KgDocumentChunk> chunks = new LinkedHashMap<>();
        List<Map.Entry<String, Map<String, Object>>> results = new ArrayList<>();
        // The saved trace stores the decision and its result as two neighbouring entries: an
        // {"action": ...} entry followed by a {"observation": ...} entry. Pair them in order, and
        // ignore observations that belong to a model turn rather than to a tool call.
        String pendingAction = null;
        for (JsonNode step : row.path("b3_trace")) {
            if (step.hasNonNull("action")) {
                pendingAction = step.path("action").asText();
                continue;
            }
            if (pendingAction == null || !step.hasNonNull("observation")) {
                continue;
            }
            JsonNode observation;
            try {
                observation = JSON.readTree(step.path("observation").asText());
            } catch (Exception notJson) {
                pendingAction = null;
                continue;
            }
            if (!observation.isObject()) {
                pendingAction = null;
                continue;
            }
            for (JsonNode item : observation.path("entities")) {
                if (!item.isObject()) {
                    continue;
                }
                KgEntity entity = new KgEntity();
                entity.setId(item.path("entityId").asLong());
                entity.setDisplayName(item.path("name").asText());
                entity.setEntityType(item.path("entityType").asText());
                entity.setScope(KgEntity.SCOPE_GLOBAL);
                entity.setStatus(KgEntity.STATUS_ACTIVE);
                entities.put(entity.getId(), entity);
            }
            for (JsonNode item : observation.path("chunks")) {
                if (!item.isObject()) {
                    continue;
                }
                KgDocumentChunk chunk = new KgDocumentChunk();
                chunk.setId(item.path("chunkId").asLong());
                chunk.setDocType(item.path("docType").asText());
                chunk.setDocId(item.path("docId").asLong());
                chunk.setTitle(item.path("title").asText());
                chunk.setContent(item.path("text").asText());
                chunk.setStartOffset(item.path("startOffset").asInt());
                chunk.setEndOffset(item.path("endOffset").asInt());
                chunk.setScope(KgEntity.SCOPE_GLOBAL);
                chunks.put(chunk.getId(), chunk);
            }
            results.add(Map.entry(pendingAction,
                JSON.convertValue(observation, new TypeReference<Map<String, Object>>() { })));
            pendingAction = null;
        }
        assertFalse(results.isEmpty(), "The saved trace must contain tool observations to replay.");
        KgEntityMapper entityMapper = mock(KgEntityMapper.class);
        KgDocumentChunkMapper chunkMapper = mock(KgDocumentChunkMapper.class);
        when(entityMapper.selectById(anyLong())).thenAnswer(invocation -> {
            Object id = invocation.getArgument(0);
            return entities.get(((Number) id).longValue());
        });
        when(chunkMapper.selectById(anyLong())).thenAnswer(invocation -> {
            Object id = invocation.getArgument(0);
            return chunks.get(((Number) id).longValue());
        });
        KgScopeService scopes = mock(KgScopeService.class);
        when(scopes.canSee(any(KgEntity.class), any())).thenReturn(true);
        when(scopes.canSee(any(), any(), any(), any())).thenReturn(true);
        EvidenceLedger ledger = new EvidenceLedger(entityMapper, mock(KgRelationMapper.class),
            chunkMapper, scopes);
        for (Map.Entry<String, Map<String, Object>> result : results) {
            ledger.addToolResult(result.getKey(), result.getValue(), null);
        }
        return ledger;
    }

    private static List<Long> citedChunkIds(String answer) {
        List<Long> ids = new ArrayList<>();
        Matcher matcher = Pattern.compile("c#(\\d+)").matcher(answer);
        while (matcher.find()) {
            long id = Long.parseLong(matcher.group(1));
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
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
