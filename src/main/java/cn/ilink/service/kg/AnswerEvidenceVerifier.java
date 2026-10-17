package cn.ilink.service.kg;

import cn.ilink.service.ai.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic checks for IDs, names, directed edges, dates and exact source facts. */
public class AnswerEvidenceVerifier {
    private static final Pattern ENTITY_REF = Pattern.compile("(?<![\\p{L}\\p{N}])#(\\d+)");
    private static final Pattern RELATION_REF = Pattern.compile("r#(\\d+)");
    private static final Pattern CHUNK_REF = Pattern.compile("c#(\\d+)");
    private static final Pattern DOCUMENT_REF = Pattern.compile(
        "(?i)(POST|COMPETITION|TASK|ASSET)[_:](\\d+)");
    private static final Pattern DIRECTED_RELATION = Pattern.compile(
        "#(\\d+)\\s*-\\[([A-Z_]+)\\]->\\s*[^#]{0,50}#(\\d+)");
    private static final Pattern EXACT_NUMBER = Pattern.compile(
        "(?i)(?<![\\p{L}\\p{N}])\\d+(?:\\.\\d+)?\\s*(?:px|%|ms|GB|MB|kb|万元|人)(?![\\p{L}\\p{N}])");
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Completion budget for one semantic verdict.
     *
     * <p>M10 (v10, 100-question run) returned {@code finishReason=length} at 512 tokens on BOTH the
     * VERIFY and EXTRA stages while its evidence packet was only 2,191 of 3,000 tokens, so the
     * failure was the verdict contract being too verbose rather than the evidence being too large.
     * Two things changed together, and both are required for the fix to hold:
     * <ol>
     *   <li>the verdict contract below was compressed to short closed codes plus a capped
     *       one-line reason per defect;</li>
     *   <li>this explicit, named budget replaced the previous bare literal so the value is
     *       auditable instead of a silent tuning knob.</li>
     * </ol>
     * Raising this budget without keeping the contract compact only hides truncation again, so any
     * further increase must be registered together with evidence-packet and cost measurements.
     *
     * <p>The value is derived from {@link cn.ilink.service.ai.ChatRequestBudget#VERDICT_COMPLETION_LIMIT}
     * so the per-stage cap and the requested budget cannot drift apart; requesting more than the
     * stage cap would silently be clamped and reintroduce the truncation.
     */
    static final int VERDICT_COMPLETION_BUDGET = cn.ilink.service.ai.ChatRequestBudget.VERDICT_COMPLETION_LIMIT;

    public record VerificationResult(boolean supported, List<String> reasons,
                                     Set<Long> usedEntityIds, Set<Long> usedRelationIds,
                                     Set<Long> usedChunkIds) {
        boolean coverageOnlyFailure() {
            return !supported && !reasons.isEmpty() && reasons.stream().allMatch(reason ->
                reason.startsWith("SEMANTIC_UNSUPPORTED:COVERAGE:")
                    || reason.startsWith("SEMANTIC_UNCERTAIN:COVERAGE:"));
        }
    }

    public VerificationResult verify(String answer, EvidenceLedger ledger, QueryEvidencePlan plan) {
        List<String> reasons = new ArrayList<>();
        Set<Long> entityIds = new LinkedHashSet<>();
        Set<Long> relationIds = new LinkedHashSet<>();
        Set<Long> chunkIds = new LinkedHashSet<>();
        if (answer == null || answer.isBlank()) {
            reasons.add("EMPTY_ANSWER");
            return result(reasons, entityIds, relationIds, chunkIds);
        }
        Matcher entityRefs = ENTITY_REF.matcher(CitationText.forReferences(answer));
        while (entityRefs.find()) {
            Long id = parseId(entityRefs.group(1));
            EvidenceLedger.EntityFact entity = id == null ? null : ledger.entity(id).orElse(null);
            if (entity == null) {
                reasons.add("UNKNOWN_ENTITY:#" + entityRefs.group(1));
                continue;
            }
            entityIds.add(id);
            if (explicitWrongName(answer, entityRefs.start(), entity.name())) {
                reasons.add("NAME_MISMATCH:#" + id);
            }
        }
        Matcher relationRefs = RELATION_REF.matcher(answer);
        while (relationRefs.find()) {
            Long id = parseId(relationRefs.group(1));
            EvidenceLedger.RelationFact relation = id == null ? null : ledger.relation(id).orElse(null);
            if (relation == null) {
                reasons.add("UNKNOWN_RELATION:r#" + relationRefs.group(1));
                continue;
            }
            relationIds.add(id);
            if (plan != null && !plan.timeRanges().isEmpty()
                && plan.timeRanges().stream().noneMatch(range -> overlaps(relation, range))) {
                reasons.add("TIME_MISMATCH:r#" + id);
            }
        }
        Matcher chunkRefs = CHUNK_REF.matcher(answer);
        while (chunkRefs.find()) {
            Long id = parseId(chunkRefs.group(1));
            if (id == null || !ledger.containsChunk(id)) {
                reasons.add("UNKNOWN_CHUNK:c#" + chunkRefs.group(1));
            } else {
                chunkIds.add(id);
            }
        }
        Matcher docRefs = DOCUMENT_REF.matcher(answer);
        while (docRefs.find()) {
            String type = docRefs.group(1).toUpperCase(Locale.ROOT);
            Long id = parseId(docRefs.group(2));
            boolean found = id != null && ledger.chunks().stream().anyMatch(chunk ->
                type.equals(chunk.docType()) && id.equals(chunk.docId()));
            if (!found) {
                reasons.add("UNKNOWN_DOCUMENT:" + docRefs.group());
            }
        }
        Matcher triples = DIRECTED_RELATION.matcher(answer);
        while (triples.find()) {
            Long source = parseId(triples.group(1));
            Long target = parseId(triples.group(3));
            String type = triples.group(2);
            boolean found = ledger.relations().stream().anyMatch(relation ->
                relation.sourceId().equals(source) && relation.targetId().equals(target)
                    && type.equals(relation.type())
                    && (plan == null || plan.timeRanges().isEmpty()
                        || plan.timeRanges().stream().anyMatch(range -> overlaps(relation, range))));
            if (!found) {
                reasons.add("RELATION_MISMATCH:" + triples.group());
            }
        }
        Matcher numbers = EXACT_NUMBER.matcher(answer);
        while (numbers.find()) {
            String number = numbers.group().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            boolean found = ledger.chunks().stream().anyMatch(chunk ->
                chunk.text() != null && chunk.text().replaceAll("\\s+", "")
                    .toLowerCase(Locale.ROOT).contains(number));
            if (!found) {
                reasons.add("UNSUPPORTED_EXACT_FACT:" + numbers.group());
            }
        }
        if (plan != null && (plan.requiresRelation() || plan.requiresTemporal()
            || plan.requiresSourceText())
            && entityIds.isEmpty() && relationIds.isEmpty() && chunkIds.isEmpty()
            && !(ledger.hasToolResult("query_temporal") && ledger.relations().isEmpty()
                && looksLikeNoRelation(answer))) {
            reasons.add("MISSING_CITATION");
        }
        if (plan != null && plan.requiresRelation()
            && relationIds.isEmpty() && chunkIds.isEmpty()
            && !(ledger.hasToolResult("query_temporal") && ledger.relations().isEmpty()
                && looksLikeNoRelation(answer))) {
            reasons.add("MISSING_RELATION_EVIDENCE");
        }
        if (plan != null && plan.requiresSourceText()
            && relationIds.isEmpty() && chunkIds.isEmpty()
            && !(ledger.hasToolResult("query_temporal") && ledger.relations().isEmpty()
                && looksLikeNoRelation(answer))) {
            reasons.add("MISSING_SOURCE_EVIDENCE");
        }
        reasons.addAll(RelationClaimVerifier.verify(answer, ledger));
        return result(reasons, entityIds, relationIds, chunkIds);
    }

    /** Question-aware absence checks run before any paid semantic request. */
    public VerificationResult verify(String question, String answer, EvidenceLedger ledger,
                                     QueryEvidencePlan plan) {
        VerificationResult checked = verify(answer, ledger, plan);
        if (question != null && plan != null && plan.requiresRelation() && !plan.requiresTemporal()
            && (QueryIntent.fromQuestion(question).graphRequested()
                || answer != null && answer.contains("图谱"))
            && !RecommendationEvidencePolicy.isRecommendationQuery(question)) {
            for (String reason : SubjectScopeLedger.assess(question, ledger).absenceViolations(answer))
                checked = addReason(checked, reason);
        }
        for (String reason : TemporalAnswerScope.absenceViolations(question, plan, ledger, answer))
            checked = addReason(checked, reason);
        return checked;
    }

    /** One narrow model check for paraphrased platform claims; uncertain output is rejected. */
    public VerificationResult verifySemantic(String answer, EvidenceLedger ledger,
                                             QueryEvidencePlan plan, AiClient ai) {
        return verifySemantic(null, answer, ledger, plan, ai);
    }

    public VerificationResult verifySemantic(String question, String answer, EvidenceLedger ledger,
                                             QueryEvidencePlan plan, AiClient ai) {
        return verifySemantic(question, answer, ledger, plan, ai, ignored -> {});
    }

    public VerificationResult verifySemantic(String question, String answer, EvidenceLedger ledger,
                                             QueryEvidencePlan plan, AiClient ai,
                                             java.util.function.Consumer<Map<String, Object>> evidenceObserver) {
        VerificationResult deterministic = verify(question, answer, ledger, plan);
        List<String> recommendationReasons = RecommendationEvidencePolicy.verify(question,
            answer, ledger, deterministic.usedEntityIds(), deterministic.usedRelationIds(),
            deterministic.usedChunkIds());
        for (String reason : recommendationReasons) {
            deterministic = addReason(deterministic, reason);
        }
        if (!deterministic.supported() || ai == null
            || deterministic.usedEntityIds().isEmpty()
                && deterministic.usedRelationIds().isEmpty()
                && deterministic.usedChunkIds().isEmpty()) {
            return deterministic;
        }
        StringBuilder evidence = new StringBuilder();
        for (Long id : deterministic.usedEntityIds()) {
            ledger.entity(id).ifPresent(fact -> evidence.append("#").append(id)
                .append(' ').append(fact.name()).append(" [").append(fact.type())
                .append("] 描述=").append(abbreviate(fact.description(), 240)).append('\n'));
        }
        for (Long id : deterministic.usedRelationIds()) {
            ledger.relation(id).ifPresent(fact -> appendRelation(evidence, fact, ledger, plan));
        }
        for (Long id : deterministic.usedChunkIds()) {
            ledger.chunk(id).ifPresent(fact -> evidence.append("c#").append(id)
                .append(" [").append(fact.docType()).append(':').append(fact.docId())
                .append("] ").append(fact.title()).append("：")
                .append(fact.text() == null ? "" : fact.text()).append('\n'));
        }
        // The permission-checked ledger can attach the relation whose source is a cited chunk.
        // This lets the verifier use the stored interval rather than demand the same date in prose.
        if (plan != null && plan.requiresTemporal()) {
            for (EvidenceLedger.RelationFact relation : ledger.relations()) {
                if (relation.sourceChunkId() != null
                    && deterministic.usedChunkIds().contains(relation.sourceChunkId())
                    && !deterministic.usedRelationIds().contains(relation.id())) {
                    appendRelation(evidence, relation, ledger, plan);
                }
            }
        }
        GraphAnswerScope.Scope graphScope = GraphAnswerScope.describe(question, ledger);
        if (graphScope.requested()) {
            evidence.append(graphScope.text()).append(graphScope.instruction()).append('\n');
        }
        VerificationEvidenceContext.Packet packet = VerificationEvidenceContext.pack(
            ledger, plan, deterministic, graphScope, evidence.toString());
        evidenceObserver.accept(packet.metadata());
        if (packet.tokenCount() > EvidenceContext.MAX_BUDGET)
            return addReason(deterministic, "SEMANTIC_UNCERTAIN:EVIDENCE_BUDGET；本地打包证据仍超限，未发起语义模型请求");
        String prompt = "分别检查事实支持和回答完整性。核验对象是回答已作出的具体断言，以及用户明确提出的作答要求。"
            + "通用查找、筛选、练习建议无需平台证据；具名赛事推荐的专业适用性不是通用建议。"
            + "即使改写删除专业名称，推荐仍隐含该赛事适合原问题中的用户目标；赛事存在、主办方正确或相邻学科均不足以证明适用。"
            + "名称相似、引用 ID 存在但事实不同均不算支持。逐个断言核对关系的实际主体、对象、方向、关系类型及端点实体类型。"
            + "粗粒度 SKILL 类型不能证明前端、语言生态等分类；未完整查询某主体不能断言该主体无边，完整查询也仅覆盖当前授权范围。"
            + "不能补全外部赛事、资格或项目使用事实。"
            + "完整性只核对用户明确要求的步骤数量、指定属性和对比对象；未要求穷举时，不得因证据还有其他名词而新增必答项。"
            + "用户问‘哪些’时，检查是否覆盖已确定属于该主体、该属性的结果；不能把未经分类、仅在原文出现的工具都视为遗漏。"
            + "授权图谱候选清单中的未引用项目也要核对；不可只检查回答自己选择引用的项目。"
            + "只有原文、查询截断或清单截断时，不能判图谱集合已完整回答，应给 coverage=INCOMPLETE。"
            + "已明确限定为部分或原文的正确断言可判 facts=SUPPORTED；信息未取齐本身不是该断言错误。"
            + "技术分类必须区分语言/框架生态和项目部署依赖。被 Java 后端使用的独立数据库、中间件或部署工具不因此成为 Java 专属生态。"
            + "允许将这些组件表述为跨语言基础设施；若生态归属不唯一，说明边界即可，不能要求额外工具才能算完整。"
            + "时序采用 [validFrom,validTo)；validTo=null 表示无已记录终止时间，而非信息缺失。"
            + "程序给出的区间重叠/覆盖结果是日期计算依据，不得因原文未重复用户日期而推翻。"
            + "列出已证实匹配关系不要求逐个排除所有无关主体；集合问题还应核对授权检索范围与明确限定，不能把截断清单视为完整。"
            + "仅返回 JSON：{\"facts\":\"SUPPORTED|UNSUPPORTED|UNCERTAIN\","
            + "\"coverage\":\"COMPLETE|INCOMPLETE|UNCERTAIN\",\"unsupported\":[\"每条≤20字\"],"
            + "\"missing\":[\"每条≤20字\"]}。"
            + "每个数组最多2条、每条不超过20字，只写结论不要复述证据。"
            + "无缺陷用空数组；无法判断才返回 UNCERTAIN；不要创造原问题没有的要求。"
            + (question == null ? "" : "\n【用户问题，改写后仍适用】" + question)
            + "\n【证据】\n" + packet.text()
            + "【回答】" + answer;
        try {
            AiClient.AiChatResult response = ai.chat(List.of(
                message("system", "你是事实核验器，只根据给定证据判断，不补全外部知识。"),
                message("user", prompt)), VERDICT_COMPLETION_BUDGET);
            if ("length".equals(response.finishReason)) return addReason(deterministic, "SEMANTIC_UNCERTAIN:TRUNCATED");
            JsonNode verdict = json.readTree(stripJsonFence(response.content));
            // Only the old no-question API accepts its old response schema; production requires both verdicts.
            boolean legacy = question == null && !verdict.has("facts") && verdict.has("verdict");
            String facts = verdict.path(legacy ? "verdict" : "facts").asText("");
            String coverage = legacy ? "COMPLETE" : verdict.path("coverage").asText("");
            VerificationResult checked = deterministic;
            boolean factContradiction = verdict.path("unsupported").isArray()
                && !verdict.path("unsupported").isEmpty();
            boolean coverageContradiction = verdict.path("missing").isArray()
                && !verdict.path("missing").isEmpty();
            if (!"SUPPORTED".equals(facts) || factContradiction) {
                checked = addReason(checked, ("UNSUPPORTED".equals(facts) || factContradiction
                    ? "SEMANTIC_UNSUPPORTED:FACTS:" : "SEMANTIC_UNCERTAIN:FACTS:")
                    + semanticReasons(verdict, "unsupported"));
            }
            boolean graphCoverageLimited = graphScope.limited() || graphScope.requested()
                && deterministic.usedRelationIds().isEmpty() && !deterministic.usedChunkIds().isEmpty();
            // A source-only property answer may be complete even when an unrelated graph traversal is incomplete.
            // Relation and collection assertions remain subject to the graph coverage gate.
            String questionFocus = question == null ? "" : QueryIntent.fromQuestion(question).focusText();
            boolean hasSourceProperty = question != null && Pattern.compile(
                "主办|举办|颜色|底色|文字色|尺寸|高度|宽度|大小|列宽|步骤|流程|规则|路径|目录")
                .matcher(question).find();
            boolean answerAssertsRelations = !deterministic.usedRelationIds().isEmpty()
                || Pattern.compile("(?:^|\\s)r#\\d+").matcher(answer).find();
            boolean sourcePropertyQuestion = hasSourceProperty && !answerAssertsRelations;
            boolean relationOrCollectionQuestion = questionFocus.matches(
                ".*(?:关联|关系|连接|涉及|对应|集合|分类|归类|全部|所有|完整|技能|技术栈|竞赛|赛事|帖子|记录|文档|实体|节点).*");
            boolean answerRequiresGraphCoverage = !sourcePropertyQuestion && question != null
                && relationOrCollectionQuestion
                || Pattern.compile("(?:图谱|图中|边|关系)\\S{0,8}(?:显示|确认|证明|存在|关联)")
                    .matcher(answer).find();
            if (graphCoverageLimited && answerRequiresGraphCoverage) {
                checked = addReason(checked, "SEMANTIC_UNSUPPORTED:COVERAGE:"
                    + (graphScope.sourceOnly() || deterministic.usedRelationIds().isEmpty()
                        ? "只有原文支持，图谱关系尚未完整确认"
                        : !graphScope.queryComplete() ? "授权查询尚未取齐"
                        : "上下文候选清单未展示完整"));
            }
            if (!answerRequiresGraphCoverage && coverageContradiction
                && verdict.path("missing").size() == 1
                && verdict.path("missing").get(0).asText().matches(
                    ".*(?:图谱|候选|授权).*?(?:未完整|不完整|未展示|未取齐).*")) {
                coverageContradiction = false;
                coverage = "COMPLETE";
            }
            if (!"COMPLETE".equals(coverage) || coverageContradiction) {
                checked = addReason(checked, ("INCOMPLETE".equals(coverage) || coverageContradiction
                    ? "SEMANTIC_UNSUPPORTED:COVERAGE:" : "SEMANTIC_UNCERTAIN:COVERAGE:")
                    + semanticReasons(verdict, "missing"));
            }
            return checked;
        } catch (Exception e) {
            if (e instanceof cn.ilink.service.ai.ChatRequestBudget.BudgetStopException stop) throw stop;
            return addReason(deterministic, "SEMANTIC_UNCERTAIN");
        }
    }

    private static void appendRelation(StringBuilder evidence, EvidenceLedger.RelationFact fact,
                                       EvidenceLedger ledger, QueryEvidencePlan plan) {
        evidence.append("r#").append(fact.id()).append(" #").append(fact.sourceId())
            .append(' ').append(ledger.entity(fact.sourceId()).map(EvidenceLedger.EntityFact::name).orElse(""))
            .append(" -> #").append(fact.targetId()).append(' ')
            .append(ledger.entity(fact.targetId()).map(EvidenceLedger.EntityFact::name).orElse(""))
            .append(' ').append(fact.type()).append(" [").append(formatDate(fact.validFrom()))
            .append(',').append(fact.validTo() == null ? "无已记录终止时间" : formatDate(fact.validTo()))
            .append(") 原文=").append(fact.factText());
        if (plan != null) {
            for (KgTimeRange range : plan.timeRanges()) {
                boolean overlaps = overlaps(fact, range);
                boolean covers = fact.validFrom() != null
                    && fact.validFrom().getTime() <= range.startDate().getTime()
                    && (fact.validTo() == null || fact.validTo().getTime() >= range.endDate().getTime());
                evidence.append("；程序日期判定：用户窗口[")
                    .append(formatDate(range.startDate())).append(',')
                    .append(formatDate(range.endDate())).append(")，重叠=")
                    .append(overlaps).append("，覆盖整个窗口=").append(covers);
            }
        }
        evidence.append('\n');
    }

    private static String formatDate(java.util.Date date) {
        if (date == null) return "未记录起始时间";
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd");
        format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(date);
    }

    private static String semanticReasons(JsonNode verdict, String field) {
        JsonNode values = verdict.path(field);
        StringBuilder reason = new StringBuilder();
        if (values.isArray()) {
            int count = 0;
            for (JsonNode value : values) {
                if (count++ == 3) break;
                if (!reason.isEmpty()) reason.append("；");
                reason.append(value.asText(""));
            }
        }
        if (reason.isEmpty()) reason.append(verdict.path("reason").asText("未提供逐项原因"));
        return abbreviate(reason.toString(), 360);
    }

    private static String stripJsonFence(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceFirst("(?s)^```(?:json)?\\s*", "")
            .replaceFirst("\\s*```$", "");
    }

    private static String abbreviate(String value, int limit) {
        if (value == null) return "";
        return value.substring(0, Math.min(limit, value.length()));
    }

    private static VerificationResult addReason(VerificationResult result, String reason) {
        List<String> reasons = new ArrayList<>(result.reasons());
        reasons.add(reason);
        return new VerificationResult(false, List.copyOf(reasons), result.usedEntityIds(),
            result.usedRelationIds(), result.usedChunkIds());
    }

    private static java.util.Map<String, String> message(String role, String content) {
        java.util.Map<String, String> row = new LinkedHashMap<>();
        row.put("role", role);
        row.put("content", content);
        return row;
    }

    private static VerificationResult result(List<String> reasons, Set<Long> entityIds,
                                             Set<Long> relationIds, Set<Long> chunkIds) {
        return new VerificationResult(reasons.isEmpty(), List.copyOf(reasons),
            Collections.unmodifiableSet(new LinkedHashSet<>(entityIds)),
            Collections.unmodifiableSet(new LinkedHashSet<>(relationIds)),
            Collections.unmodifiableSet(new LinkedHashSet<>(chunkIds)));
    }

    private static Long parseId(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean explicitWrongName(String answer, int referenceStart, String actualName) {
        int boundary = referenceStart - 1;
        while (boundary >= 0 && "。！？；;，,\n".indexOf(answer.charAt(boundary)) < 0) {
            boundary--;
        }
        String prefix = answer.substring(boundary + 1, referenceStart).trim();
        if (prefix.isEmpty() || prefix.contains(actualName)) {
            return false;
        }
        if (prefix.matches(".*(?:实体|条目|节点|记录|来源|文档|帖子|关系|主体|对象)\\s*")) {
            return false;
        }
        // A named noun phrase next to #ID must match the stored entity name.
        return prefix.matches(".*[\\p{L}]{2,}\\s*");
    }

    private static boolean overlaps(EvidenceLedger.RelationFact relation, KgTimeRange range) {
        long start = range.startDate().getTime();
        long end = range.endDate().getTime();
        return (relation.validFrom() != null && relation.validFrom().getTime() < end)
            && (relation.validTo() == null || relation.validTo().getTime() > start);
    }

    private static boolean looksLikeNoRelation(String answer) {
        return answer.contains("没有") || answer.contains("无") || answer.contains("未发现")
            || answer.contains("未见");
    }
}
