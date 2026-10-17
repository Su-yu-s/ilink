package cn.ilink.service.kg;

import cn.ilink.entity.KgCommunity;
import cn.ilink.entity.KgDocEntity;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgAgentTraceMapper;
import cn.ilink.mapper.KgCommunityMapper;
import cn.ilink.mapper.KgDocEntityMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.kg.tool.ExpandNeighborsTool;
import cn.ilink.service.kg.tool.GetCommunitySummaryTool;
import cn.ilink.service.kg.tool.GetDocumentTool;
import cn.ilink.service.kg.tool.GraphPermissionAspect;
import cn.ilink.service.kg.tool.GraphTool;
import cn.ilink.service.kg.tool.QueryTemporalTool;
import cn.ilink.service.kg.tool.SearchEntitiesTool;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 【对比测评 · 测试代码】Agentic 多轮检索（本方案 B3）vs 固定 2 跳检索（传统 GraphRAG B2）。
 *
 * <p>在确定性夹具上量化两套检索机制的差异：
 * 单跳 S / 双跳 M2 / 时序 T / 越权 P / 不可答 U / 降级 F 六类场景。
 *
 * <p><b>方法边界（诚实声明）</b>：生成模型被替换为"脚本化假模型"（按剧本返回 ReAct 输出），
 * 因此本测试度量的是<b>检索与编排机制</b>（调用哪些工具、召回哪些实体、是否受时序与权限约束、如何收敛），
 * <b>不是</b>大模型回答质量。回答质量需真实 API 端到端评测，见《Agentic-GraphRAG-测试报告》第五节。
 *
 * <p>夹具：8 实体 / 5 关系，含 1 个团队私有实体、1 个私有人实体、2 条带时间信息的关系。
 * 两套系统共用同一夹具与金标集合，保证对比公平。
 */
class AgenticVsFastRagComparisonTest {

    // ---------- 夹具常量 ----------
    private static final long TEAM_ID = 99L;
    private static final long OUTSIDER = 7L;      // 非 99 号团队成员
    private static final long MEMBER = 3L;        // 99 号团队成员
    private static final long PRIVATE_OWNER = 8L; // 私人笔记属主

    private static final long E_BLUECUP = 1L;
    private static final long E_ORGANIZER = 2L;
    private static final long E_MCM = 3L;
    private static final long E_ALGO = 4L;
    private static final long E_WRITING = 5L;
    private static final long E_TEAM_POST = 6L;   // TEAM 私有
    private static final long E_PRIVATE_NOTE = 7L; // PRIVATE
    private static final long E_RULE_UPDATE = 8L; // GLOBAL，带时间窗关系

    private final Map<Long, KgEntity> entities = new LinkedHashMap<>();
    private final List<KgRelation> relations = new ArrayList<>();

    private KgEntityMapper entityMapper;
    private KgRelationMapper relationMapper;
    private AiClient aiClient;
    private KgScopeService scopeService;
    private KgEmbeddingService embeddingService;
    private GraphRagService fastRag;
    private AgenticGraphRagService agentic;
    private GraphPermissionAspect aspect;

    private int interceptions = 0;        // 切面拦截计数
    private List<KgEntity> searchPool = new ArrayList<>(); // 关键词检索返回池

    @BeforeEach
    void setUp() {
        buildFixture();

        entityMapper = mock(KgEntityMapper.class);
        relationMapper = mock(KgRelationMapper.class);
        KgDocEntityMapper docEntityMapper = mock(KgDocEntityMapper.class);
        KgCommunityMapper communityMapper = mock(KgCommunityMapper.class);
        scopeService = mock(KgScopeService.class);
        embeddingService = mock(KgEmbeddingService.class);
        KgRelationService relationService = mock(KgRelationService.class);
        aiClient = mock(AiClient.class);
        KgAgentTraceMapper traceMapper = mock(KgAgentTraceMapper.class);
        aspect = new GraphPermissionAspect(entityMapper, scopeService);
        interceptions = 0;
        searchPool = new ArrayList<>(entities.values());

        // 可见性：GLOBAL 全员；TEAM 仅 3 号成员；PRIVATE 仅属主
        when(scopeService.visibleTeamIds(any())).thenReturn(new ArrayList<>());
        when(scopeService.canSee(any(), any())).thenAnswer(inv ->
            canSee(inv.getArgument(0), inv.getArgument(1)));

        when(entityMapper.selectById(any()))
            .thenAnswer(inv -> entities.get(((Number) inv.getArgument(0)).longValue()));
        when(entityMapper.selectList(any(Wrapper.class)))
            .thenAnswer(inv -> new ArrayList<>(searchPool));

        // 关系查询：模拟 SQL 语义（端点匹配 + valid_to/valid_from 条件）
        when(relationMapper.selectList(any(Wrapper.class)))
            .thenAnswer(inv -> queryRelations(inv.getArgument(0)));

        // 2 跳扩展与批量实体查询（注意：服务签名接收 Collection，GraphRagService 传入的是 Set）
        when(relationService.expandTwoHops(any())).thenAnswer(inv -> expand(inv.getArgument(0)));
        when(relationService.getEntitiesByIds(any())).thenAnswer(inv -> {
            List<KgEntity> result = new ArrayList<>();
            Object raw = inv.getArgument(0);
            if (raw instanceof java.util.Collection) {
                for (Object id : (java.util.Collection<?>) raw) {
                    KgEntity entity = entities.get(((Number) id).longValue());
                    if (entity != null) {
                        result.add(entity);
                    }
                }
            }
            return result;
        });

        // 文档映射（蓝桥杯 → 竞赛目录 #12）
        KgDocEntity mapping = new KgDocEntity();
        mapping.setEntityId(E_BLUECUP);
        mapping.setDocType("COMPETITION");
        mapping.setDocId(12L);
        when(docEntityMapper.selectList(any())).thenReturn(List.of(mapping));
        when(communityMapper.selectOne(any())).thenReturn((KgCommunity) null);

        // 组装 B2 与 B3
        fastRag = new GraphRagService(embeddingService, relationService, entityMapper, aiClient);
        List<GraphTool> tools = Arrays.asList(
            guarded(new SearchEntitiesTool(scopeService, entityMapper, embeddingService)),
            guarded(new ExpandNeighborsTool(scopeService, entityMapper, relationMapper)),
            guarded(new QueryTemporalTool(scopeService, entityMapper, relationMapper)),
            guarded(new GetDocumentTool(scopeService, entityMapper, docEntityMapper)),
            guarded(new GetCommunitySummaryTool(scopeService, entityMapper, communityMapper,
                relationService, new KgCommunityService(entityMapper, communityMapper, relationService, aiClient))));
        agentic = new AgenticGraphRagService(fastRag, traceMapper, aiClient, tools);
    }

    // ==================== 场景 1~6 ====================

    @Test
    @DisplayName("S 单跳：B2 与 B3 都能召回金标实体（机制等价，B3 成本更高）")
    void singleHop_bothRetrieveGold() {
        seedVector(E_BLUECUP);
        when(aiClient.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult("蓝桥杯由工信部人才交流中心主办。", 10, 20));

        GraphRagService.GraphRagAnswer fast = fastRag.query("蓝桥杯的主办单位是谁", OUTSIDER);

        assertTrue(ids(fast.citations).contains(E_ORGANIZER), "B2 的 2 跳应带出主办单位: " + ids(fast.citations));

        script("Thought: 看邻居即可\nAction: expand_neighbors\nAction Input: {\"entity_id\":1}",
            "Thought: 够了\nFinal Answer: 工信部人才交流中心。");
        AgenticGraphRagService.AgenticAnswer ag = agentic.query("蓝桥杯的主办单位是谁", OUTSIDER);

        assertTrue(ag.toolCalls >= 1, "B3 至少 1 次工具调用");
        assertFalse(ag.fallback);
        assertTrue(ag.trace.toString().contains(E_ORGANIZER + ""), "B3 观察中应出现主办单位实体");
    }

    @Test
    @DisplayName("M2 双跳：B3 用「检索→扩展」两次调用得证；B2 靠固定 2 跳一次拿全")
    void multiHop_agenticTwoCalls() {
        script("Thought: 先定位竞赛\nAction: search_entities\nAction Input: {\"query\":\"数学建模\"}",
            "Thought: 再扩展技能\nAction: expand_neighbors\nAction Input: {\"entity_id\":3}",
            "Thought: 技能齐了\nFinal Answer: 需要算法与论文写作。");

        AgenticGraphRagService.AgenticAnswer ag = agentic.query("数学建模需要哪些技能", OUTSIDER);

        assertEquals(2, ag.toolCalls, "B3 应完成检索 + 扩展两次工具调用");
        String trace = ag.trace.toString();
        assertTrue(trace.contains("REQUIRES"), "扩展观察应含 REQUIRES 关系：" + trace);

        KgRelationService.SubGraph fastGraph = expand(List.of(E_MCM));
        assertTrue(fastGraph.edges.stream().anyMatch(e -> KgRelation.REQUIRES.equals(e.getRelationType())),
            "B2 的固定 2 跳同样能覆盖该双跳（两者在此场景等价）");
    }

    @Test
    @DisplayName("T 时序：B3 按时间窗取关系；B2 无时序能力（会带出窗口外关系）")
    void temporal_agenticFiltersWindow() {
        script("Thought: 有时间约束，用时序工具\nAction: query_temporal\n"
                + "Action Input: {\"entity_id\":8,\"time_range\":{\"start\":\"2026-03-01\",\"end\":\"2026-03-31\"}}",
            "Thought: 窗口内事实已拿到\nFinal Answer: 2026 年 3 月的规则更新涉及数学建模竞赛。");

        AgenticGraphRagService.AgenticAnswer ag = agentic.query("2026 年 3 月有哪些规则更新", OUTSIDER);
        String trace = ag.trace.toString();

        assertTrue(trace.contains("query_temporal"), "B3 应调用时序工具");
        assertTrue(trace.contains("2026-03-01") && trace.contains("2026-03-31"),
            "观察应回显查询时间窗：" + trace);
        assertFalse(trace.contains("\"relationId\":4") || trace.contains("#4"),
            "窗口外的关系不应出现在观察中");

        // B2：固定 2 跳扩展只走"仍有效"的关系（valid_to 为空），
        // 因此对"窗口内有效、如今已过期"的边完全无法命中（0 条）——这正是 B3 时序能力的增量价值。
        KgRelationService.SubGraph fastGraph = expand(List.of(E_RULE_UPDATE));
        assertEquals(0, fastGraph.edges.size(),
            "B2 对 #8 的 2 跳扩展命中 0 条过期边（对照：B3 通过时间窗查询命中 1 条）");
    }

    @Test
    @DisplayName("P 越权：B3 访问团队私有实体被拦截，观察返回 PERMISSION_DENIED 后换策略")
    void permission_blockedThenRecovers() {
        script("Thought: 先查团队记录\nAction: expand_neighbors\nAction Input: {\"entity_id\":6}",
            "Thought: 被拒，改用公开信息\nFinal Answer: 团队内部分工属于私有信息，我无法查看。");

        AgenticGraphRagService.AgenticAnswer ag = agentic.query("3 月团队分工", OUTSIDER);

        assertTrue(interceptions >= 1, "切面应拦截越权访问，实际拦截=" + interceptions);
        assertTrue(ag.trace.toString().contains("PERMISSION_DENIED"), "观察应为结构化拒绝");
        assertTrue(ag.answer.contains("无法查看") || ag.answer.contains("私有"), "被拒后应换策略作答");
    }

    @Test
    @DisplayName("P-2 无误拦：团队成员访问同一实体放行")
    void permission_memberAllowed() {
        script("Thought: 我是成员，直接查\nAction: expand_neighbors\nAction Input: {\"entity_id\":6}",
            "Thought: 拿到关系了\nFinal Answer: 3 月围绕数学建模分工。");

        AgenticGraphRagService.AgenticAnswer ag = agentic.query("3 月分工", MEMBER);

        assertEquals(0, interceptions, "成员不应被拦截");
        assertFalse(ag.trace.toString().contains("PERMISSION_DENIED"));
        assertTrue(ag.trace.toString().contains("MENTIONS"), "成员应取回团队关系：" + ag.trace);
    }

    @Test
    @DisplayName("U 不可答：图谱无依据时 B3 明确说明且零引用（不编造引用）")
    void unanswerable_noFabricatedCitations() {
        // 检索池清空 + 向量不可用 → 所有检索型工具均 NO_RESULTS
        searchPool = new ArrayList<>();
        when(embeddingService.embedText(any())).thenThrow(new IllegalStateException("未配置"));
        script("Thought: 搜一下\nAction: search_entities\nAction Input: {\"query\":\"量子计算\"}",
            "Thought: 换关键词\nAction: search_entities\nAction Input: {\"query\":\"量子\"}",
            "Thought: 图谱确实没有\nFinal Answer: 知识库中没有量子计算竞赛的记录，建议先补充竞赛目录。");

        AgenticGraphRagService.AgenticAnswer ag = agentic.query("有没有量子计算方向的竞赛", OUTSIDER);

        assertEquals(0, ag.citations.size(), "无召回时不应产生引用");
        assertTrue(ag.answer.contains("没有") || ag.answer.contains("建议"), "应明确说明无依据");
        assertTrue(ag.rounds <= 8, "轮数必须在上限内");
    }

    @Test
    @DisplayName("F 降级：模型不可用时 B3 回落 B2 并留痕 fallback")
    void fallback_toFastRag() {
        when(aiClient.chat(anyList(), anyInt()))
            .thenThrow(new AiClient.AiUnavailableException("上游超时"))
            .thenReturn(new AiClient.AiChatResult("蓝桥杯由工信部人才交流中心主办。", 10, 20));
        seedVector(E_BLUECUP);

        AgenticGraphRagService.AgenticAnswer ag = agentic.query("蓝桥杯的主办单位是谁", OUTSIDER);

        assertTrue(ag.fallback, "应标记为降级结果");
        assertNotNull(ag.answer, "降级后仍必须给出回答");
    }

    @Test
    @DisplayName("汇总对比表：六类场景 B2 vs B3 机制指标（数据打印供人工核对）")
    void comparisonSummary() {
        Map<String, String> rows = new LinkedHashMap<>();

        // S
        seedVector(E_BLUECUP);
        script("Thought: 查邻居\nAction: expand_neighbors\nAction Input: {\"entity_id\":1}",
            "Thought: 完成\nFinal Answer: 工信部人才交流中心。");
        AgenticGraphRagService.AgenticAnswer s = agentic.query("蓝桥杯主办单位", OUTSIDER);
        rows.put("S 单跳", row(true, s.toolCalls >= 1, s));

        // M2
        script("Thought: 定位\nAction: search_entities\nAction Input: {\"query\":\"数学建模\"}",
            "Thought: 扩展\nAction: expand_neighbors\nAction Input: {\"entity_id\":3}",
            "Thought: 完成\nFinal Answer: 算法与论文写作。");
        AgenticGraphRagService.AgenticAnswer m2 = agentic.query("数学建模需要哪些技能", OUTSIDER);
        rows.put("M2 双跳", row(true, m2.toolCalls == 2, m2));

        // T
        script("Thought: 时序\nAction: query_temporal\n"
                + "Action Input: {\"entity_id\":8,\"time_range\":{\"start\":\"2026-03-01\",\"end\":\"2026-03-31\"}}",
            "Thought: 完成\nFinal Answer: 3 月更新涉及数学建模。");
        AgenticGraphRagService.AgenticAnswer t = agentic.query("3 月规则更新", OUTSIDER);
        rows.put("T 时序", row(false, t.trace.toString().contains("query_temporal"), t));

        // P
        interceptions = 0;
        script("Thought: 试私有\nAction: expand_neighbors\nAction Input: {\"entity_id\":6}",
            "Thought: 被拒\nFinal Answer: 团队私有信息无法查看。");
        AgenticGraphRagService.AgenticAnswer p = agentic.query("团队分工", OUTSIDER);
        rows.put("P 越权", row(false, interceptions >= 1, p));

        // U
        // 两个检索入口都要失效：关键词池清空 + 向量调用抛"未配置"（否则前序场景的向量桩会让本题误命中）
        searchPool = new ArrayList<>();
        when(embeddingService.embedText(any())).thenThrow(new IllegalStateException("未配置"));
        script("Thought: 查\nAction: search_entities\nAction Input: {\"query\":\"量子\"}",
            "Thought: 再查\nAction: search_entities\nAction Input: {\"query\":\"量子计算\"}",
            "Thought: 没有\nFinal Answer: 知识库无记录。");
        AgenticGraphRagService.AgenticAnswer u = agentic.query("量子计算竞赛", OUTSIDER);
        rows.put("U 不可答", row(true, u.citations.isEmpty(), u));

        // F
        when(aiClient.chat(anyList(), anyInt())).thenThrow(new AiClient.AiUnavailableException("超时"));
        AgenticGraphRagService.AgenticAnswer f = agentic.query("任意问题", OUTSIDER);
        rows.put("F 降级", row(true, f.fallback, f));

        StringBuilder table = new StringBuilder("\n===== B2(固定 2 跳 GraphRAG) vs B3(Agentic GraphRAG) 机制对比 =====\n")
            .append(String.format("%-9s | %-11s | %-11s | %-6s | %-8s | %-8s%n",
                "场景", "B2 机制支持", "B3 机制达标", "B3 轮数", "工具调用", "降级"))
            .append("-".repeat(70)).append('\n');
        rows.forEach((key, value) -> table.append(String.format("%-9s | %s%n", key, value)));
        System.out.println(table);

        assertTrue(rows.get("M2 双跳").contains("B3=Y"));
        assertTrue(rows.get("T 时序").contains("B3=Y"), "时序场景是 B3 相对 B2 的关键增量能力");
        assertTrue(rows.get("P 越权").contains("B3=Y"));
        assertTrue(rows.get("U 不可答").contains("B3=Y"));
        assertTrue(rows.get("F 降级").contains("B3=Y"));
    }

    // ==================== 夹具 ====================

    private void buildFixture() {
        entities.clear();
        relations.clear();
        entities.put(E_BLUECUP, entity(E_BLUECUP, "蓝桥杯", "COMPETITION", KgEntity.SCOPE_GLOBAL, 0L, 0L));
        entities.put(E_ORGANIZER, entity(E_ORGANIZER, "工信部人才交流中心", "ORGANIZATION", KgEntity.SCOPE_GLOBAL, 0L, 0L));
        entities.put(E_MCM, entity(E_MCM, "数学建模竞赛", "COMPETITION", KgEntity.SCOPE_GLOBAL, 0L, 0L));
        entities.put(E_ALGO, entity(E_ALGO, "算法", "SKILL", KgEntity.SCOPE_GLOBAL, 0L, 0L));
        entities.put(E_WRITING, entity(E_WRITING, "论文写作", "SKILL", KgEntity.SCOPE_GLOBAL, 0L, 0L));
        entities.put(E_TEAM_POST, entity(E_TEAM_POST, "3月分工记录", "POST", KgEntity.SCOPE_TEAM, TEAM_ID, 0L));
        entities.put(E_PRIVATE_NOTE, entity(E_PRIVATE_NOTE, "私人笔记", "POST", KgEntity.SCOPE_PRIVATE, 0L, PRIVATE_OWNER));
        entities.put(E_RULE_UPDATE, entity(E_RULE_UPDATE, "2026年3月规则更新", "POST", KgEntity.SCOPE_GLOBAL, 0L, 0L));

        relations.add(relation(1L, E_BLUECUP, E_ORGANIZER, KgRelation.HOSTED_BY, null, null));
        relations.add(relation(2L, E_MCM, E_ALGO, KgRelation.REQUIRES, null, null));
        relations.add(relation(3L, E_MCM, E_WRITING, KgRelation.REQUIRES, null, null));
        // 团队记录 → 数学建模：仅发生过、仍有效（valid_to 为空）
        relations.add(relation(4L, E_TEAM_POST, E_MCM, KgRelation.MENTIONS, date("2026-03-05"), null));
        // 规则更新 → 数学建模：时间窗 2026-03 内有效（已过期 → 只能在时间窗查询中命中）
        relations.add(relation(5L, E_RULE_UPDATE, E_MCM, KgRelation.MENTIONS, date("2026-03-01"), date("2026-03-31")));
    }

    private static KgEntity entity(long id, String name, String type, String scope, long teamId, long userId) {
        KgEntity entity = new KgEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDisplayName(name);
        entity.setEntityType(type);
        entity.setScope(scope);
        entity.setTeamId(teamId);
        entity.setUserId(userId);
        entity.setStatus(KgEntity.STATUS_ACTIVE);
        entity.setDescription(name + "说明");
        return entity;
    }

    private static KgRelation relation(long id, long source, long target, String type, Date from, Date to) {
        KgRelation relation = new KgRelation();
        relation.setId(id);
        relation.setSourceId(source);
        relation.setTargetId(target);
        relation.setRelationType(type);
        relation.setValidFrom(from);
        relation.setValidTo(to);
        return relation;
    }

    private static Date date(String text) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd").parse(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean canSee(KgEntity entity, Long userId) {
        if (entity == null) {
            return false;
        }
        if (KgEntity.SCOPE_GLOBAL.equals(entity.getScope())) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        if (KgEntity.SCOPE_PRIVATE.equals(entity.getScope())) {
            return entity.getUserId() != null && entity.getUserId().equals(userId);
        }
        if (KgEntity.SCOPE_TEAM.equals(entity.getScope())) {
            return Long.valueOf(MEMBER).equals(userId);
        }
        return false;
    }

    /**
     * 模拟关系表的 SQL 查询语义：
     * ① 端点匹配（source_id / target_id ∈ 参数中的实体 ID）；
     * ② valid_to IS NULL → 仅取仍有效；
     * ③ 带窗口时（同时出现 valid_from 与 valid_to >=）→ 取与 [start,end] 有交集的边。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<KgRelation> queryRelations(Wrapper<KgRelation> wrapper) {
        String sql = String.valueOf(wrapper.getSqlSegment()).replaceAll("\\s+", " ");
        Map<String, Object> params = wrapper instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper
            ? ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper) wrapper).getParamNameValuePairs()
            : new LinkedHashMap<>();
        List<Long> idParams = new ArrayList<>();
        List<Date> dateParams = new ArrayList<>();
        for (Object value : params.values()) {
            if (value instanceof Number) {
                idParams.add(((Number) value).longValue());
            } else if (value instanceof Date) {
                dateParams.add((Date) value);
            }
        }
        boolean hasWindow = sql.contains("valid_from") && sql.contains("valid_to >=");
        boolean currentlyValidOnly = sql.contains("valid_to IS NULL") && !hasWindow;
        Date start = null;
        Date end = null;
        if (hasWindow && !dateParams.isEmpty()) {
            dateParams.sort((a, b) -> a.compareTo(b));
            start = dateParams.get(0);
            end = dateParams.get(dateParams.size() - 1);
        }

        List<KgRelation> result = new ArrayList<>();
        for (KgRelation relation : relations) {
            boolean endpointMatch = idParams.isEmpty()
                || idParams.contains(relation.getSourceId()) || idParams.contains(relation.getTargetId());
            if (!endpointMatch) {
                continue;
            }
            if (currentlyValidOnly && relation.getValidTo() != null) {
                continue;
            }
            if (hasWindow) {
                if (relation.getValidFrom() == null || relation.getValidFrom().after(end)) {
                    continue;
                }
                if (relation.getValidTo() != null && relation.getValidTo().before(start)) {
                    continue;
                }
            }
            result.add(relation);
        }
        return result;
    }

    private KgRelationService.SubGraph expand(java.util.Collection<Long> seedIds) {
        KgRelationService.SubGraph graph = new KgRelationService.SubGraph();
        if (seedIds == null) {
            return graph;
        }
        List<Long> seedList = new ArrayList<>(seedIds);
        List<Long> frontier = new ArrayList<>(seedList);
        List<Long> visited = new ArrayList<>(seedList);
        for (int hop = 0; hop < 2; hop++) {
            List<Long> next = new ArrayList<>();
            for (KgRelation relation : relations) {
                if (relation.getValidTo() != null) {
                    continue; // 2 跳扩展只走仍有效的关系
                }
                if (frontier.contains(relation.getSourceId()) || frontier.contains(relation.getTargetId())) {
                    graph.addEdge(relation);
                    long other = frontier.contains(relation.getSourceId())
                        ? relation.getTargetId() : relation.getSourceId();
                    if (!visited.contains(other)) {
                        visited.add(other);
                        next.add(other);
                    }
                }
            }
            frontier = next;
        }
        return graph;
    }

    /** 把工具包一层权限切面（等价于生产环境的 AOP 织入） */
    private GraphTool guarded(GraphTool tool) {
        return new GraphTool() {
            @Override
            public String getName() {
                return tool.getName();
            }

            @Override
            public String getDescription() {
                return tool.getDescription();
            }

            @Override
            public String getParametersSchema() {
                return tool.getParametersSchema();
            }

            @Override
            public Object execute(Map<String, Object> params, Long userId) {
                ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
                when(joinPoint.getArgs()).thenReturn(new Object[]{params, userId});
                try {
                    when(joinPoint.proceed()).thenAnswer(invocation -> tool.execute(params, userId));
                    Object outcome = aspect.checkPermission(joinPoint);
                    if (outcome instanceof String && ((String) outcome).contains("PERMISSION_DENIED")) {
                        interceptions++;
                    }
                    return outcome;
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    /** B2 的向量入口：命中指定实体（用于快速检索路径） */
    private void seedVector(Long entityId) {
        when(embeddingService.embedText(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingService.searchTopK(any(), anyInt()))
            .thenReturn(List.of(new KgEmbeddingService.ScoredEntity(entityId, 0.9)));
    }

    /** B3 剧本：按顺序返回 ReAct 输出 */
    private void script(String... outputs) {
        org.mockito.stubbing.OngoingStubbing<AiClient.AiChatResult> stub = when(aiClient.chat(anyList(), anyInt()));
        for (int i = 0; i < outputs.length - 1; i++) {
            stub = stub.thenReturn(new AiClient.AiChatResult(outputs[i], 10, 20));
        }
        stub.thenReturn(new AiClient.AiChatResult(outputs[outputs.length - 1], 10, 20));
    }

    private static List<Long> ids(List<Map<String, Object>> citations) {
        List<Long> ids = new ArrayList<>();
        if (citations == null) {
            return ids;
        }
        for (Map<String, Object> citation : citations) {
            Object value = citation.get("entityId");
            if (value instanceof Number) {
                ids.add(((Number) value).longValue());
            }
        }
        return ids;
    }

    private static String row(boolean b2Supports, boolean b3Passes, AgenticGraphRagService.AgenticAnswer answer) {
        return String.format("B2=%-3s | B3=%-3s | %-6d | %-8d | %s",
            b2Supports ? "Y" : "N", b3Passes ? "Y" : "N", answer.rounds, answer.toolCalls, answer.fallback);
    }
}
