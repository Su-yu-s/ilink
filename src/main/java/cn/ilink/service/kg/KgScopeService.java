package cn.ilink.service.kg;

import cn.ilink.collab.TeamCollaborationAuthorization;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgDocEntity;
import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.TeamLibraryFile;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamLibraryFileMapper;
import cn.ilink.mapper.KgDocEntityMapper;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.mapper.TeamAgentSourceAuthorizationMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.mapper.TeamTaskMapper;
import cn.ilink.service.TeamAccessService;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import cn.ilink.teamagent.TeamAgentScope;
import cn.ilink.teamagent.TeamAgentScopeContext;
import cn.ilink.teamagent.TeamTaskPolicy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * KG 可见性服务（团队私有子图的核心）：
 * 一次查询拿到用户可见的团队 ID 集合，供各工具在 SQL 层拼接 scope/team_id/user_id 过滤。
 * 可见规则：GLOBAL 全员可见；TEAM 仅成员（队长或已通过的申请者）可见；PRIVATE 仅属主可见。
 */
@Service
public class KgScopeService {

    private final TeamAccessService teamAccessService;
    private final TeamDemandServiceImpl teamDemandService;
    private final TeamApplicationServiceImpl teamApplicationService;
    private final KgDocEntityMapper docEntityMapper;
    private final TeamTaskMapper teamTaskMapper;
    private final TeamDemandMapper teamDemandMapper;
    private final TeamTaskPolicy taskPolicy;
    private final TeamAgentSourceAuthorizationMapper agentSourceAuthorizationMapper;
    private final KgDocumentChunkMapper chunkMapper;

    /**
     * Collaboration asset authorization is resolved lazily. The collab package
     * depends on KG indexing, so a direct constructor reference here would form
     * a bean cycle.
     */
    @Autowired(required = false)
    private ObjectProvider<TeamCollaborationAuthorization> collaborationAuthorization;
    @Autowired(required = false)
    private TeamLibraryFileMapper teamLibraryFileMapper;

    @Autowired
    public KgScopeService(TeamAccessService teamAccessService,
                          TeamDemandServiceImpl teamDemandService,
                          TeamApplicationServiceImpl teamApplicationService,
                          KgDocEntityMapper docEntityMapper,
                          TeamTaskMapper teamTaskMapper,
                          TeamDemandMapper teamDemandMapper,
                          TeamTaskPolicy taskPolicy,
                          TeamAgentSourceAuthorizationMapper agentSourceAuthorizationMapper,
                          KgDocumentChunkMapper chunkMapper) {
        this.teamAccessService = teamAccessService;
        this.teamDemandService = teamDemandService;
        this.teamApplicationService = teamApplicationService;
        this.docEntityMapper = docEntityMapper;
        this.teamTaskMapper = teamTaskMapper;
        this.teamDemandMapper = teamDemandMapper;
        this.taskPolicy = taskPolicy;
        this.agentSourceAuthorizationMapper = agentSourceAuthorizationMapper;
        this.chunkMapper = chunkMapper;
    }

    /** Compatibility constructor for isolated scope tests. */
    public KgScopeService(TeamAccessService teamAccessService,
                          TeamDemandServiceImpl teamDemandService,
                          TeamApplicationServiceImpl teamApplicationService) {
        this(teamAccessService, teamDemandService, teamApplicationService,
            null, null, null, null, null, null);
    }

    /** 用户可见的团队 ID 列表（队长身份 + 已通过的申请） */
    public List<Long> visibleTeamIds(Long userId) {
        TeamAgentScope scope = TeamAgentScopeContext.current();
        if (scope != null) {
            return java.util.Objects.equals(scope.userId(), userId)
                ? Collections.singletonList(scope.teamId()) : Collections.emptyList();
        }
        if (userId == null || userId <= 0) {
            return Collections.emptyList();
        }
        List<Long> teamIds = new ArrayList<>();
        teamDemandService.list(new LambdaQueryWrapper<TeamDemand>()
                .eq(TeamDemand::getCreatorId, userId).select(TeamDemand::getId))
            .forEach(team -> teamIds.add(team.getId()));
        teamApplicationService.list(new LambdaQueryWrapper<TeamApplication>()
                .eq(TeamApplication::getUserId, userId)
                .eq(TeamApplication::getStatus, "APPROVED").select(TeamApplication::getTeamId))
            .forEach(app -> teamIds.add(app.getTeamId()));
        return teamIds;
    }

    /**
     * 判断某实体对用户是否可见。
     * GLOBAL 放行；TEAM 要求 team_id 在用户可见团队里；PRIVATE 要求 user_id 匹配。
     */
    public boolean canSee(KgEntity entity, Long userId) {
        if (entity == null) {
            return false;
        }
        if (!canSee(entity.getScope(), entity.getTeamId(), entity.getUserId(), userId)) {
            return false;
        }
        TeamAgentScope scope = TeamAgentScopeContext.current();
        if (scope == null || docEntityMapper == null) {
            return true;
        }
        List<KgDocEntity> mappings = docEntityMapper.selectList(
            new LambdaQueryWrapper<KgDocEntity>().eq(KgDocEntity::getEntityId, entity.getId()));
        if (mappings == null || mappings.isEmpty()) {
            return KgEntity.SCOPE_GLOBAL.equals(entity.getScope());
        }
        for (KgDocEntity mapping : mappings) {
            if (canSeeDocument(mapping.getDocType(), mapping.getDocId(), scope)) {
                return true;
            }
        }
        return false;
    }

    public boolean canSeeChunk(KgDocumentChunk chunk, Long userId) {
        if (chunk == null || !canSee(chunk.getScope(), chunk.getTeamId(),
            chunk.getUserId(), userId)) {
            return false;
        }
        TeamAgentScope scope = TeamAgentScopeContext.current();
        return scope == null || canSeeDocument(chunk.getDocType(), chunk.getDocId(), scope);
    }

    /** The same visibility rule for source chunks and graph relations. */
    public boolean canSee(String scope, Long teamId, Long ownerId, Long userId) {
        TeamAgentScope agentScope = TeamAgentScopeContext.current();
        if (agentScope != null) {
            if (!java.util.Objects.equals(agentScope.userId(), userId)
                || KgEntity.SCOPE_PRIVATE.equals(scope)) {
                return false;
            }
            if (KgEntity.SCOPE_GLOBAL.equals(scope)) {
                return true;
            }
            return KgEntity.SCOPE_TEAM.equals(scope)
                && java.util.Objects.equals(agentScope.teamId(), teamId);
        }
        if (KgEntity.SCOPE_GLOBAL.equals(scope)) {
            return true;
        }
        if (userId == null || userId <= 0) {
            return false;
        }
        if (KgEntity.SCOPE_PRIVATE.equals(scope)) {
            return ownerId != null && ownerId.equals(userId);
        }
        if (KgEntity.SCOPE_TEAM.equals(scope)) {
            return teamId != null && teamAccessService.isTeamParticipant(teamId, userId);
        }
        return false;
    }

    /** Record-level authorization for indexed TASK sources inside one team. */
    public boolean canSeeDocument(String docType, Long docId, TeamAgentScope scope) {
        if (scope == null || docType == null || docId == null) {
            return false;
        }
        if (!"TASK".equalsIgnoreCase(docType)) {
            return canSeeNonTaskDocument(docType, docId, scope);
        }
        if (teamTaskMapper == null || teamDemandMapper == null || taskPolicy == null) {
            return false;
        }
        TeamTask task = teamTaskMapper.selectById(docId);
        if (task == null || !java.util.Objects.equals(scope.teamId(), task.getTeamId())) {
            return false;
        }
        TeamDemand team = teamDemandMapper.selectById(scope.teamId());
        if (team == null || !scope.teamId().equals(team.getId())) {
            return false;
        }
        User user = new User();
        user.setId(scope.userId());
        return taskPolicy.canReadTask(team, task, user);
    }

    /** Public member citation authorization for the current team only. */
    public boolean canSeeTeamMember(Long memberId, TeamAgentScope scope) {
        if (scope == null || memberId == null || teamDemandMapper == null
            || teamApplicationService == null) {
            return false;
        }
        TeamDemand team = teamDemandMapper.selectById(scope.teamId());
        if (team == null) return false;
        if (java.util.Objects.equals(team.getCreatorId(), memberId)) return true;
        return teamApplicationService.count(new LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, scope.teamId())
            .eq(TeamApplication::getUserId, memberId)
            .eq(TeamApplication::getStatus, "APPROVED")) > 0;
    }

    private boolean canSeeGlobalDocument(String docType, Long docId, TeamAgentScope scope) {
        if (scope.competitionId() == null || scope.catalogCompetitionName() == null) {
            return false;
        }
        if ("COMPETITION".equalsIgnoreCase(docType)) {
            return Long.valueOf(scope.competitionId()).equals(docId);
        }
        return agentSourceAuthorizationMapper != null
            && agentSourceAuthorizationMapper.countCompetitionLinkedSource(
                docType.toUpperCase(java.util.Locale.ROOT), docId,
                scope.competitionId().longValue()) > 0;
    }

    private boolean canSeeNonTaskDocument(String docType, Long docId, TeamAgentScope scope) {
        if ("TEAM_FILE".equalsIgnoreCase(docType)) {
            return canSeeTeamLibraryFile(docId, scope);
        }
        if ("TEAM_DOCUMENT".equalsIgnoreCase(docType)
            || "TEAM_SPREADSHEET".equalsIgnoreCase(docType)) {
            return canSeeCollaborationAsset(docType, docId, scope);
        }
        if (chunkMapper != null) {
            KgDocumentChunk chunk = chunkMapper.selectOne(
                new LambdaQueryWrapper<KgDocumentChunk>()
                    .eq(KgDocumentChunk::getDocType, docType)
                    .eq(KgDocumentChunk::getDocId, docId)
                    .last("LIMIT 1"));
            if (chunk != null) {
                if (KgEntity.SCOPE_TEAM.equals(chunk.getScope())) {
                    return java.util.Objects.equals(scope.teamId(), chunk.getTeamId());
                }
                if (KgEntity.SCOPE_PRIVATE.equals(chunk.getScope())) {
                    return false;
                }
            }
        }
        return canSeeGlobalDocument(docType, docId, scope);
    }

    /** Recycle-bin, purged and cross-team files are invisible to every graph path. */
    private boolean canSeeTeamLibraryFile(Long fileId, TeamAgentScope scope) {
        if (teamLibraryFileMapper == null || fileId == null) {
            return false;
        }
        TeamLibraryFile file = teamLibraryFileMapper.selectById(fileId);
        return file != null
            && java.util.Objects.equals(scope.teamId(), file.getTeamId())
            && !Boolean.TRUE.equals(file.getIsDeleted())
            && !Boolean.TRUE.equals(file.getIsPurged());
    }

    /** RESTRICTED assets stay hidden until the current Agent user has explicit VIEW. */
    private boolean canSeeCollaborationAsset(String docType, Long docId, TeamAgentScope scope) {
        TeamCollaborationAuthorization authorization =
            collaborationAuthorization == null ? null : collaborationAuthorization.getIfAvailable();
        if (authorization == null) {
            return false;
        }
        String assetType = "TEAM_SPREADSHEET".equalsIgnoreCase(docType) ? "SPREADSHEET" : "DOCUMENT";
        return authorization.canViewAsset(assetType, docId, scope.teamId(), scope.userId());
    }
}
