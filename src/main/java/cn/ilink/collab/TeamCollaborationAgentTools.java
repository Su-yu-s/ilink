package cn.ilink.collab;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.TeamCollabDocument;
import cn.ilink.entity.TeamCollabSpreadsheet;
import cn.ilink.entity.TeamLibraryFile;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamLibraryFileMapper;
import cn.ilink.service.kg.KgDocumentChunkService;
import cn.ilink.service.kg.KgScopeService;
import cn.ilink.teamagent.TeamAgentScope;
import cn.ilink.teamagent.TeamAgentScopeContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only collaboration data for the team Agent. No parameter carries a
 * teamId or userId: the scope comes from the server-bound TeamAgentScopeContext
 * and every row is re-authorized, so a RESTRICTED asset stays absent from lists,
 * search hits, counts and citations for users without an explicit grant.
 */
@Service
public class TeamCollaborationAgentTools {
    static final Set<String> COLLAB_DOC_TYPES =
        Set.of("TEAM_DOCUMENT", "TEAM_SPREADSHEET", "TEAM_FILE");
    private static final int MAX_LISTED = 50;

    private final TeamCollaborationAuthorization authorization;
    private final TeamDocumentService documentService;
    private final TeamSpreadsheetService spreadsheetService;
    private final TeamMaterialMatrixService materialService;
    private final TeamCollaborationLeaseService leaseService;
    private final TeamCollabDocumentMapper documentMapper;
    private final TeamCollabSpreadsheetMapper spreadsheetMapper;
    private final TeamLibraryFileMapper libraryMapper;
    private final KgDocumentChunkService chunkService;
    private final KgScopeService scopeService;

    public TeamCollaborationAgentTools(TeamCollaborationAuthorization authorization,
                                       TeamDocumentService documentService,
                                       TeamSpreadsheetService spreadsheetService,
                                       TeamMaterialMatrixService materialService,
                                       TeamCollaborationLeaseService leaseService,
                                       TeamCollabDocumentMapper documentMapper,
                                       TeamCollabSpreadsheetMapper spreadsheetMapper,
                                       TeamLibraryFileMapper libraryMapper,
                                       KgDocumentChunkService chunkService,
                                       KgScopeService scopeService) {
        this.authorization = authorization;
        this.documentService = documentService;
        this.spreadsheetService = spreadsheetService;
        this.materialService = materialService;
        this.leaseService = leaseService;
        this.documentMapper = documentMapper;
        this.spreadsheetMapper = spreadsheetMapper;
        this.libraryMapper = libraryMapper;
        this.chunkService = chunkService;
        this.scopeService = scopeService;
    }

    public Map<String, Object> listAssets() {
        TeamAgentScope scope = currentScope();
        List<Map<String, Object>> assetRows = new ArrayList<>();
        for (TeamCollabDocument row : documentMapper.selectList(
                new LambdaQueryWrapper<TeamCollabDocument>()
                    .eq(TeamCollabDocument::getTeamId, scope.teamId())
                    .isNull(TeamCollabDocument::getDeletedAt)
                    .orderByDesc(TeamCollabDocument::getUpdatedAt)
                    .last("LIMIT " + MAX_LISTED))) {
            if (!canView(scope, "DOCUMENT", row.getId(), row.getVisibility(), row.getCreatorUserId())) {
                continue;
            }
            assetRows.add(assetRow("DOCUMENT", row.getId(), row.getTitle(), row.getVisibility(),
                row.getStatus(), row.getVersion(), row.getUpdatedAt()));
        }
        for (TeamCollabSpreadsheet row : spreadsheetMapper.selectList(
                new LambdaQueryWrapper<TeamCollabSpreadsheet>()
                    .eq(TeamCollabSpreadsheet::getTeamId, scope.teamId())
                    .isNull(TeamCollabSpreadsheet::getDeletedAt)
                    .orderByDesc(TeamCollabSpreadsheet::getUpdatedAt)
                    .last("LIMIT " + MAX_LISTED))) {
            if (!canView(scope, "SPREADSHEET", row.getId(), row.getVisibility(), row.getCreatorUserId())) {
                continue;
            }
            assetRows.add(assetRow("SPREADSHEET", row.getId(), row.getTitle(), row.getVisibility(),
                row.getStatus(), row.getVersion(), row.getUpdatedAt()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assets", assetRows);
        result.put("materialSummary", materialSummary(scope));
        result.put("note", "仅包含当前用户有权读取的协作资产");
        return result;
    }

    public Map<String, Object> getDocument(Long documentId, Integer versionLimit) {
        TeamAgentScope scope = currentScope();
        if (documentId == null || documentId <= 0) {
            throw new IllegalArgumentException("document_id 必须是正整数");
        }
        TeamDocumentService.DocumentView view =
            documentService.get(scope.teamId(), documentId, scope.userId());
        int limit = versionLimit == null ? 5 : Math.max(1, Math.min(versionLimit, 20));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", view.id());
        result.put("title", view.title());
        result.put("contentMarkdown", view.contentMarkdown());
        result.put("visibility", view.visibility());
        result.put("status", view.status());
        result.put("version", view.version());
        result.put("permission", view.permission());
        result.put("canEdit", view.canEdit());
        result.put("updatedAt", view.updatedAt());
        result.put("versions", documentService
            .versions(scope.teamId(), documentId, scope.userId(), limit, 0));
        return result;
    }

    public Map<String, Object> getSpreadsheet(Long spreadsheetId, Integer rowLimit) {
        TeamAgentScope scope = currentScope();
        if (spreadsheetId == null || spreadsheetId <= 0) {
            throw new IllegalArgumentException("spreadsheet_id 必须是正整数");
        }
        TeamSpreadsheetService.SheetView view =
            spreadsheetService.get(scope.teamId(), spreadsheetId, scope.userId());
        int limit = rowLimit == null ? 60 : Math.max(1, Math.min(rowLimit, 200));
        List<Object> rows = new ArrayList<>();
        if (view.rows() != null && view.rows().isArray()) {
            view.rows().forEach(node -> {
                if (rows.size() < limit) rows.add(node);
            });
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", view.id());
        result.put("title", view.title());
        result.put("visibility", view.visibility());
        result.put("status", view.status());
        result.put("version", view.version());
        result.put("permission", view.permission());
        result.put("canEdit", view.canEdit());
        result.put("columns", view.columns());
        result.put("rows", rows);
        result.put("rowLimit", limit);
        return result;
    }

    public Map<String, Object> search(String query, Integer topK) {
        TeamAgentScope scope = currentScope();
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        int limit = Math.max(1, Math.min(topK == null ? 8 : topK, 20));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KgDocumentChunk chunk : chunkService.retrieve(query, scope.userId(), limit * 3)) {
            if (rows.size() >= limit) break;
            String docType = chunk.getDocType() == null ? ""
                : chunk.getDocType().toUpperCase(Locale.ROOT);
            if (!COLLAB_DOC_TYPES.contains(docType)) continue;
            if (!scopeService.canSeeChunk(chunk, scope.userId())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkId", chunk.getId());
            row.put("docType", chunk.getDocType());
            row.put("docId", chunk.getDocId());
            row.put("title", chunk.getTitle());
            String text = chunk.getContent() == null ? "" : chunk.getContent();
            row.put("text", text.length() <= 800 ? text : text.substring(0, 800));
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", rows.size());
        result.put("chunks", rows);
        return result;
    }

    public Map<String, Object> materialMatrix(String status, Boolean required) {
        TeamAgentScope scope = currentScope();
        TeamMaterialMatrixService.MatrixView matrix =
            materialService.matrix(scope.teamId(), scope.userId(), status, required, null);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TeamMaterialMatrixService.RequirementView row : matrix.requirements()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("title", row.title());
            item.put("category", row.category());
            item.put("required", row.required());
            item.put("reviewStatus", row.reviewStatus());
            item.put("sourceType", row.sourceType());
            item.put("version", row.version());
            item.put("evidence", row.evidence().stream().map(evidence -> {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("id", evidence.id());
                cell.put("assetType", evidence.assetType());
                cell.put("assetId", evidence.assetId());
                cell.put("label", evidence.label());
                cell.put("matchStatus", evidence.matchStatus());
                cell.put("sourceDeleted", evidence.deleted());
                return cell;
            }).toList());
            rows.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requirements", rows);
        result.put("counts", matrix.counts());
        result.put("requiredTotal", matrix.requiredTotal());
        result.put("requiredMet", matrix.requiredMet());
        result.put("competition", matrix.competition());
        return result;
    }

    /** Library files the current Agent user may attach as material evidence. */
    public List<Map<String, Object>> visibleFiles(String keyword) {
        TeamAgentScope scope = currentScope();
        LambdaQueryWrapper<TeamLibraryFile> query = new LambdaQueryWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getTeamId, scope.teamId())
            .eq(TeamLibraryFile::getIsFolder, false)
            .eq(TeamLibraryFile::getIsDeleted, false)
            .eq(TeamLibraryFile::getIsPurged, false)
            .orderByDesc(TeamLibraryFile::getId)
            .last("LIMIT 30");
        if (keyword != null && !keyword.isBlank()) {
            String safe = keyword.trim().replace("%", "").replace("_", "");
            if (!safe.isEmpty()) query.like(TeamLibraryFile::getName, safe);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TeamLibraryFile file : libraryMapper.selectList(query)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", file.getId());
            row.put("name", file.getName());
            row.put("fileType", file.getFileType());
            row.put("indexStatus", file.getIndexStatus());
            rows.add(row);
        }
        return rows;
    }

    /**
     * Single-editor guard for Agent writes into an existing asset. The
     * confirming user may hold the lease; a live lease belonging to somebody
     * else always blocks the action.
     */
    public void assertNoOtherEditor(String assetType, Long assetId, Long userId) {
        var lease = leaseService.current(assetType, assetId);
        if (lease == null || lease.getExpiresAt() == null
            || !lease.getExpiresAt().after(new Date())) {
            return;
        }
        if (!Objects.equals(lease.getUserId(), userId)) {
            throw new TeamCollabException(TeamCollabException.Code.AGENT_ACTION_CONFLICT,
                "另一位成员正在编辑该资产，请等其退出编辑后重试");
        }
    }

    static TeamAgentScope currentScope() {
        TeamAgentScope scope = TeamAgentScopeContext.current();
        if (scope == null) {
            throw new IllegalStateException("协作资产工具只能在团队 Agent 作用域内调用");
        }
        return scope;
    }

    private Map<String, Object> materialSummary(TeamAgentScope scope) {
        try {
            TeamMaterialMatrixService.MatrixView matrix =
                materialService.matrix(scope.teamId(), scope.userId(), null, null, null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("total", matrix.counts().get("total"));
            row.put("met", matrix.counts().get("MET"));
            row.put("needsReview", matrix.counts().get("NEEDS_REVIEW"));
            row.put("missing", matrix.counts().get("MISSING"));
            row.put("requiredTotal", matrix.requiredTotal());
            row.put("requiredMet", matrix.requiredMet());
            return row;
        } catch (TeamCollabException e) {
            return Map.of("available", false);
        }
    }

    private boolean canView(TeamAgentScope scope, String type, Long assetId,
                            String visibility, Long creatorId) {
        return authorization.canViewAsset(type, assetId, scope.teamId(), scope.userId());
    }

    private Map<String, Object> assetRow(String type, Long id, String title, String visibility,
                                         String status, Integer version, Date updatedAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("assetType", type);
        row.put("id", id);
        row.put("title", title);
        row.put("visibility", visibility);
        row.put("status", status);
        row.put("version", version);
        row.put("updatedAt", updatedAt);
        return row;
    }
}
