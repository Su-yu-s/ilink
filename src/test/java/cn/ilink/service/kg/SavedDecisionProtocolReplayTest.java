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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in compatibility-boundary replay of actual historical candidate-partition-v2 drafts.
 * Only Stage B protocol parsing is exercised: no model, database, ledger, scorer or source writes.
 * Rejecting the old drafts does not demonstrate successful new generation, classification truth,
 * current permissions or end-to-end effects. The all-U control demonstrates syntax coverage only.
 */
@EnabledIfSystemProperty(named = "kgDecisionReplay", matches = "true")
class SavedDecisionProtocolReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void historicalM7DraftsAreRejectedAtTheClosedDecisionProtocolBoundary() throws Exception {
        String configured = System.getProperty("kgDecisionReplayFile", "");
        assertFalse(configured.isBlank(), "Set -DkgDecisionReplayFile to the saved round1/batch8.json.");
        Path source = checkedExistingPath(Path.of(configured));
        assertEquals("batch8.json", source.getFileName().toString(), "Use the original saved batch8.json.");
        byte[] sourceBefore = Files.readAllBytes(source);
        JsonNode batch = JSON.readTree(sourceBefore);
        assertTrue(batch.isArray(), "The original saved batch must be an array.");
        List<JsonNode> matches = new ArrayList<>();
        batch.forEach(row -> { if ("M7".equals(row.path("id").asText())) matches.add(row); });
        assertEquals(1, matches.size(), "Exactly one saved M7 row is required.");
        JsonNode row = matches.get(0);
        assertEquals("candidate-partition-v2", requiredText(row, "b3_classificationContract"),
            "This replay is explicitly the historical v2 compatibility boundary.");
        String question = requiredText(row, "question");
        GraphClassificationContract.Catalog catalog = savedCatalog(row.path("b3_classificationCatalog"), question);

        Map<String, Object> generation = replayDraft(row, "b3_generationResult", catalog);
        Map<String, Object> rewrite = replayDraft(row, "b3_rewriteResult", catalog);
        // Construct a positive protocol control; it contains no classification facts or invented graph data.
        String control = JSON.writeValueAsString(Map.of("groups", List.of(Map.of(
            "s", "U", "reason", "INSUFFICIENT", "ids", catalog.candidates().stream()
                .map(GraphClassificationContract.Candidate::number).toList(), "proofs", List.of()))));
        GraphClassificationContract.DecisionParsed positive = GraphClassificationContract.parseDecisions(control, catalog);
        assertTrue(positive.complete(), "The all-U protocol control must cover every saved identity: " + positive.failures());
        assertEquals(catalog.candidates().size(), positive.uncertainCount());
        assertEquals(false, positive.diagnosticsMetadata().get("semanticVerdictAccepted"));
        assertEquals(false, positive.diagnosticsMetadata().get("uncertaintySemanticallyVerified"));
        assertTrue(GraphClassificationContract.decisionClaims(catalog, positive).isEmpty(),
            "The positive protocol control must not produce classification assertions.");

        Path manifest = checkedExistingPath(source.getParent().resolve("manifest.json"));
        byte[] manifestBefore = Files.readAllBytes(manifest);
        String sourceHash = requiredText(JSON.readTree(manifestBefore), "sourceHash");
        String sourceBeforeHash = sha256(sourceBefore);
        String sourceAfterHash = sha256(Files.readAllBytes(source));
        String manifestBeforeHash = sha256(manifestBefore);
        String manifestAfterHash = sha256(Files.readAllBytes(manifest));
        assertEquals(sourceBeforeHash, sourceAfterHash, "Original batch was modified.");
        assertEquals(manifestBeforeHash, manifestAfterHash, "Original manifest was modified.");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sampleKind", "HISTORICAL_V2_CLOSED_DECISION_PROTOCOL_BOUNDARY_REPLAY");
        report.put("sampleId", "M7");
        report.put("sourcePath", source.toString());
        report.put("historicalProtocol", "candidate-partition-v2");
        report.put("productionProtocol", GraphClassificationContract.DECISION_PROTOCOL);
        report.put("originalBatchSha256Before", sourceBeforeHash);
        report.put("originalBatchSha256After", sourceAfterHash);
        report.put("originalManifestSha256Before", manifestBeforeHash);
        report.put("originalManifestSha256After", manifestAfterHash);
        report.put("originalSourceHash", sourceHash);
        report.put("contractSourceSha256", sha256(Files.readAllBytes(Path.of(
            "src/main/java/cn/ilink/service/kg/GraphClassificationContract.java"))));
        report.put("catalogPointer", "/M7/b3_classificationCatalog/candidates");
        report.put("candidateCount", catalog.candidates().size());
        report.put("queryComplete", catalog.queryComplete());
        report.put("inventoryComplete", catalog.inventoryComplete());
        report.put("candidateIdentities", catalog.metadata().get("candidates"));
        report.put("generation", generation);
        report.put("rewrite", rewrite);
        report.put("positiveControlKind", "PROGRAM_CONSTRUCTED_ALL_U; protocol coverage only");
        report.put("positiveControlSha256", sha256(control.getBytes(StandardCharsets.UTF_8)));
        report.put("positiveControlProtocolComplete", positive.complete());
        report.put("positiveControlFailures", positive.failures());
        report.put("positiveControlDiagnostics", positive.diagnosticsMetadata());
        report.put("positiveControlSemanticValidation", "NOT_RUN; all-U is not a verified uncertainty fact");
        report.put("aiInvocations", 0);
        report.put("aiInvocationAccounting", "NO_AI_CLIENT_CONSTRUCTED; production parser APIs only");
        report.put("permissionValidation", "NOT_RUN; identities copied from saved catalog, no current ledger access");
        report.put("limitations", "Confirms old v2 drafts are rejected by the v3 protocol parser only. "
            + "Does not establish new generation success, classification correctness, evidence support, "
            + "current permissions, rescoring or end-to-end quality.");
        Path outputDirectory = Path.of("target/kg-decision-replay").toAbsolutePath().normalize();
        rejectSealedPath(outputDirectory);
        Path existingAncestor = outputDirectory;
        while (!Files.exists(existingAncestor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.getParent();
            assertTrue(existingAncestor != null, "Cannot resolve a safe replay output ancestor.");
        }
        // Validate a junction/symlink ancestor before creating any missing directory beneath it.
        rejectSealedPath(existingAncestor.toRealPath());
        Files.createDirectories(outputDirectory);
        outputDirectory = outputDirectory.toRealPath();
        rejectSealedPath(outputDirectory);
        Path output = outputDirectory.resolve("saved-M7-" + System.currentTimeMillis() + ".json");
        assertFalse(output.equals(source) || output.equals(manifest), "Replay output must be separate.");
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),
            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("Historical decision protocol replay report: " + output);
        assertEquals(sourceBeforeHash, sha256(Files.readAllBytes(source)), "Original batch changed after report write.");
        assertEquals(manifestBeforeHash, sha256(Files.readAllBytes(manifest)), "Original manifest changed after report write.");
    }

    private static Map<String, Object> replayDraft(JsonNode row, String resultField,
                                                  GraphClassificationContract.Catalog catalog) throws Exception {
        JsonNode result = row.path(resultField);
        assertTrue(result.isObject(), "Actual saved result is required: " + resultField);
        String draft = requiredText(result, "draft"); // No trimming, extraction or schema conversion.
        GraphClassificationContract.DecisionParsed parsed = GraphClassificationContract.parseDecisions(draft, catalog);
        assertFalse(parsed.complete(), "The unmodified historical free-text draft must not enter v3: " + resultField);
        assertFalse(parsed.failures().isEmpty());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("draftPointer", "/" + resultField + "/draft");
        report.put("draftSha256", sha256(draft.getBytes(StandardCharsets.UTF_8)));
        report.put("historicalFinishReason", requiredText(result, "finishReason"));
        report.put("protocolComplete", parsed.complete());
        report.put("failures", parsed.failures());
        report.put("missingCandidateIds", parsed.diagnosticsMetadata().get("missingCandidateIds"));
        report.put("diagnostics", parsed.diagnosticsMetadata());
        report.put("semanticValidation", "NOT_RUN; rejected at protocol boundary");
        return report;
    }

    private static GraphClassificationContract.Catalog savedCatalog(JsonNode node, String question) {
        assertTrue(node.isObject(), "The actual saved classification catalog is required.");
        assertEquals("graph-classification-catalog-v1", requiredText(node, "policy"));
        assertTrue(requiredBoolean(node, "requested"));
        assertTrue(node.path("candidates").isArray());
        assertFalse(node.path("candidates").isEmpty());
        List<GraphClassificationContract.Candidate> candidates = new ArrayList<>();
        Set<List<Object>> identities = new LinkedHashSet<>();
        for (JsonNode candidate : node.path("candidates")) {
            assertTrue(candidate.isObject());
            int number = requiredPositiveInt(candidate, "number");
            assertEquals(candidates.size() + 1, number, "Saved candidate numbering must be contiguous.");
            long anchor = requiredPositiveLong(candidate, "anchorId");
            long other = requiredPositiveLong(candidate, "otherId");
            String direction = requiredText(candidate, "direction");
            assertTrue(Set.of("OUT", "IN").contains(direction));
            String relationType = requiredText(candidate, "relationType");
            assertTrue(identities.add(List.of(anchor, other, relationType, direction)), "Saved identity repeated.");
            JsonNode relations = candidate.path("relationIds");
            assertTrue(relations.isArray()); assertFalse(relations.isEmpty());
            Set<Long> relationIds = new LinkedHashSet<>();
            for (JsonNode relation : relations) {
                assertTrue(relation.isIntegralNumber() && relation.canConvertToLong() && relation.longValue() > 0,
                    "Missing or malformed saved relation ID.");
                assertTrue(relationIds.add(relation.longValue()), "Duplicate saved relation ID.");
            }
            candidates.add(new GraphClassificationContract.Candidate(number, anchor, other,
                requiredText(candidate, "anchorName"), requiredText(candidate, "name"),
                requiredText(candidate, "entityType"), relationType, direction, List.copyOf(relationIds)));
        }
        assertEquals(requiredPositiveInt(node, "candidateCount"), candidates.size());
        assertEquals(requiredPositiveInt(node, "shownCount"), candidates.size());
        boolean queryComplete = requiredBoolean(node, "queryComplete");
        boolean inventoryComplete = requiredBoolean(node, "inventoryComplete");
        assertTrue(inventoryComplete, "This saved sample must have a complete candidate inventory.");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("policy", requiredText(node, "policy"));
        metadata.put("identity", requiredText(node, "identity"));
        metadata.put("candidates", node.path("candidates"));
        // Catalog.text is unused by parsing. No source passages or graph facts are synthesized.
        return new GraphClassificationContract.Catalog(true, "", candidates, "", metadata,
            queryComplete, inventoryComplete, QueryIntent.fromQuestion(question).focusText());
    }

    private static String requiredText(JsonNode node, String field) {
        assertTrue(node.path(field).isTextual(), "Missing textual saved field: " + field);
        String text = node.path(field).textValue();
        assertFalse(text.isBlank(), "Blank saved field: " + field);
        return text;
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        assertTrue(node.path(field).isBoolean(), "Missing boolean saved field: " + field);
        return node.path(field).booleanValue();
    }

    private static int requiredPositiveInt(JsonNode node, String field) {
        assertTrue(node.path(field).isIntegralNumber() && node.path(field).canConvertToInt()
            && node.path(field).intValue() > 0, "Missing positive saved number: " + field);
        return node.path(field).intValue();
    }

    private static long requiredPositiveLong(JsonNode node, String field) {
        assertTrue(node.path(field).isIntegralNumber() && node.path(field).canConvertToLong()
            && node.path(field).longValue() > 0, "Missing positive saved ID: " + field);
        return node.path(field).longValue();
    }

    private static Path checkedExistingPath(Path path) throws Exception {
        Path normalized = path.toAbsolutePath().normalize();
        rejectSealedPath(normalized); // Check configured path before any file access.
        assertTrue(Files.isRegularFile(normalized), "Saved artifact does not exist: " + normalized);
        Path real = normalized.toRealPath();
        rejectSealedPath(real); // Check the resolved symlink/junction target before content access.
        return real;
    }

    private static void rejectSealedPath(Path path) {
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        assertFalse(normalized.contains("/holdout-v9-custody/private"), "Sealed holdout files must not be accessed.");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
