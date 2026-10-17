package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.KgDocEntity;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.User;
import cn.ilink.mapper.KgDocEntityMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.ai.AiAssistantService;
import cn.ilink.service.kg.AgenticGraphRagService;
import cn.ilink.service.kg.GraphRagService;
import cn.ilink.service.kg.KgScopeService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agentic GraphRAG 问答接口（阶段 2 固定检索版 + 阶段 3 Agentic 版 + 阶段 5 流式版）。
 * 全部为新增端点，走现有 Session 认证与 CSRF 机制，返回统一 Result<T>。
 */
@RestController
@RequestMapping("/api/ai/graph-rag")
@Slf4j
public class GraphRagController {

    private static final int MAX_QUESTION_LEN = 500;

    /** SSE 流超时：Agentic 多轮探索比单轮答疑慢，留足余量 */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** SSE 推送线程池：多轮 ReAct 是分钟级长阻塞，与容器线程解耦 */
    private final ExecutorService sseExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "kg-agentic-sse");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    void shutdownSseExecutor() {
        sseExecutor.shutdownNow();
    }

    @Autowired
    private GraphRagService graphRagService;

    @Autowired
    private cn.ilink.service.kg.AgenticGraphRagService agenticGraphRagService;

    @Autowired
    private AiAssistantService aiAssistantService;

    @Autowired
    private KgEntityMapper kgEntityMapper;

    @Autowired
    private KgDocEntityMapper kgDocEntityMapper;

    @Autowired
    private KgScopeService kgScopeService;

    @Autowired
    private cn.ilink.service.impl.CompetitionServiceImpl competitionService;

    /**
     * 解析可选的竞赛上下文：payload 带合法 competitionId 时返回「【绑定竞赛：名称】」前缀，
     * 让检索与生成同时感知当前关注的竞赛；无/非法则返回空串（通用提问）。
     */
    private String competitionContext(Object raw) {
        if (raw == null) {
            return "";
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text) || "undefined".equalsIgnoreCase(text)) {
            return "";
        }
        try {
            cn.ilink.entity.Competition competition =
                competitionService.getById(Long.valueOf(text));
            if (competition == null || competition.getName() == null
                    || competition.getName().trim().isEmpty()) {
                return "";
            }
            return "【用户当前关注的竞赛：" + competition.getName().trim() + "】";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /**
     * 实体详情（引用芯片点击弹出实体卡片用）：基本信息 + 抽取来源文档清单。
     * 遵循同一套可见性规则，私有/团队外实体一律 403。
     */
    @GetMapping("/entity/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> entityDetail(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        KgEntity entity = kgEntityMapper.selectById(id);
        if (entity == null || !KgEntity.STATUS_ACTIVE.equals(entity.getStatus())) {
            return Result.notFound("实体不存在").toResponseEntity();
        }
        if (!kgScopeService.canSee(entity, user.getId())) {
            return Result.forbidden().toResponseEntity();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entityId", entity.getId());
        data.put("name", entity.getDisplayName());
        data.put("entityType", entity.getEntityType());
        data.put("description", entity.getDescription());
        data.put("scope", entity.getScope());
        List<Map<String, Object>> sources = new ArrayList<>();
        for (KgDocEntity mapping : kgDocEntityMapper.selectList(
                new QueryWrapper<KgDocEntity>().eq("entity_id", id))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docType", mapping.getDocType());
            row.put("docId", mapping.getDocId());
            row.put("ref", mapping.getDocType() + ":" + mapping.getDocId());
            sources.add(row);
        }
        data.put("sources", sources);
        return Result.ok("查询成功", data).toResponseEntity();
    }

    /**
     * 固定 2 跳检索问答（快速模式）。
     */
    @PostMapping("/query")
    @ResponseBody
    public ResponseEntity<Result<?>> query(@RequestBody Map<String, Object> payload, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        String question = parseQuestion(payload);
        if (question == null) {
            return Result.badRequest("问题不能为空，且不超过 " + MAX_QUESTION_LEN + " 字").toResponseEntity();
        }
        if (!aiAssistantService.isConfigured()) {
            return Result.fail(503, "AI 服务未配置").toResponseEntity();
        }
        try {
            String context = competitionContext(payload.get("competitionId"));
            GraphRagService.GraphRagAnswer answer = graphRagService.query(context + question, user.getId());
            return Result.ok("回答完成", toMap(answer)).toResponseEntity();
        } catch (AiClient.AiUnavailableException e) {
            log.warn("[GraphRAG] 快速检索问答失败: {}", e.getMessage());
            return Result.fail(502, "AI 服务暂时不可用，请稍后重试").toResponseEntity();
        }
    }

    /**
     * Agentic 问答（完整推理链版）。
     */
    @PostMapping("/agentic-query")
    @ResponseBody
    public ResponseEntity<Result<?>> agenticQuery(@RequestBody Map<String, Object> payload,
                                                  HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        String question = parseQuestion(payload);
        if (question == null) {
            return Result.badRequest("问题不能为空，且不超过 " + MAX_QUESTION_LEN + " 字").toResponseEntity();
        }
        if (!aiAssistantService.isConfigured()) {
            return Result.fail(503, "AI 服务未配置").toResponseEntity();
        }
        try {
            String context = competitionContext(payload.get("competitionId"));
            cn.ilink.service.kg.AgenticGraphRagService.AgenticAnswer answer =
                agenticGraphRagService.query(context + question, user.getId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("answer", answer.answer);
            data.put("citations", answer.citations);
            data.put("trace", answer.trace);
            data.put("rounds", answer.rounds);
            data.put("toolCalls", answer.toolCalls);
            data.put("fallback", answer.fallback);
            return Result.ok("回答完成", data).toResponseEntity();
        } catch (AiClient.AiUnavailableException e) {
            log.warn("[GraphRAG] Agentic 问答失败: {}", e.getMessage());
            return Result.fail(502, "AI 服务暂时不可用，请稍后重试").toResponseEntity();
        }
    }

    /**
     * Agentic 问答（SSE 流式，阶段 5）：Thought/Action/Observation/Final 四类事件边推理边推送。
     * 校验失败同样开一条立刻结束的流，用 error 事件带回 code/message，前端统一渲染。
     */
    @PostMapping(value = "/agentic-query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter agenticQueryStream(@RequestBody Map<String, Object> payload, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return failedStream(401, "未登录或登录已过期");
        }
        String question = parseQuestion(payload);
        if (question == null) {
            return failedStream(400, "问题不能为空，且不超过 " + MAX_QUESTION_LEN + " 字");
        }
        if (!aiAssistantService.isConfigured()) {
            return failedStream(503, "AI 服务未配置");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        final Long userId = user.getId();
        final String q = competitionContext(payload.get("competitionId")) + question;
        sseExecutor.execute(() -> {
            try {
                AgenticGraphRagService.AgenticAnswer answer = agenticGraphRagService.query(q, userId,
                    new AgenticGraphRagService.AgenticStreamListener() {
                        @Override
                        public void onThought(int round, String text) {
                            sendEvent(emitter, "thought", eventPayload(round, text));
                        }

                        @Override
                        public void onAction(int round, String tool, String paramsJson) {
                            Map<String, Object> data = eventPayload(round, null);
                            data.put("tool", tool);
                            data.put("params", paramsJson);
                            sendEvent(emitter, "action", data);
                        }

                        @Override
                        public void onObservation(int round, String text) {
                            sendEvent(emitter, "observation", eventPayload(round, text));
                        }

                        @Override
                        public void onFinal(String answerText, Object citations, Object trace) {
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("answer", answerText);
                            data.put("citations", citations);
                            data.put("trace", trace);
                            sendEvent(emitter, "final", data);
                        }
                    });
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("rounds", answer.rounds);
                done.put("toolCalls", answer.toolCalls);
                done.put("fallback", answer.fallback);
                sendEvent(emitter, "done", done);
                emitter.complete();
            } catch (Exception e) {
                log.warn("[GraphRAG] Agentic 流式问答异常: {}", e.getMessage());
                sendEvent(emitter, "error", errorPayload(500, "推理过程被中断，请稍后重试"));
                emitter.complete();
            }
        });
        return emitter;
    }

    private Map<String, Object> eventPayload(int round, String text) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("round", round);
        if (text != null) {
            data.put("text", text);
        }
        return data;
    }

    private Map<String, Object> errorPayload(int code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        return payload;
    }

    /** 未通过前置校验时开一条立刻结束的流 */
    private SseEmitter failedStream(int code, String message) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sendEvent(emitter, "error", errorPayload(code, message));
        emitter.complete();
        return emitter;
    }

    /**
     * 发送一个 SSE 事件。载荷先序列化成单行 JSON（SSE data 按行书写，
     * 裸换行会拆帧）；客户端断连是常态，写失败静默忽略。
     */
    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(toSingleLineJson(data), MediaType.TEXT_PLAIN));
        } catch (Exception ignored) {
            // 客户端已断开
        }
    }

    private String toSingleLineJson(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "\"\"";
        }
    }

    Map<String, Object> toMap(GraphRagService.GraphRagAnswer answer) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", answer.answer);
        data.put("citations", answer.citations);
        data.put("seedCount", answer.seedCount);
        data.put("edgeCount", answer.edgeCount);
        data.put("vectorRetrieval", answer.vectorRetrieval);
        data.put("elapsedMs", answer.elapsedMs);
        return data;
    }

    /** 提取并校验 question 参数 */
    static String parseQuestion(Map<String, Object> payload) {
        if (payload == null || payload.get("question") == null) {
            return null;
        }
        String question = String.valueOf(payload.get("question")).trim();
        if (question.isEmpty() || question.length() > MAX_QUESTION_LEN) {
            return null;
        }
        return question;
    }
}
