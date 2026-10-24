package cn.ilink.collab;

import cn.ilink.entity.TeamCollabAcl;
import cn.ilink.entity.TeamCollabDocument;
import cn.ilink.entity.TeamCollabDocumentVersion;
import cn.ilink.entity.TeamCollabEditLease;
import cn.ilink.entity.TeamCollabEvent;
import cn.ilink.entity.TeamCollabSpreadsheet;
import cn.ilink.entity.TeamCollabSpreadsheetVersion;
import cn.ilink.entity.TeamMaterialEvidence;
import cn.ilink.entity.TeamMaterialRequirement;
import cn.ilink.entity.TeamMaterialReview;
import cn.ilink.mapper.TeamCollabAclMapper;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabDocumentVersionMapper;
import cn.ilink.mapper.TeamCollabEditLeaseMapper;
import cn.ilink.mapper.TeamCollabEventMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetVersionMapper;
import cn.ilink.mapper.TeamMaterialEvidenceMapper;
import cn.ilink.mapper.TeamMaterialRequirementMapper;
import cn.ilink.mapper.TeamMaterialReviewMapper;
import cn.ilink.service.kg.KgIndexService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * Lifecycle cleanup for M3 collaboration data. Team deletion removes every
 * owned row; account deletion only anonymizes actor references because the
 * assets themselves belong to the team, not to a user.
 */
@Service
@Slf4j
public class TeamCollaborationCleanupService {
    private final TeamCollabDocumentMapper documentMapper;
    private final TeamCollabDocumentVersionMapper documentVersionMapper;
    private final TeamCollabSpreadsheetMapper spreadsheetMapper;
    private final TeamCollabSpreadsheetVersionMapper spreadsheetVersionMapper;
    private final TeamCollabAclMapper aclMapper;
    private final TeamCollabEditLeaseMapper leaseMapper;
    private final TeamCollabEventMapper eventMapper;
    private final TeamMaterialRequirementMapper requirementMapper;
    private final TeamMaterialEvidenceMapper evidenceMapper;
    private final TeamMaterialReviewMapper reviewMapper;
    private final KgIndexService indexService;

    public TeamCollaborationCleanupService(TeamCollabDocumentMapper documentMapper,
                                           TeamCollabDocumentVersionMapper documentVersionMapper,
                                           TeamCollabSpreadsheetMapper spreadsheetMapper,
                                           TeamCollabSpreadsheetVersionMapper spreadsheetVersionMapper,
                                           TeamCollabAclMapper aclMapper,
                                           TeamCollabEditLeaseMapper leaseMapper,
                                           TeamCollabEventMapper eventMapper,
                                           TeamMaterialRequirementMapper requirementMapper,
                                           TeamMaterialEvidenceMapper evidenceMapper,
                                           TeamMaterialReviewMapper reviewMapper,
                                           KgIndexService indexService) {
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.spreadsheetMapper = spreadsheetMapper;
        this.spreadsheetVersionMapper = spreadsheetVersionMapper;
        this.aclMapper = aclMapper;
        this.leaseMapper = leaseMapper;
        this.eventMapper = eventMapper;
        this.requirementMapper = requirementMapper;
        this.evidenceMapper = evidenceMapper;
        this.reviewMapper = reviewMapper;
        this.indexService = indexService;
    }

    /**
     * Hard delete for a team. Version rows are removed by team_id so snapshots
     * whose parent asset was already soft-deleted cannot survive as orphans.
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteTeamData(Long teamId) {
        if (teamId == null) return 0;
        List<Long> documentIds = documentMapper.selectList(
                new LambdaQueryWrapper<TeamCollabDocument>()
                    .eq(TeamCollabDocument::getTeamId, teamId)
                    .select(TeamCollabDocument::getId))
            .stream().map(TeamCollabDocument::getId).toList();
        List<Long> spreadsheetIds = spreadsheetMapper.selectList(
                new LambdaQueryWrapper<TeamCollabSpreadsheet>()
                    .eq(TeamCollabSpreadsheet::getTeamId, teamId)
                    .select(TeamCollabSpreadsheet::getId))
            .stream().map(TeamCollabSpreadsheet::getId).toList();
        documentIds.forEach(id -> indexService.enqueueDelete("TEAM_DOCUMENT", id,
            cn.ilink.entity.KgEntity.SCOPE_TEAM, teamId, null));
        spreadsheetIds.forEach(id -> indexService.enqueueDelete("TEAM_SPREADSHEET", id,
            cn.ilink.entity.KgEntity.SCOPE_TEAM, teamId, null));
        documentVersionMapper.delete(new LambdaQueryWrapper<TeamCollabDocumentVersion>()
            .eq(TeamCollabDocumentVersion::getTeamId, teamId));
        spreadsheetVersionMapper.delete(new LambdaQueryWrapper<TeamCollabSpreadsheetVersion>()
            .eq(TeamCollabSpreadsheetVersion::getTeamId, teamId));
        documentMapper.delete(new LambdaQueryWrapper<TeamCollabDocument>()
            .eq(TeamCollabDocument::getTeamId, teamId));
        spreadsheetMapper.delete(new LambdaQueryWrapper<TeamCollabSpreadsheet>()
            .eq(TeamCollabSpreadsheet::getTeamId, teamId));
        evidenceMapper.delete(new LambdaQueryWrapper<TeamMaterialEvidence>()
            .eq(TeamMaterialEvidence::getTeamId, teamId));
        reviewMapper.delete(new LambdaQueryWrapper<TeamMaterialReview>()
            .eq(TeamMaterialReview::getTeamId, teamId));
        requirementMapper.delete(new LambdaQueryWrapper<TeamMaterialRequirement>()
            .eq(TeamMaterialRequirement::getTeamId, teamId));
        aclMapper.delete(new LambdaQueryWrapper<TeamCollabAcl>()
            .eq(TeamCollabAcl::getTeamId, teamId));
        leaseMapper.delete(new LambdaQueryWrapper<TeamCollabEditLease>()
            .eq(TeamCollabEditLease::getTeamId, teamId));
        return eventMapper.delete(new LambdaQueryWrapper<TeamCollabEvent>()
            .eq(TeamCollabEvent::getTeamId, teamId));
    }

    /**
     * A member who leaves must lose every explicit collaboration grant, lease
     * and pending material-review capability in the same transaction as the
     * membership update. The authorization layer is still the final guard;
     * this cleanup prevents stale rows from becoming valid again on rejoin.
     */
    @Transactional(rollbackFor = Exception.class)
    public int revokeMemberAccess(Long teamId, Long userId) {
        if (teamId == null || userId == null) return 0;
        int changed = aclMapper.delete(new LambdaQueryWrapper<TeamCollabAcl>()
            .eq(TeamCollabAcl::getTeamId, teamId)
            .eq(TeamCollabAcl::getUserId, userId));
        changed += leaseMapper.delete(new LambdaQueryWrapper<TeamCollabEditLease>()
            .eq(TeamCollabEditLease::getTeamId, teamId)
            .eq(TeamCollabEditLease::getUserId, userId));
        return changed;
    }

    /** Closing or dissolving a team immediately releases every editing lease. */
    @Transactional(rollbackFor = Exception.class)
    public int invalidateTeamLeases(Long teamId) {
        if (teamId == null) return 0;
        return leaseMapper.delete(new LambdaQueryWrapper<TeamCollabEditLease>()
            .eq(TeamCollabEditLease::getTeamId, teamId));
    }

    /**
     * Account deletion keeps team assets but drops every attribution to the
     * removed user, which then renders as "已注销用户".
     */
    @Transactional(rollbackFor = Exception.class)
    public int anonymizeUser(Long userId) {
        if (userId == null) return 0;
        int changed = 0;
        changed += documentMapper.update(null, new LambdaUpdateWrapper<TeamCollabDocument>()
            .eq(TeamCollabDocument::getCreatorUserId, userId)
            .set(TeamCollabDocument::getCreatorUserId, null));
        changed += documentMapper.update(null, new LambdaUpdateWrapper<TeamCollabDocument>()
            .eq(TeamCollabDocument::getLastEditorUserId, userId)
            .set(TeamCollabDocument::getLastEditorUserId, null));
        changed += documentVersionMapper.update(null,
            new LambdaUpdateWrapper<TeamCollabDocumentVersion>()
                .eq(TeamCollabDocumentVersion::getActorUserId, userId)
                .set(TeamCollabDocumentVersion::getActorUserId, null));
        changed += spreadsheetMapper.update(null,
            new LambdaUpdateWrapper<TeamCollabSpreadsheet>()
                .eq(TeamCollabSpreadsheet::getCreatorUserId, userId)
                .set(TeamCollabSpreadsheet::getCreatorUserId, null));
        changed += spreadsheetMapper.update(null,
            new LambdaUpdateWrapper<TeamCollabSpreadsheet>()
                .eq(TeamCollabSpreadsheet::getLastEditorUserId, userId)
                .set(TeamCollabSpreadsheet::getLastEditorUserId, null));
        changed += spreadsheetVersionMapper.update(null,
            new LambdaUpdateWrapper<TeamCollabSpreadsheetVersion>()
                .eq(TeamCollabSpreadsheetVersion::getActorUserId, userId)
                .set(TeamCollabSpreadsheetVersion::getActorUserId, null));
        changed += eventMapper.update(null, new LambdaUpdateWrapper<TeamCollabEvent>()
            .eq(TeamCollabEvent::getActorUserId, userId)
            .set(TeamCollabEvent::getActorUserId, null));
        changed += requirementMapper.update(null, new LambdaUpdateWrapper<TeamMaterialRequirement>()
            .eq(TeamMaterialRequirement::getCreatedBy, userId)
            .set(TeamMaterialRequirement::getCreatedBy, null));
        changed += requirementMapper.update(null, new LambdaUpdateWrapper<TeamMaterialRequirement>()
            .eq(TeamMaterialRequirement::getUpdatedBy, userId)
            .set(TeamMaterialRequirement::getUpdatedBy, null));
        changed += evidenceMapper.update(null, new LambdaUpdateWrapper<TeamMaterialEvidence>()
            .eq(TeamMaterialEvidence::getCreatedBy, userId)
            .set(TeamMaterialEvidence::getCreatedBy, null));
        changed += reviewMapper.update(null, new LambdaUpdateWrapper<TeamMaterialReview>()
            .eq(TeamMaterialReview::getReviewedBy, userId)
            .set(TeamMaterialReview::getReviewedBy, null));
        // Explicit grants lose their granter but must not keep a dead user's
        // access row; ACLs keyed on the user are removed outright.
        changed += aclMapper.delete(new LambdaQueryWrapper<TeamCollabAcl>()
            .eq(TeamCollabAcl::getUserId, userId));
        changed += aclMapper.update(null, new LambdaUpdateWrapper<TeamCollabAcl>()
            .eq(TeamCollabAcl::getGrantedBy, userId)
            .set(TeamCollabAcl::getGrantedBy, null));
        changed += leaseMapper.delete(new LambdaQueryWrapper<TeamCollabEditLease>()
            .eq(TeamCollabEditLease::getUserId, userId));
        return changed;
    }

    /** Expired leases are takeover candidates, but stale rows are removed nightly. */
    @Transactional(rollbackFor = Exception.class)
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 15 3 * * ?")
    public int purgeExpiredLeases() {
        try {
            return leaseMapper.delete(new LambdaQueryWrapper<TeamCollabEditLease>()
                .lt(TeamCollabEditLease::getExpiresAt, new Date()));
        } catch (Exception e) {
            log.warn("清理过期协作编辑租约失败: {}", e.getMessage());
            return 0;
        }
    }
}
