package cn.ilink.collab;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.TeamCollabAcl;
import cn.ilink.entity.TeamCollabDocument;
import cn.ilink.entity.TeamCollabDocumentVersion;
import cn.ilink.entity.TeamCollabEditLease;
import cn.ilink.entity.TeamCollabSpreadsheet;
import cn.ilink.entity.TeamCollabSpreadsheetVersion;
import cn.ilink.entity.TeamMaterialEvidence;
import cn.ilink.mapper.TeamCollabAclMapper;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabDocumentVersionMapper;
import cn.ilink.mapper.TeamCollabEditLeaseMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetVersionMapper;
import cn.ilink.mapper.TeamMaterialEvidenceMapper;
import cn.ilink.service.UserService;
import cn.ilink.service.kg.KgIndexService;
import cn.ilink.util.DisplayNameResolver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Recoverable trash for document and spreadsheet assets.
 *
 * <p>Soft deletion already removes an asset from normal reads and Agent
 * context. This service exposes only the author-managed 30-day recovery view,
 * restores a snapshot as a new version, and purges expired or explicitly
 * discarded assets together with their ACL, lease, versions and material
 * evidence links.</p>
 */
@Service
@Slf4j
public class TeamCollaborationTrashService {
    public static final int RETENTION_DAYS = 30;
    private static final int MAX_LISTED_PER_TYPE = 200;

    private final TeamCollaborationAuthorization authorization;
    private final TeamCollaborationEventService eventService;
    private final KgIndexService indexService;
    private final UserService userService;
    private final TeamCollabDocumentMapper documentMapper;
    private final TeamCollabSpreadsheetMapper spreadsheetMapper;
    private final TeamCollabDocumentVersionMapper documentVersionMapper;
    private final TeamCollabSpreadsheetVersionMapper spreadsheetVersionMapper;
    private final TeamCollabAclMapper aclMapper;
    private final TeamCollabEditLeaseMapper leaseMapper;
    private final TeamMaterialEvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;

    public TeamCollaborationTrashService(TeamCollaborationAuthorization authorization,
                                         TeamCollaborationEventService eventService,
                                         KgIndexService indexService,
                                         UserService userService,
                                         TeamCollabDocumentMapper documentMapper,
                                         TeamCollabSpreadsheetMapper spreadsheetMapper,
                                         TeamCollabDocumentVersionMapper documentVersionMapper,
                                         TeamCollabSpreadsheetVersionMapper spreadsheetVersionMapper,
                                         TeamCollabAclMapper aclMapper,
                                         TeamCollabEditLeaseMapper leaseMapper,
                                         TeamMaterialEvidenceMapper evidenceMapper,
                                         ObjectMapper objectMapper) {
        this.authorization = authorization;
        this.eventService = eventService;
        this.indexService = indexService;
        this.userService = userService;
        this.documentMapper = documentMapper;
        this.spreadsheetMapper = spreadsheetMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.spreadsheetVersionMapper = spreadsheetVersionMapper;
        this.aclMapper = aclMapper;
        this.leaseMapper = leaseMapper;
        this.evidenceMapper = evidenceMapper;
        this.objectMapper = objectMapper;
    }

    /** Lists recoverable assets the current user owns as creator or team leader. */
    public List<TrashAsset> list(Long teamId, Long userId) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireTeam(teamId, userId);
        Date cutoff = retentionCutoff(new Date());
        List<TrashAsset> rows = new ArrayList<>();
        documentMapper.selectList(new LambdaQueryWrapper<TeamCollabDocument>()
                .eq(TeamCollabDocument::getTeamId, teamId)
                .isNotNull(TeamCollabDocument::getDeletedAt)
                .ge(TeamCollabDocument::getDeletedAt, cutoff)
                .orderByDesc(TeamCollabDocument::getDeletedAt)
                .last("LIMIT " + MAX_LISTED_PER_TYPE))
            .stream()
            .filter(row -> canManage(team, row.getCreatorUserId()))
            .map(row -> trashAsset("DOCUMENT", row.getId(), row.getTitle(),
                row.getVisibility(), row.getVersion(), row.getCreatorUserId(),
                row.getDeletedAt()))
            .forEach(rows::add);
        spreadsheetMapper.selectList(new LambdaQueryWrapper<TeamCollabSpreadsheet>()
                .eq(TeamCollabSpreadsheet::getTeamId, teamId)
                .isNotNull(TeamCollabSpreadsheet::getDeletedAt)
                .ge(TeamCollabSpreadsheet::getDeletedAt, cutoff)
                .orderByDesc(TeamCollabSpreadsheet::getDeletedAt)
                .last("LIMIT " + MAX_LISTED_PER_TYPE))
            .stream()
            .filter(row -> canManage(team, row.getCreatorUserId()))
            .map(row -> trashAsset("SPREADSHEET", row.getId(), row.getTitle(),
                row.getVisibility(), row.getVersion(), row.getCreatorUserId(),
                row.getDeletedAt()))
            .forEach(rows::add);
        rows.sort(Comparator.comparing(TrashAsset::deletedAt,
            Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    /**
     * Restores a live asset as a new version. Versions, ACL and evidence links
     * were intentionally retained while the asset sat in trash.
     */
    @Transactional(rollbackFor = Exception.class)
    public TrashAsset restore(Long teamId, String rawType, Long assetId, Long userId,
                              Integer expectedVersion) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireWritableTeam(teamId, userId);
        String type = authorization.assetType(rawType);
        Date now = new Date();
        Date cutoff = retentionCutoff(now);
        if ("DOCUMENT".equals(type)) {
            TeamCollabDocument row = requireDeletedDocument(teamId, assetId, cutoff);
            requireManager(team, row.getCreatorUserId());
            requireVersion(row.getVersion(), expectedVersion);
            int nextVersion = row.getVersion() + 1;
            int changed = documentMapper.update(null,
                new LambdaUpdateWrapper<TeamCollabDocument>()
                    .eq(TeamCollabDocument::getId, assetId)
                    .eq(TeamCollabDocument::getTeamId, teamId)
                    .eq(TeamCollabDocument::getVersion, row.getVersion())
                    .isNotNull(TeamCollabDocument::getDeletedAt)
                    .set(TeamCollabDocument::getDeletedAt, null)
                    .set(TeamCollabDocument::getStatus, TeamCollabDocument.STATUS_ACTIVE)
                    .set(TeamCollabDocument::getVersion, nextVersion)
                    .set(TeamCollabDocument::getUpdatedAt, now));
            if (changed != 1) throw versionConflict();
            TeamCollabDocument restored = documentMapper.selectById(assetId);
            eventService.append(teamId, type, assetId, userId,
                "DOCUMENT_RESTORED_FROM_TRASH", "SUCCESS", nextVersion, null,
                Map.of("previousVersion", row.getVersion()));
            enqueueDocument(restored);
            return trashAsset(type, assetId, restored.getTitle(),
                restored.getVisibility(), restored.getVersion(),
                restored.getCreatorUserId(), null);
        }

        TeamCollabSpreadsheet row = requireDeletedSpreadsheet(teamId, assetId, cutoff);
        requireManager(team, row.getCreatorUserId());
        requireVersion(row.getVersion(), expectedVersion);
        int nextVersion = row.getVersion() + 1;
        int changed = spreadsheetMapper.update(null,
            new LambdaUpdateWrapper<TeamCollabSpreadsheet>()
                .eq(TeamCollabSpreadsheet::getId, assetId)
                .eq(TeamCollabSpreadsheet::getTeamId, teamId)
                .eq(TeamCollabSpreadsheet::getVersion, row.getVersion())
                .isNotNull(TeamCollabSpreadsheet::getDeletedAt)
                .set(TeamCollabSpreadsheet::getDeletedAt, null)
                .set(TeamCollabSpreadsheet::getStatus, TeamCollabSpreadsheet.STATUS_ACTIVE)
                .set(TeamCollabSpreadsheet::getVersion, nextVersion)
                .set(TeamCollabSpreadsheet::getUpdatedAt, now));
        if (changed != 1) throw versionConflict();
        TeamCollabSpreadsheet restored = spreadsheetMapper.selectById(assetId);
        eventService.append(teamId, type, assetId, userId,
            "SPREADSHEET_RESTORED_FROM_TRASH", "SUCCESS", nextVersion, null,
            Map.of("previousVersion", row.getVersion()));
        enqueueSpreadsheet(restored);
        return trashAsset(type, assetId, restored.getTitle(),
            restored.getVisibility(), restored.getVersion(),
            restored.getCreatorUserId(), null);
    }

    /** Explicit permanent delete. The asset may still be inside its 30-day window. */
    @Transactional(rollbackFor = Exception.class)
    public boolean purge(Long teamId, String rawType, Long assetId, Long userId,
                         Integer expectedVersion) {
        TeamCollaborationAuthorization.TeamContext team =
            authorization.requireWritableTeam(teamId, userId);
        String type = authorization.assetType(rawType);
        if ("DOCUMENT".equals(type)) {
            TeamCollabDocument row = requireDeletedDocument(teamId, assetId, null);
            requireManager(team, row.getCreatorUserId());
            requireVersion(row.getVersion(), expectedVersion);
            hardDeleteDocument(row, userId, null);
            return true;
        }
        TeamCollabSpreadsheet row = requireDeletedSpreadsheet(teamId, assetId, null);
        requireManager(team, row.getCreatorUserId());
        requireVersion(row.getVersion(), expectedVersion);
        hardDeleteSpreadsheet(row, userId, null);
        return true;
    }

    /**
     * Nightly hard-delete of assets outside the recovery window. The softer
     * delete already wrote a KG tombstone; writing it again keeps retries
     * idempotent if the first indexing request was lost.
     */
    @Scheduled(cron = "0 22 3 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public int purgeExpired() {
        Date cutoff = retentionCutoff(new Date());
        int purged = 0;
        try {
            List<TeamCollabDocument> documents = documentMapper.selectList(
                new LambdaQueryWrapper<TeamCollabDocument>()
                    .isNotNull(TeamCollabDocument::getDeletedAt)
                    .lt(TeamCollabDocument::getDeletedAt, cutoff)
                    .last("LIMIT " + MAX_LISTED_PER_TYPE));
            for (TeamCollabDocument row : documents) {
                if (hardDeleteDocument(row, null, cutoff)) purged++;
            }
            List<TeamCollabSpreadsheet> spreadsheets = spreadsheetMapper.selectList(
                new LambdaQueryWrapper<TeamCollabSpreadsheet>()
                    .isNotNull(TeamCollabSpreadsheet::getDeletedAt)
                    .lt(TeamCollabSpreadsheet::getDeletedAt, cutoff)
                    .last("LIMIT " + MAX_LISTED_PER_TYPE));
            for (TeamCollabSpreadsheet row : spreadsheets) {
                if (hardDeleteSpreadsheet(row, null, cutoff)) purged++;
            }
        } catch (RuntimeException e) {
            log.warn("清理过期协作资产失败: {}", e.getMessage());
            throw e;
        }
        return purged;
    }

    private boolean hardDeleteDocument(TeamCollabDocument row, Long actorId, Date cutoff) {
        LambdaQueryWrapper<TeamCollabDocument> condition =
            new LambdaQueryWrapper<TeamCollabDocument>()
                .eq(TeamCollabDocument::getId, row.getId())
                .eq(TeamCollabDocument::getTeamId, row.getTeamId())
                .isNotNull(TeamCollabDocument::getDeletedAt);
        if (cutoff != null) condition.lt(TeamCollabDocument::getDeletedAt, cutoff);
        if (documentMapper.delete(condition) != 1) return false;
        deleteDependents(row.getTeamId(), "DOCUMENT", row.getId(),
            documentVersionMapper, null);
        eventService.append(row.getTeamId(), "DOCUMENT", row.getId(), actorId,
            "DOCUMENT_PURGED", "SUCCESS", row.getVersion(), null, Map.of());
        indexService.enqueueDelete("TEAM_DOCUMENT", row.getId(), KgEntity.SCOPE_TEAM,
            row.getTeamId(), actorId);
        return true;
    }

    private boolean hardDeleteSpreadsheet(TeamCollabSpreadsheet row, Long actorId,
                                          Date cutoff) {
        LambdaQueryWrapper<TeamCollabSpreadsheet> condition =
            new LambdaQueryWrapper<TeamCollabSpreadsheet>()
                .eq(TeamCollabSpreadsheet::getId, row.getId())
                .eq(TeamCollabSpreadsheet::getTeamId, row.getTeamId())
                .isNotNull(TeamCollabSpreadsheet::getDeletedAt);
        if (cutoff != null) condition.lt(TeamCollabSpreadsheet::getDeletedAt, cutoff);
        if (spreadsheetMapper.delete(condition) != 1) return false;
        deleteDependents(row.getTeamId(), "SPREADSHEET", row.getId(),
            null, spreadsheetVersionMapper);
        eventService.append(row.getTeamId(), "SPREADSHEET", row.getId(), actorId,
            "SPREADSHEET_PURGED", "SUCCESS", row.getVersion(), null, Map.of());
        indexService.enqueueDelete("TEAM_SPREADSHEET", row.getId(), KgEntity.SCOPE_TEAM,
            row.getTeamId(), actorId);
        return true;
    }

    private void deleteDependents(Long teamId, String type, Long assetId,
                                  TeamCollabDocumentVersionMapper documentVersions,
                                  TeamCollabSpreadsheetVersionMapper spreadsheetVersions) {
        if (documentVersions != null) {
            documentVersions.delete(new LambdaQueryWrapper<TeamCollabDocumentVersion>()
                .eq(TeamCollabDocumentVersion::getTeamId, teamId)
                .eq(TeamCollabDocumentVersion::getDocumentId, assetId));
        }
        if (spreadsheetVersions != null) {
            spreadsheetVersions.delete(new LambdaQueryWrapper<TeamCollabSpreadsheetVersion>()
                .eq(TeamCollabSpreadsheetVersion::getTeamId, teamId)
                .eq(TeamCollabSpreadsheetVersion::getSpreadsheetId, assetId));
        }
        aclMapper.delete(new LambdaQueryWrapper<TeamCollabAcl>()
            .eq(TeamCollabAcl::getTeamId, teamId)
            .eq(TeamCollabAcl::getAssetType, type)
            .eq(TeamCollabAcl::getAssetId, assetId));
        leaseMapper.delete(new LambdaQueryWrapper<TeamCollabEditLease>()
            .eq(TeamCollabEditLease::getTeamId, teamId)
            .eq(TeamCollabEditLease::getAssetType, type)
            .eq(TeamCollabEditLease::getAssetId, assetId));
        evidenceMapper.delete(new LambdaQueryWrapper<TeamMaterialEvidence>()
            .eq(TeamMaterialEvidence::getTeamId, teamId)
            .eq(TeamMaterialEvidence::getAssetType, type)
            .eq(TeamMaterialEvidence::getAssetId, assetId));
    }

    private TeamCollabDocument requireDeletedDocument(Long teamId, Long id, Date cutoff) {
        TeamCollabDocument row = id == null ? null : documentMapper.selectById(id);
        if (row == null || !Objects.equals(row.getTeamId(), teamId)
            || row.getDeletedAt() == null
            || (cutoff != null && row.getDeletedAt().before(cutoff))) {
            throw notFound();
        }
        return row;
    }

    private TeamCollabSpreadsheet requireDeletedSpreadsheet(Long teamId, Long id,
                                                            Date cutoff) {
        TeamCollabSpreadsheet row = id == null ? null : spreadsheetMapper.selectById(id);
        if (row == null || !Objects.equals(row.getTeamId(), teamId)
            || row.getDeletedAt() == null
            || (cutoff != null && row.getDeletedAt().before(cutoff))) {
            throw notFound();
        }
        return row;
    }

    private void requireManager(TeamCollaborationAuthorization.TeamContext team,
                                Long creatorUserId) {
        if (!canManage(team, creatorUserId)) {
            throw new TeamCollabException(TeamCollabException.Code.ASSET_FORBIDDEN,
                "只有队长或资产创建者可以恢复或彻底删除该资产");
        }
    }

    private boolean canManage(TeamCollaborationAuthorization.TeamContext team,
                              Long creatorUserId) {
        return team.isLeader() || Objects.equals(team.userId(), creatorUserId);
    }

    private void requireVersion(Integer current, Integer expected) {
        if (expected != null && !Objects.equals(current, expected)) {
            throw versionConflict();
        }
    }

    private TrashAsset trashAsset(String type, Long id, String title, String visibility,
                                  Integer version, Long creatorUserId, Date deletedAt) {
        String creatorName = "已注销用户";
        if (creatorUserId != null) {
            var user = userService.getById(creatorUserId);
            if (user != null) creatorName = DisplayNameResolver.resolve(user);
        }
        Date expiresAt = deletedAt == null ? null
            : Date.from(deletedAt.toInstant().plus(RETENTION_DAYS, ChronoUnit.DAYS));
        return new TrashAsset(type, id, title, visibility, version,
            creatorName, deletedAt, expiresAt);
    }

    private Date retentionCutoff(Date now) {
        return Date.from(now.toInstant().minus(RETENTION_DAYS, ChronoUnit.DAYS));
    }

    private void enqueueDocument(TeamCollabDocument row) {
        String content = "# " + row.getTitle() + "\n\n" + row.getContentMarkdown();
        indexService.enqueueUpsert("TEAM_DOCUMENT", row.getId(), row.getTitle(), content,
            KgEntity.SCOPE_TEAM, row.getTeamId(), row.getLastEditorUserId());
    }

    private void enqueueSpreadsheet(TeamCollabSpreadsheet row) {
        StringBuilder content = new StringBuilder("# ").append(row.getTitle()).append("\n\n");
        try {
            TeamSpreadsheetSchema.Snapshot snapshot =
                TeamSpreadsheetSchema.validateAndCanonicalize(objectMapper,
                    objectMapper.readTree(row.getColumnsJson()),
                    objectMapper.readTree(row.getRowsJson()));
            for (TeamSpreadsheetSchema.Row dataRow : snapshot.rows()) {
                for (TeamSpreadsheetSchema.Column column : snapshot.columns()) {
                    String value = dataRow.cells().get(column.key());
                    if (value != null && !value.isBlank()) {
                        content.append(column.label()).append(": ")
                            .append(value).append('\n');
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

    private TeamCollabException versionConflict() {
        return new TeamCollabException(TeamCollabException.Code.EDIT_VERSION_CONFLICT,
            "资产版本已变化，请刷新回收站后重试");
    }

    private TeamCollabException notFound() {
        return new TeamCollabException(TeamCollabException.Code.ASSET_NOT_FOUND,
            "协作资产不存在或已超过 30 天恢复期");
    }

    public record TrashAsset(String assetType, Long assetId, String title,
                             String visibility, Integer version, String creatorName,
                             Date deletedAt, Date expiresAt) {
    }
}
