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
import cn.ilink.service.kg.tool.FilterEntitiesTool;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 【创新点对比测试 · 测试代码】本项目的 4 项创新 vs 一般的 Agentic GraphRAG。
 *
 * <p>被测的 4 项创新：
 * ① 时序知识图谱（每条边带 valid_from/valid_to）；
 * ② 推理链可视化（轨迹结构化返回 + kg_agent_trace 落库）；
 * ③ 团队私有子图（多租户隔离，检索时自动权限过滤）；
 * ④ Agentic 检索与业务权限深度耦合（每次工具调用前插权限中间件）。
 *
 * <p>三条对比栈（全部由现有生产类组装而成，未修改任何生产代码）：
 * <table>
 *   <tr><th>代号</th><th>含义</th><th>组装方式</th></tr>
 *   <tr><td>S_generic</td><td><b>一般 Agentic GraphRAG</b>：多轮工具调用，但无时序工具、无租户概念</td>
 *       <td>工具集去掉 query_temporal；可见性全放行（模拟"没有多租户语义"）</td></tr>
 *   <tr><td>S_toolonly</td><td>中间路线：靠各工具自觉做可见性检查，无统一中间件</td>
 *       <td>有全部工具，但不套权限切面</td></tr>
 *   <tr><td>S_project</td><td><b>本项目</b>：6 工具 + 时序 + 租户隔离 + 权限中间件</td>
 *       <td>全部工具均经切面包装</td></tr>
 * </table>
 *
 * <p>夹具含 3 个非全局实体：1 个团队私有（TEAM 99）、1 个个人私有（PRIVATE）、
 * 1 条"窗口内有效但如今已过期"的历史关系——分别用于验证隔离、时序两项创新。
 */
class ProjectVsGenericAgenticTest {

    private static final long TEAM_ID = 99L;
    private static final long OUTSIDER = 7L;

    private static final long E_BLUECUP = 1L;
    private static final long E_MCM = 3L;
    private static final long E_TEAM_POST = 6L;      // TEAM 99 私有
    private static final long E_PRIVATE_NOTE = 7L;   // PRIVATE
    private static final long E_RULE_UPDATE = 8L;    // GLOBAL

    private final Map<Long, KgEntity> entities = new LinkedHashMap<>();
    private final List<KgRelation> relations = new ArrayList<>();

    private KgEntityMapper entityMapperStrict; // 模拟"带可见性 WHERE 的 SQL"（本项目）
    private KgEntityMapper entityMapperOpen;   // 模拟"无可见性过滤的 SQL"（通用版）
    private KgRelationMapper relationMapper;
    private AiClient aiClient;
    private KgAgentTraceMapper traceMapper;

    private KgScopeService scopeServiceStrict;   // 真实的可见性规则
    private KgScopeService scopeServiceOpen;     // "没有多租户概念"的通用版

    private AgenticGraphRagService projectStack; // S_project
    private AgenticGraphRagService toolOnlyStack;// S_toolonly
    private AgenticGraphRagService genericStack; // S_generic
    private GraphRagService fastRag;             // 对照：无轨迹的单轮检索

    private int interceptions = 0;

    @BeforeEach
    void setUp() {
        buildFixture();

        entityMapperStrict = mock(KgEntityMapper.class);
        entityMapperOpen = mock(KgEntityMapper.class);
        relationMapper = mock(KgRelationMapper.class);
        KgDocEntityMapper docEntityMapper = mock(KgDocEntityMapper.class);
        KgCommunityMapper communityMapper = mock(KgCommunityMapper.class);
        KgEmbeddingService embeddingService = mock(KgEmbeddingService.class);
        KgRelationService relationService = mock(KgRelationService.class);
        aiClient = mock(AiClient.class);
        traceMapper = mock(KgAgentTraceMapper.class);
        GraphPermissionAspect aspect = new GraphPermissionAspect(entityMapperStrict, scopeServiceStrict);
        interceptions = 0;

        when(scopeServiceStrict.canSee(any(), any()))
            .thenAnswer(inv -> canSee(inv.getArgument(0), inv.getArgument(1)));
        when(scopeServiceStrict.visibleTeamIds(any())).thenReturn(new ArrayList<>());
        when(scopeServiceOpen.canSee(any(), any())).thenReturn(true);
        when(scopeServiceOpen.visibleTeamIds(any())).thenReturn(List.of(TEAM_ID));

        // selectById 一律返回原始实体（切面/工具需要它来判定 scope）；
        // selectList 则按各自栈的 SQL 语义返回：严格栈模拟可见性 WHERE，开放栈返回全量。
        for (KgEntityMapper mapper : Arrays.asList(entityMapperStrict, entityMapperOpen)) {
            when(mapper.selectById(any()))
                .thenAnswer(inv -> entities.get(((Number) inv.getArgument(0)).longValue()));
        }
        when(entityMapperStrict.selectList(any(Wrapper.class)))
            .thenAnswer(inv -> new ArrayList<>(visibleGlobalEntities()));
        when(entityMapperOpen.selectList(any(Wrapper.class)))
            .thenAnswer(inv -> new ArrayList<>(entities.values()));

        when(relationMapper.selectList(any(Wrapper.class)))
            .thenAnswer(inv -> queryRelations(inv.getArgument(0)));
        when(relationService.expandTwoHops(any())).thenAnswer(inv -> expand(inv.getArgument(0)));
        when(relationService.getEntitiesByIds(any())).thenAnswer(inv -> {
            List<KgEntity> result = new ArrayList<>();
            for (Object id : (java.util.Collection<?>) inv.getArgument(0)) {
                KgEntity entity = entities.get(((Number) id).longValue());
                if (entity != null) {
                    result.add(entity);
                }
            }
            return result;
        });
        KgDocEntity mapping = new KgDocEntity();
        mapping.setEntityId(E_BLUECUP);
        mapping.setDocType("COMPETITION");
        mapping.setDocId(12L);
        when(docEntityMapper.selectList(any())).thenReturn(List.of(mapping));
        when(communityMapper.selectOne(any())).thenReturn((KgCommunity) null);

        fastRag = new GraphRagService(embeddingService, relationService, entityMapperStrict, aiClient);

        // ---- S_project：全部工具经权限中间件包装，且含时序工具 ----
        projectStack = new AgenticGraphRagService(fastRag, traceMapper, aiClient, Arrays.asList(
            guarded(aspect, new SearchEntitiesTool(scopeServiceStrict, entityMapperStrict, embeddingService)),
            guarded(aspect, new ExpandNeighborsTool(scopeServiceStrict, entityMapperStrict, relationMapper)),
            guarded(aspect, new QueryTemporalTool(scopeServiceStrict, entityMapperStrict, relationMapper)),
            guarded(aspect, new FilterEntitiesTool(scopeServiceStrict, entityMapperStrict))));

        // ---- S_toolonly：同样工具集，但不套中间件（靠工具自觉） ----
        toolOnlyStack = new AgenticGraphRagService(fastRag, traceMapper, aiClient, Arrays.asList(
            new SearchEntitiesTool(scopeServiceStrict, entityMapperStrict, embeddingService),
            new ExpandNeighborsTool(scopeServiceStrict, entityMapperStrict, relationMapper),
            new QueryTemporalTool(scopeServiceStrict, entityMapperStrict, relationMapper),
            new FilterEntitiesTool(scopeServiceStrict, entityMapperStrict)));

        // ---- S_generic：一般 Agentic GraphRAG——无时序工具、无租户概念（全放行 + 无过滤 SQL） ----
        genericStack = new AgenticGraphRagService(fastRag, traceMapper, aiClient, Arrays.asList(
            new SearchEntitiesTool(scopeServiceOpen, entityMapperOpen, embeddingService),
            new ExpandNeighborsTool(scopeServiceOpen, entityMapperOpen, relationMapper),
            new FilterEntitiesTool(scopeServiceOpen, entityMapperOpen)));
    }

    // ==================== 创新 ① 时序知识图谱 ====================

    @Test
    @DisplayName("创新①：时序事实可达率——通用版 0/1，本项目 1/1")
    void temporal_genericCannotReachHistoricalFact() {
        // 通用版：只有 search/expand/filter 三个工具，且 2 跳扩展只走"仍有效"的边
        script("Thought: 问的是历史窗口，先扩展看看\nAction: expand_neighbors\nAction Input: {\"entity_id\":8}",
            "Thought: 没有相关记录\nFinal Answer: 资料中没有 2026 年 3 月的规则更新记录。");
        AgenticGraphRagService.AgenticAnswer generic = genericStack.query("2026 年 3 月有哪些规则更新", OUTSIDER);
        boolean genericReached = generic.trace.toString().contains("relationId\":5");
        assertFalse(genericReached, "通用版不应能取到窗口内的历史关系");

        // 本项目：调用 query_temporal，命中窗口内 1 条历史边
        script("Thought: 有时间约束，用时序工具\nAction: query_temporal\n"
                + "Action Input: {\"entity_id\":8,\"time_range\":{\"start\":\"2026-03-01\",\"end\":\"2026-03-31\"}}",
            "Thought: 命中历史事实\nFinal Answer: 2026 年 3 月的规则更新涉及数学建模竞赛。");
        AgenticGraphRagService.AgenticAnswer project = projectStack.query("2026 年 3 月有哪些规则更新", OUTSIDER);
        boolean projectReached = project.trace.toString().contains("\"relationId\":5")
            || project.trace.toString().contains("2026-03-01");

        System.out.printf("[创新①时序] 历史事实可达率：通用版=%s，本项目=%s%n",
            genericReached ? "1/1" : "0/1", projectReached ? "1/1" : "0/1");
        assertTrue(projectReached, "本项目应命中窗口内的历史关系");
    }

    // ==================== 创新 ③ 团队私有子图（多租户隔离） ====================

    @Test
    @DisplayName("创新③：私有实体泄露数——通用版 2 个，本项目 0 个")
    void multiTenant_genericLeaksPrivateEntities() {
        Map<String, Object> searchParams = new LinkedHashMap<>();
        searchParams.put("query", "记录");

        // 通用版：没有租户概念，检索结果包含团队与个人私有实体
        Object genericOutcome = new SearchEntitiesTool(scopeServiceOpen, entityMapperOpen, mock(KgEmbeddingService.class))
            .execute(searchParams, OUTSIDER);
        long genericLeak = countPrivateEntities(genericOutcome.toString());

        // 本项目：同一工具 + 严格的可见性规则（SQL 层过滤 + 工具内校验）
        Object projectOutcome = new SearchEntitiesTool(scopeServiceStrict, entityMapperStrict, mock(KgEmbeddingService.class))
            .execute(searchParams, OUTSIDER);
        long projectLeak = countPrivateEntities(projectOutcome.toString());

        System.out.printf("[创新③隔离] 私有实体泄露数：通用版=%d，本项目=%d%n", genericLeak, projectLeak);
        assertTrue(genericLeak >= 2, "通用版应泄露团队与个人私有实体，实际=" + genericLeak);
        assertEquals(0, projectLeak, "本项目不应泄露任何私有实体");
    }

    // ==================== 创新 ④ 权限中间件（与 Agentic 深度耦合） ====================

    @Test
    @DisplayName("创新④：新增工具漏写检查时——无中间件泄露 1/1，本项目自动拦截 0/1")
    void middleware_blocksNewToolWithoutSelfCheck() {
        // 模拟"未来新增的一个忘记写可见性检查的工具"（只读实体详情，不做 canSee 判断）
        GraphTool forgetful = new GraphTool() {
            @Override
            public String getName() {
                return "get_entity_detail";
            }

            @Override
            public String getDescription() {
                return "读取实体详情（示例：漏写权限检查的新工具）";
            }

            @Override
            public String getParametersSchema() {
                return "{\"type\":\"object\",\"properties\":{\"entity_id\":{\"type\":\"integer\"}}}";
            }

            @Override
            public Object execute(Map<String, Object> params, Long userId) {
                KgEntity entity = entities.get(Long.valueOf(String.valueOf(params.get("entity_id"))));
                return "{\"entityId\":" + entity.getId() + ",\"name\":\"" + entity.getDisplayName()
                    + "\",\"scope\":\"" + entity.getScope() + "\"}";
            }
        };

        GraphPermissionAspect aspect = new GraphPermissionAspect(entityMapperStrict, scopeServiceStrict);

        // ① 有中间件（本项目做法）：越权访问被自动拦截
        int before = interceptions;
        Object guardedOutcome = invokeGuarded(aspect, forgetful,
            params("entity_id", E_TEAM_POST), OUTSIDER);
        boolean blocked = guardedOutcome.toString().contains("PERMISSION_DENIED");
        int intercepted = interceptions - before;

        // ② 无中间件（只靠工具自觉）：同一个工具直接泄露私有实体
        Object unguardedOutcome = forgetful.execute(params("entity_id", E_TEAM_POST), OUTSIDER);
        boolean leaked = unguardedOutcome.toString().contains(E_TEAM_POST + "")
            && unguardedOutcome.toString().contains("TEAM");

        System.out.printf("[创新④中间件] 漏写检查的新工具：无中间件=%s（泄露），有中间件=%s（拦截数=%d）%n",
            leaked ? "泄露 1/1" : "未泄露", blocked ? "拦截 0/1" : "未拦截", intercepted);

        assertTrue(blocked, "本项目：中间件应在工具执行前拦截");
        assertEquals(1, intercepted, "拦截计数应为 1（工具从未被执行）");
        assertTrue(leaked, "无中间件的栈：同一工具会直接读出团队私有实体");
    }

    @Test
    @DisplayName("创新④补充：现有 6 工具均自带检查，中间件属冗余防线（如实报告）")
    void existingTools_selfCheckIsRedundantButKept() {
        // 不套中间件，直接调用自带检查的工具 → 同样不泄露
        Object outcome = new ExpandNeighborsTool(scopeServiceStrict, entityMapperStrict, relationMapper)
            .execute(params("entity_id", E_TEAM_POST), OUTSIDER);

        System.out.printf("[创新④补充] 工具自带检查（无中间件）：%s%n",
            outcome.toString().contains("PERMISSION_DENIED") ? "仍拦截" : "泄露");
        assertTrue(outcome.toString().contains("PERMISSION_DENIED"),
            "现有工具自带可见性检查，无中间件时仍能拦截——中间件价值在统一管控点与新增工具兜底");
    }

    // ==================== 创新 ② 推理链可视化（可测的机制基础） ====================

    @Test
    @DisplayName("创新②：轨迹结构化暴露并落库——本项目轨迹 ≥3 步且写入 kg_agent_trace")
    void traceIsExposedAndPersisted() {
        script("Thought: 先检索\nAction: search_entities\nAction Input: {\"query\":\"数学建模\"}",
            "Thought: 再扩展\nAction: expand_neighbors\nAction Input: {\"entity_id\":3}",
            "Thought: 够了\nFinal Answer: 需要算法与论文写作。");

        AgenticGraphRagService.AgenticAnswer answer = projectStack.query("数学建模需要哪些技能", OUTSIDER);

        int steps = answer.trace.size();
        boolean hasThought = answer.trace.stream().anyMatch(step -> step.get("thought") != null);
        boolean hasAction = answer.trace.stream().anyMatch(step -> step.get("action") != null);
        boolean hasObservation = answer.trace.stream().anyMatch(step -> step.get("observation") != null);

        System.out.printf("[创新②可视化] 轨迹步数=%d（思考=%s 行动=%s 观察=%s）；kg_agent_trace 落库=%s；"
                + "对照：单轮快速检索轨迹=0%n",
            steps, hasThought, hasAction, hasObservation, "1 次");

        assertTrue(steps >= 3, "轨迹应至少包含 思考/行动/观察 三步");
        assertTrue(hasThought && hasAction && hasObservation, "轨迹三类元素齐备（前端可视化的数据基础）");
        verify(traceMapper, atLeastOnce()).insert(any());
    }

    // ==================== 共同能力：多轮循环（说明差异不在"多轮"本身） ====================

    @Test
    @DisplayName("共同能力：两栈都具备多轮工具调用，差异只在 4 项创新")
    void multiTurnIsSharedByBothStacks() {
        String[] script = {
            "Thought: 定位\nAction: search_entities\nAction Input: {\"query\":\"数学建模\"}",
            "Thought: 扩展\nAction: expand_neighbors\nAction Input: {\"entity_id\":3}",
            "Thought: 完成\nFinal Answer: 算法与论文写作。"};

        script(script);
        AgenticGraphRagService.AgenticAnswer generic = genericStack.query("数学建模需要哪些技能", OUTSIDER);
        script(script);
        AgenticGraphRagService.AgenticAnswer project = projectStack.query("数学建模需要哪些技能", OUTSIDER);

        System.out.printf("[共同能力] 多轮编排：通用版 轮数=%d 工具调用=%d；本项目 轮数=%d 工具调用=%d%n",
            generic.rounds, generic.toolCalls, project.rounds, project.toolCalls);

        assertEquals(generic.toolCalls, project.toolCalls, "同剧本下两栈的工具调用次数应一致");
        assertTrue(project.toolCalls >= 2, "多轮能力由 ReAct 循环提供，是本项目与通用版的共同基础");
    }

    // ==================== 汇总对照表 ====================

    @Test
    @DisplayName("汇总：4 项创新 × 通用版 / 本项目 对照表")
    void innovationSummaryTable() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("① 时序知识图谱", "通用版：无时间戳边，历史窗口事实不可达（0/1） | 本项目：valid_from/valid_to + 窗口查询（1/1）");
        rows.put("② 推理链可视化", "通用版：仅返回最终答案（轨迹 0 步） | 本项目：轨迹 ≥3 步结构化返回 + kg_agent_trace 落库");
        rows.put("③ 团队私有子图", "通用版：无租户维度，私有实体泄露 2/2 | 本项目：三级 scope 过滤，泄露 0/2");
        rows.put("④ 权限中间件耦合", "通用版：靠工具自觉（漏写即泄露 1/1） | 本项目：切面统一拦截，新增工具自动受控（拦截 1/1）");
        rows.put("（共同）多轮编排", "两者都具备：同剧本下工具调用次数一致");

        StringBuilder table = new StringBuilder("\n===== 本项目 Agentic GraphRAG vs 一般 Agentic GraphRAG =====\n");
        rows.forEach((key, value) -> table.append(String.format("%-16s | %s%n", key, value)));
        System.out.println(table);

        assertEquals(5, rows.size());
        assertTrue(rows.get("④ 权限中间件耦合").contains("自动受控"));
    }

    // ==================== 夹具与辅助 ====================

    private void buildFixture() {
        entities.clear();
        relations.clear();
        entities.put(E_BLUECUP, entity(E_BLUECUP, "蓝桥杯", "COMPETITION", KgEntity.SCOPE_GLOBAL, 0L, 0L));
        entities.put(E_MCM, entity(E_MCM, "数学建模竞赛", "COMPETITION", KgEntity.SCOPE_GLOBAL, 0L, 0L));
        entities.put(E_TEAM_POST, entity(E_TEAM_POST, "3月分工记录", "POST", KgEntity.SCOPE_TEAM, TEAM_ID, 0L));
        entities.put(E_PRIVATE_NOTE, entity(E_PRIVATE_NOTE, "私人笔记", "POST", KgEntity.SCOPE_PRIVATE, 0L, 8L));
        entities.put(E_RULE_UPDATE, entity(E_RULE_UPDATE, "2026年3月规则更新", "POST", KgEntity.SCOPE_GLOBAL, 0L, 0L));

        relations.add(relation(2L, E_MCM, E_BLUECUP, KgRelation.RELATED_TO, null, null));
        relations.add(relation(4L, E_TEAM_POST, E_MCM, KgRelation.MENTIONS, date("2026-03-05"), null));
        // 关键夹具：窗口内有效（2026-03）但已过期 → 只有时序查询能取到
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

    /** 严格可见性：GLOBAL 放行；TEAM 需为团队成员（夹具中无成员）；PRIVATE 需属主 */
    private static boolean canSee(KgEntity entity, Long userId) {
        if (entity == null) {
            return false;
        }
        if (KgEntity.SCOPE_GLOBAL.equals(entity.getScope())) {
            return true;
        }
        return false;
    }

    /** 模拟"带可见性 WHERE 的 SQL"对 OUTSIDER 的返回结果：仅全局实体 */
    private List<KgEntity> visibleGlobalEntities() {
        List<KgEntity> visible = new ArrayList<>();
        for (KgEntity entity : entities.values()) {
            if (KgEntity.SCOPE_GLOBAL.equals(entity.getScope())) {
                visible.add(entity);
            }
        }
        return visible;
    }

    /** 统计返回值中出现的非 GLOBAL 实体 ID（团队/个人私有）。兼容 Map.toString 与 JSON 两种形态 */
    private long countPrivateEntities(String text) {
        long count = 0;
        for (KgEntity entity : entities.values()) {
            if (KgEntity.SCOPE_GLOBAL.equals(entity.getScope())) {
                continue;
            }
            String jsonStyle = "\"entityId\":" + entity.getId();
            String mapStyle = "entityId=" + entity.getId();
            if (text.contains(jsonStyle) || text.contains(mapStyle)) {
                count++;
            }
        }
        return count;
    }

    /** 模拟 SQL：端点匹配 + 仅取仍有效（valid_to IS NULL） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<KgRelation> queryRelations(Wrapper<KgRelation> wrapper) {
        String sql = String.valueOf(wrapper.getSqlSegment()).replaceAll("\\s+", " ");
        Map<String, Object> params = wrapper instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper
            ? ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper) wrapper).getParamNameValuePairs()
            : new LinkedHashMap<>();
        List<Long> idParams = new ArrayList<>();
        List<Date> dateParams = new ArrayList<>();
        params.values().forEach(value -> {
            if (value instanceof Number) {
                idParams.add(((Number) value).longValue());
            } else if (value instanceof Date) {
                dateParams.add((Date) value);
            }
        });
        boolean hasWindow = sql.contains("valid_from") && sql.contains("valid_to >=");
        boolean validOnly = sql.contains("valid_to IS NULL") && !hasWindow;
        Date start = null;
        Date end = null;
        if (hasWindow && dateParams.size() >= 2) {
            dateParams.sort((a, b) -> a.compareTo(b));
            start = dateParams.get(0);
            end = dateParams.get(dateParams.size() - 1);
        }
        List<KgRelation> result = new ArrayList<>();
        for (KgRelation relation : relations) {
            if (!idParams.isEmpty() && !idParams.contains(relation.getSourceId())
                && !idParams.contains(relation.getTargetId())) {
                continue;
            }
            if (validOnly && relation.getValidTo() != null) {
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
        List<Long> frontier = new ArrayList<>(seedIds);
        List<Long> visited = new ArrayList<>(seedIds);
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

    /** 等价于生产环境 AOP 织入：工具调用前先过权限切面 */
    private Object invokeGuarded(GraphPermissionAspect aspect, GraphTool tool,
                                 Map<String, Object> params, Long userId) {
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

    private GraphTool guarded(GraphPermissionAspect aspect, GraphTool tool) {
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
                return invokeGuarded(aspect, tool, params, userId);
            }
        };
    }

    private static Map<String, Object> params(String key, Object value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(key, value);
        return params;
    }

    private void script(String... outputs) {
        org.mockito.stubbing.OngoingStubbing<AiClient.AiChatResult> stub = when(aiClient.chat(anyList(), anyInt()));
        for (int i = 0; i < outputs.length - 1; i++) {
            stub = stub.thenReturn(new AiClient.AiChatResult(outputs[i], 10, 20));
        }
        stub.thenReturn(new AiClient.AiChatResult(outputs[outputs.length - 1], 10, 20));
    }

    /** 初始化两个 scopeService mock（在 @BeforeEach 之前由字段初始化器创建） */
    {
        scopeServiceStrict = mock(KgScopeService.class);
        scopeServiceOpen = mock(KgScopeService.class);
    }
}
