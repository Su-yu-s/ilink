package cn.ilink.service.kg;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.service.ai.AiClient;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定 2 跳 GraphRAG 问答（阶段 2 基线版）：
 * 问题 → 向量化 → top-8 实体 → 2 跳扩展 → 子图文本 → Agnes 生成答案 + 引用。
 * 向量不可用时降级为关键词 LIKE 检索；图谱完全无召回时退化为纯模型知识回答。
 * 该服务同时充当阶段 3 Agentic 循环的 fallback（fast RAG）。
 */
@Service
@Slf4j
public class GraphRagService {

    /** 向量检索召回数 */
    static final int TOP_K = 8;

    /** 统一时区：日期注入与时间语义都以北京时间为准 */
    static final java.time.ZoneId ZONE = java.time.ZoneId.of("Asia/Shanghai");

    private final KgEmbeddingService embeddingService;
    private final KgRelationService relationService;
    private final KgEntityMapperHolder holder;
    private final AiClient aiClient;

    /** 延迟持有 KgEntityMapper 的轻量包装，避免与构造参数顺序纠缠 */
    static class KgEntityMapperHolder {
        final cn.ilink.mapper.KgEntityMapper entityMapper;
        KgEntityMapperHolder(cn.ilink.mapper.KgEntityMapper entityMapper) {
            this.entityMapper = entityMapper;
        }
    }

    @Autowired
    public GraphRagService(KgEmbeddingService embeddingService,
                           KgRelationService relationService,
                           cn.ilink.mapper.KgEntityMapper entityMapper,
                           AiClient aiClient) {
        this.embeddingService = embeddingService;
        this.relationService = relationService;
        this.holder = new KgEntityMapperHolder(entityMapper);
        this.aiClient = aiClient;
    }

    /** 问答结果：答案 + 引用实体 + 元数据 */
    public static class GraphRagAnswer {
        public final String answer;
        public final List<Map<String, Object>> citations;
        public final int seedCount;
        public final int edgeCount;
        public final boolean vectorRetrieval;
        public final long elapsedMs;

        public GraphRagAnswer(String answer, List<Map<String, Object>> citations,
                              int seedCount, int edgeCount, boolean vectorRetrieval, long elapsedMs) {
            this.answer = answer;
            this.citations = citations;
            this.seedCount = seedCount;
            this.edgeCount = edgeCount;
            this.vectorRetrieval = vectorRetrieval;
            this.elapsedMs = elapsedMs;
        }
    }

    /**
     * 固定 2 跳检索问答。
     *
     * @param question 用户问题
     * @param userId   当前用户（本阶段仅用于日志追踪，过滤逻辑在阶段 3/4 下沉到工具层）
     */
    public GraphRagAnswer query(String question, Long userId) {
        long startedAt = System.currentTimeMillis();

        // ① 入口检索：向量优先，关键词兜底
        boolean vectorRetrieval = false;
        List<Long> seedIds = searchByVector(question);
        if (!seedIds.isEmpty()) {
            vectorRetrieval = true;
        } else {
            seedIds = searchByKeyword(question);
        }

        // ② 2 跳扩展 + 实体批量查询（subGraph 理论上非 null，防御一下）
        KgRelationService.SubGraph subGraph = relationService.expandTwoHops(seedIds);
        if (subGraph == null) {
            subGraph = new KgRelationService.SubGraph();
        }
        List<KgEntity> entities = relationService.getEntitiesByIds(subGraph.entityIds);

        // ③ 子图文本 + 生成（P0-1：零命中完全不注入资料块，模型无从谈起"未收录"）
        String subgraphText = entities.isEmpty() ? null : buildSubgraphText(entities, subGraph.edges);
        String answer = stripInternalStateSentences(generateAnswer(question, subgraphText, entities.size()));

        // ④ 引用：种子实体 + 子图中出现的实体（按图顺序，去重，最多 12 个）
        List<Map<String, Object>> citations = new ArrayList<>();
        for (KgEntity entity : entities) {
            if (citations.size() >= 12) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entityId", entity.getId());
            row.put("name", entity.getDisplayName());
            row.put("entityType", entity.getEntityType());
            citations.add(row);
        }
        return new GraphRagAnswer(answer, citations, seedIds.size(), subGraph.edges.size(),
            vectorRetrieval, System.currentTimeMillis() - startedAt);
    }

    /** 向量入口检索：问题向量化 → 内存余弦 top-8。未配置/失败返回空列表。 */
    List<Long> searchByVector(String question) {
        try {
            float[] queryVec = embeddingService.embedText(question);
            List<KgEmbeddingService.ScoredEntity> hits = embeddingService.searchTopK(queryVec, TOP_K);
            List<Long> ids = new ArrayList<>(hits.size());
            for (KgEmbeddingService.ScoredEntity hit : hits) {
                ids.add(hit.entityId);
            }
            return ids;
        } catch (Exception e) {
            log.info("[GraphRAG] 向量检索不可用，降级关键词: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 关键词兜底：整句 LIKE + 分词 LIKE（去查 display_name），最多 8 个 */
    List<Long> searchByKeyword(String question) {
        String normalized = question == null ? "" : question.trim();
        if (normalized.length() > 40) {
            normalized = normalized.substring(0, 40);
        }
        List<Long> ids = new ArrayList<>();
        if (normalized.isEmpty()) {
            return ids;
        }
        likeQuery(normalized, ids);
        if (ids.size() < TOP_K) {
            // 简易分词：按标点/空白切开，取 2 字以上的片段
            for (String token : normalized.split("[\\s，。？！、；：,.?!;:（）()\\[\\]【】\"']+")) {
                if (token.length() >= 2 && ids.size() < TOP_K) {
                    likeQuery(token, ids);
                }
            }
        }
        return ids;
    }

    private void likeQuery(String keyword, List<Long> ids) {
        List<KgEntity> rows = holder.entityMapper.selectList(new QueryWrapper<KgEntity>()
            .eq("status", KgEntity.STATUS_ACTIVE)
            .and(w -> w.like("display_name", keyword).or().like("name", keyword))
            .last("LIMIT " + TOP_K));
        for (KgEntity row : rows) {
            if (!ids.contains(row.getId())) {
                ids.add(row.getId());
            }
        }
    }

    /** 子图文本化：实体清单 + 关系清单，控制篇幅（实体 ≤ 40 行、边 ≤ 60 行） */
    String buildSubgraphText(List<KgEntity> entities, List<KgRelation> edges) {
        Map<Long, String> nameById = new LinkedHashMap<>();
        for (KgEntity entity : entities) {
            nameById.put(entity.getId(), "[" + entity.getEntityType() + "] "
                + entity.getDisplayName()
                + (entity.getDescription() == null || entity.getDescription().isEmpty()
                    ? "" : "：" + abbreviate(entity.getDescription(), 80)));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【内部资料：相关条目】\n");
        int line = 0;
        for (Map.Entry<Long, String> entry : nameById.entrySet()) {
            if (line++ >= 40) {
                sb.append("（其余实体省略）\n");
                break;
            }
            sb.append("- #").append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
        }
        sb.append("\n【内部资料：条目之间的关联】\n");
        int edgeLine = 0;
        for (KgRelation edge : edges) {
            if (edgeLine++ >= 60) {
                sb.append("（其余关系省略）\n");
                break;
            }
            sb.append("- #").append(edge.getSourceId())
                .append(" -[").append(edge.getRelationType()).append("]-> #")
                .append(edge.getTargetId()).append('\n');
        }
        if (edgeLine == 0) {
            sb.append("（无）\n");
        }
        return sb.toString();
    }

    /** 生成答案：图谱优先、知识兜底，绝不拒绝回答。subgraphText 为 null 时 prompt 中完全不出现资料块 */
    String generateAnswer(String question, String subgraphText, int entityCount) {
        StringBuilder user = new StringBuilder();
        user.append(currentDateLine()).append('\n');
        if (subgraphText != null) {
            user.append(subgraphText).append('\n');
        }
        user.append("学生问题：<question>\n").append(question).append("\n</question>\n\n");
        user.append(entityCount > 0
            ? "请把上面的资料与你的专业知识融合成一段完整回答，资料中出现的实体用 #ID 标注（如 #3）。"
            : "请结合你的专业知识给出完整、可执行回答。");
        user.append("回答要求：直接给结论与行动清单，使用 Markdown，长度适中（200~500 字）。");
        user.append("涉及时间、日程、赛事届次等时效性内容时，一律以【当前日期】为基准，禁止给出早于当前日期的届次或年份。");
        user.append("永远不要向用户提到检索、资料、知识库的覆盖情况，也不要写\"未收录\"\"没有相关资料\"之类的说明；"
            + "直接给出有帮助的回答即可。");

        AiClient.AiChatResult result = aiClient.chat(
            List.of(
                msg("system", SYSTEM_PROMPT),
                msg("user", user.toString())),
            2048);
        return result.content;
    }

    /** 当前日期（北京时间）：LLM 不知道今天几号，时效性问题必须显式注入 */
    static String currentDateLine() {
        return "【当前日期】" + java.time.LocalDate.now(ZONE)
            + "（星期" + "日一二三四五六".charAt(java.time.LocalDate.now(ZONE).getDayOfWeek().getValue() % 7)
            + "）。回答时效性问题（如\"最近\"\"今年\"\"报名中\"）时一律以此为准。";
    }

    static final String SYSTEM_PROMPT =
        "你是高校学科竞赛团队的问答助手。你会收到一份内部检索资料（#数字 是资料条目标识，可能为空）。"
            + "你的目标是给出可落地的指导：资料中有对应事实就采用（在实体处标注 #ID），资料没覆盖的部分由你用专业知识补足，两者自然融合成一段完整回答。"
            + "回答铁律：1）永远直接作答；2）使用 Markdown 排版；3）严禁使用任何 emoji/表情符号；"
            + "4）不要向用户描述检索过程、资料来源的覆盖情况，也不要出现\"未收录\"\"没有相关记录\"\"资料中未提到\""
            + "\"知识库中没有\"之类的表述——资料不足时直接给出通用建议即可；"
            + "5）不得声称内部资料中存在你并未在资料里看到的事实（不编造内部记录）。";

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    // ==================== P0-1 输出后处理：剔除向用户暴露内部状态的句子 ====================

    /**
     * 内部状态禁词：模型在答案里向用户解释"资料覆盖情况"时惯用的表述（评测第二轮 16/25 失败的根源）。
     * prompt 约束失效时由输出过滤兜底。
     */
    private static final List<String> INTERNAL_STATE_PHRASES = List.of(
        "未收录", "没有相关记录", "无相关记录", "资料中未提到", "资料里没有", "资料未",
        "知识库中", "知识图谱", "未检索到", "没有检索到", "检索结果", "检索过程", "检索到的",
        "暂无相关", "图谱中", "内部资料", "内部检索", "资料覆盖", "资料不足", "没有收录",
        "未包含", "没有包含", "未提及", "没有提到", "未提到", "未记录", "没有记录",
        "未见记录", "无记录", "未涉及", "没有涉及", "计数为 0", "计数为0");

    /**
     * 剔除答案中含内部状态表述的句子：面向用户的答案只保留结论（报告 4.3 设计原则）。
     * 表格行（| 开头）为结构化数据原样保留；按句末标点切分删除命中句；整段全部命中时
     * 返回原文兜底（宁可违规也不输出空白）。
     */
    static String stripInternalStateSentences(String answer) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }
        String[] lines = answer.split("(?<=\n)");
        StringBuilder out = new StringBuilder(answer.length());
        for (String line : lines) {
            if (line.startsWith("|") || !containsInternalState(line)) {
                out.append(line);
                continue;
            }
            // 命中禁词的行：按句切分，仅保留干净句子
            StringBuilder kept = new StringBuilder();
            for (String sentence : line.split("(?<=[。！？!?；;])")) {
                if (!containsInternalState(sentence)) {
                    kept.append(sentence);
                }
            }
            if (kept.length() > 0) {
                out.append(kept);
            }
        }
        return out.length() > 0 ? out.toString() : answer;
    }

    private static boolean containsInternalState(String text) {
        for (String phrase : INTERNAL_STATE_PHRASES) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> msg(String role, String content) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }
}
