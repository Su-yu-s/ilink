package cn.ilink.collab;

import cn.ilink.teamagent.TeamAgentScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Server-side preparation and execution of the seven whitelisted collaboration
 * Agent actions. Model output never supplies a version, team, actor or lease:
 * targets are read from the database here, and the business write goes through
 * the same services the REST API uses, inside the caller's action transaction.
 */
@Service
public class TeamCollaborationAgentCommands {
    public static final String CREATE_DOCUMENT = "CREATE_TEAM_DOCUMENT";
    public static final String APPEND_DOCUMENT = "APPEND_TEAM_DOCUMENT";
    public static final String CREATE_TABLE = "CREATE_TEAM_TABLE";
    public static final String UPDATE_TABLE_ROWS = "UPDATE_TEAM_TABLE_ROWS";
    public static final String CREATE_REQUIREMENT = "CREATE_MATERIAL_REQUIREMENT";
    public static final String LINK_EVIDENCE = "LINK_MATERIAL_EVIDENCE";
    public static final String SET_REVIEW = "SET_MATERIAL_REVIEW";

    public static final Set<String> ACTION_TYPES = Set.of(
        CREATE_DOCUMENT, APPEND_DOCUMENT, CREATE_TABLE, UPDATE_TABLE_ROWS,
        CREATE_REQUIREMENT, LINK_EVIDENCE, SET_REVIEW);

    private static final int MAX_APPEND_CHARS = 20_000;
    private static final int MAX_CELLS_PER_UPDATE = 200;
    private static final Set<String> REVIEW_SUGGESTIONS = Set.of("NEEDS_REVIEW");

    private final TeamCollaborationAuthorization authorization;
    private final TeamCollaborationLeaseService leaseService;
    private final TeamCollaborationAgentTools tools;
    private final TeamDocumentService documentService;
    private final TeamSpreadsheetService spreadsheetService;
    private final TeamMaterialMatrixService materialService;
    private final TeamMaterialEvidenceValidator evidenceValidator;
    private final ObjectMapper objectMapper;

    public TeamCollaborationAgentCommands(TeamCollaborationAuthorization authorization,
                                          TeamCollaborationLeaseService leaseService,
                                          TeamCollaborationAgentTools tools,
                                          TeamDocumentService documentService,
                                          TeamSpreadsheetService spreadsheetService,
                                          TeamMaterialMatrixService materialService,
                                          TeamMaterialEvidenceValidator evidenceValidator,
                                          ObjectMapper objectMapper) {
        this.authorization = authorization;
        this.leaseService = leaseService;
        this.tools = tools;
        this.documentService = documentService;
        this.spreadsheetService = spreadsheetService;
        this.materialService = materialService;
        this.evidenceValidator = evidenceValidator;
        this.objectMapper = objectMapper;
    }

    public boolean supports(String actionType) {
        return actionType != null && ACTION_TYPES.contains(actionType.toUpperCase(Locale.ROOT));
    }

    public String targetType(String actionType) {
        return switch (actionType.toUpperCase(Locale.ROOT)) {
            case CREATE_DOCUMENT, APPEND_DOCUMENT -> "COLLAB_DOCUMENT";
            case CREATE_TABLE, UPDATE_TABLE_ROWS -> "COLLAB_SPREADSHEET";
            default -> "MATERIAL_REQUIREMENT";
        };
    }

    /**
     * Proposal-time check: the target must exist in this team, the confirming
     * user must be able to perform the write, and the optimistic version and
     * `before` values always come from the database.
     */
    public Prepared prepare(TeamAgentScope scope, String actionType, Long targetId,
                            Map<String, Object> after) {
        String type = actionType.toUpperCase(Locale.ROOT);
        if (!supports(type)) {
            throw conflict("协作动作类型不在白名单内");
        }
        if (!authorization.requireTeam(scope.teamId(), scope.userId()).canWrite()) {
            throw conflict("团队当前为只读状态，无法执行协作资产动作");
        }
        return switch (type) {
            case CREATE_DOCUMENT -> {
                String title = required(after, "title", 120);
                String markdown = text(after, "contentMarkdown", MAX_APPEND_CHARS);
                Map<String, Object> safe = new LinkedHashMap<>();
                safe.put("title", title);
                safe.put("contentMarkdown", markdown);
                safe.put("visibility", visibility(after));
                yield new Prepared(targetType(type), null, null, Map.of(), safe, true);
            }
            case APPEND_DOCUMENT -> {
                Long id = requireTarget(targetId);
                TeamCollaborationAuthorization.AssetContext asset = authorization
                    .requireEditableAsset(scope.teamId(), "DOCUMENT", id, scope.userId());
                TeamDocumentService.DocumentView view =
                    documentService.get(scope.teamId(), id, scope.userId());
                String markdown = required(after, "appendMarkdown", MAX_APPEND_CHARS);
                Map<String, Object> before = new LinkedHashMap<>();
                before.put("version", view.version());
                before.put("title", view.title());
                yield new Prepared(targetType(type), id, asset.version(), before,
                    Map.of("appendMarkdown", markdown), true);
            }
            case CREATE_TABLE -> {
                String title = required(after, "title", 120);
                ArrayNode columns = array(after, "columns");
                ArrayNode rows = array(after, "rows");
                // Canonicalize now so the confirmation card shows exactly what
                // will be stored, and an invalid schema fails before anything
                // is written.
                TeamSpreadsheetSchema.validateAndCanonicalize(objectMapper, columns, rows);
                Map<String, Object> safe = new LinkedHashMap<>();
                safe.put("title", title);
                safe.put("columns", objectMapper.convertValue(columns, Object.class));
                safe.put("rows", objectMapper.convertValue(rows, Object.class));
                safe.put("visibility", visibility(after));
                yield new Prepared(targetType(type), null, null, Map.of(), safe, true);
            }
            case UPDATE_TABLE_ROWS -> {
                Long id = requireTarget(targetId);
                TeamCollaborationAuthorization.AssetContext asset = authorization
                    .requireEditableAsset(scope.teamId(), "SPREADSHEET", id, scope.userId());
                TeamSpreadsheetService.SheetView view =
                    spreadsheetService.get(scope.teamId(), id, scope.userId());
                Map<String, Object> safe = new LinkedHashMap<>();
                safe.put("updates", normalizeRowUpdates(view, after));
                yield new Prepared(targetType(type), id, asset.version(),
                    Map.of("version", view.version()), safe, true);
            }
            case CREATE_REQUIREMENT -> {
                String title = required(after, "title", 200);
                Map<String, Object> safe = new LinkedHashMap<>();
                safe.put("title", title);
                safe.put("description", optional(after, "description", 1000));
                safe.put("category", optional(after, "category", 64));
                safe.put("required", after.get("required") == null
                    ? Boolean.TRUE : Boolean.valueOf(String.valueOf(after.get("required"))));
                // The Agent can only register a suggestion; review state is
                // forced to NEEDS_REVIEW by the command itself.
                safe.put("sourceType", "AGENT_SUGGESTED");
                yield new Prepared(targetType(type), null, null, Map.of(), safe, true);
            }
            case LINK_EVIDENCE -> {
                Long id = requireTarget(targetId);
                TeamMaterialMatrixService.RequirementView requirement =
                    requirement(scope, id);
                String assetType = required(after, "assetType", 16).toUpperCase(Locale.ROOT);
                if (!Set.of("FILE", "DOCUMENT", "SPREADSHEET", "TASK").contains(assetType)) {
                    throw invalid("证据来源类型不合法");
                }
                Long assetId = requireTarget(longValue(after.get("assetId")));
                // Evidence must reference something the confirming user can read right now.
                evidenceValidator.validate(scope.teamId(), scope.userId(), assetType, assetId, null);
                Map<String, Object> safe = new LinkedHashMap<>();
                safe.put("assetType", assetType);
                safe.put("assetId", assetId);
                safe.put("evidenceExcerpt", optional(after, "evidenceExcerpt", 600));
                safe.put("matchStatus", "CANDIDATE");
                yield new Prepared(targetType(type), id, requirement.version(), Map.of(), safe, true);
            }
            case SET_REVIEW -> {
                Long id = requireTarget(targetId);
                TeamMaterialMatrixService.RequirementView requirement =
                    requirement(scope, id);
                String status = required(after, "status", 20).toUpperCase(Locale.ROOT);
                if (!REVIEW_SUGGESTIONS.contains(status)) {
                    throw conflict("Agent 只能提出待复核建议，不能判定材料已满足");
                }
                Map<String, Object> before = new LinkedHashMap<>();
                before.put("reviewStatus", requirement.reviewStatus());
                before.put("reviewVersion", requirement.reviewVersion());
                yield new Prepared(targetType(type), id, requirement.version(), before,
                    Map.of("status", status,
                        "note", optional(after, "note", 1000)), true);
            }
            default -> throw conflict("协作动作类型不在白名单内");
        };
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> execute(String actionType, Long teamId, Long actorUserId,
                                       Long targetId, Integer expectedTargetVersion,
                                       Map<String, Object> after, Long actionRowId) {
        return execute(actionType, teamId, actorUserId, targetId, expectedTargetVersion,
            after, actionRowId, null);
    }

    /**
     * Execute a confirmed action. `existingLeaseToken` is supplied by the
     * authenticated confirmation request and is only used to preserve a live
     * editor session already owned by the same user.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> execute(String actionType, Long teamId, Long actorUserId,
                                       Long targetId, Integer expectedTargetVersion,
                                       Map<String, Object> after, Long actionRowId,
                                       String existingLeaseToken) {
        String type = actionType.toUpperCase(Locale.ROOT);
        return switch (type) {
            case CREATE_DOCUMENT -> {
                TeamDocumentService.DocumentView created = documentService.create(teamId,
                    actorUserId, new TeamDocumentService.CreateRequest(
                        required(after, "title", 120),
                        text(after, "contentMarkdown", MAX_APPEND_CHARS),
                        visibility(after)));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("documentId", created.id());
                result.put("version", created.version());
                result.put("title", created.title());
                result.put("actionMessage", "已创建团队文档「" + created.title() + "」");
                yield result;
            }
            case APPEND_DOCUMENT -> {
                Long id = requireTarget(targetId);
                authorization.requireEditableAsset(teamId, "DOCUMENT", id, actorUserId);
                tools.assertNoOtherEditor("DOCUMENT", id, actorUserId);
                TeamDocumentService.DocumentView current = documentService.get(teamId, id, actorUserId);
                if (expectedTargetVersion != null
                    && !Objects.equals(expectedTargetVersion, current.version())) {
                    throw conflict("文档已被更新，追加动作已停止");
                }
                String merged = (current.contentMarkdown() == null ? ""
                    : current.contentMarkdown())
                    + "\n\n" + required(after, "appendMarkdown", MAX_APPEND_CHARS);
                TeamCollaborationLeaseService.AgentWriteLease lease = guardedLease(
                    teamId, "DOCUMENT", id, actorUserId, current.version(), existingLeaseToken);
                TeamDocumentService.DocumentView saved = documentService.update(teamId, id,
                    actorUserId, new TeamDocumentService.SaveRequest(null, merged, null,
                        current.version(), lease.leaseToken(), agentRequestId(actionRowId, "append"),
                        "AGENT", "Agent 追加章节"));
                releaseIfTemporary(teamId, "DOCUMENT", id, actorUserId, lease);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("documentId", id);
                result.put("version", saved.version());
                result.put("previousVersion", current.version());
                result.put("actionMessage", "已向「" + saved.title() + "」追加内容");
                yield result;
            }
            case CREATE_TABLE -> {
                TeamSpreadsheetService.SheetView created = spreadsheetService.create(teamId,
                    actorUserId, new TeamSpreadsheetService.CreateRequest(
                        required(after, "title", 120),
                        objectMapper.valueToTree(after.get("columns")),
                        objectMapper.valueToTree(after.get("rows")),
                        visibility(after)));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("spreadsheetId", created.id());
                result.put("version", created.version());
                result.put("title", created.title());
                result.put("actionMessage", "已创建团队表格「" + created.title() + "」");
                yield result;
            }
            case UPDATE_TABLE_ROWS -> {
                Long id = requireTarget(targetId);
                authorization.requireEditableAsset(teamId, "SPREADSHEET", id, actorUserId);
                tools.assertNoOtherEditor("SPREADSHEET", id, actorUserId);
                TeamSpreadsheetService.SheetView current =
                    spreadsheetService.get(teamId, id, actorUserId);
                if (expectedTargetVersion != null
                    && !Objects.equals(expectedTargetVersion, current.version())) {
                    throw conflict("表格已被更新，写入动作已停止");
                }
                List<Map<String, Object>> updates = readUpdates(after);
                ObjectNode columns = (ObjectNode) requireArray(current.columns(), "columns");
                ArrayNode rows = objectMapper.createArrayNode();
                Map<String, Map<String, Object>> cellsByRow = new LinkedHashMap<>();
                for (Map<String, Object> update : updates) {
                    cellsByRow.put(String.valueOf(update.get("rowId")),
                        asMap(update.get("cells")));
                }
                JsonNode existingRows = requireArray(current.rows(), "rows");
                for (JsonNode node : existingRows) {
                    if (!(node instanceof ObjectNode row)) continue;
                    String rowId = row.path("id").asText("");
                    Map<String, Object> patch = cellsByRow.remove(rowId);
                    if (patch != null && row.get("cells") instanceof ObjectNode cells) {
                        patch.forEach((key, value) -> {
                            if (cells.has(key)) cells.put(key, value == null ? "" : String.valueOf(value));
                        });
                    }
                    rows.add(row);
                }
                if (!cellsByRow.isEmpty()) {
                    throw conflict("表格行不存在或已被删除：" + String.join("、", cellsByRow.keySet()));
                }
                TeamCollaborationLeaseService.AgentWriteLease lease = guardedLease(
                    teamId, "SPREADSHEET", id, actorUserId, current.version(), existingLeaseToken);
                TeamSpreadsheetService.SheetView saved = spreadsheetService.update(teamId, id,
                    actorUserId, new TeamSpreadsheetService.SaveRequest(null, columns, rows,
                        null, current.version(), lease.leaseToken(),
                        agentRequestId(actionRowId, "rows"),
                        "AGENT", "Agent 更新表格行"));
                releaseIfTemporary(teamId, "SPREADSHEET", id, actorUserId, lease);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("spreadsheetId", id);
                result.put("version", saved.version());
                result.put("previousVersion", current.version());
                result.put("updatedRows", updates.size());
                result.put("actionMessage", "已更新表格中的 " + updates.size() + " 行");
                yield result;
            }
            case CREATE_REQUIREMENT -> {
                TeamMaterialMatrixService.RequirementView created = materialService
                    .createRequirement(teamId, actorUserId,
                        new TeamMaterialMatrixService.RequirementRequest(
                            required(after, "title", 200),
                            optional(after, "description", 2000),
                            optional(after, "category", 64),
                            after.get("required") == null ? Boolean.TRUE
                                : Boolean.valueOf(String.valueOf(after.get("required"))),
                            "MANUAL", null, null, null, null), true);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("requirementId", created.id());
                result.put("version", created.version());
                result.put("reviewStatus", created.reviewStatus());
                result.put("title", created.title());
                result.put("actionMessage", "已添加待复核的材料要求「" + created.title() + "」");
                yield result;
            }
            case LINK_EVIDENCE -> {
                Long id = requireTarget(targetId);
                TeamMaterialMatrixService.EvidenceView evidence = materialService.addEvidence(
                    teamId, id, actorUserId, new TeamMaterialMatrixService.EvidenceRequest(
                        required(after, "assetType", 16),
                        requireTarget(longValue(after.get("assetId"))),
                        null, optional(after, "evidenceExcerpt", 1000), "CANDIDATE"));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("requirementId", id);
                result.put("evidenceId", evidence.id());
                result.put("assetType", evidence.assetType());
                result.put("assetId", evidence.assetId());
                result.put("actionMessage", "已关联材料证据");
                yield result;
            }
            case SET_REVIEW -> {
                Long id = requireTarget(targetId);
                TeamMaterialMatrixService.RequirementView before =
                    materialService.requirementView(teamId, id, actorUserId);
                TeamMaterialMatrixService.RequirementView reviewed = materialService.review(
                    teamId, id, actorUserId, new TeamMaterialMatrixService.ReviewRequest(
                        required(after, "status", 20), optional(after, "note", 1000)));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("requirementId", id);
                result.put("reviewStatus", reviewed.reviewStatus());
                result.put("version", reviewed.version());
                result.put("previousReviewStatus", before.reviewStatus());
                result.put("previousReviewVersion", before.reviewVersion());
                result.put("actionMessage", "已将材料要求标记为待复核");
                yield result;
            }
            default -> throw conflict("协作动作类型不在白名单内");
        };
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> undo(String actionType, Long teamId, Long actorUserId,
                                    Long targetId, Map<String, Object> before,
                                    Map<String, Object> result, Long actionRowId) {
        return undo(actionType, teamId, actorUserId, targetId, before, result, actionRowId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> undo(String actionType, Long teamId, Long actorUserId,
                                    Long targetId, Map<String, Object> before,
                                    Map<String, Object> result, Long actionRowId,
                                    String existingLeaseToken) {
        String type = actionType.toUpperCase(Locale.ROOT);
        return switch (type) {
            case CREATE_DOCUMENT -> {
                Long id = requireTarget(longValue(result.get("documentId")));
                documentService.delete(teamId, id, actorUserId, null);
                yield Map.of("documentId", id, "actionMessage", "已撤销：删除该团队文档");
            }
            case APPEND_DOCUMENT -> {
                Long id = requireTarget(longValue(result.get("documentId")));
                Integer previous = intValue(result.get("previousVersion"));
                if (previous == null) throw conflict("缺少可恢复的历史版本");
                TeamDocumentService.DocumentView current = documentService.get(teamId, id, actorUserId);
                Integer producedVersion = intValue(result.get("version"));
                if (producedVersion != null && !Objects.equals(producedVersion, current.version())) {
                    throw conflict("文档在 Agent 追加之后又被编辑，撤销已停止");
                }
                TeamCollaborationLeaseService.AgentWriteLease lease = guardedLease(
                    teamId, "DOCUMENT", id, actorUserId, current.version(), existingLeaseToken);
                TeamDocumentService.DocumentView restored = documentService.restoreVersion(teamId,
                    id, previous, actorUserId, lease.leaseToken(),
                    agentRequestId(actionRowId, "undo"));
                releaseIfTemporary(teamId, "DOCUMENT", id, actorUserId, lease);
                yield Map.of("documentId", id, "version", restored.version(),
                    "actionMessage", "已恢复到 Agent 追加前的版本");
            }
            case CREATE_TABLE -> {
                Long id = requireTarget(longValue(result.get("spreadsheetId")));
                spreadsheetService.delete(teamId, id, actorUserId, null);
                yield Map.of("spreadsheetId", id, "actionMessage", "已撤销：删除该团队表格");
            }
            case UPDATE_TABLE_ROWS -> {
                Long id = requireTarget(longValue(result.get("spreadsheetId")));
                Integer previous = intValue(result.get("previousVersion"));
                if (previous == null) throw conflict("缺少可恢复的历史版本");
                TeamSpreadsheetService.SheetView current = spreadsheetService.get(teamId, id, actorUserId);
                Integer producedVersion = intValue(result.get("version"));
                if (producedVersion != null && !Objects.equals(producedVersion, current.version())) {
                    throw conflict("表格在 Agent 更新之后又被编辑，撤销已停止");
                }
                TeamCollaborationLeaseService.AgentWriteLease lease = guardedLease(
                    teamId, "SPREADSHEET", id, actorUserId, current.version(), existingLeaseToken);
                TeamSpreadsheetService.SheetView restored = spreadsheetService.restoreVersion(
                    teamId, id, previous, actorUserId, lease.leaseToken(),
                    agentRequestId(actionRowId, "undo"));
                releaseIfTemporary(teamId, "SPREADSHEET", id, actorUserId, lease);
                yield Map.of("spreadsheetId", id, "version", restored.version(),
                    "actionMessage", "已恢复到 Agent 更新前的版本");
            }
            case CREATE_REQUIREMENT -> {
                Long id = requireTarget(longValue(result.get("requirementId")));
                TeamMaterialMatrixService.RequirementView current =
                    materialService.requirementView(teamId, id, actorUserId);
                materialService.archiveRequirement(teamId, id, actorUserId, current.version());
                yield Map.of("requirementId", id, "actionMessage", "已撤销：归档该材料要求");
            }
            case LINK_EVIDENCE -> {
                Long id = requireTarget(longValue(result.get("evidenceId")));
                materialService.removeEvidence(teamId, id, actorUserId);
                yield Map.of("evidenceId", id, "actionMessage", "已撤销：移除该材料证据");
            }
            case SET_REVIEW -> {
                Long id = requireTarget(longValue(result.get("requirementId")));
                String previous = before.get("reviewStatus") == null
                    ? null : String.valueOf(before.get("reviewStatus"));
                if (previous == null) throw conflict("缺少可恢复的复核状态");
                TeamMaterialMatrixService.RequirementView restored =
                    materialService.restoreReviewStatus(teamId, id, actorUserId, previous,
                        "撤销 Agent 复核建议");
                yield Map.of("requirementId", id, "reviewStatus", restored.reviewStatus(),
                    "actionMessage", "已恢复到 Agent 建议前的复核状态");
            }
            default -> throw conflict("该协作动作不支持撤销");
        };
    }

    public boolean undoSupported(String actionType) {
        return supports(actionType);
    }

    // ==================== 内部 ====================

    /**
     * Temporary lease for Agent writes. When the confirming user already holds
     * a live lease it is kept (and not released); otherwise a short lease is
     * taken for this write only, which is what enforces "one editor per asset".
     */
    private TeamCollaborationLeaseService.AgentWriteLease guardedLease(
        Long teamId, String type, Long assetId, Long userId, int baseVersion,
        String existingLeaseToken) {
        return leaseService.acquireForAgentWrite(teamId, type, assetId, userId,
            baseVersion, existingLeaseToken);
    }

    private void releaseIfTemporary(Long teamId, String type, Long assetId, Long userId,
                                    TeamCollaborationLeaseService.AgentWriteLease lease) {
        if (lease.temporary()) {
            leaseService.release(teamId, type, assetId, userId, lease.leaseToken());
        }
    }

    private TeamMaterialMatrixService.RequirementView requirement(TeamAgentScope scope,
                                                                  Long requirementId) {
        return materialService.requirementView(scope.teamId(), requirementId, scope.userId());
    }

    private String agentRequestId(Long actionRowId, String suffix) {
        return "agent-" + actionRowId + "-" + suffix;
    }

    private List<Map<String, Object>> normalizeRowUpdates(TeamSpreadsheetService.SheetView view,
                                                           Map<String, Object> after) {
        Object raw = after.get("updates");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw invalid("updates 必须是非空数组");
        }
        if (list.size() > 200) throw invalid("单次最多更新 200 行");
        Set<String> keys = new java.util.HashSet<>();
        if (view.columns() != null && view.columns().isArray()) {
            view.columns().forEach(node -> keys.add(node.path("key").asText("")));
        }
        Set<String> rowIds = new java.util.HashSet<>();
        if (view.rows() != null && view.rows().isArray()) {
            view.rows().forEach(node -> rowIds.add(node.path("id").asText("")));
        }
        List<Map<String, Object>> updates = new ArrayList<>();
        int cells = 0;
        for (Object item : list) {
            Map<String, Object> row = asMap(item);
            String rowId = required(row, "rowId", 64);
            if (!rowIds.contains(rowId)) throw conflict("表格行不存在：" + rowId);
            Map<String, Object> cellPatch = asMap(row.get("cells"));
            if (cellPatch.isEmpty()) throw invalid("cells 不能为空");
            Map<String, Object> safeCells = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : cellPatch.entrySet()) {
                if (!keys.contains(entry.getKey())) {
                    throw conflict("表格列不存在：" + entry.getKey());
                }
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                if (value.length() > 4000) throw invalid("单元格内容过长");
                safeCells.put(entry.getKey(), value);
                cells++;
            }
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("rowId", rowId);
            safe.put("cells", safeCells);
            updates.add(safe);
        }
        if (cells > MAX_CELLS_PER_UPDATE) throw invalid("单次最多修改 200 个单元格");
        return updates;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readUpdates(Map<String, Object> after) {
        Object raw = after.get("updates");
        if (!(raw instanceof List<?> list)) throw invalid("updates 必须是非空数组");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) rows.add(asMap(item));
        return rows;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw invalid("对象字段格式不合法");
    }

    private JsonNode requireArray(JsonNode node, String label) {
        if (node == null || !node.isArray()) throw invalid(label + "必须是数组");
        return node;
    }

    private ArrayNode array(Map<String, Object> after, String field) {
        JsonNode node = objectMapper.valueToTree(after.get(field));
        if (node == null || node.isNull()) {
            // An empty table is a legitimate suggestion; canonical schema still
            // requires at least one column, so fall back to the default pair.
            ArrayNode columns = objectMapper.createArrayNode();
            ObjectNode first = columns.addObject();
            first.put("id", "col_task");
            first.put("key", "task");
            first.put("label", "任务");
            first.put("type", "TEXT");
            first.put("width", 220);
            return "columns".equals(field) ? columns : objectMapper.createArrayNode();
        }
        if (!node.isArray()) throw invalid(field + "必须是数组");
        return (ArrayNode) node;
    }

    private String visibility(Map<String, Object> after) {
        String value = optional(after, "visibility", 16);
        if (value == null) return "TEAM";
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!Set.of("TEAM", "RESTRICTED").contains(normalized)) {
            throw invalid("可见范围不合法");
        }
        return normalized;
    }

    private String required(Map<String, Object> values, String field, int max) {
        String value = optional(values, field, max);
        if (value == null || value.isBlank()) throw invalid(field + "不能为空");
        return value;
    }

    private String optional(Map<String, Object> values, String field, int max) {
        Object raw = values == null ? null : values.get(field);
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) return null;
        if (value.length() > max) throw invalid(field + "过长");
        return value;
    }

    private String text(Map<String, Object> values, String field, int max) {
        String value = optional(values, field, max);
        return value == null ? "" : value;
    }

    private Long requireTarget(Long targetId) {
        if (targetId == null || targetId <= 0) throw invalid("缺少动作目标");
        return targetId;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer intValue(Object value) {
        Long parsed = longValue(value);
        return parsed == null ? null : parsed.intValue();
    }

    private TeamCollabException invalid(String message) {
        return new TeamCollabException(TeamCollabException.Code.REQUEST_INVALID, message);
    }

    private TeamCollabException conflict(String message) {
        return new TeamCollabException(TeamCollabException.Code.AGENT_ACTION_CONFLICT, message);
    }

    public record Prepared(String targetType, Long targetId, Integer expectedTargetVersion,
                           Map<String, Object> before, Map<String, Object> after,
                           boolean undoSupported) {
    }
}
