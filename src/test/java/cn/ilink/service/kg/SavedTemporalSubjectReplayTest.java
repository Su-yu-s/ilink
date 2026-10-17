package cn.ilink.service.kg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用 10-07 保存的实体与关系事实回放 T/E 题的主体解析与时间窗判定。
 * 不连接数据库、不调用模型、不写入原始批次；只证明确定性解析结果。
 */
@EnabledIfSystemProperty(named = "kgSubjectReplay", matches = "true")
class SavedTemporalSubjectReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void savedTemporalSubjectsResolveAsExactAfterParsingFix() throws Exception {
        Path directory = Path.of(System.getProperty("kgSubjectReplayDir", "")).toAbsolutePath().normalize();
        assertFalse(directory.toString().replace('\\', '/').toLowerCase(java.util.Locale.ROOT)
            .contains("/holdout-v9-custody/private"), "封存留出集不得读取");
        assertTrue(Files.isDirectory(directory), "保存批次目录不存在：" + directory);
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            for (Path batch : files.filter(path -> path.getFileName().toString().matches("batch\\d+\\.json"))
                .sorted().toList()) {
                for (JsonNode row : JSON.readTree(Files.readAllBytes(batch)))
                    rows.put(row.path("id").asText(), row);
            }
        }
        assertFalse(rows.isEmpty(), "保存批次为空：" + directory);

        // 每题：请求主体、锚点实体、窗口、窗口内应命中的关系。
        record Expectation(String id, String subject, String anchor, String windowStart, String windowEnd,
                           List<String> targets) { }
        List<Expectation> cases = List.of(
            new Expectation("T3", "iLink 平台（Agentic GraphRAG 项目）", "iLink Agentic GraphRAG",
                "2026-03-11", "2026-03-20", List.of("挑战杯中国大学生创业计划大赛")),
            new Expectation("T10", "Agent 记忆机制", "Agent记忆机制",
                "2026-02-01", "2026-03-01", List.of("ACM-ICPC")),
            new Expectation("T13", "iLink 平台（Agentic GraphRAG 项目）", "iLink Agentic GraphRAG",
                "2026-02-01", "2026-02-10", List.of("全国大学生数学建模竞赛")),
            new Expectation("T14", "Link 团队空间模块", "Link 团队空间模块",
                "2026-05-01", "2026-06-01", List.of("全国大学生电子设计竞赛", "蓝桥杯全国软件和信息技术专业人才大赛")),
            new Expectation("T16", "iLink 交流社区", "iLink交流社区",
                "2026-03-01", "2026-03-10", List.of("全国大学生智能汽车竞赛")),
            new Expectation("T17", "Link 团队空间模块", "Link 团队空间模块",
                "2026-04-11", "2026-04-21", List.of("全国大学生电子设计竞赛", "蓝桥杯全国软件和信息技术专业人才大赛")));

        for (Expectation expectation : cases) {
            JsonNode row = rows.get(expectation.id());
            assertTrue(row != null, expectation.id() + " 不在保存批次中");
            String question = row.path("question").asText();
            assertEquals(List.of(expectation.subject()), QueryIntent.subjectRequests(question).subjects(),
                expectation.id() + " 主体解析");

            List<EvidenceLedger.EntityFact> entities = entities(row);
            EvidenceLedger.EntityFact anchor = entities.stream()
                .filter(entity -> expectation.anchor().equals(entity.name())).findFirst().orElse(null);
            assertTrue(anchor != null, expectation.id() + " 缺少锚点实体 " + expectation.anchor());
            assertTrue(QueryIntent.sameSubjectName(expectation.subject(), anchor.name()),
                expectation.id() + " 请求名应能对齐锚点");

            KgTimeRange window = new KgTimeRange(LocalDate.parse(expectation.windowStart()),
                LocalDate.parse(expectation.windowEnd()));
            EvidenceLedger ledger = ledger(entities, relations(row), chunks(row));
            when(ledger.completeTemporal(eq(anchor.id()), any(KgTimeRange.class))).thenReturn(true);

            TemporalAnswerScope.Analysis scope = TemporalAnswerScope.assess(question,
                QueryEvidencePlan.fromQuestion(question), ledger);
            assertEquals(TemporalAnswerScope.Resolution.EXACT, scope.subjects().get(0).resolution(),
                expectation.id() + " 应解析为 EXACT");
            assertTrue(scope.requestedSubjectsConfirmed(), expectation.id() + " 主体应已确认");
            assertTrue(scope.requestedScopeComplete(), expectation.id() + " 时间窗应完整");

            List<String> hit = windowTargets(ledger, anchor.id(), window);
            assertTrue(hit.containsAll(expectation.targets()),
                expectation.id() + " 窗口内应命中 " + expectation.targets() + "，实际 " + hit);
        }
    }

    private static List<String> windowTargets(EvidenceLedger ledger, Long anchor, KgTimeRange window) {
        List<String> names = new ArrayList<>();
        for (EvidenceLedger.RelationFact relation : ledger.relations()) {
            Long other = anchor.equals(relation.sourceId()) ? relation.targetId()
                : anchor.equals(relation.targetId()) ? relation.sourceId() : null;
            if (other == null || relation.validFrom() == null) continue;
            if (!relation.validFrom().before(window.endDate())
                || relation.validTo() != null && !relation.validTo().after(window.startDate())) continue;
            ledger.entity(other).map(EvidenceLedger.EntityFact::name).ifPresent(names::add);
        }
        return names;
    }

    private static List<EvidenceLedger.EntityFact> entities(JsonNode row) {
        Map<Long, EvidenceLedger.EntityFact> found = new LinkedHashMap<>();
        for (JsonNode step : row.path("b3_trace")) {
            JsonNode observation = step.path("observation");
            if (!observation.isTextual()) continue;
            JsonNode parsed;
            try { parsed = JSON.readTree(observation.asText()); } catch (Exception ignored) { continue; }
            for (JsonNode entity : parsed.path("entities")) {
                long id = entity.path("entityId").asLong();
                found.putIfAbsent(id, new EvidenceLedger.EntityFact(id, entity.path("name").asText(),
                    entity.path("entityType").asText(), ""));
            }
            for (JsonNode edge : parsed.path("relations")) {
                long sourceId = edge.path("sourceId").asLong();
                long targetId = edge.path("targetId").asLong();
                found.putIfAbsent(sourceId, new EvidenceLedger.EntityFact(sourceId,
                    edge.path("sourceName").asText(), "", ""));
                found.putIfAbsent(targetId, new EvidenceLedger.EntityFact(targetId,
                    edge.path("targetName").asText(), "COMPETITION", ""));
            }
        }
        return List.copyOf(found.values());
    }

    private static List<EvidenceLedger.RelationFact> relations(JsonNode row) {
        Map<Long, EvidenceLedger.RelationFact> found = new LinkedHashMap<>();
        for (JsonNode step : row.path("b3_trace")) {
            JsonNode observation = step.path("observation");
            if (!observation.isTextual()) continue;
            JsonNode parsed;
            try { parsed = JSON.readTree(observation.asText()); } catch (Exception ignored) { continue; }
            for (JsonNode edge : parsed.path("relations")) {
                long id = edge.path("relationId").asLong();
                found.putIfAbsent(id, new EvidenceLedger.RelationFact(id,
                    edge.path("sourceId").asLong(), edge.path("targetId").asLong(),
                    edge.path("relationType").asText(), date(edge.path("validFrom").asText(null)),
                    date(edge.path("validTo").asText(null)), edge.path("sourceDocId").asText(null), null, ""));
            }
        }
        return List.copyOf(found.values());
    }

    private static List<EvidenceLedger.ChunkFact> chunks(JsonNode row) {
        Map<Long, EvidenceLedger.ChunkFact> found = new LinkedHashMap<>();
        for (JsonNode step : row.path("b3_trace")) {
            JsonNode observation = step.path("observation");
            if (!observation.isTextual()) continue;
            JsonNode parsed;
            try { parsed = JSON.readTree(observation.asText()); } catch (Exception ignored) { continue; }
            for (JsonNode chunk : parsed.path("chunks")) {
                long id = chunk.path("chunkId").asLong();
                found.putIfAbsent(id, new EvidenceLedger.ChunkFact(id, chunk.path("docType").asText(),
                    chunk.path("docId").asLong(), chunk.path("title").asText(),
                    chunk.path("text").asText(), chunk.path("startOffset").asInt(), chunk.path("endOffset").asInt()));
            }
        }
        return List.copyOf(found.values());
    }

    private static EvidenceLedger ledger(List<EvidenceLedger.EntityFact> entities,
                                         List<EvidenceLedger.RelationFact> relations,
                                         List<EvidenceLedger.ChunkFact> chunks) {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.entities()).thenReturn(entities);
        when(ledger.relations()).thenReturn(relations);
        when(ledger.chunks()).thenReturn(chunks);
        for (EvidenceLedger.EntityFact entity : entities)
            when(ledger.entity(entity.id())).thenReturn(Optional.of(entity));
        for (EvidenceLedger.RelationFact relation : relations)
            when(ledger.relation(relation.id())).thenReturn(Optional.of(relation));
        return ledger;
    }

    private static Date date(String value) {
        return value == null || value.isBlank() ? null
            : Date.from(LocalDate.parse(value).atStartOfDay(KgTimeRange.ZONE).toInstant());
    }
}
