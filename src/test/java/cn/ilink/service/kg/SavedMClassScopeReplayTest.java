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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Zero-cost diagnostic replay for the M-class failures of the 2026-10-17 v10 100-case run.
 *
 * <p>It rebuilds each case's permission-checked ledger from the saved tool observations and then
 * re-runs the deterministic parts of the pipeline with the CURRENT sources: subject extraction,
 * per-subject scope assessment, graph answer scope and the graph-coverage requirement. The paid
 * generation and verification stages are not replayed, so this test explains decisions; it does
 * not produce new scores.
 *
 * <pre>
 * mvn test -DkgMClassReplay=true -DkgMClassReplayDir=&lt;round1 directory&gt; \
 *   -Dtest=SavedMClassScopeReplayTest
 * </pre>
 */
@EnabledIfSystemProperty(named = "kgMClassReplay", matches = "true")
class SavedMClassScopeReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Mirrors the production gate; kept in sync by reading, not by sharing a private method. */
    private static final Pattern GRAPH_COVERAGE_CUE = Pattern.compile(
        "关联|关系|连接|涉及|对应|哪些(?:帖子|记录|文档|实体|节点)|全部|所有|完整|集合|分类|归类");

    @Test
    void printsDeterministicMClassDecisions() throws Exception {
        Path directory = Path.of(System.getProperty("kgMClassReplayDir", ""))
            .toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(directory), "Set -DkgMClassReplayDir to the saved round directory.");

        StringBuilder report = new StringBuilder();
        List<Path> batches;
        try (Stream<Path> files = Files.list(directory)) {
            batches = files.filter(path -> path.getFileName().toString().matches("batch\\d+\\.json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }
        assertFalse(batches.isEmpty(), "No saved batch files in " + directory);

        for (Path batch : batches) {
            JsonNode root = JSON.readTree(Files.readAllBytes(batch));
            for (JsonNode row : root) {
                String id = row.path("id").asText();
                if (!id.matches("M\\d+")) {
                    continue;
                }
                report.append(describe(id, row));
            }
        }
        String text = report.toString();
        System.out.println(text);
        assertFalse(text.isBlank(), "The diagnostic must describe at least one M case.");
    }

    private static String describe(String id, JsonNode row) {
        String question = row.path("question").asText();
        String savedAnswer = row.path("b3_answer").asText("");
        StringBuilder out = new StringBuilder();
        out.append("\n=== ").append(id).append(" ").append(question).append('\n')
            .append("saved: mode=").append(row.path("b3_answerMode").asText())
            .append(" coverage=").append(row.path("b3_answerCoverage").asText())
            .append('/').append(row.path("b3_coverageReason").asText())
            .append(" pass=").append(row.path("b3_pass").asBoolean())
            .append(" b1pass=").append(row.path("b1_pass").asBoolean())
            .append(" hits=").append(row.path("hits").asText("")).append('\n');
        try {
            EvidenceLedger ledger = replayLedger(row);
            QueryIntent intent = QueryIntent.fromQuestion(question);
            out.append("  intent: subject='").append(intent.primarySubject())
                .append("' focus='").append(intent.focusText())
                .append("' graphRequested=").append(intent.graphRequested()).append('\n');
            SubjectScopeLedger.Analysis scopes = SubjectScopeLedger.assess(question, ledger);
            out.append("  scopes: identificationComplete=").append(scopes.subjectIdentificationComplete())
                .append(" reason=").append(scopes.identificationReason())
                .append(" queryComplete=").append(scopes.queryComplete()).append('\n');
            for (SubjectScopeLedger.SubjectScope subject : scopes.subjects()) {
                out.append("    - requested='").append(subject.requestedName())
                    .append("' resolution=").append(subject.resolution())
                    .append(" entity=").append(subject.entityId())
                    .append(" name='").append(subject.entityName()).append('\'')
                    .append(" matches=").append(subject.matchingEntityIds())
                    .append(" relations=").append(subject.relationIds().size()).append('\n');
            }
            GraphAnswerScope.Scope graph = GraphAnswerScope.describe(question, ledger);
            out.append("  graphScope: requested=").append(graph.requested())
                .append(" sourceOnly=").append(graph.sourceOnly())
                .append(" queryComplete=").append(graph.queryComplete())
                .append(" inventoryComplete=").append(graph.inventoryComplete())
                .append(" candidates=").append(graph.candidateCount())
                .append(" shown=").append(graph.shownCount())
                .append(" limited=").append(graph.limited()).append('\n');
            out.append("  coverageGate: graphCoverageRequired=")
                .append(graphCoverageRequired(question, savedAnswer)).append('\n');
            out.append("  ledger: entities=").append(ledger.entities().size())
                .append(" relations=").append(ledger.relations().size())
                .append(" chunks=").append(ledger.chunks().size()).append('\n');
            String subject = intent.primarySubject();
            if (subject != null && !subject.isBlank()) {
                String needle = QueryIntent.normalize(subject);
                out.append("  entity-name candidates:\n");
                for (EvidenceLedger.EntityFact fact : ledger.entities()) {
                    String name = QueryIntent.normalize(fact.name());
                    if (matches(needle, name) || matches(name, needle)) {
                        out.append("    #").append(fact.id()).append(' ').append(fact.name())
                            .append(" [").append(fact.type()).append("]\n");
                    }
                }
                out.append("  chunk-title candidates:\n");
                for (EvidenceLedger.ChunkFact chunk : ledger.chunks()) {
                    String title = QueryIntent.normalize(chunk.title());
                    if (matches(needle, title) || matches(title, needle)) {
                        out.append("    c#").append(chunk.id()).append(' ')
                            .append(chunk.docType()).append(':').append(chunk.docId())
                            .append(' ').append(chunk.title()).append('\n');
                    }
                }
            }
        } catch (Exception failure) {
            out.append("  diagnostic failed: ").append(failure).append('\n');
        }
        return out.toString();
    }

    private static boolean matches(String left, String right) {
        return !left.isEmpty() && !right.isEmpty()
            && (left.contains(right) || right.contains(left));
    }

    /** Same cue set as the production gate, applied to the question of the saved case. */
    private static boolean graphCoverageRequired(String question, String answer) {
        String body = java.util.Objects.toString(question, "");
        String requested = QueryIntent.fromQuestion(body).focusText();
        if (GRAPH_COVERAGE_CUE.matcher(body).find()
            || Pattern.compile("关联|关系|全部|所有|完整|集合|分类|归类")
                .matcher(java.util.Objects.toString(requested, "")).find()) {
            return true;
        }
        return Pattern.compile("(?:图谱|图中|边|关系)\\S{0,8}(?:显示|确认|证明|存在|关联)")
            .matcher(java.util.Objects.toString(answer, "")).find();
    }

    /**
     * Rebuilds the run's ledger from the saved tool observations. The trace stores the decision and
     * its result as two neighbouring entries: an {"action": ...} entry followed by an
     * {"observation": ...} entry.
     */
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
}
