package cn.ilink.collab;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.TeamCollabEvent;
import cn.ilink.entity.TeamCollabSpreadsheet;
import cn.ilink.entity.TeamCollabSpreadsheetVersion;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetVersionMapper;
import cn.ilink.service.UserService;
import cn.ilink.service.kg.KgIndexService;
import cn.ilink.util.DisplayNameResolver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Versioned structured team tables; no user formulas are executed. */
@Service
public class TeamSpreadsheetService {
    private static final int MAX_HISTORY = 200;
    private final TeamCollabSpreadsheetMapper spreadsheetMapper;
    private final TeamCollabSpreadsheetVersionMapper versionMapper;
    private final TeamCollaborationAuthorization authorization;
    private final TeamCollaborationLeaseService leaseService;
    private final TeamCollaborationEventService eventService;
    private final KgIndexService indexService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public TeamSpreadsheetService(TeamCollabSpreadsheetMapper spreadsheetMapper,
                                  TeamCollabSpreadsheetVersionMapper versionMapper,
                                  TeamCollaborationAuthorization authorization,
                                  TeamCollaborationLeaseService leaseService,
                                  TeamCollaborationEventService eventService,
                                  KgIndexService indexService,
                                  UserService userService,
                                  ObjectMapper objectMapper) {
        this.spreadsheetMapper = spreadsheetMapper;
        this.versionMapper = versionMapper;
        this.authorization = authorization;
        this.leaseService = leaseService;
        this.eventService = eventService;
        this.indexService = indexService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    public List<SheetView> list(Long teamId, Long userId) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireTeam(teamId, userId);
        return spreadsheetMapper.selectList(new LambdaQueryWrapper<TeamCollabSpreadsheet>()
                .eq(TeamCollabSpreadsheet::getTeamId, teamId)
                .isNull(TeamCollabSpreadsheet::getDeletedAt)
                .orderByDesc(TeamCollabSpreadsheet::getUpdatedAt))
            .stream().filter(row -> canView(team, row))
            .map(row -> viewForTeam(team, row)).toList();
    }

    public SheetView get(Long teamId, Long sheetId, Long userId) {
        TeamCollaborationAuthorization.AssetContext context = authorization
            .requireAsset(teamId, "SPREADSHEET", sheetId, userId,
                TeamCollaborationAuthorization.Permission.VIEW);
        return view(context, requireSheet(teamId, sheetId));
    }

    @Transactional(rollbackFor = Exception.class)
    public SheetView create(Long teamId, Long userId, CreateRequest request) {
        authorization.requireWritableTeam(teamId, userId);
        if (request == null) throw invalid("表格内容不能为空");
        JsonNode columns = request.columns();
        JsonNode rows = request.rows();
        if (columns == null) columns = defaultColumns();
        if (rows == null) rows = objectMapper.createArrayNode();
        TeamSpreadsheetSchema.Snapshot snapshot = TeamSpreadsheetSchema
            .validateAndCanonicalize(objectMapper, columns, rows);
        String title = title(request.title());
        String visibility = visibility(request.visibility());
        Date now = new Date();
        TeamCollabSpreadsheet row = new TeamCollabSpreadsheet();
        row.setTeamId(teamId);
        row.setTitle(title);
        row.setColumnsJson(snapshot.columnsJson());
        row.setRowsJson(snapshot.rowsJson());
        row.setVisibility(visibility);
        row.setStatus(TeamCollabSpreadsheet.STATUS_ACTIVE);
        row.setVersion(1);
        row.setContentHash(snapshot.contentHash());
        row.setCreatorUserId(userId);
        row.setLastEditorUserId(userId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        spreadsheetMapper.insert(row);
        persistVersion(row, "MANUAL", userId, null);
        eventService.append(teamId, "SPREADSHEET", row.getId(), userId,
            "SPREADSHEET_CREATED", "SUCCESS", 1, null,
            java.util.Map.of("title", title));
        enqueue(row);
        return view(authorization.requireAsset(teamId, "SPREADSHEET", row.getId(), userId,
            TeamCollaborationAuthorization.Permission.VIEW), row);
    }

    @Transactional(rollbackFor = Exception.class)
    public SheetView update(Long teamId, Long sheetId, Long userId, SaveRequest request) {
        TeamCollaborationAuthorization.AssetContext context = authorization
            .requireEditableAsset(teamId, "SPREADSHEET", sheetId, userId);
        if (request == null || request.baseVersion() == null) throw invalid("缺少基础版本");
        String requestId = requireRequestId(request.clientRequestId());
        TeamCollabSpreadsheet current = spreadsheetMapper.selectByIdForUpdate(sheetId);
        requireVisible(teamId, sheetId, current);
        TeamCollabEvent previous = eventService.findRequest(teamId, "SPREADSHEET",
            sheetId, requestId);
        if (previous != null) {
            requireSamePayload(previous, saveFingerprint(request));
            return view(context, current);
        }
        if (!Objects.equals(current.getVersion(), request.baseVersion())) {
            throw versionConflict();
        }
        leaseService.requireHeld(teamId, "SPREADSHEET", sheetId, userId, request.leaseToken());
        JsonNode columns = request.columns() == null
            ? readJson(current.getColumnsJson()) : request.columns();
        JsonNode rows = request.rows() == null
            ? readJson(current.getRowsJson()) : request.rows();
        TeamSpreadsheetSchema.Snapshot snapshot =
            TeamSpreadsheetSchema.validateAndCanonicalize(objectMapper, columns, rows);
        String title = request.title() == null ? current.getTitle() : title(request.title());
        String nextVisibility = request.visibility() == null
            ? current.getVisibility() : visibility(request.visibility());
        if (!Objects.equals(nextVisibility, current.getVisibility())
            && !context.canManage()) {
            throw new TeamCollabException(TeamCollabException.Code.ASSET_FORBIDDEN,
                "只有队长或资产创建者可以修改可见范围");
        }
        if (Objects.equals(title, current.getTitle())
            && Objects.equals(snapshot.contentHash(), current.getContentHash())
            && Objects.equals(nextVisibility, current.getVisibility())) {
            return view(context, current);
        }
        int nextVersion = current.getVersion() + 1;
        int changed = spreadsheetMapper.update(null,
            new LambdaUpdateWrapper<TeamCollabSpreadsheet>()
                .eq(TeamCollabSpreadsheet::getId, sheetId)
                .eq(TeamCollabSpreadsheet::getVersion, current.getVersion())
                .isNull(TeamCollabSpreadsheet::getDeletedAt)
                .set(TeamCollabSpreadsheet::getTitle, title)
                .set(TeamCollabSpreadsheet::getColumnsJson, snapshot.columnsJson())
                .set(TeamCollabSpreadsheet::getRowsJson, snapshot.rowsJson())
                .set(TeamCollabSpreadsheet::getContentHash, snapshot.contentHash())
                .set(TeamCollabSpreadsheet::getVisibility, nextVisibility)
                .set(TeamCollabSpreadsheet::getLastEditorUserId, userId)
                .set(TeamCollabSpreadsheet::getVersion, nextVersion)
                .set(TeamCollabSpreadsheet::getUpdatedAt, new Date()));
        if (changed != 1) throw versionConflict();
        TeamCollabSpreadsheet saved = requireSheet(teamId, sheetId);
        persistVersion(saved, request.saveKind() == null ? "AUTO" : request.saveKind(),
            userId, request.changeSummary());
        eventService.append(teamId, "SPREADSHEET", sheetId, userId,
            "SPREADSHEET_SAVED", "SUCCESS", saved.getVersion(), requestId,
            java.util.Map.of(
                "saveKind", request.saveKind() == null ? "AUTO" : request.saveKind(),
                "requestHash", saveFingerprint(request)));
        enqueue(saved);
        trimVersions(sheetId, saved.getVersion());
        return view(context, saved);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long teamId, Long sheetId, Long userId, Integer baseVersion) {
        TeamCollaborationAuthorization.AssetContext context = authorization
            .requireEditableAsset(teamId, "SPREADSHEET", sheetId, userId);
        authorization.requireManage(context);
        TeamCollabSpreadsheet row = spreadsheetMapper.selectByIdForUpdate(sheetId);
        requireVisible(teamId, sheetId, row);
        if (baseVersion != null && !Objects.equals(baseVersion, row.getVersion())) {
            throw versionConflict();
        }
        spreadsheetMapper.update(null, new LambdaUpdateWrapper<TeamCollabSpreadsheet>()
            .eq(TeamCollabSpreadsheet::getId, sheetId)
            .eq(TeamCollabSpreadsheet::getVersion, row.getVersion())
            .set(TeamCollabSpreadsheet::getDeletedAt, new Date())
            .set(TeamCollabSpreadsheet::getUpdatedAt, new Date())
            .setSql("version = version + 1"));
        leaseService.invalidateAsset("SPREADSHEET", sheetId);
        eventService.append(teamId, "SPREADSHEET", sheetId, userId,
            "SPREADSHEET_DELETED", "SUCCESS", row.getVersion() + 1, null,
            java.util.Map.of());
        indexService.enqueueDelete("TEAM_SPREADSHEET", sheetId, KgEntity.SCOPE_TEAM,
            teamId, userId);
    }

    public List<VersionView> versions(Long teamId, Long sheetId, Long userId,
                                      int limit, int offset) {
        authorization.requireAsset(teamId, "SPREADSHEET", sheetId, userId,
            TeamCollaborationAuthorization.Permission.VIEW);
        requireSheet(teamId, sheetId);
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 30 : limit, 100));
        int safeOffset = Math.max(0, offset);
        return versionMapper.selectList(new LambdaQueryWrapper<TeamCollabSpreadsheetVersion>()
                .eq(TeamCollabSpreadsheetVersion::getSpreadsheetId, sheetId)
                .orderByDesc(TeamCollabSpreadsheetVersion::getVersion)
                .last("LIMIT " + safeLimit + " OFFSET " + safeOffset))
            .stream().map(row -> new VersionView(row.getVersion(), row.getTitle(),
                row.getSaveKind(), row.getContentHash(), row.getChangeSummary(),
                row.getCreatedAt(), actorName(row.getActorUserId()))).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public SheetView restoreVersion(Long teamId, Long sheetId, Integer version,
                                    Long userId, String leaseToken, String requestId) {
        TeamCollaborationAuthorization.AssetContext context = authorization
            .requireEditableAsset(teamId, "SPREADSHEET", sheetId, userId);
        if (version == null) throw invalid("请选择要恢复的版本");
        TeamCollabSpreadsheet current = spreadsheetMapper.selectByIdForUpdate(sheetId);
        requireVisible(teamId, sheetId, current);
        leaseService.requireHeld(teamId, "SPREADSHEET", sheetId, userId, leaseToken);
        TeamCollabSpreadsheetVersion old = versionMapper.selectOne(
            new LambdaQueryWrapper<TeamCollabSpreadsheetVersion>()
                .eq(TeamCollabSpreadsheetVersion::getSpreadsheetId, sheetId)
                .eq(TeamCollabSpreadsheetVersion::getVersion, version)
                .last("LIMIT 1"));
        if (old == null) throw notFound();
        int nextVersion = current.getVersion() + 1;
        spreadsheetMapper.update(null, new LambdaUpdateWrapper<TeamCollabSpreadsheet>()
            .eq(TeamCollabSpreadsheet::getId, sheetId)
            .eq(TeamCollabSpreadsheet::getVersion, current.getVersion())
            .set(TeamCollabSpreadsheet::getTitle, old.getTitle())
            .set(TeamCollabSpreadsheet::getColumnsJson, old.getColumnsJson())
            .set(TeamCollabSpreadsheet::getRowsJson, old.getRowsJson())
            .set(TeamCollabSpreadsheet::getContentHash, old.getContentHash())
            .set(TeamCollabSpreadsheet::getLastEditorUserId, userId)
            .set(TeamCollabSpreadsheet::getVersion, nextVersion)
            .set(TeamCollabSpreadsheet::getUpdatedAt, new Date()));
        TeamCollabSpreadsheet saved = requireSheet(teamId, sheetId);
        persistVersion(saved, "RESTORE", userId, "恢复版本 " + version);
        eventService.append(teamId, "SPREADSHEET", sheetId, userId,
            "SPREADSHEET_VERSION_RESTORED", "SUCCESS", saved.getVersion(),
            requestId, java.util.Map.of("restoredVersion", version));
        enqueue(saved);
        return view(context, saved);
    }

    public Export exportCsv(Long teamId, Long sheetId, Long userId) {
        TeamCollabSpreadsheet row = requireExportable(teamId, sheetId, userId);
        try {
            TeamSpreadsheetSchema.Snapshot snapshot = snapshot(row);
            return new Export(TeamCollabMarkdown.exportFilename(row.getTitle(), ".csv"),
                "text/csv;charset=UTF-8",
                TeamSpreadsheetExporter.csv(snapshot.columns(), snapshot.rows()));
        } catch (Exception e) {
            throw new TeamCollabException(TeamCollabException.Code.EXPORT_FAILED,
                "表格导出失败");
        }
    }

    public Export exportXlsx(Long teamId, Long sheetId, Long userId) {
        TeamCollabSpreadsheet row = requireExportable(teamId, sheetId, userId);
        try {
            TeamSpreadsheetSchema.Snapshot snapshot = snapshot(row);
            return new Export(TeamCollabMarkdown.exportFilename(row.getTitle(), ".xlsx"),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                TeamSpreadsheetExporter.xlsx(row.getTitle(), snapshot.columns(), snapshot.rows()));
        } catch (Exception e) {
            throw new TeamCollabException(TeamCollabException.Code.EXPORT_FAILED,
                "表格导出失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public int deleteAllForTeam(Long teamId) {
        List<Long> ids = spreadsheetMapper.selectList(new LambdaQueryWrapper<TeamCollabSpreadsheet>()
                .eq(TeamCollabSpreadsheet::getTeamId, teamId)
                .select(TeamCollabSpreadsheet::getId))
            .stream().map(TeamCollabSpreadsheet::getId).toList();
        if (ids.isEmpty()) return 0;
        versionMapper.delete(new LambdaQueryWrapper<TeamCollabSpreadsheetVersion>()
            .eq(TeamCollabSpreadsheetVersion::getTeamId, teamId));
        return spreadsheetMapper.deleteBatchIds(ids);
    }

    private TeamCollabSpreadsheet requireExportable(Long teamId, Long sheetId, Long userId) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireTeam(teamId, userId);
        if (!team.canExport()) throw new TeamCollabException(
            TeamCollabException.Code.TEAM_READ_ONLY, "当前团队状态不允许导出表格");
        authorization.requireAsset(teamId, "SPREADSHEET", sheetId, userId,
            TeamCollaborationAuthorization.Permission.VIEW);
        return requireSheet(teamId, sheetId);
    }

    private boolean canView(TeamCollaborationAuthorization.TeamContext team,
                            TeamCollabSpreadsheet row) {
        return authorization.acl(team, "SPREADSHEET", row.getId(), row.getVisibility(),
            row.getCreatorUserId()).permission() != null;
    }

    private TeamCollabSpreadsheet requireSheet(Long teamId, Long id) {
        TeamCollabSpreadsheet row = spreadsheetMapper.selectById(id);
        if (row == null || !Objects.equals(row.getTeamId(), teamId)
            || row.getDeletedAt() != null) throw notFound();
        return row;
    }

    private void requireVisible(Long teamId, Long id, TeamCollabSpreadsheet row) {
        if (row == null || !Objects.equals(row.getTeamId(), teamId)
            || row.getDeletedAt() != null) throw notFound();
    }

    private SheetView view(TeamCollaborationAuthorization.AssetContext context,
                           TeamCollabSpreadsheet row) {
        return view(row, context.permission().name(), context.canEdit(),
            context.canManage(), activeLease(row.getId()));
    }

    private SheetView viewForTeam(TeamCollaborationAuthorization.TeamContext team,
                                  TeamCollabSpreadsheet row) {
        TeamCollaborationAuthorization.Acl acl = authorization.acl(team, "SPREADSHEET",
            row.getId(), row.getVisibility(), row.getCreatorUserId());
        return view(row, acl.permission().name(),
            team.canWrite() && acl.permission() != TeamCollaborationAuthorization.Permission.VIEW,
            acl.canManage(), activeLease(row.getId()));
    }

    private SheetView view(TeamCollabSpreadsheet row, String permission, boolean canEdit,
                           boolean canManage, Object lease) {
        try {
            return new SheetView(row.getId(), row.getTitle(),
                objectMapper.readTree(row.getColumnsJson()),
                objectMapper.readTree(row.getRowsJson()), row.getVisibility(),
                row.getStatus(), row.getVersion(), row.getContentHash(),
                actorName(row.getCreatorUserId()), actorName(row.getLastEditorUserId()),
                row.getCreatedAt(), row.getUpdatedAt(), permission, canEdit, canManage, lease);
        } catch (Exception e) {
            throw new TeamCollabException(TeamCollabException.Code.SPREADSHEET_SCHEMA_INVALID,
                "表格数据无法读取");
        }
    }

    private Object activeLease(Long id) {
        var lease = leaseService.current("SPREADSHEET", id);
        if (lease == null || lease.getExpiresAt() == null
            || !lease.getExpiresAt().after(new Date())) return null;
        return java.util.Map.of("userId", lease.getUserId(),
            "displayName", actorName(lease.getUserId()),
            "expiresAt", lease.getExpiresAt().toInstant().toString(),
            "baseVersion", lease.getBaseVersion());
    }

    private TeamSpreadsheetSchema.Snapshot snapshot(TeamCollabSpreadsheet row) throws Exception {
        return TeamSpreadsheetSchema.validateAndCanonicalize(objectMapper,
            objectMapper.readTree(row.getColumnsJson()), objectMapper.readTree(row.getRowsJson()));
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new TeamCollabException(TeamCollabException.Code.SPREADSHEET_SCHEMA_INVALID,
                "表格数据无法读取");
        }
    }

    private void persistVersion(TeamCollabSpreadsheet row, String saveKind,
                                Long actor, String summary) {
        TeamCollabSpreadsheetVersion version = new TeamCollabSpreadsheetVersion();
        version.setSpreadsheetId(row.getId());
        version.setTeamId(row.getTeamId());
        version.setVersion(row.getVersion());
        version.setTitle(row.getTitle());
        version.setColumnsJson(row.getColumnsJson());
        version.setRowsJson(row.getRowsJson());
        version.setContentHash(row.getContentHash());
        version.setSaveKind(saveKind);
        version.setActorUserId(actor);
        version.setChangeSummary(summary == null ? null
            : summary.substring(0, Math.min(500, summary.length())));
        version.setCreatedAt(new Date());
        versionMapper.insert(version);
    }

    private void trimVersions(Long sheetId, int latestVersion) {
        long count = versionMapper.selectCount(new LambdaQueryWrapper<TeamCollabSpreadsheetVersion>()
            .eq(TeamCollabSpreadsheetVersion::getSpreadsheetId, sheetId));
        if (count <= MAX_HISTORY) return;
        List<TeamCollabSpreadsheetVersion> old =
            versionMapper.selectList(new LambdaQueryWrapper<TeamCollabSpreadsheetVersion>()
                .eq(TeamCollabSpreadsheetVersion::getSpreadsheetId, sheetId)
                .eq(TeamCollabSpreadsheetVersion::getSaveKind, "AUTO")
                .lt(TeamCollabSpreadsheetVersion::getVersion, latestVersion - 20)
                .orderByAsc(TeamCollabSpreadsheetVersion::getVersion)
                .last("LIMIT " + (count - MAX_HISTORY)));
        old.forEach(row -> versionMapper.deleteById(row.getId()));
    }

    private void enqueue(TeamCollabSpreadsheet row) {
        StringBuilder content = new StringBuilder("# ").append(row.getTitle()).append("\n\n");
        try {
            TeamSpreadsheetSchema.Snapshot snapshot = snapshot(row);
            for (TeamSpreadsheetSchema.Row dataRow : snapshot.rows()) {
                for (TeamSpreadsheetSchema.Column column : snapshot.columns()) {
                    String value = dataRow.cells().get(column.key());
                    if (value != null && !value.isBlank()) {
                        content.append(column.label()).append(": ").append(value).append('\n');
                    }
                }
            }
        } catch (Exception e) {
            content.append(row.getRowsJson());
        }
        indexService.enqueueUpsert("TEAM_SPREADSHEET", row.getId(), row.getTitle(),
            content.toString(), KgEntity.SCOPE_TEAM, row.getTeamId(),
            row.getLastEditorUserId());
    }

    private String actorName(Long userId) {
        if (userId == null) return "已注销用户";
        User user = userService.getById(userId);
        return user == null ? "已注销用户" : DisplayNameResolver.resolve(user);
    }

    private JsonNode defaultColumns() {
        var root = objectMapper.createObjectNode();
        var columns = root.putArray("columns");
        var first = columns.addObject();
        first.put("id", "col_task");
        first.put("key", "task");
        first.put("label", "任务");
        first.put("type", "TEXT");
        first.put("width", 220);
        var second = columns.addObject();
        second.put("id", "col_owner");
        second.put("key", "owner");
        second.put("label", "负责人");
        second.put("type", "TEXT");
        second.put("width", 160);
        return root.get("columns");
    }

    private String title(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw invalid("表格标题不能为空");
        if (value.length() > 120) throw invalid("表格标题不能超过 120 字");
        return value;
    }

    private String visibility(String raw) {
        String value = raw == null ? TeamCollabSpreadsheet.VISIBILITY_TEAM
            : raw.trim().toUpperCase(Locale.ROOT);
        if (!TeamCollabSpreadsheet.VISIBILITY_TEAM.equals(value)
            && !TeamCollabSpreadsheet.VISIBILITY_RESTRICTED.equals(value)) {
            throw invalid("表格可见范围不合法");
        }
        return value;
    }

    private String requireRequestId(String raw) {
        if (raw == null || raw.isBlank()) throw invalid("缺少客户端请求 ID");
        String value = raw.trim();
        if (value.length() > 64 || !value.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw invalid("客户端请求 ID 格式不合法");
        }
        return value;
    }

    private String saveFingerprint(SaveRequest request) {
        String title = request.title() == null ? "" : request.title().trim();
        String visibility = request.visibility() == null ? ""
            : request.visibility().trim().toUpperCase(Locale.ROOT);
        String columns = canonical(request.columns());
        String rows = canonical(request.rows());
        return sha256(title + "\u0000" + visibility + "\u0000" + columns
            + "\u0000" + rows + "\u0000" + request.baseVersion());
    }

    private String canonical(JsonNode node) {
        if (node == null) return "";
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw invalid("表格保存内容无法校验");
        }
    }

    private void requireSamePayload(TeamCollabEvent previous, String fingerprint) {
        String recorded = null;
        try {
            recorded = previous.getMetadataJson() == null ? null
                : objectMapper.readTree(previous.getMetadataJson())
                    .path("requestHash").asText(null);
        } catch (Exception ignored) {
            // A malformed historical event cannot prove a replay is safe.
        }
        if (recorded == null || !MessageDigest.isEqual(
            recorded.getBytes(StandardCharsets.UTF_8),
            fingerprint.getBytes(StandardCharsets.UTF_8))) {
            throw new TeamCollabException(TeamCollabException.Code.REQUEST_ID_REUSED,
                "同一请求 ID 不能用于不同内容");
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw invalid("无法校验表格保存请求");
        }
    }

    private TeamCollabException invalid(String message) {
        return new TeamCollabException(TeamCollabException.Code.REQUEST_INVALID, message);
    }

    private TeamCollabException notFound() {
        return new TeamCollabException(TeamCollabException.Code.ASSET_NOT_FOUND,
            "表格不存在");
    }

    private TeamCollabException versionConflict() {
        return new TeamCollabException(TeamCollabException.Code.EDIT_VERSION_CONFLICT,
            "表格已被更新，请刷新后继续编辑");
    }

    public record CreateRequest(String title, JsonNode columns, JsonNode rows, String visibility) {}
    public record SaveRequest(String title, JsonNode columns, JsonNode rows,
                              String visibility, Integer baseVersion, String leaseToken,
                              String clientRequestId, String saveKind,
                              String changeSummary) {}
    public record Export(String filename, String contentType, byte[] bytes) {}
    public record SheetView(Long id, String title, JsonNode columns, JsonNode rows,
                            String visibility, String status, Integer version,
                            String contentHash, String creatorName, String lastEditorName,
                            Date createdAt, Date updatedAt, String permission,
                            boolean canEdit, boolean canManage, Object activeLease) {}
    public record VersionView(Integer version, String title, String saveKind,
                              String contentHash, String changeSummary,
                              Date createdAt, String actorName) {}
}
