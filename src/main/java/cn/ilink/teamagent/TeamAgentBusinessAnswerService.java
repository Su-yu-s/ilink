package cn.ilink.teamagent;

import cn.ilink.config.AiProperties;
import cn.ilink.collab.TeamCollabException;
import cn.ilink.collab.TeamCollaborationAgentTools;
import cn.ilink.collab.TeamMaterialMatrixService;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.kg.AgenticGraphRagService;
import cn.ilink.util.DisplayNameResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Deterministic business answers and the scoped Agentic GraphRAG bridge. */
@Service
public class TeamAgentBusinessAnswerService {
    private static final String ACTION_PROMPT_VERSION = "team-agent-actions-v3";
    private static final int MAX_COLLABORATION_FACTS_CHARS = 48 * 1024;
    private static final int MAX_COLLABORATION_TABLES = 5;
    private static final int MAX_COLLABORATION_TABLE_ROWS = 25;
    private static final String ACTION_SYSTEM_PROMPT = """
        你是高校竞赛团队协作平台中的任务规划 Agent。你只能根据提供的当前团队事实提出任务动作。
        输出必须是一个 JSON 对象，且只能包含 answer 与 actions 两个字段。
        answer 是给用户看的中文精简 Markdown，不得输出隐藏推理过程。
        actions 是数组，最多 10 项，每项只能包含 actionType、targetId、after、reason、evidence。
        after 必须是 JSON 对象，并按动作类型使用以下字段：
        - CREATE_TASK：taskTitle、taskDescription、taskType、priority、estimatedHours、
          deadline、assignedTo、requiresReview。taskTitle 与 assignedTo 必填，
          requiresReview 必须为 true；未知截止时间时省略 deadline。taskType 只能取
          DEVELOPMENT、DESIGN、TESTING、DOCUMENTATION、OTHER 之一；priority 只能取 1-4。
        - CREATE_SUBTASK：与 CREATE_TASK 相同，并额外必填 parentTaskId。
        - ASSIGN_TASK：{"assignedTo": 当前成员 ID}
        - SET_TASK_PRIORITY：{"priority": 1-4 的整数}
        - SET_TASK_DEADLINE：{"deadline": ISO-8601 时间或 null}
        - SEND_TASK_REMINDER：{"reminderText": 纯文本}
        - SEND_WEEKLY_REPORT：仅使用服务端提供的周报字段，不要在普通任务规划中生成。
        当请求涉及团队协作文档、表格或竞赛材料时，改用以下动作类型：
        - CREATE_TEAM_DOCUMENT：title（必填，≤120 字）、contentMarkdown（Markdown 正文，
          可省略）、visibility（TEAM 或 RESTRICTED，默认 TEAM）。
        - APPEND_TEAM_DOCUMENT：targetId 必填且来自当前协作资产事实，after 仅含
          appendMarkdown（要追加的 Markdown 章节）。
        - CREATE_TEAM_TABLE：title（必填）、columns（对象数组，每项含 key、label，
          可选 type）、rows（对象数组，每项含 cells 对象，键必须来自 columns 的 key）、
          visibility。
        - UPDATE_TEAM_TABLE_ROWS：targetId 必填且来自当前协作资产事实，after 仅含
          updates，每项为 {"rowId": 表格行 ID, "cells": {列 key: 值}}；行 ID 与列 key
          必须来自当前表格事实，不得自造。
        - CREATE_MATERIAL_REQUIREMENT：title（必填）、description、category、required
          （布尔，默认 true）。
        - LINK_MATERIAL_EVIDENCE：targetId 为材料要求 ID，after 含 assetType
          （FILE、DOCUMENT、SPREADSHEET、TASK 之一）、assetId（必须来自当前事实）、
          evidenceExcerpt（可选摘要）。
        - SET_MATERIAL_REVIEW：targetId 为材料要求 ID，after 含 status（只能是
          NEEDS_REVIEW）与可选 note。你无权判定材料已满足，真人复核由队长或导师完成。
        协作类动作只写“建议”，用户确认前不会落库；不确定时宁可不生成动作。
        除 CREATE_TASK、CREATE_SUBTASK、CREATE_TEAM_DOCUMENT、CREATE_TEAM_TABLE、
        CREATE_MATERIAL_REQUIREMENT 与 SEND_WEEKLY_REPORT 外，动作必须提供当前事实中
        真实存在的 targetId。
        同理，APPEND_TEAM_DOCUMENT 与 UPDATE_TEAM_TABLE_ROWS 的 targetId、行 ID、列 key
        与 assetId 必须来自当前事实；CREATE_* 之外的协作动作不得指向不存在的资产。
        不要输出 schemaVersion 或 before 字段。
        不存在的字段不要输出，禁止输出 teamId、userId、role、permission、expectedTargetVersion、
        SQL、URL、执行器类名或未经提供的成员/任务 ID。
        actionType 只能取 CREATE_TASK、CREATE_SUBTASK、ASSIGN_TASK、SET_TASK_PRIORITY、
        SET_TASK_DEADLINE、SEND_TASK_REMINDER、SEND_WEEKLY_REPORT、CREATE_TEAM_DOCUMENT、
        APPEND_TEAM_DOCUMENT、CREATE_TEAM_TABLE、UPDATE_TEAM_TABLE_ROWS、
        CREATE_MATERIAL_REQUIREMENT、LINK_MATERIAL_EVIDENCE、SET_MATERIAL_REVIEW。
        创建任务必须 assignedTo 有效成员，requiresReview 固定 true。
        你不得生成审核任务、删除任务、移除成员、解散团队、转让队长或修改团队状态。
        没有把握时不生成动作，只给出保守建议。
        JSON 形态示例（字段值仅作格式参考，实际内容必须来自当前团队事实）：
        {"answer":"已根据团队事实生成任务提案。","actions":[{"actionType":"CREATE_TASK","after":{"taskTitle":"完成接口联调","taskDescription":"按验收清单完成联调并记录结果","taskType":"DEVELOPMENT","priority":2,"estimatedHours":4,"deadline":"2026-10-30T10:00:00Z","assignedTo":75,"requiresReview":true},"reason":"联调是当前阶段的关键路径","evidence":[]}]}
        """;
    private static final String COLLAB_DOCUMENT_HINT = """
        本次请求聚焦团队协作文档：优先生成 CREATE_TEAM_DOCUMENT 或 APPEND_TEAM_DOCUMENT，
        正文用 Markdown 分节，只写当前事实能支撑的内容，缺口以“待补充”标注。
        """;
    private static final String COLLAB_TABLE_HINT = """
        本次请求聚焦团队协作表格：优先生成 CREATE_TEAM_TABLE 或 UPDATE_TEAM_TABLE_ROWS，
        列要少而有用（例如任务、负责人、状态、截止时间），单元格值只放事实或待确认占位。
        """;

    private final TeamAgentMetricsService metricsService;
    private final TeamScopedGraphRagAdapter graphRagAdapter;
    private final TeamAgentActionSchema actionSchema;
    private final TeamAgentActionProposalService proposalService;
    private final TeamCollaborationAgentTools collaborationTools;
    private final AiClient aiClient;
    private final AiProperties aiProperties;
    private final TeamAgentProperties teamAgentProperties;
    private final ObjectMapper objectMapper;

    public TeamAgentBusinessAnswerService(TeamAgentMetricsService metricsService,
                                          TeamScopedGraphRagAdapter graphRagAdapter,
                                          TeamAgentActionSchema actionSchema,
                                          TeamAgentActionProposalService proposalService,
                                          TeamCollaborationAgentTools collaborationTools,
                                          AiClient aiClient,
                                          AiProperties aiProperties,
                                          TeamAgentProperties teamAgentProperties,
                                          ObjectMapper objectMapper) {
        this.metricsService = metricsService;
        this.graphRagAdapter = graphRagAdapter;
        this.actionSchema = actionSchema;
        this.proposalService = proposalService;
        this.collaborationTools = collaborationTools;
        this.aiClient = aiClient;
        this.aiProperties = aiProperties;
        this.teamAgentProperties = teamAgentProperties;
        this.objectMapper = objectMapper;
    }

    public Answer answer(TeamAgentIntentRouter.Route route, String question,
                         TeamAgentScope scope, Long runId, Long conversationId,
                         AgenticGraphRagService.AgenticStreamListener listener) {
        TeamAgentMetricsService.Snapshot snapshot = metricsService.snapshot(scope);
        // Collaboration reads are scope-bound: the read-only tools refuse to run
        // without a server-bound TeamAgentScope, and this path never goes through
        // the GraphRAG adapter that binds it for knowledge routes.
        try (TeamAgentScopeContext.BoundScope ignored = TeamAgentScopeContext.bind(scope)) {
            return switch (route) {
                case KNOWLEDGE_DEEP, KNOWLEDGE_QUICK -> knowledge(route, question, scope,
                    runId, conversationId, listener);
                case TEAM_METRICS -> metrics(snapshot);
                case TEAM_RISK -> risks(snapshot);
                case WEEKLY_DRAFT -> weekly(snapshot);
                case ACTION_PLAN -> actionPlan(question, snapshot, null);
                case COLLAB_DOCUMENT -> actionPlan(question, snapshot, COLLAB_DOCUMENT_HINT);
                case COLLAB_TABLE -> actionPlan(question, snapshot, COLLAB_TABLE_HINT);
                case MATERIAL_REVIEW -> materials(snapshot);
            };
        }
    }

    private Answer knowledge(TeamAgentIntentRouter.Route route, String question,
                             TeamAgentScope scope, Long runId, Long conversationId,
                             AgenticGraphRagService.AgenticStreamListener listener) {
        requireAi();
        boolean deep = route == TeamAgentIntentRouter.Route.KNOWLEDGE_DEEP
            && teamAgentProperties.isDeepGraphRagEnabled();
        TeamScopedGraphRagAdapter.Result result = deep
            ? graphRagAdapter.query(question, scope, runId, conversationId, listener)
            : graphRagAdapter.queryQuick(question, scope);
        String content = TeamAgentMarkdownSanitizer.sanitize(result.answer(), 12000);
        if (result.fallback()) {
            content += "\n\n> 当前回答使用了受限证据模式；未找到可验证依据的内容不会作为事实给出。";
        }
        return new Answer(content,
            TeamAgentMarkdownSanitizer.sanitizeCitations(result.citations()),
            List.of(), result.rounds(), result.toolCalls(), deep ? 1 : 1);
    }

    private Answer metrics(TeamAgentMetricsService.Snapshot snapshot) {
        Map<String, Object> counts = snapshot.counts();
        String content = "## 当前任务态势\n\n"
            + "- 任务总数：" + value(counts.get("totalTasks")) + "\n"
            + "- 未结束：" + value(counts.get("openTasks")) + "；已完成："
            + value(counts.get("completedTasks")) + "\n"
            + "- 待处理：" + value(counts.get("pending")) + "；进行中："
            + value(counts.get("inProgress")) + "；待审核："
            + value(counts.get("review")) + "\n"
            + "- 已逾期：" + value(counts.get("overdue")) + "；7 天内到期："
            + value(counts.get("dueIn7Days")) + "\n"
            + "- 完成率：" + completionRate(counts.get("completionRate")) + "\n\n"
            + "> 数据生成时间：" + value(counts.get("generatedAt"))
            + "。统计仅基于当前用户有权读取的任务。";
        List<Map<String, Object>> citations = List.of(structuredCitation(
            "TEAM_METRICS", snapshot.team().getId(), "团队任务聚合",
            String.valueOf(counts.get("generatedAt"))));
        return new Answer(content, citations, List.of(), 0, 0, 1);
    }

    private Answer risks(TeamAgentMetricsService.Snapshot snapshot) {
        List<Map<String, Object>> risks = snapshot.risks();
        StringBuilder content = new StringBuilder("## 团队风险巡检\n\n");
        if (risks.isEmpty()) {
            content.append("当前没有可证明的逾期、未指派或长期待审核风险。\n\n");
        } else {
            content.append("发现 ").append(risks.size()).append(" 项需要关注：\n\n");
            risks.stream().limit(10).forEach(risk -> content
                .append("- **").append(escapeText(risk.get("title"))).append("**：")
                .append(risk.get("message")).append("\n"));
            content.append('\n');
        }
        content.append("> 风险判定使用服务器当前任务快照；未显示的任务不在你的读取范围内。\n")
            .append("> 生成时间：").append(value(snapshot.counts().get("generatedAt")));
        List<Map<String, Object>> citations = risks.stream().limit(10)
            .map(row -> structuredCitation("TEAM_TASK", longValue(row.get("taskId")),
                String.valueOf(row.getOrDefault("title", "团队任务")),
                String.valueOf(snapshot.counts().get("generatedAt"))))
            .toList();
        return new Answer(TeamAgentMarkdownSanitizer.sanitize(content.toString(), 12000),
            citations, List.of(), 0, risks.size(), 1);
    }

    private Answer weekly(TeamAgentMetricsService.Snapshot snapshot) {
        String report = TeamAgentMarkdownSanitizer.sanitize(snapshot.weeklyMarkdown(), 6000);
        List<TeamAgentActionDraft> actions = new ArrayList<>();
        if (snapshot.scope().actionsEnabled() && teamAgentProperties.isActionsEnabled()) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("reportMarkdown", report);
            after.put("reportHash", TeamAgentHashes.contentHash("weekly-report", report));
            after.put("windowStart", snapshot.weekStart().toString());
            after.put("windowEnd", snapshot.weekEnd().toString());
            actions.add(new TeamAgentActionDraft(TeamAgentActionSchema.VERSION,
                "SEND_WEEKLY_REPORT", "TEAM_CHAT", snapshot.team().getId(), null,
                Map.of(), after, "发送已生成的团队周报", List.of(structuredCitation(
                    "TEAM_METRICS", snapshot.team().getId(), "周报确定聚合",
                    String.valueOf(snapshot.counts().get("generatedAt"))))));
        }
        return new Answer(report + "\n\n> 这是可编辑前的冻结草稿。确认发送后才会出现在团队群聊。",
            List.of(structuredCitation("TEAM_METRICS", snapshot.team().getId(),
                "周报确定聚合", String.valueOf(snapshot.counts().get("generatedAt")))),
            actions, 0, 1, 1);
    }

    private Answer actionPlan(String question, TeamAgentMetricsService.Snapshot snapshot,
                              String guidance) {
        requireAi();
        String facts = actionFacts(snapshot, guidance != null);
        String normalizedQuestion = TeamAgentHashes.normalizeText(question);
        String validationFeedback = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            AiClient.AiChatResult result = requestActionPlan(
                facts, normalizedQuestion, guidance, validationFeedback);
            try {
                return parseActionPlan(result, snapshot, attempt + 1);
            } catch (TeamAgentException e) {
                if (attempt == 1
                    || e.getErrorCode() != TeamAgentErrorCode.MODEL_OUTPUT_INVALID) {
                    throw e;
                }
                validationFeedback = e.getMessage() == null ? "输出结构不符合契约"
                    : e.getMessage().substring(0, Math.min(200, e.getMessage().length()));
            }
        }
        throw new TeamAgentException(TeamAgentErrorCode.MODEL_OUTPUT_INVALID,
            "动作规划未通过输出校验");
    }

    /**
     * Deterministic material readiness answer. It never calls the model, so the
     * matrix stays readable when AI is not configured, and every status shown here
     * is the server-side review state rather than a model conclusion.
     */
    private Answer materials(TeamAgentMetricsService.Snapshot snapshot) {
        Map<String, Object> matrix;
        try {
            matrix = collaborationTools.materialMatrix(null, null);
        } catch (TeamCollabException e) {
            return new Answer("## 竞赛材料对照\n\n"
                + "> " + escapeText(e.getMessage() == null ? "暂时无法读取材料矩阵" : e.getMessage()),
                List.of(), List.of(), 0, 0, 0);
        }
        Map<String, Object> counts = asMap(matrix.get("counts"));
        List<Map<String, Object>> requirements = asList(matrix.get("requirements"));
        Object competition = matrix.get("competition");
        String generatedAt = String.valueOf(snapshot.counts().get("generatedAt"));

        StringBuilder content = new StringBuilder("## 竞赛材料对照\n\n");
        String competitionLabel = competitionLabel(competition);
        if (competitionLabel != null) {
            content.append("对照赛事：**").append(competitionLabel).append("**\n\n");
        }
        content.append("- 必交材料：").append(value(matrix.get("requiredMet")))
            .append(" / ").append(value(matrix.get("requiredTotal"))).append(" 已确认满足\n")
            .append("- 全部要求：").append(value(counts.get("total")))
            .append(" 项；已确认满足 ").append(value(counts.get("MET")))
            .append("；待复核 ").append(value(counts.get("NEEDS_REVIEW")))
            .append("；确认缺失 ").append(value(counts.get("MISSING")))
            .append("；尚未核验 ").append(value(counts.get("UNCHECKED")))
            .append("\n\n");

        List<Map<String, Object>> attention = requirements.stream()
            .filter(row -> Boolean.TRUE.equals(row.get("required")))
            .filter(row -> !Objects.equals("MET", String.valueOf(row.get("reviewStatus"))))
            .toList();
        if (!attention.isEmpty()) {
            content.append("### 优先处理（必交且未确认满足）\n\n");
            attention.stream().limit(12).forEach(row -> content.append("- **")
                .append(escapeText(row.get("title"))).append("**：")
                .append(reviewLabel(String.valueOf(row.get("reviewStatus"))))
                .append("；证据 ").append(evidenceCount(row)).append(" 项")
                .append(sourceNote(row)).append('\n'));
            if (attention.size() > 12) {
                content.append("- 另有 ").append(attention.size() - 12).append(" 项同类要求\n");
            }
            content.append('\n');
        }
        List<Map<String, Object>> agentSuggestions = requirements.stream()
            .filter(row -> "AGENT_SUGGESTED".equals(String.valueOf(row.get("sourceType"))))
            .toList();
        if (!agentSuggestions.isEmpty()) {
            content.append("### Agent 建议待真人确认\n\n")
                .append("- ").append(agentSuggestions.size())
                .append(" 项材料要求由 Agent 提出，仍需队长或导师复核。\n\n");
        }
        if (requirements.isEmpty()) {
            content.append("当前还没有材料要求。可以在团队空间的协作资产里手工添加，"
                + "或让 Agent 依据赛事通知生成待复核的建议。\n\n");
        }
        content.append("> “已确认满足”只来自队长或导师的真人复核；Agent 只能提出待复核建议。\n")
            .append("> 生成时间：").append(escapeText(generatedAt));

        List<Map<String, Object>> citations = attention.stream().limit(10)
            .map(row -> structuredCitation("MATERIAL_REQUIREMENT",
                longValue(row.get("id")), String.valueOf(row.get("title")), generatedAt))
            .toList();
        return new Answer(TeamAgentMarkdownSanitizer.sanitize(content.toString(), 12000),
            citations, List.of(), 0, requirements.size(), 0);
    }

    private AiClient.AiChatResult requestActionPlan(String facts, String question,
                                                     String guidance,
                                                     String validationFeedback) {
        StringBuilder user = new StringBuilder();
        if (validationFeedback != null) {
            user.append("上一次输出未通过服务端校验：")
                .append(validationFeedback)
                .append("\n请严格按契约重新生成，不要解释校验过程。\n\n");
        }
        if (guidance != null) {
            user.append(guidance).append('\n');
        }
        user.append("当前团队事实：\n").append(facts)
            .append("\n\n用户请求：\n").append(question)
            .append("\n\n只返回 JSON 对象。");
        try {
            return aiClient.chat(List.of(
                message("system", ACTION_SYSTEM_PROMPT),
                message("user", user.toString())
            ), 2600);
        } catch (AiClient.AiUnavailableException e) {
            throw mapAiFailure(e);
        }
    }

    private Answer parseActionPlan(AiClient.AiChatResult result,
                                   TeamAgentMetricsService.Snapshot snapshot,
                                   int modelCalls) {
        JsonNode root = parseObject(result.content);
        rejectUnknownTopLevel(root);
        String answer = root.path("answer").isTextual()
            ? root.path("answer").asText("") : "";
        if (answer.isBlank()) {
            throw new TeamAgentException(TeamAgentErrorCode.MODEL_OUTPUT_INVALID,
                "动作规划缺少说明");
        }
        JsonNode actionsNode = root.path("actions");
        List<TeamAgentActionDraft> drafts = actionsNode.isArray()
            ? actionSchema.normalizeProposal(actionsNode) : List.of();
        String safeAnswer = TeamAgentMarkdownSanitizer.sanitize(answer, 12000);
        List<Map<String, Object>> citations = List.of(structuredCitation(
            "TEAM_METRICS", snapshot.team().getId(), "任务与成员结构化快照",
            String.valueOf(snapshot.counts().get("generatedAt"))));
        return new Answer(safeAnswer, citations, drafts, 0,
            snapshot.visibleTasks().size(), modelCalls);
    }

    private void rejectUnknownTopLevel(JsonNode root) {
        var names = root.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!"answer".equals(name) && !"actions".equals(name)) {
                throw new TeamAgentException(TeamAgentErrorCode.MODEL_OUTPUT_INVALID,
                    "动作规划包含未知字段");
            }
        }
    }

    private JsonNode parseObject(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int first = text.indexOf('\n');
            int last = text.lastIndexOf("```");
            if (first >= 0 && last > first) text = text.substring(first + 1, last).trim();
        }
        try {
            JsonNode root = objectMapper.readTree(text);
            if (root == null || !root.isObject()) throw new IllegalArgumentException();
            return root;
        } catch (Exception e) {
            throw new TeamAgentException(TeamAgentErrorCode.MODEL_OUTPUT_INVALID,
                "动作规划未返回合法 JSON");
        }
    }

    private String actionFacts(TeamAgentMetricsService.Snapshot snapshot,
                               boolean includeCollaboration) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("teamId", snapshot.team().getId());
        facts.put("teamName", snapshot.team().getTitle());
        facts.put("teamStatus", snapshot.team().getStatus());
        facts.put("role", snapshot.scope().role().name());
        facts.put("competitionId", snapshot.scope().competitionId());
        facts.put("catalogCompetitionName", snapshot.scope().catalogCompetitionName());
        facts.put("customCompetition", snapshot.scope().customCompetition());
        facts.put("counts", snapshot.counts());
        facts.put("members", snapshot.members().values().stream()
            .map(user -> memberFact(user, snapshot)).toList());
        facts.put("tasks", snapshot.visibleTasks().stream().limit(80)
            .map(this::taskFact).toList());
        if (includeCollaboration) {
            facts.put("collaboration", collaborationFacts());
        }
        try {
            return objectMapper.writeValueAsString(facts);
        } catch (Exception e) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                "团队事实无法序列化");
        }
    }

    /**
     * The model can only propose writes against identifiers it can actually
     * see. Every read here goes through the scope-bound collaboration tools, so
     * RESTRICTED assets remain invisible without an explicit grant.
     */
    private Map<String, Object> collaborationFacts() {
        Map<String, Object> facts = new LinkedHashMap<>();
        Map<String, Object> listed = collaborationTools.listAssets();
        List<Map<String, Object>> assets = asList(listed == null ? null : listed.get("assets"))
            .stream().limit(30).map(row -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("assetType", row.get("assetType"));
                item.put("id", row.get("id"));
                item.put("title", row.get("title"));
                item.put("visibility", row.get("visibility"));
                item.put("status", row.get("status"));
                item.put("version", row.get("version"));
                return item;
            }).toList();
        facts.put("assets", assets);

        List<Map<String, Object>> tables = new ArrayList<>();
        assets.stream()
            .filter(row -> "SPREADSHEET".equals(String.valueOf(row.get("assetType"))))
            .limit(MAX_COLLABORATION_TABLES)
            .forEach(row -> {
                Long id = longValue(row.get("id"));
                if (id == null) return;
                Map<String, Object> table = collaborationTools.getSpreadsheet(
                    id, MAX_COLLABORATION_TABLE_ROWS);
                if (table == null) return;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", table.get("id"));
                item.put("title", table.get("title"));
                item.put("version", table.get("version"));
                item.put("columns", table.get("columns"));
                item.put("rows", table.get("rows"));
                tables.add(item);
            });
        facts.put("tables", tables);

        Map<String, Object> matrix = collaborationTools.materialMatrix(null, null);
        List<Map<String, Object>> requirements = asList(
            matrix == null ? null : matrix.get("requirements"))
            .stream().limit(40).map(row -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.get("id"));
                item.put("title", row.get("title"));
                item.put("required", row.get("required"));
                item.put("reviewStatus", row.get("reviewStatus"));
                item.put("version", row.get("version"));
                return item;
            }).toList();
        facts.put("materialRequirements", requirements);
        facts.put("files", collaborationTools.visibleFiles(null).stream()
            .limit(20).toList());
        return boundCollaborationFacts(facts);
    }

    /**
     * The action prompt must stay bounded even when a team has several dense
     * tables. Trim optional row data first, then asset descriptions, while
     * preserving identifiers that validated actions depend on.
     */
    private Map<String, Object> boundCollaborationFacts(Map<String, Object> facts) {
        try {
            if (objectMapper.writeValueAsString(facts)
                .length() <= MAX_COLLABORATION_FACTS_CHARS) {
                return facts;
            }
            List<Map<String, Object>> tables = asList(facts.get("tables"));
            for (Map<String, Object> table : tables) {
                table.put("rows", List.of());
                table.put("rowsOmitted", true);
                if (objectMapper.writeValueAsString(facts)
                    .length() <= MAX_COLLABORATION_FACTS_CHARS) {
                    return facts;
                }
            }
            List<Map<String, Object>> assets = asList(facts.get("assets"));
            for (Map<String, Object> asset : assets) {
                asset.remove("title");
                asset.put("titleOmitted", true);
                if (objectMapper.writeValueAsString(facts)
                    .length() <= MAX_COLLABORATION_FACTS_CHARS) {
                    return facts;
                }
            }
            facts.put("files", List.of());
            facts.put("filesOmitted", true);
            facts.put("materialRequirements", List.of());
            facts.put("materialRequirementsOmitted", true);
            facts.put("truncated", true);
            return facts;
        } catch (Exception e) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                "协作事实无法序列化");
        }
    }

    private Map<String, Object> memberFact(User user, TeamAgentMetricsService.Snapshot snapshot) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", user.getId());
        row.put("displayName", DisplayNameResolver.resolve(user));
        row.put("role", Objects.equals(user.getId(), snapshot.team().getCreatorId())
            ? "LEADER" : snapshot.approvedApplications().stream()
            .filter(app -> Objects.equals(app.getUserId(), user.getId()))
            .map(TeamApplication::getMemberRole).findFirst().orElse("STUDENT"));
        return row;
    }

    private Map<String, Object> taskFact(TeamTask task) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", task.getId());
        row.put("title", task.getTaskTitle());
        row.put("status", task.getStatus());
        row.put("priority", task.getPriority());
        row.put("deadline", TeamAgentTime.iso(task.getDeadline()));
        row.put("assignedTo", task.getAssignedTo());
        row.put("parentTaskId", task.getParentTaskId());
        row.put("version", task.getVersion());
        return row;
    }

    private Map<String, Object> structuredCitation(String kind, Long id, String label,
                                                    String generatedAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.put("id", id);
        row.put("label", label);
        row.put("generatedAt", generatedAt);
        return row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) rows.add((Map<String, Object>) map);
        }
        return rows;
    }

    /** The catalog competition name when bound, otherwise the custom entry. */
    private String competitionLabel(Object raw) {
        Map<String, Object> context = asMap(raw);
        Object catalog = context.get("catalogName");
        if (catalog != null && !String.valueOf(catalog).isBlank()) {
            return escapeText(catalog);
        }
        Object custom = context.get("customCompetition");
        if (custom != null && !String.valueOf(custom).isBlank()) {
            return escapeText(custom) + "（自定义赛事）";
        }
        return null;
    }

    private String reviewLabel(String status) {
        return switch (status == null ? "" : status) {
            case "MET" -> "已确认满足";
            case "PARTIAL" -> "已确认部分满足";
            case "MISSING" -> "已确认缺失";
            case "NEEDS_REVIEW" -> "待真人复核";
            default -> "尚未核验";
        };
    }

    private String evidenceCount(Map<String, Object> requirement) {
        return String.valueOf(asList(requirement.get("evidence")).size());
    }

    private String sourceNote(Map<String, Object> requirement) {
        return "AGENT_SUGGESTED".equals(String.valueOf(requirement.get("sourceType")))
            ? "（Agent 建议）" : "";
    }

    private void requireAi() {
        if (!aiProperties.isConfigured()) {
            throw new TeamAgentException(TeamAgentErrorCode.AI_NOT_CONFIGURED,
                "AI 服务尚未配置，暂时无法执行该智能分析");
        }
    }

    private TeamAgentException mapAiFailure(AiClient.AiUnavailableException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("超时")) {
            return new TeamAgentException(TeamAgentErrorCode.AI_TIMEOUT,
                "AI 服务响应超时，请稍后重试");
        }
        return new TeamAgentException(TeamAgentErrorCode.AI_UPSTREAM_FAILED,
            "AI 服务暂时不可用，请稍后重试");
    }

    private static Map<String, String> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private String completionRate(Object raw) {
        if (!(raw instanceof Number number)) return "暂无任务";
        return String.format(java.util.Locale.ROOT, "%.2f%%", number.doubleValue() * 100.0);
    }

    private String value(Object raw) {
        return raw == null ? "0" : escapeText(raw);
    }

    private String escapeText(Object raw) {
        return String.valueOf(raw).replace("<", "&lt;").replace(">", "&gt;");
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record Answer(String content, List<Map<String, Object>> citations,
                         List<TeamAgentActionDraft> actionDrafts, int rounds,
                         int toolCalls, int modelCalls) {}
}
