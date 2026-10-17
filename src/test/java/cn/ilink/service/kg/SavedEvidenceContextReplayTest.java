package cn.ilink.service.kg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Replays source-slot selection from saved real retrieval results.
 * This test makes no model, database or write calls.
 */
@EnabledIfSystemProperty(named = "kgEvidenceReplay", matches = "true")
class SavedEvidenceContextReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void savedSourceQuestionsKeepTheirAnswerFacts() throws Exception {
        Path directory = Path.of(System.getProperty("kgEvidenceReplayDir", "")).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(directory), "Saved batch directory does not exist: " + directory);
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            for (Path batch : files.filter(path -> path.getFileName().toString().matches("batch\\d+\\.json"))
                .sorted().toList()) {
                for (JsonNode row : JSON.readTree(Files.readAllBytes(batch)))
                    rows.put(row.path("id").asText(), row);
            }
        }
        assertFalse(rows.isEmpty(), "No saved batches found: " + directory);

        Map<String, List<String>> expectations = Map.of(
            "E15", List.of("{bizType}/yyyy/MM/dd/", "按日期"),
            "E20", List.of("#fafafa", "#171717"),
            "E25", List.of("72px", "56px"));
        for (var expected : expectations.entrySet()) {
            JsonNode row = rows.get(expected.getKey());
            assertTrue(row != null, expected.getKey() + " missing from saved batches");
            EvidenceLedger ledger = ledger(row);
            EvidenceContext.Selection selected = EvidenceContext.select(row.path("question").asText(), ledger);
            for (String fact : expected.getValue())
                assertTrue(selected.text().contains(fact),
                    expected.getKey() + " selected " + selected.selected() + "/" + selected.available()
                        + " units but lost " + fact + "; evidence=" + selected.text());
        }
    }

    private static EvidenceLedger ledger(JsonNode row) {
        Map<Long, EvidenceLedger.EntityFact> entities = new LinkedHashMap<>();
        Map<Long, EvidenceLedger.RelationFact> relations = new LinkedHashMap<>();
        Map<Long, EvidenceLedger.ChunkFact> chunks = new LinkedHashMap<>();
        for (JsonNode step : row.path("b3_trace")) {
            JsonNode observation = step.path("observation");
            if (!observation.isTextual()) continue;
            JsonNode parsed;
            try {
                parsed = JSON.readTree(observation.asText());
            } catch (Exception ignored) {
                continue;
            }
            for (JsonNode entity : parsed.path("entities")) {
                long id = entity.path("entityId").asLong();
                entities.putIfAbsent(id, new EvidenceLedger.EntityFact(id,
                    entity.path("name").asText(), entity.path("entityType").asText(), ""));
            }
            for (JsonNode edge : parsed.path("relations")) {
                long sourceId = edge.path("sourceId").asLong();
                long targetId = edge.path("targetId").asLong();
                entities.putIfAbsent(sourceId, new EvidenceLedger.EntityFact(sourceId,
                    edge.path("sourceName").asText(), "", ""));
                entities.putIfAbsent(targetId, new EvidenceLedger.EntityFact(targetId,
                    edge.path("targetName").asText(), "COMPETITION", ""));
                long relationId = edge.path("relationId").asLong();
                relations.putIfAbsent(relationId, new EvidenceLedger.RelationFact(relationId,
                    sourceId, targetId, edge.path("relationType").asText(), null, null, "", null,
                    edge.path("factText").asText("")));
            }
            for (JsonNode chunk : parsed.path("chunks")) {
                long id = chunk.path("chunkId").asLong();
                chunks.putIfAbsent(id, new EvidenceLedger.ChunkFact(id,
                    chunk.path("docType").asText(), chunk.path("docId").asLong(),
                    chunk.path("title").asText(), chunk.path("text").asText(),
                    chunk.path("startOffset").isNumber() ? chunk.path("startOffset").asInt() : null,
                    chunk.path("endOffset").isNumber() ? chunk.path("endOffset").asInt() : null));
            }
        }
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.entities()).thenReturn(List.copyOf(entities.values()));
        when(ledger.relations()).thenReturn(List.copyOf(relations.values()));
        when(ledger.chunks()).thenReturn(List.copyOf(chunks.values()));
        when(ledger.coverage()).thenReturn(List.of());
        for (var entity : entities.values())
            when(ledger.entity(entity.id())).thenReturn(java.util.Optional.of(entity));
        for (var relation : relations.values())
            when(ledger.relation(relation.id())).thenReturn(java.util.Optional.of(relation));
        for (var chunk : chunks.values())
            when(ledger.chunk(chunk.id())).thenReturn(java.util.Optional.of(chunk));
        return ledger;
    }
}
