package cn.ilink.collab;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.TeamCollabDocument;
import cn.ilink.entity.TeamCollabDocumentVersion;
import cn.ilink.entity.TeamCollabEvent;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabDocumentVersionMapper;
import cn.ilink.service.UserService;
import cn.ilink.service.kg.KgIndexService;
import cn.ilink.util.DisplayNameResolver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Team Markdown documents. All writes are versioned and append a snapshot in
 * the same transaction; index work is deliberately best-effort and never
 * blocks the document save.
 */
@Service
public class TeamDocumentService {
    public static final int MAX_VERSION_HISTORY = 200;
    private final TeamCollabDocumentMapper documentMapper;
    private final TeamCollabDocumentVersionMapper versionMapper;
    private final TeamCollaborationAuthorization authorization;
    private final TeamCollaborationLeaseService leaseService;
    private final TeamCollaborationEventService eventService;
    private final KgIndexService indexService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public TeamDocumentService(TeamCollabDocumentMapper documentMapper,
                               TeamCollabDocumentVersionMapper versionMapper,
                               TeamCollaborationAuthorization authorization,
                               TeamCollaborationLeaseService leaseService,
                               TeamCollaborationEventService eventService,
                               KgIndexService indexService,
                               UserService userService,
                               ObjectMapper objectMapper) {
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
        this.authorization = authorization;
        this.leaseService = leaseService;
        this.eventService = eventService;
        this.indexService = indexService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    public List<DocumentView> list(Long teamId, Long userId) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireTeam(teamId, userId);
        return documentMapper.selectList(new LambdaQueryWrapper<TeamCollabDocument>()
                .eq(TeamCollabDocument::getTeamId, teamId)
                .isNull(TeamCollabDocument::getDeletedAt)
                .orderByDesc(TeamCollabDocument::getUpdatedAt))
            .stream().filter(row -> canView(team, row))
            .map(row -> viewForTeam(team, row)).toList();
    }

    public DocumentView get(Long teamId, Long documentId, Long userId) {
        TeamCollaborationAuthorization.AssetContext context = authorization
            .requireAsset(teamId, "DOCUMENT", documentId, userId,
                TeamCollaborationAuthorization.Permission.VIEW);
        TeamCollabDocument row = requireDocument(teamId, documentId);
        return view(context, row);
    }

    @Transactional(rollbackFor = Exception.class)
    public DocumentView create(Long teamId, Long userId, CreateRequest request) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireWritableTeam(teamId, userId);
        String title = title(request == null ? null : request.title());
        String markdown = TeamCollabMarkdown.normalize(request == null ? "" : request.contentMarkdown());
        String visibility = visibility(request == null ? null : request.visibility());
        Date now = new Date();
        TeamCollabDocument row = new TeamCollabDocument();
        row.setTeamId(teamId);
        row.setTitle(title);
        row.setContentMarkdown(markdown);
        row.setVisibility(visibility);
        row.setStatus(TeamCollabDocument.STATUS_ACTIVE);
        row.setVersion(1);
        row.setContentHash(TeamCollabMarkdown.hash(markdown));
        row.setCreatorUserId(userId);
        row.setLastEditorUserId(userId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (documentMapper.insert(row) != 1) throw failed("文档创建失败");
        snapshot(row, "MANUAL", userId, null);
        eventService.append(teamId, "DOCUMENT", row.getId(), userId, "DOCUMENT_CREATED",
            "SUCCESS", row.getVersion(), null, java.util.Map.of("title", title));
        enqueue(row);
        return view(authorization.requireAsset(teamId, "DOCUMENT", row.getId(), userId,
            TeamCollaborationAuthorization.Permission.VIEW), row);
    }

    @Transactional(rollbackFor = Exception.class)
    public DocumentView update(Long teamId, Long documentId, Long userId, SaveRequest request) {
        TeamCollaborationAuthorization.AssetContext context = authorization
            .requireEditableAsset(teamId, "DOCUMENT", documentId, userId);
        if (request == null || request.baseVersion() == null) throw invalid("缺少基础版本");
        String requestId = requireRequestId(request.clientRequestId());
        TeamCollabDocument current = documentMapper.selectByIdForUpdate(documentId);
        requireVisible(teamId, documentId, current, context);
        TeamCollabEvent previous = eventService.findRequest(teamId, "DOCUMENT",
            documentId, requestId);
        if (previous != null) {
            requireSamePayload(previous, saveFingerprint(request));
            return view(context, current);
        }
        if (!Objects.equals(current.getVersion(), request.baseVersion())) {
            throw versionConflict(current);
        }
        leaseService.requireHeld(teamId, "DOCUMENT", documentId, userId,
            request.leaseToken());
        String title = request.title() == null ? current.getTitle() : title(request.title());
        String markdown = request.contentMarkdown() == null
            ? current.getContentMarkdown() : TeamCollabMarkdown.normalize(request.contentMarkdown());
        String nextVisibility = request.visibility() == null
            ? current.getVisibility() : visibility(request.visibility());
        if (!Objects.equals(nextVisibility, current.getVisibility())
            && !context.canManage()) {
            throw new TeamCollabException(TeamCollabException.Code.ASSET_FORBIDDEN,
                "只有队长或资产创建者可以修改可见范围");
        }
        String hash = TeamCollabMarkdown.hash(markdown);
        if (Objects.equals(title, current.getTitle())
            && Objects.equals(hash, current.getContentHash())
            && Objects.equals(nextVisibility, current.getVisibility())) {
            return view(context, current);
        }
        int nextVersion = current.getVersion() + 1;
        int changed = documentMapper.update(null, new LambdaUpdateWrapper<TeamCollabDocument>()
            .eq(TeamCollabDocument::getId, documentId)
            .eq(TeamCollabDocument::getVersion, current.getVersion())
            .isNull(TeamCollabDocument::getDeletedAt)
            .set(TeamCollabDocument::getTitle, title)
            .set(TeamCollabDocument::getContentMarkdown, markdown)
            .set(TeamCollabDocument::getContentHash, hash)
            .set(TeamCollabDocument::getVisibility, nextVisibility)
            .set(TeamCollabDocument::getLastEditorUserId, userId)
            .set(TeamCollabDocument::getVersion, nextVersion)
            .set(TeamCollabDocument::getUpdatedAt, new Date()));
        if (changed != 1) throw versionConflict(requireDocument(teamId, documentId));
        TeamCollabDocument saved = requireDocument(teamId, documentId);
        snapshot(saved, request.saveKind() == null ? "AUTO" : request.saveKind(),
            userId, request.changeSummary());
        eventService.append(teamId, "DOCUMENT", documentId, userId, "DOCUMENT_SAVED",
            "SUCCESS", saved.getVersion(), requestId,
            java.util.Map.of(
                "saveKind", request.saveKind() == null ? "AUTO" : request.saveKind(),
                "requestHash", saveFingerprint(request)));
        enqueue(saved);
        trimVersions(documentId, saved.getVersion());
        return view(context, saved);
    }

    @Transactional(rollbackFor = Exception.class)
    public void archive(Long teamId, Long documentId, Long userId, boolean archived) {
        TeamCollaborationAuthorization.AssetContext context = authorization
            .requireEditableAsset(teamId, "DOCUMENT", documentId, userId);
        TeamCollabDocument row = documentMapper.selectByIdForUpdate(documentId);
        requireVisible(teamId, documentId, row, context);
        String status = archived ? TeamCollabDocument.STATUS_ARCHIVED
            : TeamCollabDocument.STATUS_ACTIVE;
        documentMapper.update(null, new LambdaUpdateWrapper<TeamCollabDocument>()
            .eq(TeamCollabDocument::getId, documentId)
            .isNull(TeamCollabDocument::getDeletedAt)
            .set(TeamCollabDocument::getStatus, status)
            .set(TeamCollabDocument::getUpdatedAt, new Date()));
        leaseService.invalidateAsset("DOCUMENT", documentId);
        eventService.append(teamId, "DOCUMENT", documentId, userId,
            archived ? "DOCUMENT_ARCHIVED" : "DOCUMENT_RESTORED", "SUCCESS",
            row.getVersion(), null, java.util.Map.of());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long teamId, Long documentId, Long userId, Integer baseVersion) {
        TeamCollaborationAuthorization.AssetContext context = authorization
            .requireEditableAsset(teamId, "DOCUMENT", documentId, userId);
        authorization.requireManage(context);
        TeamCollabDocument row = documentMapper.selectByIdForUpdate(documentId);
        requireVisible(teamId, documentId, row, context);
        if (baseVersion != null && !Objects.equals(baseVersion, row.getVersion())) {
            throw versionConflict(row);
        }
        documentMapper.update(null, new LambdaUpdateWrapper<TeamCollabDocument>()
            .eq(TeamCollabDocument::getId, documentId)
            .eq(TeamCollabDocument::getVersion, row.getVersion())
            .isNull(TeamCollabDocument::getDeletedAt)
            .set(TeamCollabDocument::getDeletedAt, new Date())
            .set(TeamCollabDocument::getUpdatedAt, new Date())
            .setSql("version = version + 1"));
        leaseService.invalidateAsset("DOCUMENT", documentId);
        eventService.append(teamId, "DOCUMENT", documentId, userId, "DOCUMENT_DELETED",
            "SUCCESS", row.getVersion() + 1, null, java.util.Map.of());
        indexService.enqueueDelete("TEAM_DOCUMENT", documentId, KgEntity.SCOPE_TEAM,
            teamId, userId);
    }

    public List<VersionView> versions(Long teamId, Long documentId, Long userId,
                                      int limit, int offset) {
        TeamCollaborationAuthorization.AssetContext context = authorization
            .requireAsset(teamId, "DOCUMENT", documentId, userId,
                TeamCollaborationAuthorization.Permission.VIEW);
        TeamCollabDocument row = requireDocument(teamId, documentId);
        if (!Objects.equals(context.assetId(), row.getId())) throw notFound();
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 30 : limit, 100));
        int safeOffset = Math.max(0, offset);
        return versionMapper.selectList(new LambdaQueryWrapper<TeamCollabDocumentVersion>()
                .eq(TeamCollabDocumentVersion::getDocumentId, documentId)
                .orderByDesc(TeamCollabDocumentVersion::getVersion)
                .last("LIMIT " + safeLimit + " OFFSET " + safeOffset))
            .stream().map(this::versionView).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public DocumentView restoreVersion(Long teamId, Long documentId, Integer version,
                                       Long userId, String leaseToken, String requestId) {
        TeamCollaborationAuthorization.AssetContext context = authorization
            .requireEditableAsset(teamId, "DOCUMENT", documentId, userId);
        if (version == null) throw invalid("请选择要恢复的版本");
        TeamCollabDocument current = documentMapper.selectByIdForUpdate(documentId);
        requireVisible(teamId, documentId, current, context);
        leaseService.requireHeld(teamId, "DOCUMENT", documentId, userId, leaseToken);
        TeamCollabDocumentVersion old = versionMapper.selectOne(
            new LambdaQueryWrapper<TeamCollabDocumentVersion>()
                .eq(TeamCollabDocumentVersion::getDocumentId, documentId)
                .eq(TeamCollabDocumentVersion::getVersion, version)
                .last("LIMIT 1"));
        if (old == null) throw notFound();
        int nextVersion = current.getVersion() + 1;
        documentMapper.update(null, new LambdaUpdateWrapper<TeamCollabDocument>()
            .eq(TeamCollabDocument::getId, documentId)
            .eq(TeamCollabDocument::getVersion, current.getVersion())
            .set(TeamCollabDocument::getTitle, old.getTitle())
            .set(TeamCollabDocument::getContentMarkdown, old.getContentMarkdown())
            .set(TeamCollabDocument::getContentHash, old.getContentHash())
            .set(TeamCollabDocument::getLastEditorUserId, userId)
            .set(TeamCollabDocument::getVersion, nextVersion)
            .set(TeamCollabDocument::getUpdatedAt, new Date()));
        TeamCollabDocument saved = requireDocument(teamId, documentId);
        snapshot(saved, "RESTORE", userId, "恢复版本 " + version);
        eventService.append(teamId, "DOCUMENT", documentId, userId, "DOCUMENT_VERSION_RESTORED",
            "SUCCESS", nextVersion, requestId,
            java.util.Map.of("restoredVersion", version));
        enqueue(saved);
        return view(context, saved);
    }

    public Export export(Long teamId, Long documentId, Long userId) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireTeam(teamId, userId);
        if (!team.canExport()) {
            throw new TeamCollabException(TeamCollabException.Code.TEAM_READ_ONLY,
                "当前团队状态不允许导出");
        }
        TeamCollaborationAuthorization.AssetContext context = authorization
            .requireAsset(teamId, "DOCUMENT", documentId, userId,
                TeamCollaborationAuthorization.Permission.VIEW);
        TeamCollabDocument row = requireDocument(teamId, documentId);
        return new Export(TeamCollabMarkdown.exportFilename(row.getTitle(), ".md"),
            row.getContentMarkdown().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Transactional(rollbackFor = Exception.class)
    public int deleteAllForTeam(Long teamId) {
        List<Long> ids = documentMapper.selectList(new LambdaQueryWrapper<TeamCollabDocument>()
                .eq(TeamCollabDocument::getTeamId, teamId)
                .select(TeamCollabDocument::getId))
            .stream().map(TeamCollabDocument::getId).toList();
        if (ids.isEmpty()) return 0;
        versionMapper.delete(new LambdaQueryWrapper<TeamCollabDocumentVersion>()
            .eq(TeamCollabDocumentVersion::getTeamId, teamId));
        return documentMapper.deleteBatchIds(ids);
    }

    private boolean canView(TeamCollaborationAuthorization.TeamContext team,
                            TeamCollabDocument row) {
        try {
            TeamCollaborationAuthorization.Acl acl = authorization.acl(team, "DOCUMENT",
                row.getId(), row.getVisibility(), row.getCreatorUserId());
            return acl.permission() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private TeamCollabDocument requireDocument(Long teamId, Long id) {
        TeamCollabDocument row = documentMapper.selectById(id);
        if (row == null || !Objects.equals(row.getTeamId(), teamId)
            || row.getDeletedAt() != null) throw notFound();
        return row;
    }

    private void requireVisible(Long teamId, Long id, TeamCollabDocument row,
                                TeamCollaborationAuthorization.AssetContext context) {
        if (row == null || !Objects.equals(row.getTeamId(), teamId)
            || row.getDeletedAt() != null) throw notFound();
    }

    private DocumentView view(TeamCollaborationAuthorization.AssetContext context,
                              TeamCollabDocument row) {
        User creator = row.getCreatorUserId() == null ? null : userService.getById(row.getCreatorUserId());
        User editor = row.getLastEditorUserId() == null ? null : userService.getById(row.getLastEditorUserId());
        return new DocumentView(row.getId(), row.getTitle(), row.getContentMarkdown(), row.getVisibility(),
            row.getStatus(), row.getVersion(), row.getContentHash(),
            creator == null ? "已注销用户" : DisplayNameResolver.resolve(creator),
            editor == null ? "已注销用户" : DisplayNameResolver.resolve(editor),
            row.getCreatedAt(), row.getUpdatedAt(), context.permission().name(),
            context.canEdit(), context.canManage(), leaseView("DOCUMENT", row.getId()));
    }

    private DocumentView viewForTeam(TeamCollaborationAuthorization.TeamContext team,
                                     TeamCollabDocument row) {
        TeamCollaborationAuthorization.Acl acl = authorization.acl(team, "DOCUMENT",
            row.getId(), row.getVisibility(), row.getCreatorUserId());
        if (acl.permission() == null) {
            throw new TeamCollabException(TeamCollabException.Code.ASSET_FORBIDDEN,
                "无权访问该文档");
        }
        User creator = row.getCreatorUserId() == null ? null : userService.getById(row.getCreatorUserId());
        User editor = row.getLastEditorUserId() == null ? null : userService.getById(row.getLastEditorUserId());
        return new DocumentView(row.getId(), row.getTitle(), row.getContentMarkdown(),
            row.getVisibility(), row.getStatus(), row.getVersion(), row.getContentHash(),
            creator == null ? "已注销用户" : DisplayNameResolver.resolve(creator),
            editor == null ? "已注销用户" : DisplayNameResolver.resolve(editor),
            row.getCreatedAt(), row.getUpdatedAt(), acl.permission().name(),
            team.canWrite() && acl.permission() != TeamCollaborationAuthorization.Permission.VIEW,
            acl.canManage(), leaseView("DOCUMENT", row.getId()));
    }

    private Object leaseView(String type, Long id) {
        var lease = leaseService.current(type, id);
        if (lease == null || lease.getExpiresAt() == null
            || !lease.getExpiresAt().after(new Date())) return null;
        User editor = userService.getById(lease.getUserId());
        return java.util.Map.of(
            "userId", lease.getUserId(),
            "displayName", editor == null ? "团队成员" : DisplayNameResolver.resolve(editor),
            "expiresAt", lease.getExpiresAt().toInstant().toString(),
            "baseVersion", lease.getBaseVersion());
    }

    private void snapshot(TeamCollabDocument row, String saveKind, Long actor, String summary) {
        TeamCollabDocumentVersion version = new TeamCollabDocumentVersion();
        version.setDocumentId(row.getId());
        version.setTeamId(row.getTeamId());
        version.setVersion(row.getVersion());
        version.setTitle(row.getTitle());
        version.setContentMarkdown(row.getContentMarkdown());
        version.setContentHash(row.getContentHash());
        version.setSaveKind(saveKind);
        version.setActorUserId(actor);
        version.setChangeSummary(summary == null ? null : summary.substring(0, Math.min(500, summary.length())));
        version.setCreatedAt(new Date());
        versionMapper.insert(version);
    }

    private void trimVersions(Long documentId, int latestVersion) {
        long count = versionMapper.selectCount(new LambdaQueryWrapper<TeamCollabDocumentVersion>()
            .eq(TeamCollabDocumentVersion::getDocumentId, documentId));
        if (count <= MAX_VERSION_HISTORY) return;
        List<TeamCollabDocumentVersion> candidates =
            versionMapper.selectList(new LambdaQueryWrapper<TeamCollabDocumentVersion>()
                .eq(TeamCollabDocumentVersion::getDocumentId, documentId)
                .eq(TeamCollabDocumentVersion::getSaveKind, "AUTO")
                .lt(TeamCollabDocumentVersion::getVersion, latestVersion - 20)
                .orderByAsc(TeamCollabDocumentVersion::getVersion)
                .last("LIMIT " + (count - MAX_VERSION_HISTORY)));
        for (TeamCollabDocumentVersion row : candidates) {
            versionMapper.deleteById(row.getId());
        }
    }

    private VersionView versionView(TeamCollabDocumentVersion row) {
        return new VersionView(row.getVersion(), row.getTitle(), row.getSaveKind(),
            row.getContentHash(), row.getChangeSummary(), row.getCreatedAt(),
            row.getActorUserId() == null ? "已注销用户"
                : DisplayNameResolver.resolve(userService.getById(row.getActorUserId())));
    }

    private void enqueue(TeamCollabDocument row) {
        String content = "# " + row.getTitle() + "\n\n" + row.getContentMarkdown();
        indexService.enqueueUpsert("TEAM_DOCUMENT", row.getId(), row.getTitle(), content,
            KgEntity.SCOPE_TEAM, row.getTeamId(), row.getLastEditorUserId());
    }

    private String title(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw invalid("文档标题不能为空");
        if (value.length() > 120) throw invalid("文档标题不能超过 120 字");
        return value;
    }

    private String visibility(String raw) {
        String value = raw == null ? TeamCollabDocument.VISIBILITY_TEAM
            : raw.trim().toUpperCase(Locale.ROOT);
        if (!TeamCollabDocument.VISIBILITY_TEAM.equals(value)
            && !TeamCollabDocument.VISIBILITY_RESTRICTED.equals(value)) {
            throw invalid("文档可见范围不合法");
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
        String markdown = request.contentMarkdown() == null
            ? "" : TeamCollabMarkdown.normalize(request.contentMarkdown());
        String visibility = request.visibility() == null ? ""
            : request.visibility().trim().toUpperCase(Locale.ROOT);
        return sha256(title + "\u0000" + markdown + "\u0000" + visibility
            + "\u0000" + request.baseVersion());
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
            throw failed("无法校验保存请求");
        }
    }

    private TeamCollabException versionConflict(TeamCollabDocument row) {
        return new TeamCollabException(TeamCollabException.Code.EDIT_VERSION_CONFLICT,
            "文档已被更新，请刷新后继续编辑");
    }

    private TeamCollabException invalid(String message) {
        return new TeamCollabException(TeamCollabException.Code.REQUEST_INVALID, message);
    }

    private TeamCollabException notFound() {
        return new TeamCollabException(TeamCollabException.Code.ASSET_NOT_FOUND,
            "文档不存在");
    }

    private TeamCollabException failed(String message) {
        return new TeamCollabException(TeamCollabException.Code.ASSET_FORBIDDEN, message);
    }

    public record CreateRequest(String title, String contentMarkdown, String visibility) {}
    public record SaveRequest(String title, String contentMarkdown, String visibility,
                              Integer baseVersion, String leaseToken,
                              String clientRequestId, String saveKind,
                              String changeSummary) {}
    public record Export(String filename, byte[] content) {}
    public record DocumentView(Long id, String title, String contentMarkdown,
                               String visibility, String status, Integer version,
                               String contentHash, String creatorName,
                               String lastEditorName, Date createdAt, Date updatedAt,
                               String permission, boolean canEdit, boolean canManage,
                               Object activeLease) {}
    public record VersionView(Integer version, String title, String saveKind,
                              String contentHash, String changeSummary, Date createdAt,
                              String actorName) {}
}
