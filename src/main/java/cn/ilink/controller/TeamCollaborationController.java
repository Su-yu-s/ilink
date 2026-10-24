package cn.ilink.controller;

import cn.ilink.collab.TeamCollabException;
import cn.ilink.collab.TeamCollaborationAclService;
import cn.ilink.collab.TeamCollaborationAuthorization;
import cn.ilink.collab.TeamCollaborationLeaseService;
import cn.ilink.collab.TeamCollaborationTrashService;
import cn.ilink.collab.TeamDocumentService;
import cn.ilink.collab.TeamMaterialMatrixService;
import cn.ilink.collab.TeamSpreadsheetService;
import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Team collaboration assets REST API. Session user is the only identity source;
 * roles, asset visibility and team state are rebuilt in the service layer for
 * every call, so no request body can assert extra permission.
 */
@RestController
@RequestMapping("/api/team/{teamId}")
public class TeamCollaborationController {

    private final TeamCollaborationAuthorization authorization;
    private final TeamCollaborationLeaseService leaseService;
    private final TeamCollaborationAclService aclService;
    private final TeamDocumentService documentService;
    private final TeamSpreadsheetService spreadsheetService;
    private final TeamMaterialMatrixService materialService;
    private final TeamCollaborationTrashService trashService;
    private final ObjectMapper objectMapper;
    private final cn.ilink.service.UserService userService;

    public TeamCollaborationController(TeamCollaborationAuthorization authorization,
                                       TeamCollaborationLeaseService leaseService,
                                       TeamCollaborationAclService aclService,
                                       TeamDocumentService documentService,
                                       TeamSpreadsheetService spreadsheetService,
                                       TeamMaterialMatrixService materialService,
                                       TeamCollaborationTrashService trashService,
                                       ObjectMapper objectMapper,
                                       cn.ilink.service.UserService userService) {
        this.authorization = authorization;
        this.leaseService = leaseService;
        this.aclService = aclService;
        this.documentService = documentService;
        this.spreadsheetService = spreadsheetService;
        this.materialService = materialService;
        this.trashService = trashService;
        this.objectMapper = objectMapper;
        this.userService = userService;
    }

    // ==================== 公共 ====================

    @GetMapping("/collaboration/bootstrap")
    public ResponseEntity<Result<?>> bootstrap(@PathVariable Long teamId, HttpSession session) {
        Long userId = requireUser(session).getId();
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireTeam(teamId, userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teamId", teamId);
        data.put("teamStatus", team.team().getStatus());
        data.put("role", team.role().name());
        data.put("canWrite", team.canWrite());
        data.put("canExport", team.canExport());
        data.put("readOnlyReason", team.canWrite() ? null
            : "团队当前为只读状态，确认组队后才能编辑协作资产");
        data.put("documents", documentService.list(teamId, userId));
        data.put("spreadsheets", spreadsheetService.list(teamId, userId));
        return privateOk(data);
    }

    // ==================== 回收站 ====================

    @GetMapping("/collaboration/trash")
    public ResponseEntity<Result<?>> trash(@PathVariable Long teamId, HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(trashService.list(teamId, userId));
    }

    @PostMapping("/collaboration/trash/{assetType}/{assetId}/restore")
    public ResponseEntity<Result<?>> restoreFromTrash(@PathVariable Long teamId,
                                                      @PathVariable String assetType,
                                                      @PathVariable Long assetId,
                                                      @RequestBody(required = false)
                                                          Map<String, Object> body,
                                                      HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateResponse(HttpStatus.OK, "已恢复到协作资产",
            trashService.restore(teamId, assetType, assetId, userId,
                intOf(body, "version")));
    }

    @DeleteMapping("/collaboration/trash/{assetType}/{assetId}")
    public ResponseEntity<Result<?>> purgeFromTrash(@PathVariable Long teamId,
                                                    @PathVariable String assetType,
                                                    @PathVariable Long assetId,
                                                    @RequestParam(required = false)
                                                        Integer version,
                                                    HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(Map.of("purged",
            trashService.purge(teamId, assetType, assetId, userId, version)));
    }

    // ==================== 文档 ====================

    @GetMapping("/documents")
    public ResponseEntity<Result<?>> documents(@PathVariable Long teamId, HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(documentService.list(teamId, userId));
    }

    @PostMapping("/documents")
    public ResponseEntity<Result<?>> createDocument(@PathVariable Long teamId,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpSession session) {
        Long userId = requireUser(session).getId();
        TeamDocumentService.DocumentView view = documentService.create(teamId, userId,
            new TeamDocumentService.CreateRequest(str(body, "title"),
                str(body, "contentMarkdown"), str(body, "visibility")));
        return privateResponse(HttpStatus.CREATED, "文档已创建", view);
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<Result<?>> document(@PathVariable Long teamId,
                                              @PathVariable Long documentId,
                                              HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(documentService.get(teamId, documentId, userId));
    }

    @PutMapping("/documents/{documentId}")
    public ResponseEntity<Result<?>> saveDocument(@PathVariable Long teamId,
                                                  @PathVariable Long documentId,
                                                  @RequestBody Map<String, Object> body,
                                                  HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(documentService.update(teamId, documentId, userId,
            new TeamDocumentService.SaveRequest(str(body, "title"),
                str(body, "contentMarkdown"), str(body, "visibility"),
                intOf(body, "baseVersion"), str(body, "leaseToken"),
                str(body, "clientRequestId"), str(body, "saveKind"),
                str(body, "changeSummary"))));
    }

    @PutMapping("/documents/{documentId}/status")
    public ResponseEntity<Result<?>> archiveDocument(@PathVariable Long teamId,
                                                     @PathVariable Long documentId,
                                                     @RequestBody Map<String, Object> body,
                                                     HttpSession session) {
        Long userId = requireUser(session).getId();
        boolean archived = Boolean.parseBoolean(String.valueOf(
            body == null ? null : body.getOrDefault("archived", "true")));
        documentService.archive(teamId, documentId, userId, archived);
        return privateOk(Map.of("archived", archived));
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Result<?>> deleteDocument(@PathVariable Long teamId,
                                                    @PathVariable Long documentId,
                                                    @RequestParam(required = false) Integer version,
                                                    HttpSession session) {
        Long userId = requireUser(session).getId();
        documentService.delete(teamId, documentId, userId, version);
        return privateOk(Map.of("deleted", true));
    }

    @GetMapping("/documents/{documentId}/versions")
    public ResponseEntity<Result<?>> documentVersions(@PathVariable Long teamId,
                                                      @PathVariable Long documentId,
                                                      @RequestParam(defaultValue = "30") int limit,
                                                      @RequestParam(defaultValue = "0") int offset,
                                                      HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(documentService.versions(teamId, documentId, userId, limit, offset));
    }

    @PostMapping("/documents/{documentId}/versions/{version}/restore")
    public ResponseEntity<Result<?>> restoreDocument(@PathVariable Long teamId,
                                                     @PathVariable Long documentId,
                                                     @PathVariable Integer version,
                                                     @RequestBody Map<String, Object> body,
                                                     HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(documentService.restoreVersion(teamId, documentId, version, userId,
            str(body, "leaseToken"), str(body, "clientRequestId")));
    }

    @GetMapping("/documents/{documentId}/export.md")
    public ResponseEntity<byte[]> exportDocument(@PathVariable Long teamId,
                                                 @PathVariable Long documentId,
                                                 HttpSession session) {
        Long userId = requireUser(session).getId();
        TeamDocumentService.Export export = documentService.export(teamId, documentId, userId);
        return download(export.filename(),
            MediaType.parseMediaType("text/markdown;charset=UTF-8"), export.content());
    }

    // ==================== 表格 ====================

    @GetMapping("/spreadsheets")
    public ResponseEntity<Result<?>> spreadsheets(@PathVariable Long teamId, HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(spreadsheetService.list(teamId, userId));
    }

    @PostMapping("/spreadsheets")
    public ResponseEntity<Result<?>> createSpreadsheet(@PathVariable Long teamId,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpSession session) {
        Long userId = requireUser(session).getId();
        TeamSpreadsheetService.SheetView view = spreadsheetService.create(teamId, userId,
            new TeamSpreadsheetService.CreateRequest(str(body, "title"),
                json(body, "columns"), json(body, "rows"), str(body, "visibility")));
        return privateResponse(HttpStatus.CREATED, "表格已创建", view);
    }

    @GetMapping("/spreadsheets/{spreadsheetId}")
    public ResponseEntity<Result<?>> spreadsheet(@PathVariable Long teamId,
                                                 @PathVariable Long spreadsheetId,
                                                 HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(spreadsheetService.get(teamId, spreadsheetId, userId));
    }

    @PutMapping("/spreadsheets/{spreadsheetId}")
    public ResponseEntity<Result<?>> saveSpreadsheet(@PathVariable Long teamId,
                                                     @PathVariable Long spreadsheetId,
                                                     @RequestBody Map<String, Object> body,
                                                     HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(spreadsheetService.update(teamId, spreadsheetId, userId,
            new TeamSpreadsheetService.SaveRequest(str(body, "title"),
                json(body, "columns"), json(body, "rows"), str(body, "visibility"),
                intOf(body, "baseVersion"),
                str(body, "leaseToken"), str(body, "clientRequestId"),
                str(body, "saveKind"), str(body, "changeSummary"))));
    }

    @DeleteMapping("/spreadsheets/{spreadsheetId}")
    public ResponseEntity<Result<?>> deleteSpreadsheet(@PathVariable Long teamId,
                                                       @PathVariable Long spreadsheetId,
                                                       @RequestParam(required = false) Integer version,
                                                       HttpSession session) {
        Long userId = requireUser(session).getId();
        spreadsheetService.delete(teamId, spreadsheetId, userId, version);
        return privateOk(Map.of("deleted", true));
    }

    @GetMapping("/spreadsheets/{spreadsheetId}/versions")
    public ResponseEntity<Result<?>> spreadsheetVersions(@PathVariable Long teamId,
                                                         @PathVariable Long spreadsheetId,
                                                         @RequestParam(defaultValue = "30") int limit,
                                                         @RequestParam(defaultValue = "0") int offset,
                                                         HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(spreadsheetService.versions(teamId, spreadsheetId, userId, limit, offset));
    }

    @PostMapping("/spreadsheets/{spreadsheetId}/versions/{version}/restore")
    public ResponseEntity<Result<?>> restoreSpreadsheet(@PathVariable Long teamId,
                                                        @PathVariable Long spreadsheetId,
                                                        @PathVariable Integer version,
                                                        @RequestBody Map<String, Object> body,
                                                        HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(spreadsheetService.restoreVersion(teamId, spreadsheetId, version, userId,
            str(body, "leaseToken"), str(body, "clientRequestId")));
    }

    @GetMapping("/spreadsheets/{spreadsheetId}/export.csv")
    public ResponseEntity<byte[]> exportCsv(@PathVariable Long teamId,
                                            @PathVariable Long spreadsheetId,
                                            HttpSession session) {
        Long userId = requireUser(session).getId();
        TeamSpreadsheetService.Export export =
            spreadsheetService.exportCsv(teamId, spreadsheetId, userId);
        return download(export.filename(),
            MediaType.parseMediaType("text/csv;charset=UTF-8"), export.bytes());
    }

    @GetMapping("/spreadsheets/{spreadsheetId}/export.xlsx")
    public ResponseEntity<byte[]> exportXlsx(@PathVariable Long teamId,
                                             @PathVariable Long spreadsheetId,
                                             HttpSession session) {
        Long userId = requireUser(session).getId();
        TeamSpreadsheetService.Export export =
            spreadsheetService.exportXlsx(teamId, spreadsheetId, userId);
        return download(export.filename(), MediaType.parseMediaType(export.contentType()),
            export.bytes());
    }

    // ==================== 租约与授权 ====================

    @PostMapping("/collaboration/{assetType}/{assetId}/lease")
    public ResponseEntity<Result<?>> acquireLease(@PathVariable Long teamId,
                                                  @PathVariable String assetType,
                                                  @PathVariable Long assetId,
                                                  HttpSession session) {
        Long userId = requireUser(session).getId();
        String type = authorization.assetType(assetType);
        TeamCollaborationAuthorization.AssetContext asset = authorization.requireEditableAsset(
            teamId, type, assetId, userId);
        try {
            TeamCollaborationLeaseService.LeaseView lease = leaseService.acquire(teamId, type,
                assetId, userId, asset.version());
            return privateResponse(HttpStatus.CREATED, "已进入编辑", lease);
        } catch (TeamCollabException e) {
            if (e.code() == TeamCollabException.Code.EDIT_LEASE_HELD) {
                return privateResponse(HttpStatus.CONFLICT, e.getMessage(),
                    Map.of("activeLease", leaseInfo(type, assetId),
                        "readOnly", true), e.code().name());
            }
            throw e;
        }
    }

    @PutMapping("/collaboration/{assetType}/{assetId}/lease")
    public ResponseEntity<Result<?>> renewLease(@PathVariable Long teamId,
                                                @PathVariable String assetType,
                                                @PathVariable Long assetId,
                                                @RequestBody Map<String, Object> body,
                                                HttpSession session) {
        Long userId = requireUser(session).getId();
        String type = authorization.assetType(assetType);
        authorization.requireEditableAsset(teamId, type, assetId, userId);
        Integer baseVersion = intOf(body, "baseVersion");
        return privateOk(leaseService.renew(teamId, type, assetId, userId,
            str(body, "leaseToken"), baseVersion == null ? 0 : baseVersion));
    }

    @DeleteMapping("/collaboration/{assetType}/{assetId}/lease")
    public ResponseEntity<Result<?>> releaseLease(@PathVariable Long teamId,
                                                  @PathVariable String assetType,
                                                  @PathVariable Long assetId,
                                                  @RequestBody(required = false) Map<String, Object> body,
                                                  HttpSession session) {
        Long userId = requireUser(session).getId();
        String type = authorization.assetType(assetType);
        authorization.requireAsset(teamId, type, assetId, userId,
            TeamCollaborationAuthorization.Permission.VIEW);
        leaseService.release(teamId, type, assetId, userId, str(body, "leaseToken"));
        return privateOk(Map.of("released", true));
    }

    @GetMapping("/collaboration/{assetType}/{assetId}/acl")
    public ResponseEntity<Result<?>> listAcl(@PathVariable Long teamId,
                                             @PathVariable String assetType,
                                             @PathVariable Long assetId,
                                             HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(aclService.list(teamId, assetType, assetId, userId));
    }

    @PostMapping("/collaboration/{assetType}/{assetId}/acl")
    public ResponseEntity<Result<?>> grantAcl(@PathVariable Long teamId,
                                              @PathVariable String assetType,
                                              @PathVariable Long assetId,
                                              @RequestBody Map<String, Object> body,
                                              HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateResponse(HttpStatus.CREATED, "授权已生效",
            aclService.grant(teamId, assetType, assetId, userId,
                longOf(body, "userId"), str(body, "permission")));
    }

    @DeleteMapping("/collaboration/{assetType}/{assetId}/acl/{userId}")
    public ResponseEntity<Result<?>> revokeAcl(@PathVariable Long teamId,
                                               @PathVariable String assetType,
                                               @PathVariable Long assetId,
                                               @PathVariable Long userId,
                                               HttpSession session) {
        Long actorId = requireUser(session).getId();
        return privateOk(Map.of("revoked",
            aclService.revoke(teamId, assetType, assetId, actorId, userId) > 0));
    }

    // ==================== 材料对照 ====================

    @GetMapping("/materials/matrix")
    public ResponseEntity<Result<?>> matrix(@PathVariable Long teamId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Boolean required,
                                            @RequestParam(required = false) String category,
                                            HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(materialService.matrix(teamId, userId, status, required, category));
    }

    @PostMapping("/materials/requirements")
    public ResponseEntity<Result<?>> createRequirement(@PathVariable Long teamId,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateResponse(HttpStatus.CREATED, "材料要求已添加",
            materialService.createRequirement(teamId, userId, requirementRequest(body)));
    }

    @PutMapping("/materials/requirements/{requirementId}")
    public ResponseEntity<Result<?>> updateRequirement(@PathVariable Long teamId,
                                                       @PathVariable Long requirementId,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(materialService.updateRequirement(teamId, requirementId, userId,
            requirementRequest(body)));
    }

    @DeleteMapping("/materials/requirements/{requirementId}")
    public ResponseEntity<Result<?>> archiveRequirement(@PathVariable Long teamId,
                                                        @PathVariable Long requirementId,
                                                        @RequestParam int version,
                                                        HttpSession session) {
        Long userId = requireUser(session).getId();
        materialService.archiveRequirement(teamId, requirementId, userId, version);
        return privateOk(Map.of("archived", true));
    }

    @PostMapping("/materials/requirements/{requirementId}/evidence")
    public ResponseEntity<Result<?>> addEvidence(@PathVariable Long teamId,
                                                 @PathVariable Long requirementId,
                                                 @RequestBody Map<String, Object> body,
                                                 HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateResponse(HttpStatus.CREATED, "证据已关联",
            materialService.addEvidence(teamId, requirementId, userId,
                new TeamMaterialMatrixService.EvidenceRequest(str(body, "assetType"),
                    longOf(body, "assetId"), longOf(body, "evidenceChunkId"),
                    str(body, "evidenceExcerpt"), str(body, "matchStatus"))));
    }

    @DeleteMapping("/materials/evidence/{evidenceId}")
    public ResponseEntity<Result<?>> removeEvidence(@PathVariable Long teamId,
                                                    @PathVariable Long evidenceId,
                                                    HttpSession session) {
        Long userId = requireUser(session).getId();
        materialService.removeEvidence(teamId, evidenceId, userId);
        return privateOk(Map.of("removed", true));
    }

    @PutMapping("/materials/requirements/{requirementId}/review")
    public ResponseEntity<Result<?>> reviewRequirement(@PathVariable Long teamId,
                                                       @PathVariable Long requirementId,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(materialService.review(teamId, requirementId, userId,
            new TeamMaterialMatrixService.ReviewRequest(str(body, "status"), str(body, "note"))));
    }

    @GetMapping("/materials/matrix/export.csv")
    public ResponseEntity<byte[]> exportMatrix(@PathVariable Long teamId, HttpSession session) {
        Long userId = requireUser(session).getId();
        TeamMaterialMatrixService.Export export = materialService.exportCsv(teamId, userId);
        return download(export.filename(),
            MediaType.parseMediaType("text/csv;charset=UTF-8"), export.bytes());
    }

    // ==================== 内部 ====================

    private TeamMaterialMatrixService.RequirementRequest requirementRequest(Map<String, Object> body) {
        if (body == null) {
            throw new TeamCollabException(TeamCollabException.Code.REQUEST_INVALID, "请求体不能为空");
        }
        return new TeamMaterialMatrixService.RequirementRequest(
            str(body, "title"), str(body, "description"), str(body, "category"),
            body.get("required") == null ? null : Boolean.valueOf(String.valueOf(body.get("required"))),
            str(body, "sourceType"), intOf(body, "competitionId"),
            str(body, "customCompetition"), intOf(body, "sortOrder"), intOf(body, "version"));
    }

    private Map<String, Object> leaseInfo(String type, Long assetId) {
        var lease = leaseService.current(type, assetId);
        if (lease == null || lease.getExpiresAt() == null
            || lease.getExpiresAt().before(new java.util.Date())) {
            return Map.of();
        }
        User holder = lease.getUserId() == null ? null : userService.getById(lease.getUserId());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("userId", lease.getUserId());
        row.put("displayName", holder == null
            ? "团队成员" : cn.ilink.util.DisplayNameResolver.resolve(holder));
        row.put("expiresAt", lease.getExpiresAt().toInstant().toString());
        return row;
    }

    private ResponseEntity<byte[]> download(String filename, MediaType type, byte[] bytes) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + encoded)
            .cacheControl(CacheControl.noStore().mustRevalidate())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(bytes);
    }

    private ResponseEntity<Result<?>> privateOk(Object data) {
        return privateResponse(HttpStatus.OK, "获取成功", data);
    }

    private ResponseEntity<Result<?>> privateResponse(HttpStatus status, String message, Object data) {
        return privateResponse(status, message, data, null);
    }

    private ResponseEntity<Result<?>> privateResponse(HttpStatus status, String message, Object data,
                                                       String errorCode) {
        Result<Object> result = status.is2xxSuccessful()
            ? Result.ok(message, data) : Result.fail(status.value(), message);
        if (!status.is2xxSuccessful() && data != null) {
            result.setData(data);
        }
        if (errorCode != null) {
            result.withExtra("errorCode", errorCode);
        }
        return ResponseEntity.status(status)
            .cacheControl(CacheControl.noStore().mustRevalidate())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(result);
    }

    private User requireUser(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            throw new TeamCollabException(TeamCollabException.Code.ASSET_FORBIDDEN, "登录状态已失效");
        }
        return user;
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intOf(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOf(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private com.fasterxml.jackson.databind.JsonNode json(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null) return null;
        return objectMapper.valueToTree(value);
    }
}
