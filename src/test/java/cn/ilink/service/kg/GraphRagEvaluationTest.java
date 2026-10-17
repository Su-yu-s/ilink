package cn.ilink.service.kg;

import cn.ilink.service.ai.AiClient;
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

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
@ActiveProfiles("dev")
@EnabledIfSystemProperty(named = "kgEval", matches = "true")
class GraphRagEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(GraphRagEvaluationTest.class);
    private static final String OUT_DIR = "E:/Code/html/Ilink/.workbuddy/eval_results";
    private static final String CASES_FILE = "E:/Code/html/Ilink/docs/评测图表/round2_cases.json";

    /** 禁止出现在答案中的"内部状态"表述（需求方口径：资料不足时直接给建议，不得暴露检索覆盖情况） */
    private static final List<String> INTERNAL_LEAK_PHRASES = Arrays.asList(
        "未收录", "没有相关记录", "没有找到相关", "未找到相关", "资料中未提到", "资料未提到",
        "知识库中没有", "知识库未", "图谱中没有", "图谱中未", "图谱未", "未包含", "未涵盖",
        "未检索到", "资料不足", "暂无相关", "无相关记录", "未命中");

    @Autowired
    private GraphRagService graphRagService;

    @Autowired
    private AgenticGraphRagService agenticGraphRagService;

    @SpyBean
    private AiClient aiClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadLocal<int[]> tokenCounter = ThreadLocal.withInitial(() -> new int[2]);

    @BeforeEach
    void wrapAiClient() {
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (result instanceof AiClient.AiChatResult) {
                AiClient.AiChatResult chat = (AiClient.AiChatResult) result;
                if (chat.promptTokens != null) {
                    tokenCounter.get()[0] += chat.promptTokens;
                }
                if (chat.completionTokens != null) {
                    tokenCounter.get()[1] += chat.completionTokens;
                }
            }
            return result;
        }).when(aiClient).chat(anyList(), anyInt());
    }

    @Test
    @DisplayName("第二轮评测批次执行（-DkgEvalBatch=1 E类 / 2 U类 / 3 T类）")
    void runBatch() throws Exception {
        int batch = Integer.parseInt(System.getProperty("kgEvalBatch", "1"));
        Long userId = 8L;
        JsonNode cases = objectMapper.readTree(new File(CASES_FILE)).get("cases");

        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode node : cases) {
            if (node.path("batch").asInt() != batch) {
                continue;
            }
            log.info("[第二轮评测] 开始 {} 题：{}", node.path("id").asText(), node.path("question").asText());
            results.add(evaluateOne(node, userId));
        }

        new File(OUT_DIR).mkdirs();
        File out = new File(OUT_DIR, "round2_batch" + batch + ".json");
        Files.write(out.toPath(), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(results));

        long discriminative = results.stream()
            .filter(row -> Boolean.FALSE.equals(row.get("b0_pass"))).count();
        long b2Hit = results.stream().filter(row -> Boolean.TRUE.equals(row.get("b2_pass"))).count();
        long b3Hit = results.stream().filter(row -> Boolean.TRUE.equals(row.get("b3_pass"))).count();
        System.out.printf("%n[第二轮 · 批次 %d 汇总] 题数=%d | B0 预筛：高区分度 %d 题 / 低区分度 %d 题 | "
                + "命中数 B2=%d B3=%d%n",
            batch, results.size(), discriminative, results.size() - discriminative, b2Hit, b3Hit);
        System.out.println("[第二轮] 明细已写入 " + out.getAbsolutePath());
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

        // ---- B0：纯 LLM 预筛（同时决定该题的区分度） ----
        try {
            int[] tokens = resetTokens();
            long start = System.currentTimeMillis();
            String answer = aiClient.chat(Arrays.asList(
                message("system", "你是高校竞赛领域助手。请直接回答学生的问题，使用 Markdown，不要使用任何 emoji。"),
                message("user", evalCase.question)), 800).content;
            row.put("b0_answer", answer);
            row.put("b0_ms", System.currentTimeMillis() - start);
            row.put("b0_promptTokens", tokens[0]);
            row.put("b0_completionTokens", tokens[1]);
            row.put("b0_pass", judge(evalCase, answer, List.of()).pass);
        } catch (Exception e) {
            row.put("b0_answer", "[调用失败] " + e.getMessage());
            row.put("b0_pass", false);
        }

        // ---- B2：固定 2 跳 ----
        try {
            int[] tokens = resetTokens();
            long start = System.currentTimeMillis();
            GraphRagService.GraphRagAnswer answer = graphRagService.query(evalCase.question, userId);
            row.put("b2_answer", answer.answer);
            row.put("b2_ms", System.currentTimeMillis() - start);
            row.put("b2_promptTokens", tokens[0]);
            row.put("b2_completionTokens", tokens[1]);
            row.put("b2_citations", answer.citations.size());
            row.put("b2_pass", judge(evalCase, answer.answer, idsFrom(answer.citations)).pass);
        } catch (Exception e) {
            row.put("b2_answer", "[调用失败] " + e.getMessage());
            row.put("b2_pass", false);
        }

        // ---- B3：Agentic ----
        try {
            int[] tokens = resetTokens();
            long start = System.currentTimeMillis();
            AgenticGraphRagService.AgenticAnswer answer = agenticGraphRagService.query(evalCase.question, userId);
            row.put("b3_answer", answer.answer);
            row.put("b3_ms", System.currentTimeMillis() - start);
            row.put("b3_promptTokens", tokens[0]);
            row.put("b3_completionTokens", tokens[1]);
            row.put("b3_rounds", answer.rounds);
            row.put("b3_toolCalls", answer.toolCalls);
            row.put("b3_fallback", answer.fallback);
            row.put("b3_traceSteps", answer.trace.size());
            row.put("b3_citations", answer.citations.size());
            row.put("b3_actions", actions(answer.trace));
            JudgeResult judge = judge(evalCase, answer.answer, idsFrom(answer.citations));
            row.put("b3_pass", judge.pass);
            row.put("b3_judgeNote", judge.note);
        } catch (Exception e) {
            row.put("b3_answer", "[调用失败] " + e.getMessage());
            row.put("b3_pass", false);
        }

        System.out.printf("[%s/%s] B0=%s B2=%s B3=%s | B0 %sms / B2 %sms / B3 %sms | tokens B3=%s%n",
            evalCase.id, evalCase.type, row.get("b0_pass"), row.get("b2_pass"), row.get("b3_pass"),
            row.getOrDefault("b0_ms", "-"), row.getOrDefault("b2_ms", "-"), row.getOrDefault("b3_ms", "-"),
            row.getOrDefault("b3_promptTokens", "-"));
        return row;
    }

    private int[] resetTokens() {
        int[] fresh = new int[2];
        tokenCounter.set(fresh);
        return tokenCounter.get();
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

    static JudgeResult judge(EvalCase evalCase, String answer, List<Long> citationIds) {
        if (answer == null || answer.trim().isEmpty()) {
            return new JudgeResult(false, "答案为空");
        }
        // v3.1：去空白 + 小写归一化后匹配（兼容「30 秒/30秒」「5 MB/5MB」「Point ROPE/PointROPE」等写法差异）
        String text = answer.toLowerCase().replaceAll("\\s+", "");
        String rawText = answer.toLowerCase();

        // 规则①：不得出现内部状态表述
        for (String phrase : INTERNAL_LEAK_PHRASES) {
            if (text.contains(phrase.toLowerCase().replaceAll("\\s+", ""))) {
                return new JudgeResult(false, "出现内部状态表述：" + phrase);
            }
        }
        // 规则②：答案中的实体 ID 必须全部来自本次引用列表（v3.2 堵「编造内容 + 引用列表非空」的漏洞）
        java.util.regex.Matcher cited = Pattern.compile("#(\\d{1,9})").matcher(answer);
        while (cited.find()) {
            long id = Long.parseLong(cited.group(1));
            if (!citationIds.contains(id)) {
                return new JudgeResult(false, "答案引用了未检索到的实体 #" + id);
            }
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
            return new JudgeResult(true, "给出通用建议且未暴露内部状态");
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

    /** 否定语境线索：禁词前后邻域出现这些词时，该次出现视为对禁词的否定性说明而非关联陈述 */
    private static final List<String> NEGATION_CUES = Arrays.asList(
        "不再", "已失效", "已结束", "已过期", "已下线", "没有", "并非", "除外", "以外", "未", "无", "不");

    /**
     * v3.1 forbid 检查：禁词的一次出现，若其前 6 字 / 后 10 字邻域内含否定线索，则豁免该次出现；
     * 全部出现均被豁免才算通过。使「小挑已失效，不应再关联」这类正确解释不被误判。
     */
    private static boolean violatesForbid(String lowerText, String forbidden) {
        Matcher matcher = Pattern.compile(forbidden, Pattern.CASE_INSENSITIVE).matcher(lowerText);
        while (matcher.find()) {
            String before = lowerText.substring(Math.max(0, matcher.start() - 6), matcher.start());
            String after = lowerText.substring(matcher.end(),
                Math.min(lowerText.length(), matcher.end() + 10));
            boolean negated = NEGATION_CUES.stream().anyMatch(before::contains)
                || NEGATION_CUES.stream().anyMatch(after::contains);
            if (!negated) {
                return true;
            }
        }
        return false;
    }

    /** 单个要点匹配：纯数字要点按词边界匹配（避免「300」误中「1300」），其余按归一化正则匹配 */
    private static boolean matchesOption(String option, String normalizedText) {
        String opt = option.toLowerCase().replaceAll("\\s+", "");
        Pattern pattern = opt.matches("\\d+")
            ? Pattern.compile("(?<!\\d)" + opt + "(?!\\d)")
            : Pattern.compile(opt, Pattern.CASE_INSENSITIVE);
        return pattern.matcher(normalizedText).find();
    }

    private static List<Long> idsFrom(List<Map<String, Object>> citations) {
        List<Long> ids = new ArrayList<>();
        if (citations == null) {
            return ids;
        }
        for (Map<String, Object> cite : citations) {
            Object v = cite.get("entityId");
            if (v instanceof Number) {
                ids.add(((Number) v).longValue());
            }
        }
        return ids;
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
