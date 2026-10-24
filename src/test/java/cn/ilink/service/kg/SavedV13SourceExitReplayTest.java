package cn.ilink.service.kg;

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
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zero-model replay of v12.1 source failures. It consumes saved tool observations, not saved model
 * prose, and verifies only deterministic behavior that can be established from that snapshot.
 */
@EnabledIfSystemProperty(named = "kgV13SourceReplay", matches = "true")
class SavedV13SourceExitReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String E8 = "平台介绍里用哪句话概括 iLink 的组队理念？";
    private static final String M1 =
        "知识图谱里 iLink 平台本身（ilink 实体）直接关联了哪些竞赛？";
    private static final String M6 =
        "「高校学生竞赛成果管理平台」这条成果记录在图谱里关联了哪些技术栈？";
    private static final String M10 = "知识图谱里哪些帖子记录提到了 LitePT？";
    private static final String M3 =
        "团队空间模块开发日志在图谱里关联了哪些前端交互设计和拖拽接口预留？";

    @Test
    void savedSourcesReachDeterministicExitsWithoutANewModelCall() throws Exception {
        Path directory = Path.of(System.getProperty("kgV13SourceReplayDir", ""))
            .toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(directory), "Saved batch directory does not exist: " + directory);
        Map<String, JsonNode> rows = readRows(directory);
        for (String id : List.of("M1", "M6", "E8", "M3", "M10"))
            assertTrue(rows.containsKey(id), id + " missing from saved snapshot");

        EvidenceLedger m1Ledger = ledger(rows.get("M1"));
        List<Map<String, Object>> m1Trace = new ArrayList<>();
        AgenticGraphRagService.AgenticAnswer m1 =
            AgenticGraphRagService.structuredSourceCompetitionListForReplay(
                M1, m1Ledger, m1Trace, 0, 4);
        assertNotNull(m1, "M1 saved source did not reach the unique-list exit");
        assertTrue(m1.answer.contains("c#380"), m1.answer);
        assertEquals(List.of("互联网+", "挑战杯", "数模"),
            m1Trace.get(1).get("items"));
        assertTrue(m1.answer.contains("“互联网+”"));
        assertTrue(m1.answer.contains("“挑战杯”"));
        assertTrue(m1.answer.contains("“数模”"));
        assertTrue(m1.citations.stream().anyMatch(citation ->
            Long.valueOf(380L).equals(citation.get("chunkId"))));

        EvidenceLedger m6Ledger = ledger(rows.get("M6"));
        List<Map<String, Object>> m6Trace = new ArrayList<>();
        AgenticGraphRagService.AgenticAnswer m6 =
            AgenticGraphRagService.structuredSourceTechnologySummaryForReplay(
                M6, m6Ledger, m6Trace, 0, 4);
        assertNotNull(m6, "M6 saved source did not reach the technology-summary exit");
        for (String technology : List.of("Spring Boot 2.7", "MyBatis Plus", "Thymeleaf",
            "Spring Security", "Nginx")) {
            assertTrue(m6.answer.contains(technology), technology + " missing from: " + m6.answer);
        }
        assertTrue(m6.answer.contains("c#261"), m6.answer);
        assertTrue(m6.citations.stream().anyMatch(citation ->
            Long.valueOf(261L).equals(citation.get("chunkId"))));

        EvidenceLedger e8Ledger = ledger(rows.get("E8"));
        SourcePhraseSelector.Selection phrase = SourcePhraseSelector.select(E8, e8Ledger).orElse(null);
        assertNotNull(phrase, "E8 title slogan was not selected");
        assertEquals(379L, phrase.chunkId());
        assertEquals("同声相应", phrase.phrase());
        AnswerEvidenceVerifier.VerificationResult wrong = new AnswerEvidenceVerifier().verify(
            E8, "平台介绍里用“竞赛不是一个人的全力以赴，是一群人的同频共振”这句话概括组队理念。c#379",
            e8Ledger, QueryEvidencePlan.fromQuestion(E8));
        assertFalse(wrong.supported(), "A real but wrong source sentence must remain rejected");
        assertTrue(wrong.reasons().contains("LITERAL_SOURCE_QUOTE_CONFLICT:c#379"),
            wrong.reasons().toString());
        AnswerEvidenceVerifier.VerificationResult correct = new AnswerEvidenceVerifier().verify(
            E8, "平台介绍里用“同声相应”这句话概括组队理念。c#379",
            e8Ledger, QueryEvidencePlan.fromQuestion(E8));
        assertTrue(correct.supported(), correct.reasons().toString());

        EvidenceLedger m10Ledger = ledger(rows.get("M10"));
        List<Map<String, Object>> m10Trace = new ArrayList<>();
        AgenticGraphRagService.AgenticAnswer m10 =
            AgenticGraphRagService.structuredSourceRecordList(M10, m10Ledger, m10Trace, 0, 2);
        assertNotNull(m10);
        assertTrue(m10.answer.contains("POST:6"));
        assertTrue(m10.answer.contains("iLink交流社区文件上传问题修复&&LitePT分享"));
        assertTrue(m10.answer.contains("c#371"));
        assertTrue(m10.citations.stream().allMatch(citation ->
            "POST".equals(citation.get("docType")) && Long.valueOf(6L).equals(citation.get("docId"))));
        assertEquals("STRUCTURED_SOURCE_RECORD_LIST", m10Trace.get(1).get("answerMode"));
        assertEquals(false, m10Trace.get(1).get("absenceConclusionAllowed"));

        EvidenceLedger m3Ledger = ledger(rows.get("M3"));
        SourceRecordExcerptSelector.Selection m3Source =
            SourceRecordExcerptSelector.select(M3, m3Ledger).orElse(null);
        assertNotNull(m3Source, "The saved M3 record did not reach the deterministic source exit");
        assertEquals(Set.of(341L, 342L),
            m3Source.chunks().stream().map(EvidenceLedger.ChunkFact::id)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(m3Source.text().contains("Kanban"));
        assertTrue(m3Source.text().contains("data-task-id"));
        assertTrue(m3Source.text().contains("拖拽更新状态"));
        List<Map<String, Object>> m3DeterministicTrace = new ArrayList<>();
        AgenticGraphRagService.AgenticAnswer m3Deterministic =
            AgenticGraphRagService.namedRecordSourceAnswer(M3, m3Ledger, m3DeterministicTrace, 0, 3);
        assertNotNull(m3Deterministic);
        assertEquals("STRUCTURED_NAMED_RECORD_SOURCE", m3DeterministicTrace.get(1).get("answerMode"));
        assertEquals(Set.of(341L, 342L), m3Deterministic.citations.stream()
            .map(citation -> (Long) citation.get("chunkId"))
            .collect(java.util.stream.Collectors.toSet()));
        // Use a coverage-only verdict mirroring the saved semantic result to exercise the actual
        // named-record exit and its technical-state cleanup without making a verifier request.
        var draft = rows.get("M3").path("b3_generationResult").path("draft").asText();
        var check = new AnswerEvidenceVerifier.VerificationResult(false,
            List.of("SEMANTIC_UNSUPPORTED:COVERAGE:只有原文支持，图谱关系尚未完整确认"),
            java.util.Set.of(), java.util.Set.of(), java.util.Set.of(341L, 342L, 264L, 376L));
        GraphAnswerScope.Scope scope = GraphAnswerScope.describe(M3, m3Ledger);
        List<Map<String, Object>> m3Trace = new ArrayList<>();
        AgenticGraphRagService.AgenticAnswer m3 = AgenticGraphRagService.sourceScopedRecordAnswer(
            M3, draft, check, scope, m3Ledger, m3Trace, 0, 3);
        assertNotNull(m3, "The saved M3 coverage-only answer did not reach the named-record exit. "
            + "subject=" + QueryIntent.fromQuestion(M3).primarySubject()
            + " subjectRecords=" + SubjectScopeLedger.assess(M3, m3Ledger).subjects()
            + " chunks=" + check.usedChunkIds().stream().map(m3Ledger::chunk).toList());
        assertFalse(m3.answer.contains("图谱中未确认存在"), m3.answer);
        assertFalse(m3.answer.contains("图谱查询完整=true"), m3.answer);
        assertTrue(m3Source.text().contains("Kanban"), m3Source.text());
        assertTrue(m3Source.text().contains("data-task-id"), m3Source.text());

        EvidenceLedger m4Ledger = ledger(rows.get("M4"));
        SourceRecordExcerptSelector.Selection m4Source =
            SourceRecordExcerptSelector.select(rows.get("M4").path("question").asText(), m4Ledger)
                .orElse(null);
        assertNotNull(m4Source, "The saved M4 record did not reach the deterministic source exit");
        assertEquals(Set.of(344L, 345L),
            m4Source.chunks().stream().map(EvidenceLedger.ChunkFact::id)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(m4Source.text().contains("JDK 动态代理"));
        assertTrue(m4Source.text().contains("CGLIB"));
        List<Map<String, Object>> m4DeterministicTrace = new ArrayList<>();
        AgenticGraphRagService.AgenticAnswer m4Deterministic =
            AgenticGraphRagService.namedRecordSourceAnswer(rows.get("M4").path("question").asText(),
                m4Ledger, m4DeterministicTrace, 0, 3);
        assertNotNull(m4Deterministic);
        assertEquals("STRUCTURED_NAMED_RECORD_SOURCE", m4DeterministicTrace.get(1).get("answerMode"));
        assertEquals(Set.of(344L, 345L), m4Deterministic.citations.stream()
            .map(citation -> (Long) citation.get("chunkId"))
            .collect(java.util.stream.Collectors.toSet()));
        String m4Draft = rows.get("M4").path("b3_generationResult").path("draft").asText();
        Set<Long> m4Chunks = referencedIds(m4Draft, Pattern.compile("c#(\\d+)"));
        Set<Long> m4Relations = referencedIds(m4Draft, Pattern.compile("r#(\\d+)"));
        var m4Check = new AnswerEvidenceVerifier.VerificationResult(false,
            List.of("SEMANTIC_UNSUPPORTED:COVERAGE:只有原文支持，图谱关系尚未完整确认"),
            Set.of(), m4Relations, m4Chunks);
        List<Map<String, Object>> m4Trace = new ArrayList<>();
        AgenticGraphRagService.AgenticAnswer m4 = AgenticGraphRagService.sourceScopedRecordAnswer(
            rows.get("M4").path("question").asText(), m4Draft, m4Check,
            GraphAnswerScope.describe(rows.get("M4").path("question").asText(), m4Ledger),
            m4Ledger, m4Trace, 0, 3);
        assertNotNull(m4, "The saved M4 coverage-only answer did not reach the named-record exit");
        assertFalse(m4.answer.contains("图谱查询完整=false"), m4.answer);
        assertTrue(m4.answer.contains("动态代理"), m4.answer);
        assertTrue(m4.answer.contains("JDK"), m4.answer);
        assertTrue(m4.answer.contains("CGLIB"), m4.answer);

        EvidenceLedger m2Ledger = ledger(rows.get("M2"));
        String m2Question = rows.get("M2").path("question").asText();
        SourceHostSummary.Result hosts = SourceHostSummary.build(m2Question, m2Ledger).orElse(null);
        assertNotNull(hosts, "The saved M2 source records did not yield a host-field summary");
        assertTrue(hosts.text().contains("教指委"));
        assertTrue(hosts.text().contains("全国高等学校计算机教育研究会"));
        assertEquals(Set.of(291L, 336L), hosts.chunkIds());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sampleKind", "V12_1_SAVED_ZERO_MODEL_SOURCE_EXIT_REPLAY");
        report.put("sourceDirectory", directory.toString());
        report.put("manifestSourceHash", manifestHash(directory.resolve("manifest.json")));
        report.put("m1AnswerMode", m1Trace.get(1).get("answerMode"));
        report.put("m1SelectedChunkId", 380L);
        report.put("m1Items", m1Trace.get(1).get("items"));
        report.put("m6AnswerMode", m6Trace.get(1).get("answerMode"));
        report.put("m6SelectedChunkId", 261L);
        report.put("m6Answer", m6.answer);
        report.put("e8SelectedPhrase", phrase.phrase());
        report.put("e8SelectedChunkId", phrase.chunkId());
        report.put("e8WrongLiteralReasons", wrong.reasons());
        report.put("e8CorrectLiteralSupported", correct.supported());
        report.put("m10AnswerMode", m10Trace.get(1).get("answerMode"));
        report.put("m10Records", m10.citations);
        report.put("m3AnswerMode", m3Trace.get(0).get("answerMode"));
        report.put("m3DeterministicSourceChunkIds", m3Source.chunks().stream()
            .map(EvidenceLedger.ChunkFact::id).toList());
        report.put("m3TechnicalStateRemoved", true);
        report.put("m4AnswerMode", m4Trace.get(0).get("answerMode"));
        report.put("m4DeterministicSourceChunkIds", m4Source.chunks().stream()
            .map(EvidenceLedger.ChunkFact::id).toList());
        report.put("m4TechnicalStateRemoved", true);
        report.put("m2SourceHostChunks", hosts.chunkIds());
        report.put("aiInvocations", 0);
        report.put("limitations", "Deterministic exits only. This does not assert an end-to-end score "
            + "and does not re-run semantic fact verification.");
        Path output = Path.of("target/kg-v13-source-replay",
            "saved-v12.1-source-exit-" + System.currentTimeMillis() + ".json");
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("v13 source exit replay report: " + output.toAbsolutePath());
    }

    private static EvidenceLedger ledger(JsonNode row) throws Exception {
        return SavedV13FoundationReplayTest.replayLedger(row);
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
        return Files.isRegularFile(manifest)
            ? JSON.readTree(Files.readAllBytes(manifest)).path("sourceHash").asText("UNAVAILABLE")
            : "UNAVAILABLE";
    }

    private static Set<Long> referencedIds(String text, Pattern pattern) {
        Set<Long> ids = new java.util.LinkedHashSet<>();
        var matcher = pattern.matcher(text);
        while (matcher.find()) ids.add(Long.parseLong(matcher.group(1)));
        return ids;
    }
}
