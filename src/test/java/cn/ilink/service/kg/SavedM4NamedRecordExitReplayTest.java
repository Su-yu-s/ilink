package cn.ilink.service.kg;

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in replay of the saved 2026-10-17 M4 case against the current named-record source exit.
 *
 * <p>M4's saved verdict was {@code SEMANTIC_UNSUPPORTED:COVERAGE:...}: the collection claim was
 * unsupported, the facts were not. That verdict is handled before the {@code supported} branch, so a
 * source exit placed only on the supported path never applies to the case it was written for. This
 * replay rebuilds the ledger from the saved tool observations, takes the saved generation draft (the
 * text that existed before the partial-answer label was added) and runs the release check on the real
 * record. No model, database, scorer or source file is touched.
 *
 * <pre>
 * mvn test -DkgM4Replay=true -DkgM4ReplayFile=&lt;round1/batch8.json&gt; -Dtest=SavedM4NamedRecordExitReplayTest
 * </pre>
 */
@EnabledIfSystemProperty(named = "kgM4Replay", matches = "true")
class SavedM4NamedRecordExitReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PARTIAL_PREFIX =
        "以下依据原文说明相关内容，尚不能完整确认所问的图谱关联：\n";
    private static final Pattern CHUNK_REFERENCE = Pattern.compile("c#(\\d+)");
    private static final Pattern RELATION_REFERENCE = Pattern.compile("r#(\\d+)");

    /**
     * Mirrored copy of {@code GraphRagEvaluationTest.explicitlyIncomplete} (the public scorer's
     * completeness rule). Production code must not depend on the scorer, but a released answer must
     * not look like a self-declared partial result either. The scorer file hash is recorded so a
     * future divergence is visible in the artefact rather than silent.
     */
    private static final Pattern SCORER_INCOMPLETE = Pattern.compile(
        "供核对部分内容|结果仅覆盖本次可核对部分|以下列出已核对的部分结果"
            + "|(?:尚不能|无法|不能)(?:完整)?(?:确认|证实)(?:所问|上述)?的?图谱(?:关联|关系|集合)"
            + "|无法确认图谱中是否存在关联边"
            + "|(?:无法|不能|尚未)确认(?:上述|这些|所问)?(?:技能|实体|节点)[^。！？；]{0,100}"
            + "图谱中[^。！？；]{0,50}(?:关联|关系)");

    @Test
    void savedM4SourceAnswerIsNoLongerRelabelledAsAPartialAnswer() throws Exception {
        String configured = System.getProperty("kgM4ReplayFile", "");
        assertFalse(configured.isBlank(), "Set -DkgM4ReplayFile to the saved round1/batch8.json.");
        Path source = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(source), "Saved batch does not exist: " + source);
        byte[] before = Files.readAllBytes(source);
        JsonNode batch = JSON.readTree(before);
        assertTrue(batch.isArray(), "The saved evaluation batch must be an array.");

        List<JsonNode> rows = new ArrayList<>();
        batch.forEach(row -> { if ("M4".equals(row.path("id").asText())) rows.add(row); });
        assertEquals(1, rows.size(), "Exactly one saved M4 row is required.");
        JsonNode row = rows.get(0);
        String question = row.path("question").asText();
        String draft = row.path("b3_generationResult").path("draft").asText("");
        String savedAnswer = row.path("b3_answer").asText("");
        assertFalse(draft.isBlank(), "The saved generation draft is required.");

        // The historical outcome: the draft was released with a partial-answer label in front of it.
        assertEquals(PARTIAL_PREFIX + draft, savedAnswer,
            "The saved answer must still record the label this replay explains.");
        assertTrue(SCORER_INCOMPLETE.matcher(prose(savedAnswer)).find(),
            "The saved answer must still trip the public completeness rule.");

        List<String> reasons = JSON.convertValue(row.path("b3_answerVerification").path("reasons"),
            new TypeReference<List<String>>() { });
        Set<Long> usedChunks = referencedIds(draft, CHUNK_REFERENCE);
        Set<Long> usedRelations = referencedIds(draft, RELATION_REFERENCE);
        assertFalse(usedChunks.isEmpty(), "The draft must cite at least one source chunk.");
        assertTrue(usedRelations.isEmpty(), "The saved M4 draft must not rely on relation edges.");
        AnswerEvidenceVerifier.VerificationResult check = new AnswerEvidenceVerifier.VerificationResult(
            row.path("b3_answerVerification").path("supported").asBoolean(), reasons,
            Set.of(), usedRelations, usedChunks);
        assertTrue(check.coverageOnlyFailure(),
            "The saved verdict must be a coverage-only failure, i.e. the branch that predates the exit.");

        EvidenceLedger ledger = replayLedger(row);
        GraphAnswerScope.Scope scope = savedScope(row.path("b3_graphAnswerScope"));
        List<Map<String, Object>> trace = new ArrayList<>();
        AgenticGraphRagService.AgenticAnswer released =
            AgenticGraphRagService.sourceScopedRecordAnswer(question, draft, check, scope, ledger, trace, 0, 2);

        assertNotNull(released, "The real M4 draft must now reach the named-record source exit: "
            + "usedChunks=" + usedChunks + " reasons=" + reasons);
        assertTrue(released.answer.startsWith("依据《java面试的一些要点》（POST:5）的本次授权原文："),
            released.answer);
        assertTrue(released.answer.contains("| **动态代理** |"), "The released answer must keep the verified facts.");
        assertTrue(released.answer.contains("原文未提及静态代理"), "Factual source limits must remain visible.");
        assertFalse(released.answer.contains("图谱查询完整=false"),
            "Implementation completeness state must not be exposed in the user answer.");
        assertFalse(SCORER_INCOMPLETE.matcher(prose(released.answer)).find(),
            "The released wording must describe its basis without declaring itself incomplete.");
        assertEquals("SOURCE_SCOPED_RECORD_ANSWER", trace.get(0).get("answerMode"));
        assertEquals("PARTIAL", trace.get(0).get("graphCoverage"));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sampleKind", "SAVED_NAMED_RECORD_SOURCE_EXIT_REPLAY");
        report.put("sampleId", "M4");
        report.put("sourcePath", source.toString());
        report.put("originalBatchSha256", sha256(before));
        report.put("scorerSourceSha256", sha256(Files.readAllBytes(Path.of(
            "src/test/java/cn/ilink/service/kg/GraphRagEvaluationTest.java"))));
        report.put("historicalVerdict", Map.of("stage", row.path("b3_answerVerification").path("stage").asText(),
            "supported", check.supported(), "reasons", reasons));
        report.put("historicalBranch", "coverage-only failure -> partial-answer label (before the exit)");
        report.put("historicalAnswerLabel", PARTIAL_PREFIX.strip());
        report.put("scorerRejectedSavedAnswer", true);
        report.put("usedChunkIds", usedChunks);
        report.put("usedRelationIds", usedRelations);
        report.put("releasedPrefix", "依据《java面试的一些要点》（POST:5）的本次授权原文：");
        report.put("releasedAnswerSha256", sha256(released.answer.getBytes(StandardCharsets.UTF_8)));
        report.put("scorerRejectsReleasedAnswer", false);
        report.put("aiInvocations", 0);
        report.put("limitations", "Confirms only that the real saved M4 draft now reaches the named-record "
            + "source exit and that the released wording no longer trips the mirrored scorer rule. It does "
            + "not reproduce the run, does not re-verify the facts with a model and does not establish any "
            + "score change.");
        Path directory = Path.of("target/kg-m4-replay").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path output = directory.resolve("saved-M4-" + System.currentTimeMillis() + ".json");
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),
            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("Saved M4 named-record exit replay report: " + output);
        assertEquals(sha256(before), sha256(Files.readAllBytes(source)), "The saved batch was modified.");
    }

    private static String prose(String answer) {
        return answer.replaceAll("(?s)```.*?```|“[^”]*”|\"[^\"]*\"", "")
            .replaceAll("[*`_\\s]", "");
    }

    private static Set<Long> referencedIds(String text, Pattern pattern) {
        Set<Long> ids = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) ids.add(Long.parseLong(matcher.group(1)));
        return ids;
    }

    private static GraphAnswerScope.Scope savedScope(JsonNode node) {
        assertTrue(node.isObject(), "The saved graph answer scope is required.");
        Set<Long> relationIds = new LinkedHashSet<>();
        node.path("relationIds").forEach(id -> relationIds.add(id.asLong()));
        return new GraphAnswerScope.Scope(true, node.path("sourceOnly").asBoolean(),
            node.path("queryComplete").asBoolean(), node.path("inventoryComplete").asBoolean(),
            "", relationIds, node.path("candidateCount").asInt(), node.path("shownCount").asInt(),
            node.path("targetEntityUnresolved").asBoolean(false));
    }

    /** Rebuilds the run's ledger from the saved tool observations, without inventing identities. */
    private static EvidenceLedger replayLedger(JsonNode row) throws Exception {
        Map<Long, KgEntity> entities = new LinkedHashMap<>();
        Map<Long, KgDocumentChunk> chunks = new LinkedHashMap<>();
        List<Map.Entry<String, Map<String, Object>>> results = new ArrayList<>();
        String pendingAction = null;
        for (JsonNode step : row.path("b3_trace")) {
            if (step.hasNonNull("action")) {
                pendingAction = step.path("action").asText();
                continue;
            }
            if (pendingAction == null || !step.hasNonNull("observation")) continue;
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
                if (!item.isObject()) continue;
                KgEntity entity = new KgEntity();
                entity.setId(item.path("entityId").asLong());
                entity.setDisplayName(item.path("name").asText());
                entity.setEntityType(item.path("entityType").asText());
                entity.setScope(KgEntity.SCOPE_GLOBAL);
                entity.setStatus(KgEntity.STATUS_ACTIVE);
                entities.put(entity.getId(), entity);
            }
            for (JsonNode item : observation.path("chunks")) {
                if (!item.isObject()) continue;
                KgDocumentChunk chunk = new KgDocumentChunk();
                chunk.setId(item.path("chunkId").asLong());
                chunk.setDocType(item.path("docType").asText());
                chunk.setDocId(item.path("docId").asLong());
                chunk.setTitle(item.path("title").asText());
                chunk.setContent(item.path("text").asText());
                chunk.setScope(KgEntity.SCOPE_GLOBAL);
                chunks.put(chunk.getId(), chunk);
            }
            results.add(Map.entry(pendingAction,
                JSON.convertValue(observation, new TypeReference<Map<String, Object>>() { })));
            pendingAction = null;
        }
        KgEntityMapper entityMapper = mock(KgEntityMapper.class);
        KgDocumentChunkMapper chunkMapper = mock(KgDocumentChunkMapper.class);
        when(entityMapper.selectById(anyLong())).thenAnswer(invocation ->
            entities.get(((Number) invocation.getArgument(0)).longValue()));
        when(chunkMapper.selectById(anyLong())).thenAnswer(invocation ->
            chunks.get(((Number) invocation.getArgument(0)).longValue()));
        KgScopeService scopes = mock(KgScopeService.class);
        when(scopes.canSee(any(KgEntity.class), any())).thenReturn(true);
        when(scopes.canSee(any(), any(), any(), any())).thenReturn(true);
        EvidenceLedger ledger = new EvidenceLedger(entityMapper, mock(KgRelationMapper.class),
            chunkMapper, scopes);
        for (Map.Entry<String, Map<String, Object>> result : results)
            ledger.addToolResult(result.getKey(), result.getValue(), null);
        return ledger;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
