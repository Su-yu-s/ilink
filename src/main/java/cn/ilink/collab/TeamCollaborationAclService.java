package cn.ilink.collab;

import cn.ilink.entity.TeamCollabAcl;
import cn.ilink.mapper.TeamCollabAclMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit grants for RESTRICTED assets. Leaders and the asset creator may
 * grant; every mutation re-verifies the asset against the URL-scoped team so a
 * forged asset id from another team can never receive an ACL row.
 */
@Service
public class TeamCollaborationAclService {
    private static final Set<String> PERMISSIONS = Set.of("VIEW", "EDIT", "MANAGE");

    private final TeamCollabAclMapper aclMapper;
    private final TeamCollaborationAuthorization authorization;
    private final TeamCollaborationEventService eventService;
    private final cn.ilink.service.TeamMembershipService membershipService;
    private final cn.ilink.service.UserService userService;

    public TeamCollaborationAclService(TeamCollabAclMapper aclMapper,
                                       TeamCollaborationAuthorization authorization,
                                       TeamCollaborationEventService eventService,
                                       cn.ilink.service.TeamMembershipService membershipService,
                                       cn.ilink.service.UserService userService) {
        this.aclMapper = aclMapper;
        this.authorization = authorization;
        this.eventService = eventService;
        this.membershipService = membershipService;
        this.userService = userService;
    }

    public List<GrantView> list(Long teamId, String rawType, Long assetId, Long userId) {
        authorization.requireAsset(teamId, rawType, assetId, userId,
            TeamCollaborationAuthorization.Permission.MANAGE);
        String type = authorization.assetType(rawType);
        return aclMapper.selectList(new LambdaQueryWrapper<TeamCollabAcl>()
                .eq(TeamCollabAcl::getTeamId, teamId)
                .eq(TeamCollabAcl::getAssetType, type)
                .eq(TeamCollabAcl::getAssetId, assetId)
                .orderByAsc(TeamCollabAcl::getUserId))
            .stream().map(row -> new GrantView(row.getUserId(),
                displayName(row.getUserId()), row.getPermission(), row.getCreatedAt()))
            .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public GrantView grant(Long teamId, String rawType, Long assetId, Long userId,
                           Long targetUserId, String permission) {
        authorization.requireAsset(teamId, rawType, assetId, userId,
            TeamCollaborationAuthorization.Permission.MANAGE);
        String type = authorization.assetType(rawType);
        String normalized = permission == null ? "" : permission.trim().toUpperCase(Locale.ROOT);
        if (!PERMISSIONS.contains(normalized)) {
            throw new TeamCollabException(TeamCollabException.Code.REQUEST_INVALID,
                "授权级别仅支持 VIEW/EDIT/MANAGE");
        }
        if (targetUserId == null || targetUserId <= 0) {
            throw new TeamCollabException(TeamCollabException.Code.REQUEST_INVALID,
                "请选择被授权成员");
        }
        if (!membershipService.isApprovedMember(teamId, targetUserId)) {
            throw new TeamCollabException(TeamCollabException.Code.REQUEST_INVALID,
                "只能授权给当前团队成员");
        }
        TeamCollabAcl existing = aclMapper.selectOne(new LambdaQueryWrapper<TeamCollabAcl>()
            .eq(TeamCollabAcl::getAssetType, type)
            .eq(TeamCollabAcl::getAssetId, assetId)
            .eq(TeamCollabAcl::getUserId, targetUserId)
            .last("LIMIT 1"));
        Date now = new Date();
        if (existing == null) {
            TeamCollabAcl row = new TeamCollabAcl();
            row.setTeamId(teamId);
            row.setAssetType(type);
            row.setAssetId(assetId);
            row.setUserId(targetUserId);
            row.setPermission(normalized);
            row.setGrantedBy(userId);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            aclMapper.insert(row);
        } else {
            if (!Objects.equals(existing.getTeamId(), teamId)) {
                throw new TeamCollabException(TeamCollabException.Code.AGENT_ACTION_CONFLICT,
                    "授权记录归属异常");
            }
            existing.setPermission(normalized);
            existing.setGrantedBy(userId);
            existing.setUpdatedAt(now);
            aclMapper.updateById(existing);
        }
        eventService.append(teamId, type, assetId, userId, "ACL_GRANTED", "SUCCESS", null, null,
            java.util.Map.of("permission", normalized, "targetUserId", targetUserId));
        return new GrantView(targetUserId, displayName(targetUserId), normalized, now);
    }

    @Transactional(rollbackFor = Exception.class)
    public int revoke(Long teamId, String rawType, Long assetId, Long userId, Long targetUserId) {
        authorization.requireAsset(teamId, rawType, assetId, userId,
            TeamCollaborationAuthorization.Permission.MANAGE);
        String type = authorization.assetType(rawType);
        int removed = aclMapper.delete(new LambdaQueryWrapper<TeamCollabAcl>()
            .eq(TeamCollabAcl::getTeamId, teamId)
            .eq(TeamCollabAcl::getAssetType, type)
            .eq(TeamCollabAcl::getAssetId, assetId)
            .eq(TeamCollabAcl::getUserId, targetUserId));
        if (removed > 0) {
            eventService.append(teamId, type, assetId, userId, "ACL_REVOKED", "SUCCESS", null, null,
                java.util.Map.of("targetUserId", targetUserId));
        }
        return removed;
    }

    /** Only current members can keep a grant; used when a member leaves the team. */
    @Transactional(rollbackFor = Exception.class)
    public int invalidateUser(Long teamId, Long userId) {
        return aclMapper.delete(new LambdaQueryWrapper<TeamCollabAcl>()
            .eq(TeamCollabAcl::getTeamId, teamId)
            .eq(TeamCollabAcl::getUserId, userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public int deleteAllForTeam(Long teamId) {
        return aclMapper.delete(new LambdaQueryWrapper<TeamCollabAcl>()
            .eq(TeamCollabAcl::getTeamId, teamId));
    }

    private String displayName(Long userId) {
        cn.ilink.entity.User user = userId == null ? null : userService.getById(userId);
        return user == null ? "已注销用户"
            : cn.ilink.util.DisplayNameResolver.resolve(user);
    }

    public record GrantView(Long userId, String displayName, String permission, Date grantedAt) {
    }
}
