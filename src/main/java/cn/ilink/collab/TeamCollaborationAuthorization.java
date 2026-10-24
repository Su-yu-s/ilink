package cn.ilink.collab;

import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamCollabDocument;
import cn.ilink.entity.TeamCollabSpreadsheet;
import cn.ilink.entity.TeamDemand;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamCollabAclMapper;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.service.TeamMembershipService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;

/**
 * The single authorization boundary for team collaboration assets. Every
 * caller supplies the current URL-scoped team; roles, visibility and ACLs are
 * rebuilt from durable rows and never accepted from a request body.
 */
@Service
public class TeamCollaborationAuthorization {
    public enum Permission { VIEW, EDIT, MANAGE }
    public enum TeamRole { LEADER, MENTOR, STUDENT }

    public record TeamContext(TeamDemand team, Long userId, TeamRole role,
                              boolean canWrite, boolean canExport) {
        public boolean isLeader() {
            return role == TeamRole.LEADER;
        }

        public boolean isMentor() {
            return role == TeamRole.MENTOR;
        }
    }

    public record AssetContext(TeamContext team, String assetType, Long assetId,
                               String visibility, int version, Permission permission,
                               boolean canEdit, boolean canManage) {
    }

    private final TeamDemandMapper teamMapper;
    private final TeamApplicationMapper applicationMapper;
    private final TeamCollabDocumentMapper documentMapper;
    private final TeamCollabSpreadsheetMapper spreadsheetMapper;
    private final TeamCollabAclMapper aclMapper;

    public TeamCollaborationAuthorization(TeamDemandMapper teamMapper,
                                          TeamApplicationMapper applicationMapper,
                                          TeamCollabDocumentMapper documentMapper,
                                          TeamCollabSpreadsheetMapper spreadsheetMapper,
                                          TeamCollabAclMapper aclMapper) {
        this.teamMapper = teamMapper;
        this.applicationMapper = applicationMapper;
        this.documentMapper = documentMapper;
        this.spreadsheetMapper = spreadsheetMapper;
        this.aclMapper = aclMapper;
    }

    public TeamContext requireTeam(Long teamId, Long userId) {
        if (teamId == null || teamId <= 0 || userId == null || userId <= 0) {
            throw new TeamCollabException(TeamCollabException.Code.TEAM_NOT_FOUND,
                "团队不存在");
        }
        TeamDemand team = teamMapper.selectById(teamId);
        if (team == null || "DISSOLVED".equalsIgnoreCase(team.getStatus())) {
            throw new TeamCollabException(TeamCollabException.Code.TEAM_NOT_FOUND,
                "团队不存在或已解散");
        }

        TeamRole role;
        if (Objects.equals(team.getCreatorId(), userId)) {
            role = TeamRole.LEADER;
        } else {
            TeamApplication membership = applicationMapper.selectOne(
                new LambdaQueryWrapper<TeamApplication>()
                    .eq(TeamApplication::getTeamId, teamId)
                    .eq(TeamApplication::getUserId, userId)
                    .eq(TeamApplication::getStatus, TeamMembershipService.STATUS_APPROVED)
                    .last("LIMIT 1"));
            if (membership == null) {
                throw new TeamCollabException(TeamCollabException.Code.ASSET_FORBIDDEN,
                    "你不是该团队的当前成员");
            }
            role = TeamMembershipService.ROLE_MENTOR.equalsIgnoreCase(
                membership.getMemberRole()) ? TeamRole.MENTOR : TeamRole.STUDENT;
        }

        String status = normalize(team.getStatus());
        boolean canWrite = "TEAMING".equals(status);
        boolean canExport = "OPEN".equals(status) || "TEAMING".equals(status)
            || "CLOSED".equals(status);
        return new TeamContext(team, userId, role, canWrite, canExport);
    }

    public TeamContext requireWritableTeam(Long teamId, Long userId) {
        TeamContext context = requireTeam(teamId, userId);
        if (!context.canWrite()) {
            throw new TeamCollabException(TeamCollabException.Code.TEAM_READ_ONLY,
                "团队当前为只读状态，确认组队后才能修改协作资产");
        }
        return context;
    }

    public AssetContext requireAsset(Long teamId, String rawType, Long assetId,
                                     Long userId, Permission required) {
        TeamContext team = requireTeam(teamId, userId);
        String type = assetType(rawType);
        AssetState asset = loadAsset(type, teamId, assetId);
        if (asset.deleted()) {
            throw new TeamCollabException(TeamCollabException.Code.ASSET_NOT_FOUND,
                "协作资产不存在");
        }
        Acl acl = acl(team, type, assetId, asset.visibility(), asset.creatorId());
        Permission effective = acl.permission();
        if (rank(effective) < rank(required)) {
            throw new TeamCollabException(TeamCollabException.Code.ASSET_FORBIDDEN,
                visibilityReason(asset.visibility()));
        }
        return new AssetContext(team, type, assetId, asset.visibility(),
            asset.version(), effective, team.canWrite() && rank(effective) >= rank(Permission.EDIT),
            acl.canManage());
    }

    public AssetContext requireEditableAsset(Long teamId, String type, Long assetId,
                                             Long userId) {
        AssetContext asset = requireAsset(teamId, type, assetId, userId, Permission.EDIT);
        if (!asset.team().canWrite()) {
            throw new TeamCollabException(TeamCollabException.Code.TEAM_READ_ONLY,
                "团队当前为只读状态");
        }
        return asset;
    }

    /** Used by GraphRAG record authorization; failures mean "not visible". */
    public boolean canViewAsset(String type, Long assetId, Long teamId, Long userId) {
        try {
            requireAsset(teamId, type, assetId, userId, Permission.VIEW);
            return true;
        } catch (TeamCollabException e) {
            return false;
        }
    }

    public boolean canViewTeamFile(Long fileId, Long teamId, Long userId) {
        TeamContext team;
        try {
            team = requireTeam(teamId, userId);
        } catch (TeamCollabException e) {
            return false;
        }
        return team != null;
    }

    public Acl acl(TeamContext team, String rawType, Long assetId,
                   String visibility, Long creatorId) {
        String type = assetType(rawType);
        if (team.isLeader()) {
            return new Acl(Permission.MANAGE, true);
        }
        String explicit = aclMapper.selectList(new LambdaQueryWrapper<cn.ilink.entity.TeamCollabAcl>()
                .eq(cn.ilink.entity.TeamCollabAcl::getTeamId, team.team().getId())
                .eq(cn.ilink.entity.TeamCollabAcl::getAssetType, type)
                .eq(cn.ilink.entity.TeamCollabAcl::getAssetId, assetId)
                .eq(cn.ilink.entity.TeamCollabAcl::getUserId, team.userId()))
            .stream().map(cn.ilink.entity.TeamCollabAcl::getPermission).findFirst().orElse(null);
        Permission explicitPermission = parsePermission(explicit);
        boolean isCreator = Objects.equals(creatorId, team.userId());
        if (TeamCollabDocument.VISIBILITY_TEAM.equals(visibility)) {
            Permission permission = max(explicitPermission, Permission.VIEW);
            if (team.canWrite()) permission = max(permission, Permission.EDIT);
            if (isCreator && team.canWrite()) permission = max(permission, Permission.MANAGE);
            return new Acl(permission, permission == Permission.MANAGE);
        }
        if (isCreator) {
            return new Acl(team.canWrite() ? Permission.MANAGE : Permission.VIEW, true);
        }
        if (explicitPermission == null) {
            return new Acl(null, false);
        }
        return new Acl(explicitPermission, explicitPermission == Permission.MANAGE);
    }

    /** Manage access is limited to leaders and the asset creator in this phase. */
    public void requireManage(AssetContext asset) {
        if (!asset.canManage()) {
            throw new TeamCollabException(TeamCollabException.Code.ASSET_FORBIDDEN,
                "只有队长或资产创建者可以管理该资产");
        }
    }

    public String assetType(String raw) {
        String type = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if ("DOCUMENT".equals(type) || "DOC".equals(type)) return "DOCUMENT";
        if ("SPREADSHEET".equals(type) || "SHEET".equals(type) || "TABLE".equals(type)) {
            return "SPREADSHEET";
        }
        throw new TeamCollabException(TeamCollabException.Code.REQUEST_INVALID,
            "协作资产类型不合法");
    }

    private AssetState loadAsset(String type, Long teamId, Long assetId) {
        if (assetId == null || assetId <= 0) {
            throw new TeamCollabException(TeamCollabException.Code.ASSET_NOT_FOUND,
                "协作资产不存在");
        }
        if ("DOCUMENT".equals(type)) {
            TeamCollabDocument row = documentMapper.selectById(assetId);
            if (row == null || !Objects.equals(row.getTeamId(), teamId)) {
                throw new TeamCollabException(TeamCollabException.Code.ASSET_NOT_FOUND,
                    "文档不存在");
            }
            return new AssetState(row.getDeletedAt() != null, row.getVisibility(),
                row.getCreatorUserId(), row.getVersion() == null ? 1 : row.getVersion());
        }
        TeamCollabSpreadsheet row = spreadsheetMapper.selectById(assetId);
        if (row == null || !Objects.equals(row.getTeamId(), teamId)) {
            throw new TeamCollabException(TeamCollabException.Code.ASSET_NOT_FOUND,
                "表格不存在");
        }
        return new AssetState(row.getDeletedAt() != null, row.getVisibility(),
            row.getCreatorUserId(), row.getVersion() == null ? 1 : row.getVersion());
    }

    private static Permission parsePermission(String raw) {
        if (raw == null) return null;
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "MANAGE" -> Permission.MANAGE;
            case "EDIT" -> Permission.EDIT;
            case "VIEW" -> Permission.VIEW;
            default -> null;
        };
    }

    private static Permission max(Permission left, Permission right) {
        if (left == null) return right;
        if (right == null) return left;
        return rank(left) >= rank(right) ? left : right;
    }

    private static int rank(Permission permission) {
        if (permission == null) return -1;
        return switch (permission) {
            case VIEW -> 1;
            case EDIT -> 2;
            case MANAGE -> 3;
        };
    }

    private static String visibilityReason(String visibility) {
        return TeamCollabDocument.VISIBILITY_RESTRICTED.equals(visibility)
            ? "该资产仅对获得授权的成员开放" : "无权限访问该协作资产";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record AssetState(boolean deleted, String visibility, Long creatorId, int version) {
    }

    public record Acl(Permission permission, boolean canManage) {
    }
}
