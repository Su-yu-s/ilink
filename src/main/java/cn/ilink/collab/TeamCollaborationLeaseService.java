package cn.ilink.collab;

import cn.ilink.entity.TeamCollabEditLease;
import cn.ilink.mapper.TeamCollabEditLeaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;

/** 90-second single-editor lease with hashed tokens and atomic takeover. */
@Service
public class TeamCollaborationLeaseService {
    public static final int LEASE_SECONDS = 90;
    private final TeamCollabEditLeaseMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public TeamCollaborationLeaseService(TeamCollabEditLeaseMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public LeaseView acquire(Long teamId, String assetType, Long assetId, Long userId,
                             int baseVersion) {
        TeamCollabEditLease existing = mapper.selectByAssetForUpdate(assetType, assetId);
        Date now = new Date();
        String token = token();
        if (existing == null) {
            TeamCollabEditLease row = new TeamCollabEditLease();
            row.setTeamId(teamId);
            row.setAssetType(assetType);
            row.setAssetId(assetId);
            row.setUserId(userId);
            row.setTokenHash(hash(token));
            row.setBaseVersion(baseVersion);
            row.setAcquiredAt(now);
            row.setRenewedAt(now);
            row.setExpiresAt(expiry(now));
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException race) {
                throw held();
            }
            return view(token, row);
        }
        if (!Objects.equals(existing.getTeamId(), teamId)) throw held();
        if (!Objects.equals(existing.getUserId(), userId) && existing.getExpiresAt() != null
            && existing.getExpiresAt().after(now)) {
            throw held();
        }
        existing.setUserId(userId);
        existing.setTokenHash(hash(token));
        existing.setBaseVersion(baseVersion);
        existing.setAcquiredAt(now);
        existing.setRenewedAt(now);
        existing.setExpiresAt(expiry(now));
        if (mapper.updateById(existing) != 1) throw held();
        return view(token, existing);
    }

    /**
     * Agent write gate. A lease already held by the same user remains intact;
     * in that case the caller must present its live token. Otherwise a short
     * lease is acquired for this write and must be released afterwards.
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentWriteLease acquireForAgentWrite(Long teamId, String assetType, Long assetId,
                                                Long userId, int baseVersion,
                                                String existingToken) {
        TeamCollabEditLease existing = mapper.selectByAssetForUpdate(assetType, assetId);
        Date now = new Date();
        if (existing != null && Objects.equals(existing.getTeamId(), teamId)
            && Objects.equals(existing.getUserId(), userId)
            && existing.getExpiresAt() != null && existing.getExpiresAt().after(now)) {
            requireOwned(existing, teamId, userId, existingToken);
            return new AgentWriteLease(existingToken, false);
        }
        return new AgentWriteLease(
            acquire(teamId, assetType, assetId, userId, baseVersion).leaseToken(), true);
    }

    @Transactional(rollbackFor = Exception.class)
    public LeaseView renew(Long teamId, String assetType, Long assetId, Long userId,
                           String token, int baseVersion) {
        TeamCollabEditLease row = mapper.selectByAssetForUpdate(assetType, assetId);
        requireOwned(row, teamId, userId, token);
        Date now = new Date();
        row.setBaseVersion(baseVersion);
        row.setRenewedAt(now);
        row.setExpiresAt(expiry(now));
        if (mapper.updateById(row) != 1) {
            throw new TeamCollabException(TeamCollabException.Code.EDIT_LEASE_INVALID,
                "编辑租约已失效");
        }
        return view(token, row);
    }

    @Transactional(rollbackFor = Exception.class)
    public void release(Long teamId, String assetType, Long assetId, Long userId,
                        String token) {
        TeamCollabEditLease row = mapper.selectByAssetForUpdate(assetType, assetId);
        if (row == null) return;
        requireOwned(row, teamId, userId, token);
        mapper.deleteById(row.getId());
    }

    /**
     * Save-path validation. Call inside the business transaction before writing.
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public TeamCollabEditLease requireHeld(Long teamId, String assetType, Long assetId,
                                           Long userId, String token) {
        TeamCollabEditLease row = mapper.selectByAssetForUpdate(assetType, assetId);
        requireOwned(row, teamId, userId, token);
        return row;
    }

    public TeamCollabEditLease current(String assetType, Long assetId) {
        return mapper.selectOne(new LambdaQueryWrapper<TeamCollabEditLease>()
            .eq(TeamCollabEditLease::getAssetType, assetType)
            .eq(TeamCollabEditLease::getAssetId, assetId)
            .last("LIMIT 1"));
    }

    @Transactional(rollbackFor = Exception.class)
    public int invalidateAsset(String assetType, Long assetId) {
        return mapper.delete(new LambdaQueryWrapper<TeamCollabEditLease>()
            .eq(TeamCollabEditLease::getAssetType, assetType)
            .eq(TeamCollabEditLease::getAssetId, assetId));
    }

    @Transactional(rollbackFor = Exception.class)
    public int invalidateTeam(Long teamId) {
        return mapper.delete(new LambdaQueryWrapper<TeamCollabEditLease>()
            .eq(TeamCollabEditLease::getTeamId, teamId));
    }

    private void requireOwned(TeamCollabEditLease row, Long teamId, Long userId, String token) {
        if (row == null || !Objects.equals(row.getTeamId(), teamId)
            || !Objects.equals(row.getUserId(), userId)) {
            throw new TeamCollabException(TeamCollabException.Code.EDIT_LEASE_INVALID,
                "你当前没有该资产的编辑租约");
        }
        if (row.getExpiresAt() == null || !row.getExpiresAt().after(new Date())) {
            throw new TeamCollabException(TeamCollabException.Code.EDIT_LEASE_EXPIRED,
                "编辑租约已过期，请重新进入编辑");
        }
        if (token == null || token.isBlank()
            || !MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),
                Objects.toString(row.getTokenHash(), "").getBytes(StandardCharsets.US_ASCII))) {
            throw new TeamCollabException(TeamCollabException.Code.EDIT_LEASE_INVALID,
                "编辑租约凭据无效");
        }
    }

    private LeaseView view(String token, TeamCollabEditLease row) {
        return new LeaseView(token, row.getExpiresAt(), row.getBaseVersion(), LEASE_SECONDS);
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(("iLink/team-collab/lease/v1\u0000" + token)
                    .getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Date expiry(Date now) {
        return Date.from(now.toInstant().plusSeconds(LEASE_SECONDS));
    }

    private static TeamCollabException held() {
        return new TeamCollabException(TeamCollabException.Code.EDIT_LEASE_HELD,
            "另一位成员正在编辑该资产");
    }

    public record LeaseView(String leaseToken, Date expiresAt, int baseVersion,
                            int renewAfterSeconds) {
    }

    public record AgentWriteLease(String leaseToken, boolean temporary) {
    }
}
