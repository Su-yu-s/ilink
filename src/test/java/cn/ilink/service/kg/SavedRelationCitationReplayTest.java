package cn.ilink.service.kg;

import cn.ilink.service.ai.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Opt-in replay of an unchanged historical model draft against historical authorized tool facts.
 * This proves production citation checking for that saved sample, not current permissions or a
 * complete end-to-end generation pipeline. No database, provider, scorer or original batch writes.
 */
@EnabledIfSystemProperty(named = "kgCitationReplay", matches = "true")
class SavedRelationCitationReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FACT_TOOLS = Set.of("search_entities", "filter_entities",
        "expand_neighbors", "get_relations", "query_temporal", "search_documents", "get_document");

    @Test
    void originalM7RewriteRejectsWrongObjectBeforeSemanticModelCall() throws Exception {
        String configuredFile = System.getProperty("kgCitationReplayFile", "");
        assertFalse(configuredFile.isBlank(), "Set -DkgCitationReplayFile to the saved batch8.json.");
        Path source = Path.of(configuredFile).toAbsolutePath().normalize();
        assertFalse(source.toString().replace('\\', '/').toLowerCase(java.util.Locale.ROOT)
            .contains("/holdout-v9-custody/private"), "Sealed holdout files must not be read.");
        assertTrue(Files.isRegularFile(source), "Saved batch does not exist: " + source);
        source = source.toRealPath();
        assertFalse(source.toString().replace('\\', '/').toLowerCase(java.util.Locale.ROOT)
            .contains("/holdout-v9-custody/private"), "Sealed holdout files must not be read.");
        byte[] originalBytes = Files.readAllBytes(source);
        JsonNode root = JSON.readTree(originalBytes);
        assertTrue(root.isArray(), "The saved evaluation batch must be an array.");
        List<JsonNode> matches = new ArrayList<>();
        root.forEach(row -> { if ("M7".equals(row.path("id").asText())) matches.add(row); });
        assertEquals(1, matches.size(), "The saved batch must contain exactly one M7 row.");
        JsonNode row = matches.get(0);
        JsonNode draftNode = row.path("b3_rewriteResult").path("draft");
        assertTrue(draftNode.isTextual(), "This replay requires the actual saved rewrite draft.");
        String draft = draftNode.textValue(); // Preserve the model output verbatim.
        assertFalse(draft.isBlank());
        assertEquals("stop", row.path("b3_rewriteResult").path("finishReason").asText());
        String question = row.path("question").asText();
        assertFalse(question.isBlank());

        SavedFacts facts = SavedFacts.read(row.path("b3_trace"));
        assertNotNull(facts.relations.get(1060L), "r#1060 must come from a real tool observation.");
        EvidenceLedger ledger = facts.ledger();
        AiClient ai = mock(AiClient.class);
        AnswerEvidenceVerifier.VerificationResult result = new AnswerEvidenceVerifier()
            .verifySemantic(question, draft, ledger, QueryEvidencePlan.fromQuestion(question), ai);
        // A limited source answer is a real positive citation control, not a full M7 classification.
        String control = row.path("b3_answer").asText();
        assertFalse(control.isBlank());
        var controlResult = new AnswerEvidenceVerifier().verify(question, control, ledger,
            QueryEvidencePlan.fromQuestion(question));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sampleKind", "HISTORICAL_AUTHORIZED_TOOL_FACT_REPLAY");
        report.put("sourcePath", source.toString());
        report.put("sampleId", row.path("id").asText());
        report.put("originalBatchSha256", sha256(originalBytes));
        report.put("draftSha256", sha256(draft.getBytes(StandardCharsets.UTF_8)));
        report.put("originalSourceHash", manifestSourceHash(source.getParent().resolve("manifest.json")));
        Path verifier = Path.of("src/main/java/cn/ilink/service/kg/AnswerEvidenceVerifier.java");
        Path correspondence = Path.of("src/main/java/cn/ilink/service/kg/RelationClaimVerifier.java");
        report.put("answerVerifierSourceSha256", sha256(Files.readAllBytes(verifier)));
        report.put("relationVerifierSourceSha256", sha256(Files.readAllBytes(correspondence)));
        report.put("toolObservationPointers", facts.observationPointers);
        report.put("entityCount", facts.entities.size());
        report.put("relationCount", facts.relations.size());
        report.put("chunkCount", facts.chunks.size());
        report.put("supported", result.supported());
        report.put("reasons", result.reasons());
        report.put("positiveControlKind", "SAVED_LIMITED_SOURCE_ANSWER; not full classification correctness");
        report.put("positiveControlSha256", sha256(control.getBytes(StandardCharsets.UTF_8)));
        report.put("positiveControlSupported", controlResult.supported());
        report.put("positiveControlReasons", controlResult.reasons());
        report.put("aiInvocations", org.mockito.Mockito.mockingDetails(ai).getInvocations().size());
        report.put("permissionValidation", "NOT_RUN; replay uses saved authorized tool output only");
        report.put("limitations", "Not a current permission test, full generation replay, rescoring, or new dev20 run.");
        Path outputDirectory = Path.of("target/kg-citation-replay");
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve("saved-M7-" + System.currentTimeMillis() + ".json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("Historical citation replay report: " + output.toAbsolutePath());

        assertFalse(result.supported(), result.reasons().toString());
        assertTrue(result.reasons().contains("RELATION_ENDPOINT_MISMATCH:r#1060"),
            "The specific wrong-object citation must be rejected, not just another defect: " + result.reasons());
        verifyNoInteractions(ai);
        assertTrue(controlResult.supported(), "A saved source citation was incorrectly rejected: " + controlResult.reasons());
        assertFalse(controlResult.usedChunkIds().isEmpty(), "The control must actually cite authorized source text.");
        assertEquals(sha256(originalBytes), sha256(Files.readAllBytes(source)), "Original batch changed.");
    }

    private static String manifestSourceHash(Path manifest) throws Exception {
        return Files.isRegularFile(manifest) ? JSON.readTree(Files.readAllBytes(manifest))
            .path("sourceHash").asText("UNAVAILABLE") : "UNAVAILABLE";
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class SavedFacts {
        private final Map<Long, EvidenceLedger.EntityFact> entities = new LinkedHashMap<>();
        private final Map<Long, EvidenceLedger.RelationFact> relations = new LinkedHashMap<>();
        private final Map<Long, EvidenceLedger.ChunkFact> chunks = new LinkedHashMap<>();
        private final Set<String> successfulTools = new LinkedHashSet<>();
        private final List<String> observationPointers = new ArrayList<>();

        private static SavedFacts read(JsonNode trace) throws Exception {
            assertTrue(trace.isArray(), "Raw b3_trace tool observations are required.");
            SavedFacts facts = new SavedFacts();
            String pendingTool = null;
            for (int i = 0; i < trace.size(); i++) {
                JsonNode step = trace.get(i);
                if (step.hasNonNull("action")) pendingTool = step.path("action").asText();
                if (pendingTool == null || !step.hasNonNull("observation")) continue;
                String tool = pendingTool;
                pendingTool = null; // A model observation cannot reuse an earlier tool action.
                if (!FACT_TOOLS.contains(tool) || !step.path("observation").isTextual()) continue;
                String raw = step.path("observation").asText();
                if (!raw.stripLeading().startsWith("{")) continue;
                JsonNode observed = JSON.readTree(raw);
                if (!observed.isObject() || observed.has("error")) continue;
                facts.successfulTools.add(tool);
                facts.observationPointers.add("/b3_trace/" + i + "/observation [" + tool + "]");
                for (JsonNode entity : observed.path("entities")) facts.addEntity(entity);
                for (JsonNode neighbor : observed.path("neighbors")) {
                    facts.addEntity(neighbor);
                    facts.addRelation(neighbor);
                }
                for (JsonNode relation : observed.path("relations")) facts.addRelation(relation);
                for (JsonNode chunk : observed.path("chunks")) facts.addChunk(chunk);
            }
            for (EvidenceLedger.RelationFact relation : facts.relations.values()) {
                assertTrue(facts.entities.containsKey(relation.sourceId()),
                    "Missing typed source entity in raw tool observations for r#" + relation.id());
                assertTrue(facts.entities.containsKey(relation.targetId()),
                    "Missing typed target entity in raw tool observations for r#" + relation.id());
            }
            assertFalse(facts.entities.isEmpty());
            assertFalse(facts.relations.isEmpty());
            return facts;
        }

        private void addEntity(JsonNode row) {
            Long id = number(row, "entityId");
            if (id == null || !row.hasNonNull("name") || !row.hasNonNull("entityType")) return;
            EvidenceLedger.EntityFact fact = new EvidenceLedger.EntityFact(id, row.path("name").asText(),
                row.path("entityType").asText(), row.path("description").asText(null));
            EvidenceLedger.EntityFact previous = entities.putIfAbsent(id, fact);
            if (previous != null) {
                assertEquals(previous.name(), fact.name(), "Conflicting raw entity names for #" + id);
                assertEquals(previous.type(), fact.type(), "Conflicting raw entity types for #" + id);
            }
        }

        private void addRelation(JsonNode row) {
            Long id = number(row, "relationId");
            Long source = number(row, "sourceId");
            Long target = number(row, "targetId");
            if (id == null || source == null || target == null) return;
            String type = row.path("relationType").asText(row.path("relation").asText());
            assertFalse(type.isBlank(), "Missing raw relation type for r#" + id);
            EvidenceLedger.RelationFact fact = new EvidenceLedger.RelationFact(id, source, target, type,
                date(row, "validFromMillis", "validFrom"), date(row, "validToMillis", "validTo"),
                row.path("sourceDocId").asText(null), number(row, "sourceChunkId"),
                row.path("factText").asText(null));
            EvidenceLedger.RelationFact previous = relations.putIfAbsent(id, fact);
            if (previous != null) assertEquals(previous, fact, "Conflicting raw relation r#" + id);
        }

        private void addChunk(JsonNode row) {
            Long id = number(row, "chunkId");
            if (id == null) return;
            String text = row.path("text").asText(null);
            if (text == null) return; // Never invent missing source text from model output.
            EvidenceLedger.ChunkFact fact = new EvidenceLedger.ChunkFact(id, row.path("docType").asText(),
                number(row, "docId"), row.path("title").asText(), text,
                integer(row, "startOffset"), integer(row, "endOffset"));
            EvidenceLedger.ChunkFact previous = chunks.putIfAbsent(id, fact);
            if (previous != null) assertEquals(previous, fact, "Conflicting raw source c#" + id);
        }

        private EvidenceLedger ledger() {
            EvidenceLedger ledger = mock(EvidenceLedger.class);
            when(ledger.entities()).thenReturn(List.copyOf(entities.values()));
            when(ledger.relations()).thenReturn(List.copyOf(relations.values()));
            when(ledger.chunks()).thenReturn(List.copyOf(chunks.values()));
            when(ledger.entity(any())).thenAnswer(call -> Optional.ofNullable(entities.get(call.getArgument(0))));
            when(ledger.relation(any())).thenAnswer(call -> Optional.ofNullable(relations.get(call.getArgument(0))));
            when(ledger.chunk(any())).thenAnswer(call -> Optional.ofNullable(chunks.get(call.getArgument(0))));
            when(ledger.containsEntity(anyLong())).thenAnswer(call -> entities.containsKey(call.getArgument(0)));
            when(ledger.containsChunk(anyLong())).thenAnswer(call -> chunks.containsKey(call.getArgument(0)));
            when(ledger.hasToolResult(anyString())).thenAnswer(call -> successfulTools.contains(call.getArgument(0)));
            return ledger;
        }

        private static Long number(JsonNode row, String name) {
            return row.hasNonNull(name) && row.path(name).canConvertToLong() ? row.path(name).longValue() : null;
        }

        private static Integer integer(JsonNode row, String name) {
            return row.hasNonNull(name) && row.path(name).canConvertToInt() ? row.path(name).intValue() : null;
        }

        private static Date date(JsonNode row, String millis, String iso) {
            Long value = number(row, millis);
            if (value != null) return new Date(value);
            if (!row.hasNonNull(iso)) return null;
            String text = row.path(iso).asText();
            return text.length() == 10 ? Date.from(java.time.LocalDate.parse(text).atStartOfDay(KgTimeRange.ZONE).toInstant())
                : Date.from(Instant.parse(text));
        }
    }
}
