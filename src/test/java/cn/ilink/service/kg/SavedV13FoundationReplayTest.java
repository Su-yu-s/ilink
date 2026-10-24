package cn.ilink.service.kg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.ilink.mapper.KgAgentTraceMapper;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.kg.tool.GraphTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Zero-model replay from v12.1 saved tool observations.
 * This proves deterministic verifier and temporal-seed behavior for the saved snapshot only.
 */
@EnabledIfSystemProperty(named = "kgV13FoundationReplay", matches = "true")
class SavedV13FoundationReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FACT_TOOLS = Set.of(
        "search_entities", "filter_entities", "expand_neighbors", "query_temporal",
        "search_documents", "get_document", "read_relation_source", "get_relations");

    @Test
    void savedM2VerifierAndT5TemporalPreflightReplayWithoutModelCalls() throws Exception {
        Path directory = Path.of(System.getProperty("kgV13FoundationReplayDir", ""))
            .toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(directory), "Saved batch directory does not exist: " + directory);
        Map<String, JsonNode> rows = readRows(directory);
        JsonNode m2 = rows.get("M2");
        JsonNode t5 = rows.get("T5");
        assertTrue(m2 != null && t5 != null, "M2 and T5 are required in the saved snapshot");

        SavedFacts m2Facts = SavedFacts.read(m2.path("b3_trace"));
        String m2Draft = m2.path("b3_generationResult").path("draft").asText();
        var result = new AnswerEvidenceVerifier().verify(m2.path("question").asText(), m2Draft,
            m2Facts.ledger(), QueryEvidencePlan.fromQuestion(m2.path("question").asText()));
        assertTrue(result.reasons().stream().noneMatch(reason -> reason.startsWith("NAME_MISMATCH:#1867")),
            "Structural relation endpoint was still treated as a prose name: " + result.reasons());

        SavedFacts t5Facts = SavedFacts.read(t5.path("b3_trace"));
        List<EvidenceLedger.EntityFact> seeds = AgenticGraphRagService.temporalSeeds(
            t5.path("question").asText(), t5Facts.ledger());
        List<Long> ids = seeds.stream().map(EvidenceLedger.EntityFact::id).toList();
        assertTrue(ids.contains(1965L), "T5 must resolve the Link team-space record");
        assertTrue(ids.contains(1735L), "T5 must resolve the visual-refactor report");
        assertEquals(Set.of(1725L, 1965L, 1735L), Set.copyOf(ids));

        List<String> targetedQueries = new ArrayList<>();
        var t5Answer = t5ReplayService(targetedQueries, seeds).query(
            t5.path("question").asText(), 8L);
        assertTrue(targetedQueries.contains("团队空间"), targetedQueries.toString());
        assertTrue(targetedQueries.contains("视觉重构"), targetedQueries.toString());
        Map<?, ?> preflight = (Map<?, ?>) t5Answer.trace.stream()
            .filter(step -> step.containsKey("temporalPreflightCoverage"))
            .reduce((first, second) -> second).orElseThrow()
            .get("temporalPreflightCoverage");
        assertEquals(Set.of(1725L, 1965L, 1735L), Set.copyOf((List<?>) preflight.get("requestedSeeds")));
        assertEquals(Set.of(1725L, 1965L, 1735L), Set.copyOf((List<?>) preflight.get("missingSeeds")));
        assertEquals(List.of(), preflight.get("completeSeeds"));
        assertEquals(Set.of("团队空间", "视觉重构"), Set.copyOf(targetedQueries),
            targetedQueries.toString());
        Map<?, ?> recordScope = (Map<?, ?>) t5Answer.trace.stream()
            .filter(step -> step.containsKey("temporalRecordScope"))
            .reduce((first, second) -> second).orElseThrow()
            .get("temporalRecordScope");
        assertEquals(false, recordScope.get("allWindowsComplete"));
        assertEquals(false, recordScope.get("requestedRecordsConfirmed"));
        assertEquals(List.of(1725L, 1735L, 1965L), recordScope.get("missingCompleteMemberIds"));
        // v12.1 never executed query_temporal for all three members. The saved ledger therefore
        // cannot support a claimed COMPLETE answer or be retroactively upgraded into one.
        assertFalse("COMPLETE".equals(last(t5Answer, "answerCoverage")), t5Answer.trace.toString());
        assertTrue(t5Answer.trace.stream().anyMatch(step ->
            "TEMPORAL_RECORD_MEMBERS_INCOMPLETE".equals(step.get("coverageReason"))
                || "PARTIAL".equals(step.get("answerCoverage"))
                || "UNANSWERED".equals(step.get("answerCoverage"))), t5Answer.trace.toString());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sampleKind", "V12_1_SAVED_ZERO_MODEL_FOUNDATION_REPLAY");
        report.put("sourceDirectory", directory.toString());
        report.put("manifestSourceHash", manifestHash(directory.resolve("manifest.json")));
        report.put("m2DraftSha256InputLength", m2Draft.length());
        report.put("m2Reasons", result.reasons());
        report.put("t5ResolvedSeedIds", ids);
        report.put("t5ResolvedSeedNames", seeds.stream().map(EvidenceLedger.EntityFact::name).toList());
        report.put("t5TargetedQueries", targetedQueries);
        report.put("t5PreflightCoverage", preflight);
        report.put("t5RecordScope", recordScope);
        report.put("t5AnswerMode", last(t5Answer, "answerMode"));
        report.put("t5AnswerCoverage", last(t5Answer, "answerCoverage"));
        report.put("t5CoverageReason", last(t5Answer, "coverageReason"));
        report.put("t5Answer", t5Answer.answer);
        report.put("t5Citations", t5Answer.citations);
        report.put("aiInvocations", 0);
        report.put("limitations", "Deterministic verifier and seed selection only; not end-to-end scoring.");
        Path output = Path.of("target/kg-v13-foundation-replay",
            "saved-v12.1-foundation-" + System.currentTimeMillis() + ".json");
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("v13 foundation replay report: " + output.toAbsolutePath());
    }

    private static Map<String, JsonNode> readRows(Path directory) throws Exception {
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            for (Path batch : files.filter(path -> path.getFileName().toString().matches("batch\\d+\\.json"))
                .sorted().toList()) {
                for (JsonNode row : JSON.readTree(Files.readAllBytes(batch)))
                    rows.put(row.path("id").asText(), row);
            }
        }
        return rows;
    }

    private static String manifestHash(Path manifest) throws Exception {
        return Files.isRegularFile(manifest) ? JSON.readTree(Files.readAllBytes(manifest))
            .path("sourceHash").asText("UNAVAILABLE") : "UNAVAILABLE";
    }

    static EvidenceLedger replayLedger(JsonNode row) throws Exception {
        return SavedFacts.read(row.path("b3_trace")).ledger();
    }

    private static AgenticGraphRagService t5ReplayService(
        List<String> targetedQueries, List<EvidenceLedger.EntityFact> savedEntities
    ) {
        SavedFacts saved = SavedFacts.fromPreAuthorized(savedEntities);
        EvidenceLedger replayLedger = saved.ledger();
        EvidenceLedgerFactory factory = mock(EvidenceLedgerFactory.class);
        when(factory.create()).thenReturn(replayLedger);
        AiClient ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt())).thenReturn(new AiClient.AiChatResult("Final Answer: READY", 1, 1));
        GraphTool search = new GraphTool() {
            public String getName() { return "search_entities"; }
            public String getDescription() { return "saved replay search"; }
            public String getParametersSchema() { return "{}"; }
            public Object execute(Map<String, Object> params, Long userId) {
                targetedQueries.add(String.valueOf(params.get("query")));
                List<Map<String, Object>> entities = savedEntities.stream()
                    .filter(entity -> QueryIntent.normalize(entity.name())
                        .contains(QueryIntent.normalize(String.valueOf(params.get("query")))))
                    .map(entity -> Map.<String, Object>of(
                        "entityId", entity.id(), "name", entity.name(),
                        "entityType", entity.type()))
                    .toList();
                return Map.of("entities", entities);
            }
        };
        GraphTool temporal = new GraphTool() {
            public String getName() { return "query_temporal"; }
            public String getDescription() { return "saved replay temporal query"; }
            public String getParametersSchema() { return "{}"; }
            public Object execute(Map<String, Object> params, Long userId) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("relations", List.of());
                empty.put("coverage", Map.of("complete", false, "truncated", false,
                    "returned", 0, "scope", Map.of("entityId", params.get("entity_id"))));
                return empty;
            }
        };
        return new AgenticGraphRagService(mock(GraphRagService.class), mock(KgAgentTraceMapper.class),
            ai, List.of(search, temporal), factory);
    }

    private static Object last(AgenticGraphRagService.AgenticAnswer answer, String key) {
        return answer.trace.stream().filter(step -> step.containsKey(key))
            .reduce((first, second) -> second).orElseThrow().get(key);
    }

    private static final class SavedFacts {
        private final Map<Long, EvidenceLedger.EntityFact> entities = new LinkedHashMap<>();
        private final Map<Long, EvidenceLedger.RelationFact> relations = new LinkedHashMap<>();
        private final Map<Long, EvidenceLedger.ChunkFact> chunks = new LinkedHashMap<>();
        private final Set<String> successfulTools = new LinkedHashSet<>();

        private static SavedFacts read(JsonNode trace) throws Exception {
            assertTrue(trace.isArray(), "Raw b3_trace is required");
            SavedFacts facts = new SavedFacts();
            String pendingTool = null;
            for (JsonNode step : trace) {
                if (step.hasNonNull("action")) pendingTool = step.path("action").asText();
                if (pendingTool == null || !step.path("observation").isTextual()) continue;
                String tool = pendingTool;
                pendingTool = null;
                if (!FACT_TOOLS.contains(tool)) continue;
                String raw = step.path("observation").asText();
                if (!raw.stripLeading().startsWith("{")) continue;
                JsonNode observed = JSON.readTree(raw);
                if (!observed.isObject() || observed.has("error")) continue;
                facts.successfulTools.add(tool);
                for (JsonNode entity : observed.path("entities")) facts.addEntity(entity);
                for (JsonNode neighbor : observed.path("neighbors")) {
                    facts.addEntity(neighbor);
                    facts.addRelation(neighbor);
                }
                for (JsonNode relation : observed.path("relations")) facts.addRelation(relation);
                for (JsonNode chunk : observed.path("chunks")) facts.addChunk(chunk);
            }
            return facts;
        }

        private static SavedFacts fromPreAuthorized(List<EvidenceLedger.EntityFact> entities) {
            SavedFacts facts = new SavedFacts();
            for (EvidenceLedger.EntityFact entity : entities) facts.entities.put(entity.id(), entity);
            facts.successfulTools.add("search_entities");
            return facts;
        }

        private void addEntity(JsonNode row) {
            Long id = number(row, "entityId");
            if (id == null) return;
            String name = row.path("name").asText(row.path("sourceName").asText(""));
            String type = row.path("entityType").asText(row.path("sourceType").asText(""));
            if (!name.isBlank() && !type.isBlank())
                entities.putIfAbsent(id, new EvidenceLedger.EntityFact(id, name, type, ""));
        }

        private void addRelation(JsonNode row) {
            Long id = number(row, "relationId");
            Long source = number(row, "sourceId");
            Long target = number(row, "targetId");
            if (id == null || source == null || target == null) return;
            String type = row.path("relationType").asText(row.path("relation").asText(""));
            if (type.isBlank()) return;
            relations.putIfAbsent(id, new EvidenceLedger.RelationFact(id, source, target, type,
                date(row.path("validFrom").asText(null)), date(row.path("validTo").asText(null)),
                row.path("sourceDocId").asText(null), number(row, "sourceChunkId"),
                row.path("factText").asText(null)));
        }

        private void addChunk(JsonNode row) {
            Long id = number(row, "chunkId");
            if (id == null) return;
            chunks.putIfAbsent(id, new EvidenceLedger.ChunkFact(id, row.path("docType").asText(),
                number(row, "docId"), row.path("title").asText(), row.path("text").asText(""),
                integer(row, "startOffset"), integer(row, "endOffset")));
        }

        private EvidenceLedger ledger() {
            return EvidenceLedger.preAuthorized(entities.values(), relations.values(),
                chunks.values(), successfulTools);
        }

        private static Long number(JsonNode row, String name) {
            return row.hasNonNull(name) && row.path(name).canConvertToLong() ? row.path(name).longValue() : null;
        }

        private static Integer integer(JsonNode row, String name) {
            return row.hasNonNull(name) && row.path(name).canConvertToInt() ? row.path(name).intValue() : null;
        }

        private static Date date(String value) {
            return value == null || value.isBlank() ? null
                : Date.from(LocalDate.parse(value).atStartOfDay(KgTimeRange.ZONE).toInstant());
        }
    }
}
