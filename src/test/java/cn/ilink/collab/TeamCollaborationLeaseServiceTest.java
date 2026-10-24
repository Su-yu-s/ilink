package cn.ilink.collab;

import cn.ilink.entity.TeamCollabEditLease;
import cn.ilink.mapper.TeamCollabEditLeaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamCollaborationLeaseServiceTest {
    private TeamCollabEditLeaseMapper mapper;
    private TeamCollaborationLeaseService service;

    @BeforeEach
    void setUp() {
        mapper = mock(TeamCollabEditLeaseMapper.class);
        service = new TeamCollaborationLeaseService(mapper);
    }

    @Test
    void acquireStoresOnlyAHashedToken() {
        when(mapper.selectByAssetForUpdate("DOCUMENT", 9L)).thenReturn(null);

        TeamCollaborationLeaseService.LeaseView view =
            service.acquire(3L, "DOCUMENT", 9L, 7L, 4);

        ArgumentCaptor<TeamCollabEditLease> row = ArgumentCaptor.forClass(TeamCollabEditLease.class);
        verify(mapper).insert(row.capture());
        assertNotEquals(view.leaseToken(), row.getValue().getTokenHash());
        assertFalse(row.getValue().getTokenHash().contains(view.leaseToken()));
        assertEquals(TeamCollaborationLeaseService.hash(view.leaseToken()),
            row.getValue().getTokenHash());
        assertEquals(7L, row.getValue().getUserId());
        assertEquals(4, row.getValue().getBaseVersion());
    }

    @Test
    void aLiveLeaseHeldByAnotherMemberBlocksAcquisition() {
        when(mapper.selectByAssetForUpdate("DOCUMENT", 9L))
            .thenReturn(lease(3L, 8L, 10, Instant.now().plusSeconds(30)));

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> service.acquire(3L, "DOCUMENT", 9L, 7L, 4));

        assertEquals(TeamCollabException.Code.EDIT_LEASE_HELD, error.code());
    }

    @Test
    void anExpiredLeaseCanBeTakenOver() {
        TeamCollabEditLease expired = lease(3L, 8L, 10, Instant.now().minusSeconds(5));
        when(mapper.selectByAssetForUpdate("DOCUMENT", 9L)).thenReturn(expired);
        when(mapper.updateById(expired)).thenReturn(1);

        TeamCollaborationLeaseService.LeaseView view =
            service.acquire(3L, "DOCUMENT", 9L, 7L, 12);

        assertEquals(7L, expired.getUserId());
        assertEquals(12, view.baseVersion());
        assertTrue(expired.getExpiresAt().after(new Date()));
    }

    @Test
    void renewRejectsAStaleToken() {
        when(mapper.selectByAssetForUpdate("DOCUMENT", 9L))
            .thenReturn(lease(3L, 7L, 10, Instant.now().plusSeconds(30)));

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> service.renew(3L, "DOCUMENT", 9L, 7L, "forged-token", 10));

        assertEquals(TeamCollabException.Code.EDIT_LEASE_INVALID, error.code());
    }

    @Test
    void renewRejectsAnExpiredLeaseEvenWithTheRightToken() {
        String token = "token";
        TeamCollabEditLease row = lease(3L, 7L, 10, Instant.now().minusSeconds(1));
        row.setTokenHash(TeamCollaborationLeaseService.hash(token));
        when(mapper.selectByAssetForUpdate("DOCUMENT", 9L)).thenReturn(row);

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> service.renew(3L, "DOCUMENT", 9L, 7L, token, 10));

        assertEquals(TeamCollabException.Code.EDIT_LEASE_EXPIRED, error.code());
    }

    @Test
    void releaseRequiresTheOwnerToken() {
        TeamCollabEditLease row = lease(3L, 7L, 10, Instant.now().plusSeconds(30));
        row.setTokenHash(TeamCollaborationLeaseService.hash("right-token"));
        when(mapper.selectByAssetForUpdate("DOCUMENT", 9L)).thenReturn(row);

        assertThrows(TeamCollabException.class,
            () -> service.release(3L, "DOCUMENT", 9L, 7L, "wrong-token"));
        verify(mapper, org.mockito.Mockito.never())
            .deleteById((java.io.Serializable) any());
    }

    private TeamCollabEditLease lease(Long teamId, Long userId, int baseVersion, Instant expiresAt) {
        TeamCollabEditLease row = new TeamCollabEditLease();
        row.setId(1L);
        row.setTeamId(teamId);
        row.setUserId(userId);
        row.setAssetType("DOCUMENT");
        row.setAssetId(9L);
        row.setBaseVersion(baseVersion);
        row.setTokenHash("hashed");
        row.setExpiresAt(Date.from(expiresAt));
        return row;
    }

    @Test
    void agentWriteKeepsTheSameUserLiveLeaseAndReusesItsToken() {
        String token = "browser-editor-token";
        TeamCollabEditLease row = lease(3L, 7L, 10, Instant.now().plusSeconds(30));
        row.setTokenHash(TeamCollaborationLeaseService.hash(token));
        when(mapper.selectByAssetForUpdate("DOCUMENT", 9L)).thenReturn(row);

        TeamCollaborationLeaseService.AgentWriteLease write =
            service.acquireForAgentWrite(3L, "DOCUMENT", 9L, 7L, 10, token);

        assertFalse(write.temporary());
        assertEquals(token, write.leaseToken());
        verify(mapper, org.mockito.Mockito.never()).updateById(any());
    }

    @Test
    void agentWriteWithoutALiveLeaseTakesATemporaryOne() {
        when(mapper.selectByAssetForUpdate("DOCUMENT", 9L)).thenReturn(null);

        TeamCollaborationLeaseService.AgentWriteLease write =
            service.acquireForAgentWrite(3L, "DOCUMENT", 9L, 7L, 10, null);

        assertTrue(write.temporary());
        assertFalse(write.leaseToken().isBlank());
    }

    @Test
    void agentWriteRejectsAForeignTokenForTheOwnersLiveLease() {
        TeamCollabEditLease row = lease(3L, 7L, 10, Instant.now().plusSeconds(30));
        row.setTokenHash(TeamCollaborationLeaseService.hash("real-token"));
        when(mapper.selectByAssetForUpdate("DOCUMENT", 9L)).thenReturn(row);

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> service.acquireForAgentWrite(3L, "DOCUMENT", 9L, 7L, 10, "guessed-token"));

        assertEquals(TeamCollabException.Code.EDIT_LEASE_INVALID, error.code());
    }
}
