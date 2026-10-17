package cn.ilink.service.kg;

import cn.ilink.service.ai.AiClient;
import cn.ilink.config.AiProperties;
import cn.ilink.config.AiEmbeddingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/**
 * 【第二轮真实端到端评测 · 测试代码】按《评测与对比方案》§2.5 / §3.2.1 执行。
 *
 * <p>相比第一轮的三项升级：
 * ① **量表外置**：从 {@code docs/评测图表/round2_cases.json} 读取题目
 *    （v3.1：100 题 = E 证据依赖 30 / U 不可答 35 / T 时序 25 / M 多跳聚合 10），改题无需改代码；
 * ② **token 统计**：{@code @SpyBean AiClient} 包装 {@code chat()} 累加每题 prompt/completion tokens，支撑"每题正确成本"与"同质量成本"；
 * ③ **判定口径 v3.1**：答案中不得出现"未收录/没有相关记录/资料中未提到"等**内部状态表述**（出现即不通过）；
 *    禁止"编造引用"（答案里出现 #ID 但引用列表为空即不通过）；
 *    要点匹配先做**去空白+小写归一化**，纯数字要点按**词边界**匹配；
 *    forbid 禁词支持**否定语境豁免**（"小挑已失效，不再关联"这类正确解释不判违规）。
 *
 * <p>启用方式（默认禁用，不影响 {@code mvn test} 全量套件）：
 * <pre>
 * mvn test -DkgEval=true -DkgEvalBatch=1 -Dtest=GraphRagEvaluationTest   # 批 1-3 = E 类（各 10 题）
 * mvn test -DkgEval=true -DkgEvalBatch=4 -Dtest=GraphRagEvaluationTest   # 批 4-7 = U 类（10/10/10/5）
 * mvn test -DkgEval=true -DkgEvalBatch=8 -Dtest=GraphRagEvaluationTest   # 批 8 = M 多跳聚合 10 题
 * mvn test -DkgEval=true -DkgEvalBatch=9 -Dtest=GraphRagEvaluationTest   # 批 9-10 = T 类（13/12）
 * </pre>
 *
 * <p>前置：合成时序数据已插入（{@code docs/评测图表/round2_synthetic_temporal.sql}，评测后可回滚）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"dev", "local"})
@ContextConfiguration(initializers = KgStageRebuildTest.StageUrlGuard.class)
@EnabledIfSystemProperty(named = "kgEval", matches = "true")
class GraphRagEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(GraphRagEvaluationTest.class);
    private static final String DEFAULT_CASES_FILE = "docs/评测图表/round2_cases.json";
    static final String SCORER_VERSION = "v9-foundation-4";
    private static final String ORDER_POLICY = "round-latin-rotation-v1";

    /** 禁止出现在答案中的"内部状态"表述（需求方口径：资料不足时直接给建议，不得暴露检索覆盖情况） */
    private static final List<String> INTERNAL_LEAK_PHRASES = Arrays.asList(
        "未收录", "没有相关记录", "没有找到相关", "未找到相关", "资料中未提到", "资料未提到",
        "知识库中没有", "知识库未", "图谱中没有", "图谱中未", "图谱未", "未包含", "未涵盖",
        "未检索到", "资料不足", "暂无相关", "无相关记录", "未命中", "计数为0", "无直接关系记录");

    @Autowired
    private GraphRagService graphRagService;

    @Autowired
    private PlainRagService plainRagService;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private AiEmbeddingProperties embeddingProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgenticGraphRagService agenticGraphRagService;

    @SpyBean
    private AiClient aiClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadLocal<int[]> tokenCounter = ThreadLocal.withInitial(() -> new int[4]);

    @BeforeEach
    void wrapAiClient() {
        doAnswer(invocation -> {
            try (var observer = cn.ilink.service.ai.ChatUsage.observe(usage -> {
                int[] counter = tokenCounter.get();
                if (usage.promptTokens() != null) counter[0] += usage.promptTokens();
                if (usage.completionTokens() != null) counter[1] += usage.completionTokens();
                if (!usage.complete()) counter[2]++;
                counter[3]++;
            })) {
                return invocation.callRealMethod();
            }
        }).when(aiClient).chat(anyList(), anyInt());
    }

    @Test
    @DisplayName("第二轮评测批次执行（-DkgEvalBatch=1 E类 / 2 U类 / 3 T类）")
    void runBatch() throws Exception {
        String selection = System.getProperty("kgEvalBatch", "1");
        Long userId = 8L;
        Path casesPath = Path.of(System.getProperty("kgEvalCases", DEFAULT_CASES_FILE))
            .toAbsolutePath().normalize();
        JsonNode cases = objectMapper.readTree(casesPath.toFile()).get("cases");
        String outSetting = System.getProperty("kgEvalOutDir");
        if (outSetting == null || outSetting.isBlank()) {
            throw new IllegalArgumentException("必须提供 -DkgEvalOutDir=独立运行目录，避免覆盖历史结果");
        }
        Path outDir = Path.of(outSetting).toAbsolutePath().normalize();
        Files.createDirectories(outDir);
        writeOrValidateManifest(outDir, casesPath);
        if ("all".equalsIgnoreCase(selection)) {
            for (int batch = 1; batch <= 10; batch++) {
                runSelectedBatch(batch, cases, outDir, userId);
            }
        } else {
            runSelectedBatch(Integer.parseInt(selection), cases, outDir, userId);
        }
    }

    private void runSelectedBatch(int batch, JsonNode cases, Path outDir, Long userId)
        throws Exception {
        if (batch < 1 || batch > 10) {
            throw new IllegalArgumentException("kgEvalBatch 必须为 1..10 或 all");
        }
        Path out = outDir.resolve("batch" + batch + ".json");
        if (Files.exists(out)) {
            throw new IllegalStateException("评测批次结果已存在，禁止覆盖: " + out);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode node : cases) {
            String selectedIds = System.getProperty("kgEvalIds", "");
            if (!selectedIds.isBlank() && !Arrays.asList(selectedIds.split(",")).contains(node.path("id").asText())) continue;
            if (node.path("batch").asInt() != batch) {
                continue;
            }
            log.info("[第二轮评测] 开始 {} 题：{}", node.path("id").asText(), node.path("question").asText());
            Map<String, Object> result = evaluateOne(node, userId);
            GraphRagEvaluationHarness.validateRow(result);
            results.add(result);
        }

        Files.write(out, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(results),
            StandardOpenOption.CREATE_NEW);

        long b1Hit = results.stream().filter(row -> Boolean.TRUE.equals(row.get("b1_pass"))).count();
        long b2Hit = results.stream().filter(row -> Boolean.TRUE.equals(row.get("b2_pass"))).count();
        long b3Hit = results.stream().filter(row -> Boolean.TRUE.equals(row.get("b3_pass"))).count();
        System.out.printf("%n[同条件评测 · 批次 %d] 题数=%d | 命中数 B1=%d B2=%d B3=%d%n",
            batch, results.size(), b1Hit, b2Hit, b3Hit);
        System.out.println("[同条件评测] 明细已写入 " + out);
    }

    private Map<String, Object> evaluateOne(JsonNode node, Long userId) {
        EvalCase evalCase = EvalCase.from(node);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", evalCase.id);
        row.put("type", evalCase.type);
        row.put("question", evalCase.question);
        row.put("hits", evalCase.hits);
        row.put("forbid", evalCase.forbid);
        if (evalCase.note != null) {
            row.put("note", evalCase.note);
        }
        List<String> order = algorithmOrder();
        row.put("evaluationRound", evaluationRound());
        row.put("algorithmOrder", order);
        row.put("scorerVersion", SCORER_VERSION);
        for (String algorithm : order) {
            evaluateAlgorithm(evalCase, userId, algorithm, row);
        }

        System.out.printf("[%s/%s] B1=%s B2=%s B3=%s | B1 %sms / B2 %sms / B3 %sms | tokens B3=%s%n",
            evalCase.id, evalCase.type, row.get("b1_pass"), row.get("b2_pass"), row.get("b3_pass"),
            row.getOrDefault("b1_ms", "-"), row.getOrDefault("b2_ms", "-"), row.getOrDefault("b3_ms", "-"),
            row.getOrDefault("b3_promptTokens", "-"));
        return row;
    }

    private void evaluateAlgorithm(EvalCase evalCase, Long userId, String algorithm,
                                   Map<String, Object> row) {
        String prefix = algorithm.toLowerCase(java.util.Locale.ROOT);
        long started = System.currentTimeMillis();
        int[] tokens = resetTokens();
        String answerText;
        List<Map<String, Object>> citations;
        try {
            switch (algorithm) {
                case "B1" -> {
                    PlainRagService.PlainRagAnswer answer = plainRagService.query(evalCase.question, userId);
                    answerText = answer.answer;
                    citations = answer.citations;
                    row.put("b1_retrievedChunkCount", answer.retrievedChunkCount);
                }
                case "B2" -> {
                    GraphRagService.GraphRagAnswer answer = graphRagService.query(evalCase.question, userId);
                    answerText = answer.answer;
                    citations = answer.citations;
                }
                case "B3" -> {
                    AgenticGraphRagService.AgenticAnswer answer = agenticGraphRagService.query(evalCase.question, userId);
                    answerText = answer.answer;
                    citations = answer.citations;
                    row.put("b3_rounds", answer.rounds);
                    row.put("b3_toolCalls", answer.toolCalls);
                    row.put("b3_fallback", answer.fallback);
                    row.put("b3_traceSteps", answer.trace.size());
                    row.put("b3_trace", answer.trace);
                    for (String field : List.of("queryRoute", "routePolicy", "requestedDomain", "recommendationPreflight",
                        "graphAnswerScope", "graphCandidateInventory", "graphScopeInstruction", "generationEvidence",
                        "generationResult", "rewriteResult", "verificationEvidence", "verificationStage",
                        "answerCoverage", "coverageReason", "answerVerification", "temporalMatchPolicy",
                        "subjectIdentification", "subjectScopes", "optimizationStage", "strategyCandidate",
                        "temporalAnswerScope", "classificationSkipReason", "classificationCatalog",
                        "classificationContract", "classificationCriteria", "classificationDisposition", "classificationOutputShape",
                        "classificationEvidenceCoverage", "classificationVerificationResult", "classificationSelection",
                        "classificationMemberEvidence", "classificationRepairHints")) {
                        row.put("b3_" + field, answer.trace.stream().filter(step -> step.containsKey(field))
                            .map(step -> step.get(field)).reduce((first, last) -> last).orElse(null));
                    }
                    row.put("b3_answerMode", answer.trace.stream().filter(step -> step.containsKey("answerMode"))
                        .map(step -> step.get("answerMode")).reduce((first, last) -> last).orElse("EVIDENCE_ANSWER"));
                    row.put("b3_budget", answer.trace.stream().filter(step -> step.containsKey("requestBudget"))
                        .map(step -> step.get("requestBudget")).findFirst().orElse(Map.of()));
                    row.put("b3_actions", actions(answer.trace));
                }
                default -> throw new IllegalArgumentException("未知算法: " + algorithm);
            }
            JudgeResult judged = judge(evalCase, answerText, citations, algorithm);
            row.put(prefix + "_pass", judged.pass);
            row.put(prefix + "_judgeNote", judged.note);
            row.put(prefix + "_legacyPass", judge(evalCase, answerText, citations.size()).pass);
        } catch (Exception e) {
            answerText = "[调用失败] " + e.getMessage();
            citations = List.of();
            row.put(prefix + "_pass", false);
            row.put(prefix + "_judgeNote", "调用失败");
            row.put(prefix + "_legacyPass", false);
        }
        row.put(prefix + "_answer", answerText);
        row.put(prefix + "_ms", System.currentTimeMillis() - started);
        row.put(prefix + "_promptTokens", tokens[0]);
        row.put(prefix + "_completionTokens", tokens[1]);
        row.put(prefix + "_unknownUsageRequests", tokens[2]);
        row.put(prefix + "_modelRequests", tokens[3]);
        row.put(prefix + "_citations", citations);
        row.put(prefix + "_autoPass", row.get(prefix + "_pass"));
        // A lexical pass is not a factual verdict. Keep manual decisions separate from generated results.
        row.put(prefix + "_validatedPass", null);
        row.put(prefix + "_evidenceAudit", evidenceAudit(evalCase, answerText, citations.size()));
    }

    private static int evaluationRound() {
        int round = Integer.parseInt(System.getProperty("kgEvalRound", "1"));
        if (round < 1 || round > 3) {
            throw new IllegalArgumentException("kgEvalRound 必须为 1..3");
        }
        return round;
    }

    static List<String> algorithmOrder() {
        return switch (evaluationRound()) {
            case 1 -> List.of("B1", "B2", "B3");
            case 2 -> List.of("B2", "B3", "B1");
            case 3 -> List.of("B3", "B1", "B2");
            default -> throw new IllegalStateException("无效评测轮次");
        };
    }

    private static Map<String, Object> evidenceAudit(EvalCase evalCase, String answer, int citationCount) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("status", "PENDING");
        audit.put("citationCount", citationCount);
        audit.put("defects", null);
        audit.put("checks", evalCase.unanswerable
            ? List.of("FACT_SUPPORT", "RECOMMENDATION_APPLICABILITY", "ANSWER_CONSISTENCY", "COMPLETENESS")
            : List.of("FACT_SUPPORT", "ANSWER_CONSISTENCY", "COMPLETENESS"));
        if (evalCase.unanswerable) {
            // This signal only prioritizes human review; neither its presence nor absence proves correctness.
            audit.put("targetedRecommendationSignal", Pattern.compile(
                "(?:推荐|建议|适合|适配|匹配|可(?:以)?参加|参加|参赛|赛道)")
                .matcher(answer == null ? "" : answer).find());
            audit.put("note", "必须核实具体赛事对用户专业的适用依据；引用真实、字数达标不等于推荐成立。信号仅供审计排序。");
        } else {
            audit.put("note", "要点命中未验证事实支持、答案自相矛盾或列表完整性，等待证据审计。");
        }
        return audit;
    }

    private int[] resetTokens() {
        int[] fresh = new int[4];
        tokenCounter.set(fresh);
        return tokenCounter.get();
    }

    private void writeOrValidateManifest(Path outDir, Path casesPath) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("caseHash", sha256(Files.readAllBytes(casesPath)));
        manifest.put("caseFile", casesPath.toString());
        manifest.put("gitRevision", gitRevision());
        manifest.put("sourceHash", sourceHash());
        manifest.put("selectedIds", System.getProperty("kgEvalIds", "ALL"));
        manifest.put("evaluationDate", KgTimeRange.today().toString());
        manifest.put("timezone", KgTimeRange.ZONE.toString());
        manifest.put("evaluationUserId", 8);
        manifest.put("scorerVersion", SCORER_VERSION);
        manifest.put("evaluationRound", evaluationRound());
        manifest.put("algorithmOrderPolicy", ORDER_POLICY);
        manifest.put("algorithmOrder", algorithmOrder());
        manifest.put("factualAuditPolicy", "manual-evidence-audit-required; auto-pass-is-not-validated-pass");
        manifest.put("runPurpose", System.getProperty("kgEvalPurpose", "development"));
        manifest.put("promptReservation", "qwen-sdk-bpe-plus-framing-allowance; server-template-not-exact");
        manifest.put("model", aiProperties.getModel());
        manifest.put("temperature", aiProperties.getTemperature());
        manifest.put("embeddingModel", embeddingProperties.getModel());
        manifest.put("database", jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
        manifest.put("corpusHash", hashQuery("SELECT doc_type,doc_id,title,content,scope,team_id,user_id"
            + " FROM kg_index_task ORDER BY doc_type,doc_id", 7));
        manifest.put("graphHash", hashQuery("SELECT id,name,display_name,entity_type,description,scope,team_id,user_id,status"
            + " FROM kg_entity ORDER BY id", 9)
            + ":" + hashQuery("SELECT id,source_id,target_id,relation_type,fact_text,valid_from,valid_to,"
                + "scope,team_id,user_id,source_doc_id,source_chunk_id FROM kg_relation ORDER BY id", 12)
            + ":" + hashQuery("SELECT entity_id,model,dim,text_hash,SHA2(vector,256)"
                + " FROM kg_entity_embedding ORDER BY entity_id,model", 5));
        manifest.put("chunkHash", hashQuery("SELECT doc_type,doc_id,ordinal_no,content_hash,"
            + "scope,team_id,user_id,embedding_model,embedding_dim,SHA2(embedding,256)"
            + " FROM kg_document_chunk ORDER BY doc_type,doc_id,ordinal_no", 10));
        manifest.put("entityCount", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kg_entity", Long.class));
        manifest.put("relationCount", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kg_relation", Long.class));
        manifest.put("chunkCount", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kg_document_chunk", Long.class));
        manifest.put("b1TopK", 8);
        manifest.put("b2TopK", GraphRagService.TOP_K);
        manifest.put("b3MaxToolCalls", AgenticGraphRagService.MAX_TOOL_CALLS);
        manifest.put("b3MaxRounds", AgenticGraphRagService.MAX_ROUNDS);
        manifest.put("tokenizerHash", cn.ilink.service.ai.QwenTokenCounter.VOCAB_SHA256);
        String requiredSnapshot = System.getProperty("kgEvalSnapshotHash");
        if (requiredSnapshot != null && !requiredSnapshot.equals(manifest.get("graphHash"))) {
            throw new IllegalStateException("图谱快照与 kgEvalSnapshotHash 不一致");
        }
        String frozenManifest = System.getProperty("kgEvalFrozenManifest");
        if (frozenManifest != null && !frozenManifest.isBlank()) {
            JsonNode frozen = objectMapper.readTree(Path.of(frozenManifest).toFile());
            // Normalize numeric node types through JSON, as the frozen manifest was loaded from JSON too.
            JsonNode current = objectMapper.readTree(objectMapper.writeValueAsBytes(manifest));
            for (String key : manifest.keySet()) {
                if (Set.of("evaluationRound", "algorithmOrder").contains(key)) continue;
                if (!current.path(key).equals(frozen.path(key))) {
                    throw new IllegalStateException("轮间冻结条件变化，禁止继续生成答案: " + key);
                }
            }
        }
        Path path = outDir.resolve("manifest.json");
        byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        if (Files.exists(path)) {
            JsonNode previous = objectMapper.readTree(path.toFile());
            if (!previous.equals(objectMapper.readTree(bytes))) {
                throw new IllegalStateException("当前模型或数据快照与已有评测批次不一致: " + path);
            }
        } else {
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
        }
    }

    private static String sourceHash() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> paths = new ArrayList<>();
        for (String root : List.of("src/main/java", "src/test/java", "src/main/resources/db/migration", "tools/kg_eval", "src/main/resources/tokenizers")) {
            try (var files = Files.walk(Path.of(root))) {
                files.filter(Files::isRegularFile).filter(p -> p.toString().matches(".*\\.(java|sql|py|ps1|tiktoken)$"))
                    .forEach(paths::add);
            }
        }
        paths.add(Path.of("pom.xml"));
        paths.add(Path.of("src/main/resources/application.yml"));
        paths.sort(java.util.Comparator.comparing(p -> p.toString().replace('\\', '/')));
        for (Path path : paths) {
            digest.update(path.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0); digest.update(Files.readAllBytes(path)); digest.update((byte) 0);
        }
        return hex(digest.digest());
    }

    private String hashQuery(String sql, int columns) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        jdbcTemplate.query(sql, rs -> {
            for (int index = 1; index <= columns; index++) {
                Object value = rs.getObject(index);
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            digest.update((byte) 10);
        });
        return hex(digest.digest());
    }

    private static String sha256(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte value : data) {
            out.append(String.format("%02x", value & 0xff));
        }
        return out.toString();
    }

    private static String gitRevision() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD").start();
            String revision = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? revision : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ==================== 判定口径 v3 ====================

    static class JudgeResult {
        final boolean pass;
        final String note;

        JudgeResult(boolean pass, String note) {
            this.pass = pass;
            this.note = note;
        }
    }

    static JudgeResult judge(EvalCase evalCase, String answer, int citationCount) {
        if (answer == null || answer.trim().isEmpty()) {
            return new JudgeResult(false, "答案为空");
        }
        // v3.1：去空白 + 小写归一化后匹配（兼容「30 秒/30秒」「5 MB/5MB」「Point ROPE/PointROPE」等写法差异）
        String text = answer.toLowerCase().replaceAll("\\s+", "");
        String rawText = answer.toLowerCase();

        // 规则①：不得出现内部状态表述
        for (String phrase : evalCase.unanswerable ? INTERNAL_LEAK_PHRASES : List.<String>of()) {
            if (text.contains(phrase.toLowerCase().replaceAll("\\s+", ""))) {
                return new JudgeResult(false, "出现内部状态表述：" + phrase);
            }
        }
        // 规则②：不得编造引用（答案含 #ID 但引用列表为空）
        if (citationCount == 0 && Pattern.compile("#\\d+").matcher(CitationText.forReferences(answer)).find()) {
            return new JudgeResult(false, "答案含 #ID 引用但引用列表为空（疑似编造引用）");
        }
        // 规则③：不得出现 forbid 中的实体名（时序题校验时间窗过滤）；否定语境豁免见 violatesForbid
        for (String forbidden : evalCase.forbid) {
            if (violatesForbid(rawText, forbidden)) {
                return new JudgeResult(false, "出现了不该出现的实体（窗口外）：" + forbidden);
            }
        }
        // 规则④：U 类须给出可执行建议（≥80 字），且不得把不存在的赛事说成平台收录
        if (evalCase.unanswerable) {
            if (answer.length() < 80) {
                return new JudgeResult(false, "未给出实质性建议（长度不足）");
            }
            if (text.contains("平台收录") || text.contains("平台已收录")) {
                return new JudgeResult(false, "把不存在的赛事说成平台收录");
            }
            return new JudgeResult(true, "通过U类形式规则；推荐适用性及事实支持待人工审计");
        }
        // 规则⑤：要点分组须全部命中（hits 为空则只需通过规则①~③）
        for (String group : evalCase.hits) {
            boolean matched = false;
            for (String option : group.split("\\|")) {
                if (matchesOption(option, text)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return new JudgeResult(false, "要点未命中：" + group);
            }
        }
        return new JudgeResult(true, "全部要点命中");
    }

    static JudgeResult judge(EvalCase evalCase, String answer,
                             List<Map<String, Object>> citations, String algorithm) {
        JudgeResult legacy = judge(evalCase, answer, citations.size());
        if (!legacy.pass) {
            return legacy;
        }
        Set<Long> entities = new HashSet<>();
        Set<Long> relations = new HashSet<>();
        Set<Long> chunks = new HashSet<>();
        Set<String> documents = new HashSet<>();
        for (Map<String, Object> citation : citations) {
            addId(citation.get("entityId"), entities);
            addId(citation.get("relationId"), relations);
            addId(citation.get("chunkId"), chunks);
            if (citation.get("docType") != null && citation.get("docId") != null) {
                documents.add(String.valueOf(citation.get("docType")).toUpperCase()
                    + ":" + citation.get("docId"));
            }
        }
        Set<Long> plain = "B1".equals(algorithm) ? chunks : entities;
        JudgeResult invalid = unknownReference(CitationText.forReferences(answer),
            Pattern.compile("(?<![\\p{L}\\p{N}])#(\\d+)"), plain, "#");
        if (invalid != null) {
            return invalid;
        }
        invalid = unknownReference(CitationText.forReferences(answer), Pattern.compile("r#(\\d+)"), relations, "r#");
        if (invalid != null) {
            return invalid;
        }
        invalid = unknownReference(CitationText.forReferences(answer), Pattern.compile("c#(\\d+)"), chunks, "c#");
        if (invalid != null) {
            return invalid;
        }
        Matcher docRefs = Pattern.compile("(?i)(POST|COMPETITION|TASK|ASSET|EVAL_FIXTURE)[_:](\\d+)")
            .matcher(answer);
        while (docRefs.find()) {
            String ref = docRefs.group(1).toUpperCase() + ":" + docRefs.group(2);
            if (!documents.contains(ref)) {
                return new JudgeResult(false, "答案引用了未证实的文档：" + ref);
            }
        }
        if (!"B1".equals(algorithm)) {
            for (Map<String, Object> citation : citations) {
                Object id = citation.get("entityId");
                Object name = citation.get("name");
                if (id != null && name != null && !answer.contains(String.valueOf(name))) {
                    String marker = "#" + id;
                    int index = answer.indexOf(marker);
                    if (index > 0) {
                        String nearby = answer.substring(Math.max(0, index - 35), index);
                        if (nearby.matches("(?s).*(?:挑战赛|竞赛|大赛|论文|记录)\\s*")) {
                            return new JudgeResult(false, "实体名称与引用 ID 不一致：" + marker);
                        }
                    }
                }
            }
        }
        // Apply the same public-answer completeness rule to B1, B2 and B3.
        // Do not rely on B3-only self-reported metadata to make the baselines look better.
        boolean graphOrCollection = QueryIntent.fromQuestion(evalCase.question).graphRequested()
            || QueryEvidencePlan.fromQuestion(evalCase.question).requiresRelation()
            || Pattern.compile("分类|归类|不属于|分别|对比|比较|差异|区别").matcher(evalCase.question).find();
        if (!evalCase.unanswerable && graphOrCollection && explicitlyIncomplete(answer))
            return new JudgeResult(false, "答案明确限定为原文摘录或部分结果，未完整回答所问图谱/集合要求；事实仍待审计");
        return legacy;
    }

    private static boolean explicitlyIncomplete(String answer) {
        // Limit this deterministic guard to explicit scope statements in the answer's own voice.
        // Quoted source material is evidence, and must not be treated as the assistant's limitation.
        String prose = answer.replaceAll("(?s)```.*?```|“[^”]*”|\"[^\"]*\"", "")
            .replaceAll("[*`_\\s]", "");
        return Pattern.compile("供核对部分内容|结果仅覆盖本次可核对部分|以下列出已核对的部分结果"
            + "|(?:尚不能|无法|不能)(?:完整)?(?:确认|证实)(?:所问|上述)?的?图谱(?:关联|关系|集合)"
            + "|无法确认图谱中是否存在关联边"
            + "|(?:无法|不能|尚未)确认(?:上述|这些|所问)?(?:技能|实体|节点)[^。！？；]{0,100}"
                + "图谱中[^。！？；]{0,50}(?:关联|关系)").matcher(prose).find();
    }

    private static void addId(Object raw, Set<Long> ids) {
        if (raw instanceof Number number) {
            ids.add(number.longValue());
        } else if (raw != null) {
            try {
                ids.add(Long.parseLong(String.valueOf(raw)));
            } catch (NumberFormatException ignored) {
                // A malformed citation cannot support a numeric reference.
            }
        }
    }

    private static JudgeResult unknownReference(String answer, Pattern pattern,
                                                Set<Long> allowed, String prefix) {
        Matcher matcher = pattern.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            try {
                if (!allowed.contains(Long.parseLong(matcher.group(1)))) {
                    return new JudgeResult(false, "答案含无效引用：" + prefix + matcher.group(1));
                }
            } catch (NumberFormatException e) {
                return new JudgeResult(false, "答案含无效引用：" + prefix + matcher.group(1));
            }
        }
        return null;
    }

    private static final Pattern EXCLUDED_BEFORE_ENTITY = Pattern.compile(
        "(?:排除|不包括|不包含|不涉及|未涉及|不关联|未关联|不再关联|没有关联|而非|并非|不是)\\s*$");
    private static final Pattern DOUBLE_NEGATION_BEFORE_ENTITY = Pattern.compile(
        "(?:不是|并非|并不是|不能说|不表示|不意味着)"
            + "(?:不包括|不包含|不涉及|未涉及|不关联|未关联|不再关联|没有关联)\\s*$");
    private static final Pattern EXCLUDED_ENTITY_PREDICATE = Pattern.compile(
        "^(?:的)?(?:关系|关联|记录|有效期|比赛|赛事|竞赛)?"
            + "(?:也|均|都|全部)?(?:已|已经|现已|目前|现在|当前)?(?:于)?"
            + "(?:[0-9年月日:/ .-]{0,24})?(?:已|已经)?"
            + "(?:失效|结束|过期|下线|终止|不再关联|不关联|未关联|没有关联|无关联|不涉及|未涉及|被排除|不应计入"
            + "|不在(?:该|此|当前|上述)?(?:窗口|时间段|时段|范围)内)");

    /**
     * 排除说明只豁免谓词明确作用于该次实体的提及。日期可以位于实体与结束谓词之间，
     * 如「ACM-ICPC 的关系已于 2026-02-28 结束」。其他分句里的否定词不豁免当前实体。
     * 模糊复合句仍按违规记录并由人工审计纠正，避免宽泛的「不/无」窗口误放过肯定关联。
     */
    private static boolean violatesForbid(String lowerText, String forbidden) {
        String text = lowerText.replaceAll("[*`_]", "");
        Matcher matcher = Pattern.compile(forbidden, Pattern.CASE_INSENSITIVE).matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            int left = start;
            int right = end;
            while (left > 0 && start - left < 96 && !isClauseBoundary(text.charAt(left - 1))) left--;
            while (right < text.length() && right - end < 96 && !isClauseBoundary(text.charAt(right))) right++;
            String before = text.substring(left, start).strip();
            String after = text.substring(end, right).strip();
            // The forbid may be a prefix (ACM in ACM-ICPC); retain the grammatical predicate after its suffix.
            after = after.replaceFirst("^[a-z0-9+./·—-]*", "").strip()
                .replaceFirst("^[（(]", "").strip();
            boolean negated = !DOUBLE_NEGATION_BEFORE_ENTITY.matcher(before).find()
                && (EXCLUDED_BEFORE_ENTITY.matcher(before).find()
                    || EXCLUDED_ENTITY_PREDICATE.matcher(after).find());
            if (!negated) {
                return true;
            }
        }
        return false;
    }

    private static boolean isClauseBoundary(char value) {
        return "，,。；;！!？?\n\r".indexOf(value) >= 0;
    }

    /** 单个要点匹配：纯数字要点按词边界匹配（避免「300」误中「1300」），其余按归一化正则匹配 */
    private static boolean matchesOption(String option, String normalizedText) {
        String opt = option.toLowerCase().replaceAll("\\s+", "");
        Pattern pattern = opt.matches("\\d+")
            ? Pattern.compile("(?<!\\d)" + opt + "(?!\\d)")
            : Pattern.compile(opt, Pattern.CASE_INSENSITIVE);
        return pattern.matcher(normalizedText).find();
    }

    private static List<String> actions(List<Map<String, Object>> trace) {
        List<String> actions = new ArrayList<>();
        if (trace != null) {
            for (Map<String, Object> step : trace) {
                Object action = step.get("action");
                if (action != null) {
                    actions.add(String.valueOf(action));
                }
            }
        }
        return actions;
    }

    private static Map<String, String> message(String role, String content) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }

    /** 量表条目（从 round2_cases.json 读取） */
    static final class EvalCase {
        final String id;
        final String type;
        final String question;
        final List<String> hits;
        final List<String> forbid;
        final boolean unanswerable;
        final String note;

        EvalCase(String id, String type, String question, List<String> hits,
                 List<String> forbid, boolean unanswerable, String note) {
            this.id = id;
            this.type = type;
            this.question = question;
            this.hits = hits;
            this.forbid = forbid;
            this.unanswerable = unanswerable;
            this.note = note;
        }

        static EvalCase from(JsonNode node) {
            List<String> hits = new ArrayList<>();
            node.path("hits").forEach(item -> hits.add(item.asText()));
            List<String> forbid = new ArrayList<>();
            node.path("forbid").forEach(item -> forbid.add(item.asText()));
            return new EvalCase(
                node.path("id").asText(),
                node.path("type").asText(),
                node.path("question").asText(),
                hits,
                forbid,
                node.path("unanswerable").asBoolean(false),
                node.path("note").isMissingNode() ? null : node.path("note").asText());
        }
    }
}
