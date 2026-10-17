package cn.ilink.service.kg;

import cn.ilink.entity.KgAgentTrace;
import cn.ilink.entity.KgEntity;
import cn.ilink.mapper.KgAgentTraceMapper;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.kg.tool.GraphTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 阶段 3：ReAct 循环（解析、分发、终止条件、降级、trace 落库） */
class AgenticGraphRagServiceTest {

    private GraphRagService fastRag;
    private KgAgentTraceMapper traceMapper;
    private AiClient aiClient;
    private AgenticGraphRagService service;
    private GraphTool stubTool;

    /** 固定返回同一观察的桩工具（模拟永远查不到东西的场景） */
    private static class StubTool implements GraphTool {
        @Override
        public String getName() { return "search_entities"; }

        @Override
        public String getDescription() { return "test"; }

        @Override
        public String getParametersSchema() { return "{\"type\":\"object\"}"; }

        @Override
        public Object execute(Map<String, Object> params, Long userId) {
            return "{\"error\":\"NO_RESULTS\",\"message\":\"没有找到\"}";
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        fastRag = mock(GraphRagService.class);
        traceMapper = mock(KgAgentTraceMapper.class);
        aiClient = mock(AiClient.class);
        stubTool = new StubTool();
        service = new AgenticGraphRagService(fastRag, traceMapper, aiClient, List.of(stubTool));
    }

    private static AiClient.AiChatResult chat(String content) {
        return new AiClient.AiChatResult(content, 10, 20);
    }

    @Test
    void budgetStopNeverStartsPaidFallbackAndReleasesRequestScope() {
        when(aiClient.chat(anyList(), anyInt())).thenThrow(
            new cn.ilink.service.ai.ChatRequestBudget.BudgetStopException("TOKEN_RESERVATION_LIMIT"));
        var answer = service.query("测试", 8L);
        org.mockito.Mockito.verifyNoInteractions(fastRag);
        org.junit.jupiter.api.Assertions.assertNull(cn.ilink.service.ai.ChatRequestBudget.current());
        assertTrue(answer.trace.stream().anyMatch(step -> step.containsKey("requestBudget")));
    }

    // ==================== 输出解析 ====================

    @Test
    void parseStepExtractsActionAndInput() {
        AgenticGraphRagService.ReActStep step = AgenticGraphRagService.parseStep(
            "Thought: 我需要先检索\nAction: search_entities\nAction Input: {\"query\":\"建模\",\"top_k\":8}");
        assertFalse(step.isFinal());
        assertEquals("search_entities", step.action);
        assertEquals("{\"query\":\"建模\",\"top_k\":8}", step.actionInput);
        assertEquals("我需要先检索", step.thought);
    }

    @Test
    void parseStepExtractsFinalAnswer() {
        AgenticGraphRagService.ReActStep step = AgenticGraphRagService.parseStep(
            "Thought: 信息够了\nFinal Answer: 数学建模需要编程与写作能力。");
        assertTrue(step.isFinal());
        assertEquals("数学建模需要编程与写作能力。", step.finalAnswer);
    }

    @Test
    void nestedFinalAnswerIsNotInvokedAsATool() {
        var step = AgenticGraphRagService.parseStep("Thought: 完成\nAction: Final Answer: 页面底色是白色。");
        assertTrue(step.isFinal());
        assertEquals("页面底色是白色。", step.finalAnswer);
        assertEquals(null, step.action);
        assertFalse(AgenticGraphRagService.parseStep("Thought: 还需要检索").isFinal());
    }

    @Test
    void parseStepTreatsUnformattedTextAsFinal() {
        AgenticGraphRagService.ReActStep step = AgenticGraphRagService.parseStep("直接给答案，没有任何格式。");
        assertTrue(step.isFinal());
        assertEquals("直接给答案，没有任何格式。", step.finalAnswer);
    }

    @Test
    void parseStepHandlesNullAndEmpty() {
        assertTrue(AgenticGraphRagService.parseStep(null).isFinal());
        assertTrue(AgenticGraphRagService.parseStep("   ").isFinal());
    }

    @Test
    void parseStepIsCaseInsensitiveOnPrefixes() {
        AgenticGraphRagService.ReActStep step = AgenticGraphRagService.parseStep(
            "thought: 想\nfinal answer: 结论");
        assertTrue(step.isFinal());
        assertEquals("结论", step.finalAnswer);
    }

    // ==================== 错误观察识别 ====================

    @Test
    void errorObservationDetection() {
        assertTrue(AgenticGraphRagService.isErrorObservation(
            "{\"error\":\"NO_RESULTS\",\"message\":\"x\"}"));
        assertTrue(AgenticGraphRagService.isErrorObservation(
            "{\"error\":\"PERMISSION_DENIED\",\"message\":\"x\"}"));
        assertTrue(AgenticGraphRagService.isErrorObservation(
            "{\"error\":\"UNKNOWN_TOOL\",\"message\":\"x\"}"));
        assertFalse(AgenticGraphRagService.isErrorObservation(
            "{\"entityId\":1,\"name\":\"数学建模\"}"));
    }

    // ==================== 工具分发 ====================

    @Test
    void invokeToolReturnsUnknownToolErrorGracefully() {
        String result = service.invokeTool("no_such_tool", "{}", 1L);
        assertTrue(result.contains("UNKNOWN_TOOL"));
        assertTrue(result.contains("search_entities"));
    }

    @Test
    void invokeToolReportsInvalidJsonInput() {
        String result = service.invokeTool("search_entities", "不是JSON", 1L);
        assertTrue(result.contains("INVALID_INPUT"));
    }

    @Test
    void invokeToolConvertsToolExceptionToStructuredError() {
        GraphTool exploding = new GraphTool() {
            @Override public String getName() { return "boom"; }
            @Override public String getDescription() { return "boom"; }
            @Override public String getParametersSchema() { return "{\"type\":\"object\"}"; }
            @Override public Object execute(Map<String, Object> params, Long userId) {
                throw new IllegalStateException("数据库炸了");
            }
        };
        AgenticGraphRagService service2 = new AgenticGraphRagService(
            fastRag, traceMapper, aiClient, List.of(exploding));
        String result = service2.invokeTool("boom", "{}", 1L);
        assertTrue(result.contains("TOOL_FAILED"));
        assertTrue(result.contains("数据库炸了"));
    }

    // ==================== 引用抽取 ====================

    @Test
    void extractCitationsDeduplicatesAndCapsAtTwelve() {
        List<Map<String, Object>> trace = new java.util.ArrayList<>();
        Map<String, Object> obs1 = new HashMap<>();
        obs1.put("observation", "{\"entities\":[{\"entityId\":1,\"name\":\"A\"},{\"entityId\":2,\"name\":\"B\"},{\"entityId\":1,\"name\":\"A\"}]}");
        Map<String, Object> entry1 = new HashMap<>();
        entry1.put("observation", obs1.get("observation"));
        trace.add(entry1);
        // 再塞 20 个不同 ID，验证上限 12
        for (long id = 100; id < 120; id++) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("observation", "{\"entityId\":" + id + "}");
            trace.add(entry);
        }
        List<Map<String, Object>> citations = AgenticGraphRagService.extractCitations(trace);
        assertEquals(12, citations.size());
        assertEquals(1L, citations.get(0).get("entityId"));
        assertEquals("A", citations.get(0).get("name"));
    }

    // ==================== 循环终止条件 ====================

    @Test
    @SuppressWarnings("unchecked")
    void immediateFinalAnswerEndsLoopInOneRound() {
        when(aiClient.chat(anyList(), anyInt()))
            .thenReturn(chat("Thought: 我直接知道答案\nFinal Answer: 这是最终答案。"));

        AgenticGraphRagService.AgenticAnswer answer = service.query("问题", 1L);

        assertEquals(1, answer.rounds);
        assertEquals(0, answer.toolCalls);
        assertFalse(answer.fallback);
        assertEquals("这是最终答案。", answer.answer);
        verify(aiClient, times(1)).chat(anyList(), anyInt());
        // trace 落库，状态 OK；推理链里应能找到最终答案文本与 Thought
        ArgumentCaptor<KgAgentTrace> captor = ArgumentCaptor.forClass(KgAgentTrace.class);
        verify(traceMapper).insert(captor.capture());
        assertEquals(KgAgentTrace.STATUS_OK, captor.getValue().getStatus());
        assertTrue(captor.getValue().getTrace().contains("这是最终答案。"));
        assertTrue(captor.getValue().getTrace().contains("thought"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoRoundsWithoutNewInfoStopEarlyThenSummarize() {
        // 前两轮都返回相同动作，桩工具永远返回同样的 NO_RESULTS 观察 → 提前收束
        when(aiClient.chat(anyList(), anyInt()))
            .thenReturn(chat("Thought: 找找看\nAction: search_entities\nAction Input: {\"query\":\"建模\"}"))
            .thenReturn(chat("Thought: 再找找\nAction: search_entities\nAction Input: {\"query\":\"建模\"}"))
            .thenReturn(chat("Thought: 没有更多信息\nFinal Answer: 基于现有信息作答。"));

        AgenticGraphRagService.AgenticAnswer answer = service.query("问题", 1L);

        assertFalse(answer.fallback);
        assertEquals("基于现有信息作答。", answer.answer);
        // 前两轮各调一次模型，第 3 次是直接 Final Answer（还没触发 summarize）
        assertTrue(answer.trace.size() >= 4);
        assertTrue(answer.toolCalls >= 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void loopFailureFallsBackToFastRag() {
        when(aiClient.chat(anyList(), anyInt()))
            .thenThrow(new AiClient.AiUnavailableException("AI 服务连接超时"));
        GraphRagService.GraphRagAnswer fastAnswer = new GraphRagService.GraphRagAnswer(
            "快速回答", new java.util.ArrayList<>(), 0, 0, false, 10L);
        when(fastRag.query(any(), any())).thenReturn(fastAnswer);

        AgenticGraphRagService.AgenticAnswer answer = service.query("问题", 3L);

        assertTrue(answer.fallback);
        assertEquals("快速回答", answer.answer);
        ArgumentCaptor<KgAgentTrace> captor = ArgumentCaptor.forClass(KgAgentTrace.class);
        verify(traceMapper).insert(captor.capture());
        assertEquals(KgAgentTrace.STATUS_FALLBACK, captor.getValue().getStatus());
    }

    // ==================== 工具描述（Prompt 组装） ====================

    @Test
    void askModelPromptContainsToolsAndQuestion() {
        when(aiClient.chat(anyList(), anyInt())).thenReturn(chat("Thought: x\nFinal Answer: y"));

        service.askModel("数学建模需要什么？", "Thought: 之前想过\nObservation: 已有信息");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<java.util.Map<String, String>>> captor =
            ArgumentCaptor.forClass((Class) List.class);
        verify(aiClient).chat(captor.capture(), anyInt());
        String userPrompt = captor.getValue().get(1).get("content");
        assertTrue(userPrompt.contains("search_entities"));
        assertTrue(userPrompt.contains("数学建模需要什么？"));
        assertTrue(userPrompt.contains("Thought: 之前想过"));
        assertTrue(userPrompt.contains("Final Answer:"));
    }
}
