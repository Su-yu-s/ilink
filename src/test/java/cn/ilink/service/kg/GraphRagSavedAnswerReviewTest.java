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

/** Offline review tool: reuses the actual Java scorer, without Spring, database or model calls. */
@EnabledIfSystemProperty(named = "kgReviewSaved", matches = "true")
class GraphRagSavedAnswerReviewTest {
    private static final List<String> ALGORITHMS = List.of("b1", "b2", "b3");

    @Test
    void reviewSavedAnswers() throws Exception {
        Path input = Path.of(required("kgReviewInput")).toAbsolutePath().normalize();
        Path output = Path.of(required("kgReviewOutput")).toAbsolutePath().normalize();
        if (Files.exists(output)) throw new IllegalStateException("Review output already exists: " + output);
        ObjectMapper json = new ObjectMapper();
        Path manifestPath = input.resolve("manifest.json");
        JsonNode originalManifest = json.readTree(Files.readAllBytes(manifestPath));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("purpose", "SAVED_ANSWER_RESCORING_NOT_NEW_GENERATION");
        manifest.put("scorerVersion", GraphRagEvaluationTest.SCORER_VERSION);
        manifest.put("originalManifest", originalManifest);
        manifest.put("inputDirectory", input.toString());
        manifest.put("inputManifestHash", sha256(manifestPath));
        manifest.put("judgeSourceHash", sha256(Path.of("src/test/java/cn/ilink/service/kg/GraphRagEvaluationTest.java")));
        manifest.put("newModelRequests", 0);
        manifest.put("factualAuditStatus", "NOT_PERFORMED_BY_THIS_TOOL");
        Map<String, String> batchHashes = new LinkedHashMap<>();
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (String algorithm : ALGORITHMS) counts.put(algorithm, new int[6]);
        List<Map<String, Object>> reviewed = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        int primaryTotal = 0;
        for (int batch = 1; batch <= 10; batch++) {
            Path file = input.resolve("batch" + batch + ".json");
            byte[] bytes = Files.readAllBytes(file);
            batchHashes.put(file.getFileName().toString(), sha256(bytes));
            JsonNode rows = json.readTree(bytes);
            if (!rows.isArray()) throw new IllegalStateException("Batch is not an array: " + file);
            for (JsonNode row : rows) {
                String id = row.path("id").asText();
                if (id.isBlank() || !ids.add(id)) throw new IllegalStateException("Invalid or duplicate ID: " + id);
                GraphRagEvaluationTest.EvalCase parsed = GraphRagEvaluationTest.EvalCase.from(row);
                if (!Set.of("E", "T", "M", "U").contains(parsed.type) || parsed.question.isBlank()
                    || !row.path("hits").isArray() || !row.path("forbid").isArray())
                    throw new IllegalStateException(id + ": malformed saved case definition");
                // Saved answer rows omit unanswerable; the frozen case format identifies U questions by type.
                GraphRagEvaluationTest.EvalCase evalCase = new GraphRagEvaluationTest.EvalCase(parsed.id, parsed.type,
                    parsed.question, parsed.hits, parsed.forbid,
                    row.has("unanswerable") ? row.path("unanswerable").asBoolean() : "U".equals(parsed.type), parsed.note);
                boolean primary = List.of("E", "T", "M").contains(evalCase.type);
                if (primary) primaryTotal++;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("type", evalCase.type);
                item.put("question", evalCase.question);
                item.put("sourceBatch", file.toString());
                Map<String, Object> algorithms = new LinkedHashMap<>();
                for (String algorithm : ALGORITHMS) {
                    if (!row.path(algorithm + "_pass").isBoolean()
                        || !row.path(algorithm + "_answer").isTextual()
                        || !row.path(algorithm + "_citations").isArray())
                        throw new IllegalStateException(id + ": missing saved fields for " + algorithm);
                    List<Map<String, Object>> citations = new ArrayList<>();
                    for (JsonNode citation : row.path(algorithm + "_citations")) {
                        if (!citation.isObject()) throw new IllegalStateException(id + ": malformed citation");
                        citations.add(json.convertValue(citation,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
                    }
                    String answer = row.path(algorithm + "_answer").asText();
                    GraphRagEvaluationTest.JudgeResult verdict = GraphRagEvaluationTest.judge(evalCase,
                        answer, citations, algorithm.toUpperCase(java.util.Locale.ROOT));
                    boolean old = row.path(algorithm + "_pass").asBoolean();
                    int[] tally = counts.get(algorithm);
                    tally[0] += old ? 1 : 0;
                    tally[1] += verdict.pass ? 1 : 0;
                    tally[2] += primary && old ? 1 : 0;
                    tally[3] += primary && verdict.pass ? 1 : 0;
                    tally[4] += !primary && old ? 1 : 0;
                    tally[5] += !primary && verdict.pass ? 1 : 0;
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("originalAutoPass", old);
                    result.put("rescoredAutoPass", verdict.pass);
                    result.put("rescoredJudgeNote", verdict.note);
                    result.put("answer", answer);
                    result.put("citations", citations);
                    // Preserve prior audit information as source material; do not turn re-scoring into an audit.
                    result.put("originalValidatedPass", row.path(algorithm + "_validatedPass"));
                    result.put("originalEvidenceAudit", row.path(algorithm + "_evidenceAudit"));
                    algorithms.put(algorithm, result);
                }
                item.put("algorithms", algorithms);
                reviewed.add(item);
            }
        }
        if (reviewed.isEmpty()) throw new IllegalStateException("No saved answers found");
        manifest.put("sourceBatchHashes", batchHashes);
        manifest.put("caseCount", reviewed.size());
        StringBuilder report = new StringBuilder("# 保存答案统一重评\n\n")
            .append("评分器：").append(GraphRagEvaluationTest.SCORER_VERSION)
            .append("。未重新生成答案、未调用模型或数据库。原分保留；本工具没有完成独立事实审计。\n\n")
            .append("| 算法 | 原总分 | 新自动总分 | 原 E+T+M | 新自动 E+T+M | 原 U | 新 U |\n")
            .append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (String algorithm : ALGORITHMS) {
            int[] tally = counts.get(algorithm);
            report.append('|').append(algorithm.toUpperCase(java.util.Locale.ROOT)).append('|')
                .append(tally[0]).append('/').append(reviewed.size()).append('|')
                .append(tally[1]).append('/').append(reviewed.size()).append('|')
                .append(tally[2]).append('/').append(primaryTotal).append('|')
                .append(tally[3]).append('/').append(primaryTotal).append('|')
                .append(tally[4]).append('/').append(reviewed.size() - primaryTotal).append('|')
                .append(tally[5]).append('/').append(reviewed.size() - primaryTotal).append("|\n");
        }
        report.append("\n## 分数变化\n\n");
        for (Map<String, Object> item : reviewed) {
            @SuppressWarnings("unchecked") Map<String, Object> algorithms = (Map<String, Object>) item.get("algorithms");
            for (String algorithm : ALGORITHMS) {
                @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) algorithms.get(algorithm);
                if (!result.get("originalAutoPass").equals(result.get("rescoredAutoPass")))
                    report.append("- ").append(item.get("id")).append('/').append(algorithm)
                        .append("：").append(result.get("originalAutoPass")).append(" → ")
                        .append(result.get("rescoredAutoPass")).append("；")
                        .append(result.get("rescoredJudgeNote")).append('\n');
            }
        }
        Files.createDirectories(output);
        Files.write(output.resolve("review_manifest.json"), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest),
            StandardOpenOption.CREATE_NEW);
        Files.write(output.resolve("review.json"), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(reviewed),
            StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("review.md"), report.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing property: " + key);
        return value;
    }

    private static String sha256(Path path) throws Exception { return sha256(Files.readAllBytes(path)); }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
