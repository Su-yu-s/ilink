package cn.ilink.controller;

import cn.ilink.entity.Competition;
import cn.ilink.entity.KgDocEntity;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.User;
import cn.ilink.mapper.KgDocEntityMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.service.ai.AiAssistantService;
import cn.ilink.service.impl.CompetitionServiceImpl;
import cn.ilink.service.kg.AgenticGraphRagService;
import cn.ilink.service.kg.GraphRagService;
import cn.ilink.service.kg.KgScopeService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 【接口契约测试 · 测试代码】GraphRagController 四个端点 × 边界条件。
 *
 * <p>覆盖：未登录 401、空问题 400、超长问题 400、AI 未配置 503、
 * 正常 200 的字段结构、竞赛上下文注入、实体详情的 404/403/200、SSE 流的内容类型。
 */
@WebMvcTest(controllers = GraphRagController.class)
@AutoConfigureMockMvc(addFilters = false)
class GraphRagControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GraphRagService graphRagService;

    @MockBean
    private AgenticGraphRagService agenticGraphRagService;

    @MockBean
    private AiAssistantService aiAssistantService;

    @MockBean
    private KgEntityMapper kgEntityMapper;

    @MockBean
    private KgDocEntityMapper kgDocEntityMapper;

    @MockBean
    private KgScopeService kgScopeService;

    @MockBean
    private CompetitionServiceImpl competitionService;

    @MockBean
    private cn.ilink.service.ai.AiClient aiClient;

    private MockHttpSession session;
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(7L);
        user.setUsername("tester");
        session = new MockHttpSession();
        session.setAttribute("user", user);
        when(aiAssistantService.isConfigured()).thenReturn(true);
    }

    private String body(String question) {
        return body(question, null);
    }

    private String body(String question, Object competitionId) {
        StringBuilder sb = new StringBuilder("{\"question\":\"").append(question).append('"');
        if (competitionId != null) {
            sb.append(",\"competitionId\":").append(competitionId);
        }
        return sb.append('}').toString();
    }

    // ==================== 快速问答 ====================

    @Test
    @DisplayName("未登录：401（HTTP 状态与业务码一致）")
    void query_unauthorized() throws Exception {
        mockMvc.perform(post("/api/ai/graph-rag/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("测试问题")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("空问题：400")
    void query_blankQuestion() throws Exception {
        mockMvc.perform(post("/api/ai/graph-rag/query").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body("   ")))
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("超长问题（>500 字）：400")
    void query_tooLong() throws Exception {
        StringBuilder longQuestion = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            longQuestion.append('问');
        }
        mockMvc.perform(post("/api/ai/graph-rag/query").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body(longQuestion.toString())))
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("AI 未配置：503")
    void query_aiNotConfigured() throws Exception {
        when(aiAssistantService.isConfigured()).thenReturn(false);
        mockMvc.perform(post("/api/ai/graph-rag/query").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body("测试")))
            .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    @DisplayName("正常路径：200 且返回答案与检索元数据")
    void query_success() throws Exception {
        when(graphRagService.query(anyString(), anyLong())).thenReturn(new GraphRagService.GraphRagAnswer(
            "数学建模需要算法与论文写作。", List.of(citation(3L, "数学建模竞赛", "COMPETITION")), 2, 3, true, 1200L));

        mockMvc.perform(post("/api/ai/graph-rag/query").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body("数学建模需要什么")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.answer").value("数学建模需要算法与论文写作。"))
            .andExpect(jsonPath("$.data.seedCount").value(2))
            .andExpect(jsonPath("$.data.edgeCount").value(3))
            .andExpect(jsonPath("$.data.vectorRetrieval").value(true))
            .andExpect(jsonPath("$.data.citations[0].entityId").value(3));
    }

    @Test
    @DisplayName("竞赛上下文：competitionId 合法时请求带【绑定竞赛】前缀")
    void query_competitionContextInjected() throws Exception {
        Competition competition = new Competition();
        competition.setId(5L);
        competition.setName("全国大学生数学建模竞赛");
        when(competitionService.getById(5L)).thenReturn(competition);
        when(graphRagService.query(anyString(), anyLong())).thenReturn(new GraphRagService.GraphRagAnswer(
            "答案", new ArrayList<>(), 0, 0, false, 100L));

        mockMvc.perform(post("/api/ai/graph-rag/query").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body("赛程节点有哪些", 5)))
            .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<String> questionCaptor = ArgumentCaptor.forClass(String.class);
        verify(graphRagService).query(questionCaptor.capture(), anyLong());
        assertTrue(questionCaptor.getValue().startsWith("【用户当前关注的竞赛：全国大学生数学建模竞赛】"),
            "实际问题=" + questionCaptor.getValue());
    }

    // ==================== 深度问答 ====================

    @Test
    @DisplayName("Agentic：200 且返回推理链与统计字段")
    void agenticQuery_success() throws Exception {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("round", 1);
        step.put("thought", "先检索");
        when(agenticGraphRagService.query(anyString(), anyLong())).thenReturn(
            new AgenticGraphRagService.AgenticAnswer("最终答案",
                List.of(citation(1L, "蓝桥杯", "COMPETITION")), List.of(step), 2, 3, false));

        mockMvc.perform(post("/api/ai/graph-rag/agentic-query").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body("偏算法的竞赛有哪些")))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.answer").value("最终答案"))
            .andExpect(jsonPath("$.data.rounds").value(2))
            .andExpect(jsonPath("$.data.toolCalls").value(3))
            .andExpect(jsonPath("$.data.fallback").value(false))
            .andExpect(jsonPath("$.data.trace[0].round").value(1));
    }

    @Test
    @DisplayName("SSE 流：空问题也返回事件流（错误走 error 事件）")
    void agenticStream_contentType() throws Exception {
        mockMvc.perform(post("/api/ai/graph-rag/agentic-query/stream").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body("   ")))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    // ==================== 实体详情 ====================

    @Test
    @DisplayName("实体详情：未登录 401")
    void entity_unauthorized() throws Exception {
        mockMvc.perform(get("/api/ai/graph-rag/entity/1"))
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("实体详情：不存在 404")
    void entity_notFound() throws Exception {
        when(kgEntityMapper.selectById(999L)).thenReturn(null);
        mockMvc.perform(get("/api/ai/graph-rag/entity/999").session(session))
            .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("实体详情：越权 403（团队/私有实体对非成员不可见）")
    void entity_forbidden() throws Exception {
        when(kgEntityMapper.selectById(6L)).thenReturn(entity(6L, KgEntity.SCOPE_TEAM));
        when(kgScopeService.canSee(any(), any())).thenReturn(false);
        mockMvc.perform(get("/api/ai/graph-rag/entity/6").session(session))
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("实体详情：200 且带上来源文档列表")
    void entity_success() throws Exception {
        when(kgEntityMapper.selectById(1L)).thenReturn(entity(1L, KgEntity.SCOPE_GLOBAL));
        when(kgScopeService.canSee(any(), any())).thenReturn(true);
        KgDocEntity mapping = new KgDocEntity();
        mapping.setEntityId(1L);
        mapping.setDocType("COMPETITION");
        mapping.setDocId(12L);
        when(kgDocEntityMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mapping));

        mockMvc.perform(get("/api/ai/graph-rag/entity/1").session(session))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.name").value("蓝桥杯"))
            .andExpect(jsonPath("$.data.entityType").value("COMPETITION"))
            .andExpect(jsonPath("$.data.sources[0].ref").value("COMPETITION:12"));
    }

    private static KgEntity entity(long id, String scope) {
        KgEntity entity = new KgEntity();
        entity.setId(id);
        entity.setName("蓝桥杯");
        entity.setDisplayName("蓝桥杯");
        entity.setEntityType("COMPETITION");
        entity.setScope(scope);
        entity.setStatus(KgEntity.STATUS_ACTIVE);
        entity.setDescription("蓝桥杯说明");
        return entity;
    }

    private static Map<String, Object> citation(long id, String name, String type) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entityId", id);
        row.put("name", name);
        row.put("entityType", type);
        return row;
    }
}
