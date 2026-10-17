package cn.ilink.service.kg;

import cn.ilink.entity.KgAgentTrace;
import cn.ilink.mapper.KgAgentTraceMapper;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.kg.tool.GraphTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agentic GraphRAG：ReAct 循环（Thought → Action → Observation → … → Final Answer）。
 * 终止条件（取先）：LLM 输出 Final Answer；达到 MAX_ROUNDS 轮；连续 2 轮无新信息；
 * 工具调用次数超过 MAX_TOOL_CALLS。
 * 任何异常降级到 GraphRagService（固定 2 跳 fast RAG），并在 kg_agent_trace 留痕。
 */
@Service
@Slf4j
public class AgenticGraphRagService {

    // 实测 Agnes 免费模型稳定性一般：轮数越多失败面越大、耗时越长。5 轮内绝大多数问题足够收敛。
    static final int MAX_ROUNDS = 5;
    static final int MAX_TOOL_CALLS = 12;

    private final GraphRagService fastRag;
    private final KgAgentTraceMapper traceMapper;
    private final AiClient aiClient;
    private final List<GraphTool> tools;
    private final Map<String, GraphTool> toolsByName = new LinkedHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** SSE/前端事件回调（阶段 5 流式展示用；同步调用传 null） */
    public interface AgenticStreamListener {
        void onThought(int round, String text);

        void onAction(int round, String tool, String paramsJson);

        void onObservation(int round, String text);

        void onFinal(String answer, Object citations, Object trace);
    }

    /** 最终回答 + 完整推理链 */
    public static class AgenticAnswer {
        public final String answer;
        public final List<Map<String, Object>> citations;
        public final List<Map<String, Object>> trace;
        public final int rounds;
        public final int toolCalls;
        public final boolean fallback;

        public AgenticAnswer(String answer, List<Map<String, Object>> citations,
                             List<Map<String, Object>> trace, int rounds, int toolCalls, boolean fallback) {
            this.answer = answer;
            this.citations = citations;
            this.trace = trace;
            this.rounds = rounds;
            this.toolCalls = toolCalls;
            this.fallback = fallback;
        }
    }

    @Autowired
    public AgenticGraphRagService(GraphRagService fastRag, KgAgentTraceMapper traceMapper,
                                  AiClient aiClient, List<GraphTool> tools) {
        this.fastRag = fastRag;
        this.traceMapper = traceMapper;
        this.aiClient = aiClient;
        this.tools = tools;
        for (GraphTool tool : tools) {
            toolsByName.put(tool.getName(), tool);
        }
    }

    /** 对外主入口：ReAct 循环，异常自动降级 fast RAG */
    public AgenticAnswer query(String question, Long userId) {
        return query(question, userId, null);
    }

    /** 带流式回调的入口（阶段 5 SSE 直接复用） */
    public AgenticAnswer query(String question, Long userId, AgenticStreamListener listener) {
        long startedAt = System.currentTimeMillis();
        List<Map<String, Object>> trace = new ArrayList<>();
        try {
            return runLoop(question, userId, trace, listener, startedAt);
        } catch (Exception e) {
            log.warn("[Agentic] ReAct 循环失败，回落到快速答疑: {}", e.getMessage());
            AgenticAnswer fallback = fallbackToFastRag(question, userId, trace);
            persistTrace(question, userId, trace, fallback, startedAt, KgAgentTrace.STATUS_FALLBACK);
            return fallback;
        }
    }

    // ==================== ReAct 循环 ====================

    private AgenticAnswer runLoop(String question, Long userId, List<Map<String, Object>> trace,
                                  AgenticStreamListener listener, long startedAt) {
        StringBuilder history = new StringBuilder();
        int toolCalls = 0;
        int noNewInfoStreak = 0;
        int groundingRetries = 0;
        String lastObservation = null;

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            String output = askModel(question, history.toString());
            ReActStep step = parseStep(output);

            if (step.finalAnswer != null) {
                // Grounded 校验（第三轮评测发现：模型会编造 entityId）。答案引用的实体 ID
                // 必须都在工具 Observation 真实返回过的 ID 集合里；越界打回重写一次。
                java.util.Set<Long> seenIds = collectObservedEntityIds(trace);
                List<Long> fabricated = fabricatedEntityIds(step.finalAnswer, seenIds);
                if (!fabricated.isEmpty() && groundingRetries < 1) {
                    groundingRetries++;
                    log.info("[Agentic] Final Answer 引用未检索实体 {}，打回重写: question={}",
                        fabricated, abbreviate(question, 40));
                    emitThought(listener, round, step.thought);
                    trace.add(stepMap(round, "[grounded] 答案引用了未检索到的实体 " + fabricated
                        + "，已要求重写", null, null, null));
                    history.append("Thought: ").append(nullToEmpty(step.thought)).append('\n')
                        .append("Final Answer: ").append(step.finalAnswer).append("\n\n")
                        .append("Supervisor: 上面的 Final Answer 引用了工具结果中不存在的实体 ID ")
                        .append(fabricated).append("。禁止编造：只能引用 Observation 中真实出现过的实体；")
                        .append("若信息不足以引用实体，就基于通用知识收尾，不要虚构平台记录。")
                        .append("请重新给出 Final Answer。\n\n");
                    continue;
                }
                emitThought(listener, round, step.thought);
                trace.add(stepMap(round, step.thought, null, null, step.finalAnswer));
                // P0-1：最终答案剔除内部状态表述，面向用户的正文只留结论
                AgenticAnswer answer = new AgenticAnswer(
                    GraphRagService.stripInternalStateSentences(step.finalAnswer),
                    extractCitations(trace), trace, round, toolCalls, false);
                persistTrace(question, userId, trace, answer, startedAt, KgAgentTrace.STATUS_OK);
                emitFinal(listener, answer);
                return answer;
            }

            String action = step.action;
            String actionInput = step.actionInput == null ? "{}" : step.actionInput;
            emitThought(listener, round, step.thought);
            emitAction(listener, round, action, actionInput);
            trace.add(stepMap(round, step.thought, action, actionInput, null));

            // 工具调用超限：强制收束
            if (toolCalls >= MAX_TOOL_CALLS) {
                AgenticAnswer answer = summarize(question, trace, userId, round, toolCalls);
                persistTrace(question, userId, trace, answer, startedAt, KgAgentTrace.STATUS_OK);
                emitFinal(listener, answer);
                return answer;
            }

            String observation = invokeTool(action, actionInput, userId);
            toolCalls++;
            emitObservation(listener, round, observation);
            trace.add(stepMap(round, null, null, null, observation));

            // 无新信息检测：连续 2 轮出现相同观察，或连续 2 轮都是未命中类错误
            if (observation.equals(lastObservation) || isErrorObservation(observation)) {
                noNewInfoStreak++;
            } else {
                noNewInfoStreak = 0;
            }
            lastObservation = observation;
            if (noNewInfoStreak >= 2) {
                log.info("[Agentic] 连续 2 轮无新信息，提前收束: question={}", abbreviate(question, 40));
                break;
            }

            history.append("Thought: ").append(nullToEmpty(step.thought)).append('\n')
                .append("Action: ").append(action).append('\n')
                .append("Action Input: ").append(actionInput).append('\n')
                .append("Observation: ").append(abbreviate(observation, 700)).append("\n\n");
        }

        // 达到轮数上限 / 无新信息：用已有观察总结一次
        AgenticAnswer answer = summarize(question, trace, userId, MAX_ROUNDS, toolCalls);
        persistTrace(question, userId, trace, answer, startedAt, KgAgentTrace.STATUS_OK);
        emitFinal(listener, answer);
        return answer;
    }

    /** 单轮调用：问题 + 历史 + 工具清单 */
    String askModel(String question, String history) {
        String prompt = REACT_PROMPT_TEMPLATE
            .replace("{tools_description}", buildToolsDescription())
            .replace("{history}", history.isEmpty() ? "（尚无历史，这是第 1 轮）" : history)
            .replace("{question}", question);
        return aiClient.chat(List.of(
                AiMsg.system("你是一个严谨的知识图谱探索专家，严格按指定格式输出，不输出任何多余文本。"
                    + "生成的 Final Answer 面向学生用户：不要描述检索过程或资料覆盖情况，"
                    + "也不要出现\"未收录\"\"没有相关记录\"\"资料中未提到\"之类的表述；"
                    + "证据不足时直接给出可执行的通用建议，但不得声称图谱中存在你没见过的记录。"
                    + GraphRagService.currentDateLine()),
                AiMsg.user(prompt)), 600).content;
    }

    // ==================== 输出解析 ====================

    /** 一轮 ReAct 输出的解析结果：要么是 Final Answer，要么是 Action + Input */
    static class ReActStep {
        String thought;
        String action;
        String actionInput;
        String finalAnswer;

        boolean isFinal() {
            return finalAnswer != null;
        }
    }

    /**
     * 容错解析：按行找 Thought:/Action:/Action Input:/Final Answer: 前缀。
     * 全部缺失时，把整段文本当 Final Answer（弱模型的常见输出，保底可用）。
     */
    static ReActStep parseStep(String raw) {
        ReActStep step = new ReActStep();
        if (raw == null || raw.trim().isEmpty()) {
            step.finalAnswer = "（本轮模型没有输出，重试或换策略）";
            return step;
        }
        String[] lines = raw.trim().split("\n");
        StringBuilder current = null;
        String currentKey = null;
        Map<String, StringBuilder> captured = new LinkedHashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            String key = null;
            if (startsWithKey(trimmed, "Thought:")) {
                key = "thought";
            } else if (startsWithKey(trimmed, "Action Input:")) {
                key = "actionInput";
            } else if (startsWithKey(trimmed, "Action:")) {
                key = "action";
            } else if (startsWithKey(trimmed, "Final Answer:")) {
                key = "finalAnswer";
            }
            if (key != null) {
                if (current != null && currentKey != null) {
                    captured.put(currentKey, current);
                }
                currentKey = key;
                current = new StringBuilder(trimmed.substring(trimmed.indexOf(':') + 1).trim());
            } else if (current != null) {
                current.append('\n').append(trimmed);
            }
        }
        if (current != null && currentKey != null) {
            captured.put(currentKey, current);
        }
        step.thought = captured.containsKey("thought") ? captured.get("thought").toString().trim() : null;
        step.action = captured.containsKey("action") ? captured.get("action").toString().trim() : null;
        step.actionInput = captured.containsKey("actionInput")
            ? captured.get("actionInput").toString().trim() : null;
        step.finalAnswer = captured.containsKey("finalAnswer")
            ? captured.get("finalAnswer").toString().trim() : null;

        if (step.finalAnswer == null && step.action == null) {
            // 整段无格式：视为最终回答兜底
            step.finalAnswer = raw.trim();
        }
        if (step.action != null && step.actionInput == null) {
            step.actionInput = "{}";
        }
        return step;
    }

    private static boolean startsWithKey(String line, String key) {
        return line.regionMatches(true, 0, key, 0, key.length());
    }

    // ==================== 工具分发 ====================

    /** 调用工具；未知工具/参数错误/执行异常都转成结构化 Observation（不抛出） */
    String invokeTool(String action, String actionInputJson, Long userId) {
        GraphTool tool = toolsByName.get(action);
        if (tool == null) {
            return "{\"error\":\"UNKNOWN_TOOL\",\"message\":\"工具 " + action
                + " 不存在。可用工具：" + toolsByName.keySet() + "\"}";
        }
        Map<String, Object> params;
        try {
            JsonNode node = objectMapper.readTree(actionInputJson == null || actionInputJson.isEmpty()
                ? "{}" : actionInputJson);
            params = objectMapper.convertValue(node, Map.class);
        } catch (Exception e) {
            return "{\"error\":\"INVALID_INPUT\",\"message\":\"Action Input 不是合法 JSON："
                + escape(actionInputJson) + "\"}";
        }
        try {
            Object result = tool.execute(params, userId);
            String text = result instanceof String ? (String) result
                : objectMapper.writeValueAsString(result);
            return abbreviate(text, 2000);
        } catch (Exception e) {
            log.warn("[Agentic] 工具 {} 执行异常: {}", action, e.getMessage());
            return "{\"error\":\"TOOL_FAILED\",\"message\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    static boolean isErrorObservation(String observation) {
        return observation != null && observation.contains("\"error\":\"")
            && (observation.contains("NO_RESULTS") || observation.contains("PERMISSION_DENIED")
                || observation.contains("UNKNOWN_TOOL") || observation.contains("INVALID_INPUT")
                || observation.contains("NOT_FOUND"));
    }

    /** 轮数用尽后用已有 trace 总结出 Final Answer（模型若仍带格式前缀，解析后再取答案） */
    private AgenticAnswer summarize(String question, List<Map<String, Object>> trace,
                                    Long userId, int rounds, int toolCalls) {
        StringBuilder observations = new StringBuilder();
        for (Map<String, Object> entry : trace) {
            Object obs = entry.get("observation");
            if (obs != null) {
                observations.append("- ").append(abbreviate(String.valueOf(obs), 300)).append('\n');
            }
        }
        String prompt = "以下是探索知识图谱时收集到的观察记录：\n" + observations
            + "\n用户问题：" + question + "\n\n请基于以上信息直接给出最终回答（不要提到探索过程）。";
        String raw = aiClient.chat(List.of(
                AiMsg.system(GraphRagService.SYSTEM_PROMPT),
                AiMsg.user(prompt)), 2048).content;
        // 弱模型可能仍按 ReAct 格式输出，剥掉前缀取真正的答案；再过滤内部状态表述
        ReActStep step = parseStep(raw);
        String answer = GraphRagService.stripInternalStateSentences(
            step.finalAnswer != null ? step.finalAnswer : raw);
        return new AgenticAnswer(answer, extractCitations(trace), trace, rounds, toolCalls, false);
    }

    // ==================== 降级与落库 ====================

    /** 降级：固定 2 跳 fast RAG（方案 5.6 的 fallbackToFastRag） */
    AgenticAnswer fallbackToFastRag(String question, Long userId, List<Map<String, Object>> trace) {
        try {
            GraphRagService.GraphRagAnswer fast = fastRag.query(question, userId);
            return new AgenticAnswer(fast.answer, fast.citations, trace, 0, 0, true);
        } catch (Exception inner) {
            log.warn("[Agentic] 快速答疑也失败: {}", inner.getMessage());
            return new AgenticAnswer("当前 AI 服务不可用，请稍后重试。",
                new ArrayList<>(), trace, 0, 0, true);
        }
    }

    private void persistTrace(String question, Long userId, List<Map<String, Object>> trace,
                              AgenticAnswer answer, long startedAt, String status) {
        try {
            KgAgentTrace row = new KgAgentTrace();
            row.setUserId(userId == null ? 0L : userId);
            row.setQuestion(abbreviate(question, 1000));
            row.setTrace(objectMapper.writeValueAsString(trace));
            row.setAnswer(answer.answer);
            row.setCitations(objectMapper.writeValueAsString(answer.citations));
            row.setRounds(answer.rounds);
            row.setToolCalls(answer.toolCalls);
            row.setStatus(status);
            row.setElapsedMs(System.currentTimeMillis() - startedAt);
            row.setCreatedAt(new Date());
            traceMapper.insert(row);
        } catch (Exception e) {
            log.warn("[Agentic] 推理链落库失败（不影响回答）: {}", e.getMessage());
        }
    }

    // ==================== 杂项 ====================

    private String buildToolsDescription() {
        StringBuilder sb = new StringBuilder();
        for (GraphTool tool : tools) {
            sb.append("- ").append(tool.getName()).append("：").append(tool.getDescription())
                .append("\n  参数：").append(tool.getParametersSchema()).append('\n');
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> extractCitations(List<Map<String, Object>> trace) {
        // 从观察 JSON 中收集出现过的 entityId + name（去重，最多 12 个）。
        // 两趟扫描：先取带 name 的完整对，再扫纯 id；都基于原始文本只调 find()，避免任何重写文本的循环。
        List<Map<String, Object>> citations = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        java.util.regex.Pattern withName = java.util.regex.Pattern
            .compile("\"entityId\":(\\d+),\"name\":\"([^\"]+)\"");
        java.util.regex.Pattern idOnly = java.util.regex.Pattern
            .compile("\"entityId\":(\\d+)");
        for (Map<String, Object> entry : trace) {
            Object obs = entry.get("observation");
            if (!(obs instanceof String)) {
                continue;
            }
            String text = (String) obs;
            java.util.regex.Matcher matcher = withName.matcher(text);
            while (matcher.find()) {
                Long id = Long.valueOf(matcher.group(1));
                if (seen.contains(id)) {
                    continue;
                }
                seen.add(id);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("entityId", id);
                row.put("name", matcher.group(2));
                citations.add(row);
                if (citations.size() >= 12) {
                    return citations;
                }
            }
            if (citations.size() >= 12) {
                return citations;
            }
            matcher = idOnly.matcher(text);
            while (matcher.find()) {
                Long id = Long.valueOf(matcher.group(1));
                if (seen.contains(id)) {
                    continue;
                }
                seen.add(id);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("entityId", id);
                citations.add(row);
                if (citations.size() >= 12) {
                    return citations;
                }
            }
        }
        return citations;
    }

    /** 收集工具 Observation 里真实出现过的全部 entityId（grounded 校验的事实源） */
    static java.util.Set<Long> collectObservedEntityIds(List<Map<String, Object>> trace) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"entityId\":(\\d+)");
        for (Map<String, Object> entry : trace) {
            Object obs = entry.get("observation");
            if (!(obs instanceof String)) {
                continue;
            }
            java.util.regex.Matcher m = p.matcher((String) obs);
            while (m.find()) {
                ids.add(Long.valueOf(m.group(1)));
            }
        }
        return ids;
    }

    /** 提取 Final Answer 中出现的实体 ID（#3 / entityId: 251 / 实体ID: 1001 等写法），返回不在事实源里的（编造的） */
    static List<Long> fabricatedEntityIds(String finalAnswer, java.util.Set<Long> seenIds) {
        List<Long> fabricated = new ArrayList<>();
        if (finalAnswer == null || finalAnswer.isEmpty()) {
            return fabricated;
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "#(\\d{1,9})"
                + "|(?i)entity\\s*id\\p{Z}*[:：]?\\p{Z}*(\\d{1,9})"
                + "|实体\\s*ID\\p{Z}*[:：]?\\p{Z}*(\\d{1,9})");
        java.util.regex.Matcher m = p.matcher(finalAnswer);
        while (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) {
                    Long id = Long.valueOf(m.group(g));
                    if (!seenIds.contains(id) && !fabricated.contains(id)) {
                        fabricated.add(id);
                    }
                    break;
                }
            }
        }
        return fabricated;
    }

    private static Map<String, Object> stepMap(int round, String thought, String action,
                                               String actionInput, String observation) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("round", round);
        if (thought != null) {
            map.put("thought", thought);
        }
        if (action != null) {
            map.put("action", action);
        }
        if (actionInput != null) {
            map.put("actionInput", actionInput);
        }
        if (observation != null) {
            map.put("observation", observation);
        }
        return map;
    }

    private static void emitThought(AgenticStreamListener listener, int round, String text) {
        if (listener != null && text != null && !text.isEmpty()) {
            listener.onThought(round, text);
        }
    }

    private static void emitAction(AgenticStreamListener listener, int round, String tool, String paramsJson) {
        if (listener != null) {
            listener.onAction(round, tool, paramsJson);
        }
    }

    private static void emitObservation(AgenticStreamListener listener, int round, String text) {
        if (listener != null) {
            listener.onObservation(round, text);
        }
    }

    private static void emitFinal(AgenticStreamListener listener, AgenticAnswer answer) {
        if (listener != null) {
            listener.onFinal(answer.answer, answer.citations, answer.trace);
        }
    }

    static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 方案 5.4 的 ReAct Prompt 模板（2026-09-24 按第三轮评测结论重写探索策略） */
    static final String REACT_PROMPT_TEMPLATE =
        "你是一个知识图谱探索专家。你可以调用以下工具来获取信息。\n\n"
            + "【可用工具】\n{tools_description}\n\n"
            + "【探索策略】按问题类型选择对应规则：\n"
            + "1) 问题在问「哪些 / 列举 / 关联 / 提到 / 涉及 / 有什么」（需要多条证据）："
            + "必须先用 search_entities 定位主体实体，再用 get_relations 或 expand_neighbors "
            + "展开该实体的关系之后才能回答。只凭 search 命中的实体描述就作答属于违规——"
            + "实体描述不包含它的关联清单。\n"
            + "2) 时间敏感问题（含具体年月、时间段）：必须先定位实体，再调用 query_temporal "
            + "查询该实体在时间窗内的有效关系，禁止猜测 entityId 或时间窗。\n"
            + "3) 单点属性问题（某实体是什么、某概念解释）：search 命中后即可回答，无需额外探索。\n"
            + "4) 检索无结果：换关键词或换工具重试一次，仍无结果才可基于通用知识收尾，"
            + "且不得虚构平台上的记录。\n"
            + "每轮 Thought 明确「当前还缺什么信息」；要点尚未收集齐时禁止输出 Final Answer。\n\n"
            + "【答案风格】Final Answer 只给结论与可执行建议，不提及检索过程、命中情况或资料覆盖范围"
            + "（不要出现\"未收录\"\"没有相关记录\"\"资料中未提到\"等表述）。"
            + "答案中的实体引用只能来自 Observation 里真实出现过的实体。\n\n"
            + "【输出格式】\n你必须严格按以下格式输出，不要有任何多余文本：\n\n"
            + "Thought: <你的思考>\nAction: <工具名>\nAction Input: <JSON 格式的参数>\n\n"
            + "或者：\n\nThought: <你的思考>\nFinal Answer: <最终答案>\n\n"
            + "【历史】\n{history}\n\n"
            + "【用户问题】\n{question}\n\n"
            + "【你的输出】";

    /** 消息构造小工具 */
    private static final class AiMsg {
        private static Map<String, String> system(String content) {
            return message("system", content);
        }

        private static Map<String, String> user(String content) {
            return message("user", content);
        }

        private static Map<String, String> message(String role, String content) {
            Map<String, String> map = new HashMap<>();
            map.put("role", role);
            map.put("content", content);
            return map;
        }
    }
}
