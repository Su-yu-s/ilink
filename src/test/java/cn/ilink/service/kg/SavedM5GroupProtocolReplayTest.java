package cn.ilink.service.kg;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in replay of the saved v10 M5 draft against the current decision parser.
 *
 * <p>M5 failed with {@code CLASSIFICATION_TOO_MANY_GROUPS} although its draft was a well formed
 * closed partition: the old parser treated the reason codes as partition slots, so a five-group
 * partition that reused {@code MATCH} four times was rejected. This replay parses the unmodified
 * saved draft with the current parser and records the outcome. Only the parser runs: no model,
 * database, ledger, scorer or source write is involved, and compatibility of a saved draft is not
 * evidence of classification correctness.
 *
 * <pre>
 * mvn test -DkgM5Replay=true -DkgM5ReplayFile=&lt;round1/batch8.json&gt; -Dtest=SavedM5GroupProtocolReplayTest
 * </pre>
 */
@EnabledIfSystemProperty(named = "kgM5Replay", matches = "true")
class SavedM5GroupProtocolReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void savedM5PartitionIsNoLongerRejectedAsTooManyGroups() throws Exception {
        String configured = System.getProperty("kgM5ReplayFile", "");
        assertFalse(configured.isBlank(), "Set -DkgM5ReplayFile to the saved round1/batch8.json.");
        Path source = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(source), "Saved batch does not exist: " + source);
        byte[] before = Files.readAllBytes(source);
        JsonNode batch = JSON.readTree(before);
        assertTrue(batch.isArray(), "The saved evaluation batch must be an array.");

        List<JsonNode> rows = new ArrayList<>();
        batch.forEach(row -> { if ("M5".equals(row.path("id").asText())) rows.add(row); });
        assertEquals(1, rows.size(), "Exactly one saved M5 row is required.");
        JsonNode row = rows.get(0);
        assertEquals("candidate-decisions-v3", row.path("b3_classificationContract").asText(),
            "This replay targets the closed decision contract that was in force when M5 ran.");
        assertEquals(List.of("CLASSIFICATION_TOO_MANY_GROUPS"),
            JSON.convertValue(row.path("b3_answerVerification").path("reasons"),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { }),
            "The saved row must still record the historical rejection this replay explains.");

        GraphClassificationContract.Catalog catalog =
            savedCatalog(row.path("b3_classificationCatalog"), row.path("question").asText());
        Map<String, Object> generation = replay(row.path("b3_generationResult"), catalog);
        Map<String, Object> rewrite = replay(row.path("b3_rewriteResult"), catalog);

        assertEquals(0L, generation.get("tooManyGroupsFailures"));
        assertTrue((Boolean) generation.get("complete"),
            "The saved M5 partition must now clear the protocol boundary: " + generation.get("failures"));
        assertEquals(0L, rewrite.get("tooManyGroupsFailures"));
        assertFalse(((List<?>) generation.get("missingCandidateIds")).size() > 0,
            "A complete partition cannot omit candidates: " + generation.get("missingCandidateIds"));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sampleKind", "SAVED_M5_CLOSED_PARTITION_PROTOCOL_REPLAY");
        report.put("sampleId", "M5");
        report.put("sourcePath", source.toString());
        report.put("originalBatchSha256", sha256(before));
        report.put("contractSourceSha256", sha256(Files.readAllBytes(Path.of(
            "src/main/java/cn/ilink/service/kg/GraphClassificationContract.java"))));
        report.put("historicalRejection", "CLASSIFICATION_TOO_MANY_GROUPS");
        report.put("candidateCount", catalog.candidates().size());
        report.put("generation", generation);
        report.put("rewrite", rewrite);
        report.put("aiInvocations", 0);
        report.put("limitations", "Confirms only that the saved draft now clears the parser's group "
            + "cardinality and reason-code rules. It does not establish member evidence sufficiency, "
            + "classification correctness, new generation success or any score change.");
        Path directory = Path.of("target/kg-m5-replay").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path output = directory.resolve("saved-M5-" + System.currentTimeMillis() + ".json");
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),
            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("Saved M5 protocol replay report: " + output);
        assertEquals(sha256(before), sha256(Files.readAllBytes(source)), "The saved batch was modified.");
    }

    private static Map<String, Object> replay(JsonNode result, GraphClassificationContract.Catalog catalog)
        throws Exception {
        String draft = result.path("draft").asText("");
        assertFalse(draft.isBlank(), "The saved draft is required.");
        GraphClassificationContract.DecisionParsed parsed =
            GraphClassificationContract.parseDecisions(draft, catalog);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("draftSha256", sha256(draft.getBytes(StandardCharsets.UTF_8)));
        report.put("historicalFinishReason", result.path("finishReason").asText(""));
        report.put("complete", parsed.complete());
        report.put("failures", parsed.failures());
        report.put("tooManyGroupsFailures", parsed.failures().stream()
            .filter(failure -> failure.startsWith("CLASSIFICATION_TOO_MANY_GROUPS")
                || failure.startsWith("CLASSIFICATION_DUPLICATE_REASON")).count());
        report.put("missingCandidateIds", parsed.diagnosticsMetadata().get("missingCandidateIds"));
        report.put("semanticValidation", "NOT_RUN; parser boundary only");
        return report;
    }

    /** Rebuilds the saved catalog without synthesising identities or proof availability. */
    private static GraphClassificationContract.Catalog savedCatalog(JsonNode node, String question) {
        assertTrue(node.isObject(), "The saved classification catalog is required.");
        assertEquals("graph-classification-catalog-v1", node.path("policy").asText());
        assertTrue(node.path("requested").asBoolean());
        List<GraphClassificationContract.Candidate> candidates = new ArrayList<>();
        Set<List<Object>> identities = new LinkedHashSet<>();
        for (JsonNode candidate : node.path("candidates")) {
            int number = candidate.path("number").asInt();
            assertEquals(candidates.size() + 1, number, "Saved candidate numbering must be contiguous.");
            long anchor = candidate.path("anchorId").asLong();
            long other = candidate.path("otherId").asLong();
            String direction = candidate.path("direction").asText();
            String relationType = candidate.path("relationType").asText();
            assertTrue(identities.add(List.of(anchor, other, relationType, direction)));
            List<Long> relationIds = new ArrayList<>();
            candidate.path("relationIds").forEach(id -> relationIds.add(id.asLong()));
            candidates.add(new GraphClassificationContract.Candidate(number, anchor, other,
                candidate.path("anchorName").asText(), candidate.path("name").asText(),
                candidate.path("entityType").asText(), relationType, direction, relationIds));
        }
        assertFalse(candidates.isEmpty());
        assertEquals(node.path("candidateCount").asInt(), candidates.size());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("policy", node.path("policy").asText());
        metadata.put("identity", node.path("identity").asText());
        metadata.put("candidates", node.path("candidates"));
        return new GraphClassificationContract.Catalog(true, "", candidates, "", metadata,
            node.path("queryComplete").asBoolean(), node.path("inventoryComplete").asBoolean(),
            QueryIntent.fromQuestion(question).focusText());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
