package cn.ilink.teamagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict schemaVersion=1.0 action boundary. Unknown fields are rejected; before
 * values are never accepted from the model and are added by the server later.
 */
@Component
public class TeamAgentActionSchema {
    public static final String VERSION = "1.0";
    public static final int MAX_ACTIONS = 10;
    public static final int MAX_CREATES = 5;

    private static final Set<String> ROOT_FIELDS = Set.of(
        "actionType", "targetId", "after", "reason", "evidence");
    private static final Set<String> EVIDENCE_FIELDS = Set.of(
        "kind", "id", "label", "generatedAt");
    private static final Set<String> CREATE_FIELDS = Set.of(
        "taskTitle", "taskDescription", "taskType", "priority", "estimatedHours",
        "deadline", "assignedTo", "requiresReview");
    private static final Set<String> SUBTASK_FIELDS = Set.of(
        "taskTitle", "taskDescription", "taskType", "priority", "estimatedHours",
        "deadline", "assignedTo", "requiresReview", "parentTaskId");
    private static final Set<String> TASK_TYPES = Set.of(
        "DEVELOPMENT", "DESIGN", "TESTING", "DOCUMENTATION", "OTHER");
    private static final Set<String> ACTION_TYPES = Set.of(
        "CREATE_TASK", "CREATE_SUBTASK", "ASSIGN_TASK", "SET_TASK_PRIORITY",
        "SET_TASK_DEADLINE", "SEND_TASK_REMINDER", "SEND_WEEKLY_REPORT",
        cn.ilink.collab.TeamCollaborationAgentCommands.CREATE_DOCUMENT,
        cn.ilink.collab.TeamCollaborationAgentCommands.APPEND_DOCUMENT,
        cn.ilink.collab.TeamCollaborationAgentCommands.CREATE_TABLE,
        cn.ilink.collab.TeamCollaborationAgentCommands.UPDATE_TABLE_ROWS,
        cn.ilink.collab.TeamCollaborationAgentCommands.CREATE_REQUIREMENT,
        cn.ilink.collab.TeamCollaborationAgentCommands.LINK_EVIDENCE,
        cn.ilink.collab.TeamCollaborationAgentCommands.SET_REVIEW);

    /** Collaboration action shapes; values are re-validated against the DB later. */
    private static final Set<String> COLLAB_CREATE_DOC_FIELDS = Set.of(
        "title", "contentMarkdown", "visibility");
    private static final Set<String> COLLAB_APPEND_DOC_FIELDS = Set.of("appendMarkdown");
    private static final Set<String> COLLAB_CREATE_TABLE_FIELDS = Set.of(
        "title", "columns", "rows", "visibility");
    private static final Set<String> COLLAB_UPDATE_TABLE_FIELDS = Set.of("updates");
    private static final Set<String> COLLAB_REQUIREMENT_FIELDS = Set.of(
        "title", "description", "category", "required");
    private static final Set<String> COLLAB_EVIDENCE_FIELDS = Set.of(
        "assetType", "assetId", "evidenceExcerpt");
    private static final Set<String> COLLAB_REVIEW_FIELDS = Set.of("status", "note");
    private static final Set<String> COLLAB_ROW_UPDATE_FIELDS = Set.of("rowId", "cells");
    private static final Set<String> COLLAB_WRITE_ACTIONS = Set.of(
        cn.ilink.collab.TeamCollaborationAgentCommands.APPEND_DOCUMENT,
        cn.ilink.collab.TeamCollaborationAgentCommands.UPDATE_TABLE_ROWS,
        cn.ilink.collab.TeamCollaborationAgentCommands.LINK_EVIDENCE,
        cn.ilink.collab.TeamCollaborationAgentCommands.SET_REVIEW);
    private static final Set<String> COLLAB_CREATE_ACTIONS = Set.of(
        cn.ilink.collab.TeamCollaborationAgentCommands.CREATE_DOCUMENT,
        cn.ilink.collab.TeamCollaborationAgentCommands.CREATE_TABLE,
        cn.ilink.collab.TeamCollaborationAgentCommands.CREATE_REQUIREMENT);

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<TeamAgentActionDraft> normalizeProposal(JsonNode root) {
        if (root == null || !root.isArray()) {
            throw invalid("动作提案必须是数组");
        }
        if (root.size() > MAX_ACTIONS) {
            throw invalid("单次最多 " + MAX_ACTIONS + " 个动作");
        }
        List<TeamAgentActionDraft> drafts = new ArrayList<>();
        int creates = 0;
        Set<String> duplicateKeys = new HashSet<>();
        for (JsonNode node : root) {
            TeamAgentActionDraft draft = normalize(node);
            if ("CREATE_TASK".equals(draft.actionType())
                || "CREATE_SUBTASK".equals(draft.actionType())
                || COLLAB_CREATE_ACTIONS.contains(draft.actionType())) {
                creates++;
            }
            String duplicateKey = draft.actionType() + "|" + draft.targetType() + "|"
                + Objects.toString(draft.targetId(), "") + "|"
                + TeamAgentHashes.structureHash("action-dedupe", draft.after());
            if (!duplicateKeys.add(duplicateKey)) {
                throw new TeamAgentException(TeamAgentErrorCode.ACTION_DUPLICATE,
                    "同一动作组不能包含重复动作");
            }
            drafts.add(draft);
        }
        if (creates > MAX_CREATES) {
            throw invalid("单次最多创建 " + MAX_CREATES + " 个任务");
        }
        validateCombination(drafts);
        drafts.sort(Comparator
            .comparingInt((TeamAgentActionDraft row) -> actionOrder(row.actionType()))
            .thenComparing(row -> row.targetId() == null ? Long.MAX_VALUE : row.targetId())
            .thenComparing(row -> TeamAgentHashes.structureHash("action-order", row.after())));
        return List.copyOf(drafts);
    }

    public TeamAgentActionDraft normalize(JsonNode node) {
        requireObject(node, "动作");
        rejectUnknown(node, ROOT_FIELDS, "动作");
        String type = requiredText(node, "actionType", 32).toUpperCase(Locale.ROOT);
        if (!ACTION_TYPES.contains(type)) {
            throw invalid("动作类型不在白名单内");
        }
        String targetType;
        Long targetId = optionalLong(node, "targetId");
        // The target version is always read from the database by the proposal
        // service. A model cannot author or override this optimistic fence.
        Integer expectedVersion = null;
        JsonNode afterNode = node.get("after");
        requireObject(afterNode, "after");

        Map<String, Object> after;
        switch (type) {
            case "CREATE_TASK" -> {
                targetType = "TASK";
                if (targetId != null) throw invalid("创建任务不能携带 targetId");
                targetId = null;
                expectedVersion = null;
                after = normalizeCreate(afterNode, false);
            }
            case "CREATE_SUBTASK" -> {
                targetType = "TASK";
                if (targetId != null) throw invalid("创建子任务不能携带 targetId");
                targetId = null;
                expectedVersion = null;
                after = normalizeCreate(afterNode, true);
            }
            case "ASSIGN_TASK" -> {
                targetType = "TASK";
                requireExistingTarget(targetId);
                after = normalizeAssign(afterNode);
            }
            case "SET_TASK_PRIORITY" -> {
                targetType = "TASK";
                requireExistingTarget(targetId);
                after = normalizePriority(afterNode);
            }
            case "SET_TASK_DEADLINE" -> {
                targetType = "TASK";
                requireExistingTarget(targetId);
                after = normalizeDeadline(afterNode);
            }
            case "SEND_TASK_REMINDER" -> {
                targetType = "TASK";
                requireExistingTarget(targetId);
                after = normalizeReminder(afterNode);
            }
            case "SEND_WEEKLY_REPORT" -> {
                targetType = "TEAM_CHAT";
                // The current URL-scoped team is the only accepted target.
                targetId = null;
                expectedVersion = null;
                after = normalizeWeekly(afterNode);
            }
            case "CREATE_TEAM_DOCUMENT" -> {
                targetType = "COLLAB_DOCUMENT";
                if (targetId != null) throw invalid("创建文档不能携带 targetId");
                targetId = null;
                expectedVersion = null;
                after = normalizeCollabCreateDocument(afterNode);
            }
            case "APPEND_TEAM_DOCUMENT" -> {
                targetType = "COLLAB_DOCUMENT";
                requireExistingTarget(targetId);
                after = normalizeCollabAppendDocument(afterNode);
            }
            case "CREATE_TEAM_TABLE" -> {
                targetType = "COLLAB_SPREADSHEET";
                if (targetId != null) throw invalid("创建表格不能携带 targetId");
                targetId = null;
                expectedVersion = null;
                after = normalizeCollabCreateTable(afterNode);
            }
            case "UPDATE_TEAM_TABLE_ROWS" -> {
                targetType = "COLLAB_SPREADSHEET";
                requireExistingTarget(targetId);
                after = normalizeCollabUpdateTable(afterNode);
            }
            case "CREATE_MATERIAL_REQUIREMENT" -> {
                targetType = "MATERIAL_REQUIREMENT";
                if (targetId != null) throw invalid("创建材料要求不能携带 targetId");
                targetId = null;
                expectedVersion = null;
                after = normalizeCollabRequirement(afterNode);
            }
            case "LINK_MATERIAL_EVIDENCE" -> {
                targetType = "MATERIAL_REQUIREMENT";
                requireExistingTarget(targetId);
                after = normalizeCollabEvidence(afterNode);
            }
            case "SET_MATERIAL_REVIEW" -> {
                targetType = "MATERIAL_REQUIREMENT";
                requireExistingTarget(targetId);
                after = normalizeCollabReview(afterNode);
            }
            default -> throw invalid("动作类型不在白名单内");
        }
        String reason = optionalText(node, "reason", 500);
        if (reason != null) reason = TeamAgentMarkdownSanitizer.sanitize(reason, 500);
        List<Map<String, Object>> evidence = normalizeEvidence(node.get("evidence"));
        return new TeamAgentActionDraft(VERSION, type, targetType, targetId, expectedVersion,
            Map.of(), after, reason, evidence);
    }

    private Map<String, Object> normalizeCreate(JsonNode after, boolean subtask) {
        rejectUnknown(after, subtask ? SUBTASK_FIELDS : CREATE_FIELDS, "after");
        Map<String, Object> values = new LinkedHashMap<>();
        String title = requiredText(after, "taskTitle", 200);
        String description = optionalText(after, "taskDescription", 4000);
        String taskType = optionalText(after, "taskType", 24);
        taskType = taskType == null ? "OTHER" : taskType.toUpperCase(Locale.ROOT);
        if (!TASK_TYPES.contains(taskType)) {
            throw invalid("任务类型不合法，taskType 必须为 DEVELOPMENT、DESIGN、"
                + "TESTING、DOCUMENTATION 或 OTHER");
        }
        int priority = optionalInt(after, "priority") == null ? 2 : optionalInt(after, "priority");
        if (priority < 1 || priority > 4) throw invalid("优先级需在 1-4 之间");
        BigDecimal hours = optionalDecimal(after, "estimatedHours");
        if (hours != null && (hours.compareTo(BigDecimal.ZERO) < 0
            || hours.compareTo(new BigDecimal("999")) > 0 || hours.scale() > 2)) {
            throw invalid("预估工时需为 0-999 且最多两位小数");
        }
        String deadline = optionalDeadline(after, "deadline", false);
        Long assignedTo = optionalLong(after, "assignedTo");
        if (assignedTo == null || assignedTo <= 0) throw invalid("创建任务必须指定负责人");
        JsonNode review = after.get("requiresReview");
        if (review != null && (!review.isBoolean() || !review.booleanValue())) {
            throw invalid("第一阶段创建任务必须要求审核");
        }
        values.put("taskTitle", title);
        values.put("taskDescription", description);
        values.put("taskType", taskType);
        values.put("priority", priority);
        values.put("estimatedHours", hours == null ? null : hours.stripTrailingZeros().toPlainString());
        values.put("deadline", deadline);
        values.put("assignedTo", assignedTo);
        values.put("requiresReview", true);
        if (subtask) {
            Long parentTaskId = optionalLong(after, "parentTaskId");
            if (parentTaskId == null || parentTaskId <= 0) throw invalid("子任务缺少父任务");
            values.put("parentTaskId", parentTaskId);
        }
        return immutable(values);
    }

    private Map<String, Object> normalizeAssign(JsonNode after) {
        rejectUnknown(after, Set.of("assignedTo"), "after");
        Long assignedTo = optionalLong(after, "assignedTo");
        if (assignedTo == null || assignedTo <= 0) throw invalid("改派必须指定有效负责人");
        return Map.of("assignedTo", assignedTo);
    }

    private Map<String, Object> normalizePriority(JsonNode after) {
        rejectUnknown(after, Set.of("priority"), "after");
        Integer priority = optionalInt(after, "priority");
        if (priority == null || priority < 1 || priority > 4) throw invalid("优先级需在 1-4 之间");
        return Map.of("priority", priority);
    }

    private Map<String, Object> normalizeDeadline(JsonNode after) {
        rejectUnknown(after, Set.of("deadline"), "after");
        if (!after.has("deadline")) throw invalid("截止日期动作必须显式提供 deadline");
        String deadline = optionalDeadline(after, "deadline", true);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("deadline", deadline);
        return immutable(values);
    }

    private Map<String, Object> normalizeReminder(JsonNode after) {
        rejectUnknown(after, Set.of("reminderText"), "after");
        String text = requiredText(after, "reminderText", 200);
        String safe = TeamAgentMarkdownSanitizer.sanitize(text, 200);
        if (safe.isBlank() || safe.contains("<") || safe.contains(">")
            || safe.matches("(?is).*https?://.*")) {
            throw invalid("提醒内容必须是纯文本");
        }
        return Map.of("reminderText", safe);
    }

    private Map<String, Object> normalizeWeekly(JsonNode after) {
        rejectUnknown(after, Set.of("reportMarkdown", "reportHash", "windowStart", "windowEnd"),
            "after");
        String report = requiredText(after, "reportMarkdown", 6000);
        report = TeamAgentMarkdownSanitizer.sanitize(report, 6000);
        String hash = requiredText(after, "reportHash", 64);
        if (!hash.equals(TeamAgentHashes.contentHash("weekly-report", report))) {
            throw invalid("周报正文哈希不匹配");
        }
        Instant start = requiredInstant(after, "windowStart");
        Instant end = requiredInstant(after, "windowEnd");
        if (!end.isAfter(start)) throw invalid("周报时间范围不合法");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("reportMarkdown", report);
        values.put("reportHash", hash);
        values.put("windowStart", start.toString());
        values.put("windowEnd", end.toString());
        return immutable(values);
    }

    private Map<String, Object> normalizeCollabCreateDocument(JsonNode after) {
        rejectUnknown(after, COLLAB_CREATE_DOC_FIELDS, "after");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", requiredText(after, "title", 120));
        values.put("contentMarkdown", sanitizeMarkdown(
            optionalText(after, "contentMarkdown", 20000)));
        values.put("visibility", collabVisibility(after));
        return immutable(values);
    }

    private Map<String, Object> normalizeCollabAppendDocument(JsonNode after) {
        rejectUnknown(after, COLLAB_APPEND_DOC_FIELDS, "after");
        return Map.of("appendMarkdown", requiredCollabMarkdown(after, "appendMarkdown", 20000));
    }

    private Map<String, Object> normalizeCollabCreateTable(JsonNode after) {
        rejectUnknown(after, COLLAB_CREATE_TABLE_FIELDS, "after");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", requiredText(after, "title", 120));
        values.put("columns", jsonValue(after, "columns", 60_000));
        values.put("rows", jsonValue(after, "rows", 200_000));
        values.put("visibility", collabVisibility(after));
        return immutable(values);
    }

    private Map<String, Object> normalizeCollabUpdateTable(JsonNode after) {
        rejectUnknown(after, COLLAB_UPDATE_TABLE_FIELDS, "after");
        JsonNode updates = after.get("updates");
        if (updates == null || !updates.isArray() || updates.isEmpty()) {
            throw invalid("updates 必须是非空数组");
        }
        if (updates.size() > 200) throw invalid("单次最多更新 200 行");
        List<Map<String, Object>> rows = new ArrayList<>();
        int cells = 0;
        for (JsonNode node : updates) {
            requireObject(node, "updates 项");
            rejectUnknown(node, COLLAB_ROW_UPDATE_FIELDS, "updates 项");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rowId", requiredText(node, "rowId", 64));
            JsonNode cellsNode = node.get("cells");
            requireObject(cellsNode, "cells");
            Map<String, Object> cellPatch = new LinkedHashMap<>();
            var names = cellsNode.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                JsonNode value = cellsNode.get(key);
                if (!value.isTextual() && !value.isNumber() && !value.isBoolean()
                    && !value.isNull()) {
                    throw invalid("单元格值只能是文本、数字或布尔值");
                }
                String text = value.isNull() ? "" : value.asText();
                if (text.length() > 4000) throw invalid("单元格内容过长");
                cellPatch.put(key, text);
                cells++;
            }
            if (cellPatch.isEmpty()) throw invalid("cells 不能为空");
            row.put("cells", immutable(cellPatch));
            rows.add(immutable(row));
        }
        if (cells > 200) throw invalid("单次最多修改 200 个单元格");
        return Map.of("updates", List.copyOf(rows));
    }

    private Map<String, Object> normalizeCollabRequirement(JsonNode after) {
        rejectUnknown(after, COLLAB_REQUIREMENT_FIELDS, "after");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", requiredText(after, "title", 200));
        values.put("description", optionalText(after, "description", 2000));
        values.put("category", optionalText(after, "category", 64));
        JsonNode required = after.get("required");
        values.put("required", required == null || required.isNull()
            ? Boolean.TRUE : required.booleanValue());
        return immutable(values);
    }

    private Map<String, Object> normalizeCollabEvidence(JsonNode after) {
        rejectUnknown(after, COLLAB_EVIDENCE_FIELDS, "after");
        Map<String, Object> values = new LinkedHashMap<>();
        String assetType = requiredText(after, "assetType", 16).toUpperCase(Locale.ROOT);
        if (!Set.of("FILE", "DOCUMENT", "SPREADSHEET", "TASK").contains(assetType)) {
            throw invalid("证据来源类型不合法");
        }
        values.put("assetType", assetType);
        Long assetId = optionalLong(after, "assetId");
        if (assetId == null || assetId <= 0) throw invalid("assetId 必须是正整数");
        values.put("assetId", assetId);
        values.put("evidenceExcerpt", optionalText(after, "evidenceExcerpt", 1000));
        return immutable(values);
    }

    private Map<String, Object> normalizeCollabReview(JsonNode after) {
        rejectUnknown(after, COLLAB_REVIEW_FIELDS, "after");
        String status = requiredText(after, "status", 20).toUpperCase(Locale.ROOT);
        // Deliberately narrow: a model may only propose a review, never a verdict.
        if (!"NEEDS_REVIEW".equals(status)) {
            throw invalid("Agent 只能提出待复核建议，不能判定材料已满足");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", status);
        values.put("note", optionalText(after, "note", 1000));
        return immutable(values);
    }

    private String collabVisibility(JsonNode after) {
        String value = optionalText(after, "visibility", 16);
        if (value == null) return "TEAM";
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!Set.of("TEAM", "RESTRICTED").contains(normalized)) {
            throw invalid("可见范围只能是 TEAM 或 RESTRICTED");
        }
        return normalized;
    }

    private String requiredCollabMarkdown(JsonNode after, String field, int max) {
        String value = requiredText(after, field, max);
        String safe = TeamAgentMarkdownSanitizer.sanitize(value, max);
        if (safe.isBlank()) throw invalid(field + "不能为空");
        return safe;
    }

    private String sanitizeMarkdown(String value) {
        if (value == null) return null;
        return TeamAgentMarkdownSanitizer.sanitize(value, 20000);
    }

    private Object jsonValue(JsonNode node, String field, int maxBytes) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isArray()) throw invalid(field + "必须是数组");
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw invalid(field + "无法解析");
        }
        if (serialized.length() > maxBytes) throw invalid(field + "内容过大");
        try {
            return objectMapper.convertValue(value, Object.class);
        } catch (IllegalArgumentException e) {
            throw invalid(field + "结构不合法");
        }
    }

    private List<Map<String, Object>> normalizeEvidence(JsonNode evidence) {
        if (evidence == null || evidence.isNull()) return List.of();
        if (!evidence.isArray() || evidence.size() > 8) throw invalid("证据列表不合法");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode node : evidence) {
            requireObject(node, "evidence");
            rejectUnknown(node, EVIDENCE_FIELDS, "evidence");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", requiredText(node, "kind", 32).toUpperCase(Locale.ROOT));
            Long id = optionalLong(node, "id");
            row.put("id", id);
            row.put("label", optionalText(node, "label", 160));
            String generatedAt = optionalText(node, "generatedAt", 64);
            if (generatedAt != null) {
                try {
                    generatedAt = Instant.parse(generatedAt).toString();
                } catch (DateTimeParseException e) {
                    throw invalid("证据时间必须为 ISO-8601");
                }
            }
            row.put("generatedAt", generatedAt);
            rows.add(immutable(row));
        }
        return List.copyOf(rows);
    }

    private void validateCombination(List<TeamAgentActionDraft> drafts) {
        boolean weekly = drafts.stream().anyMatch(row -> "SEND_WEEKLY_REPORT".equals(row.actionType()));
        if (weekly && drafts.size() != 1) throw invalid("周报动作必须单独成组");
        Set<String> changedTargets = new HashSet<>();
        for (TeamAgentActionDraft draft : drafts) {
            if (Set.of("ASSIGN_TASK", "SET_TASK_PRIORITY", "SET_TASK_DEADLINE",
                "SEND_TASK_REMINDER").contains(draft.actionType())
                && !changedTargets.add(draft.targetType() + "#" + draft.targetId())) {
                throw invalid("同一任务只能包含一个修改或提醒动作");
            }
            if (COLLAB_WRITE_ACTIONS.contains(draft.actionType())
                && !changedTargets.add(draft.targetType() + "#" + draft.targetId())) {
                throw invalid("同一协作资产或材料要求只能包含一个写动作");
            }
        }
    }

    private void requireExistingTarget(Long targetId) {
        if (targetId == null || targetId <= 0) throw invalid("动作缺少目标任务");
    }

    private void rejectUnknown(JsonNode node, Set<String> allowed, String label) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) throw invalid(label + "包含未知字段: " + name);
        }
    }

    private void requireObject(JsonNode node, String label) {
        if (node == null || !node.isObject()) throw invalid(label + "必须是对象");
    }

    private String requiredText(JsonNode node, String field, int max) {
        String text = optionalText(node, field, max);
        if (text == null || text.isBlank()) throw invalid(field + "不能为空");
        return text;
    }

    private String optionalText(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid(field + "必须是字符串");
        String text = TeamAgentHashes.normalizeText(value.asText()).trim();
        if (text.length() > max) throw invalid(field + "过长");
        return text.isEmpty() ? null : text;
    }

    private Integer optionalInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(field + "必须是整数");
        }
        return value.asInt();
    }

    private Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(field + "必须是整数");
        }
        return value.asLong();
    }

    private BigDecimal optionalDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw invalid(field + "必须是数字");
        return value.decimalValue();
    }

    private String optionalDeadline(JsonNode node, String field, boolean allowNull) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (allowNull) return null;
            return null;
        }
        if (!value.isTextual()) throw invalid(field + "必须是 ISO-8601 字符串");
        try {
            Instant instant = Instant.parse(value.asText());
            if (!allowNull && instant.isBefore(Instant.now())) {
                throw invalid("截止日期不能早于当前时间");
            }
            return instant.toString();
        } catch (DateTimeParseException e) {
            throw invalid(field + "必须是带时区的 ISO-8601 时间");
        }
    }

    private Instant requiredInstant(JsonNode node, String field) {
        String text = requiredText(node, field, 64);
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw invalid(field + "必须是 ISO-8601 时间");
        }
    }

    private Map<String, Object> immutable(Map<String, Object> source) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private int actionOrder(String actionType) {
        return switch (actionType) {
            case "CREATE_TASK", "CREATE_SUBTASK" -> 10;
            case "ASSIGN_TASK" -> 20;
            case "SET_TASK_PRIORITY" -> 30;
            case "SET_TASK_DEADLINE" -> 40;
            case "SEND_TASK_REMINDER" -> 50;
            case "SEND_WEEKLY_REPORT" -> 60;
            case "CREATE_TEAM_DOCUMENT", "CREATE_TEAM_TABLE",
                "CREATE_MATERIAL_REQUIREMENT" -> 70;
            case "APPEND_TEAM_DOCUMENT", "UPDATE_TEAM_TABLE_ROWS" -> 80;
            case "LINK_MATERIAL_EVIDENCE", "SET_MATERIAL_REVIEW" -> 90;
            default -> 99;
        };
    }

    private TeamAgentException invalid(String message) {
        return new TeamAgentException(TeamAgentErrorCode.MODEL_OUTPUT_INVALID, message);
    }
}
