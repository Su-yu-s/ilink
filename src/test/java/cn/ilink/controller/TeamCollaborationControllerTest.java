package cn.ilink.controller;

import cn.ilink.collab.TeamCollabException;
import cn.ilink.collab.TeamCollaborationAclService;
import cn.ilink.collab.TeamCollaborationAuthorization;
import cn.ilink.collab.TeamCollaborationLeaseService;
import cn.ilink.collab.TeamCollaborationTrashService;
import cn.ilink.collab.TeamDocumentService;
import cn.ilink.collab.TeamMaterialMatrixService;
import cn.ilink.collab.TeamSpreadsheetService;
import cn.ilink.common.Result;
import cn.ilink.entity.TeamCollabEditLease;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamCollaborationControllerTest {

    private TeamCollaborationAuthorization authorization;
    private TeamCollaborationLeaseService leaseService;
    private UserService userService;
    private TeamCollaborationController controller;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        authorization = mock(TeamCollaborationAuthorization.class);
        leaseService = mock(TeamCollaborationLeaseService.class);
        userService = mock(UserService.class);
        controller = new TeamCollaborationController(
            authorization,
            leaseService,
            mock(TeamCollaborationAclService.class),
            mock(TeamDocumentService.class),
            mock(TeamSpreadsheetService.class),
            mock(TeamMaterialMatrixService.class),
            mock(TeamCollaborationTrashService.class),
            new ObjectMapper(),
            userService);

        session = mock(HttpSession.class);
        when(session.getAttribute("user")).thenReturn(user(7L, "当前成员"));
    }

    @Test
    void leaseConflictUsesAnActualFailureEnvelope() {
        TeamDemand team = new TeamDemand();
        team.setId(3L);
        team.setStatus("TEAMING");
        TeamCollaborationAuthorization.TeamContext teamContext =
            new TeamCollaborationAuthorization.TeamContext(team, 7L,
                TeamCollaborationAuthorization.TeamRole.STUDENT, true, true);
        TeamCollaborationAuthorization.AssetContext asset =
            new TeamCollaborationAuthorization.AssetContext(teamContext, "DOCUMENT", 9L,
                "TEAM", 4, TeamCollaborationAuthorization.Permission.EDIT, true, false);
        when(authorization.assetType("DOCUMENT")).thenReturn("DOCUMENT");
        when(authorization.requireEditableAsset(3L, "DOCUMENT", 9L, 7L)).thenReturn(asset);
        when(leaseService.acquire(3L, "DOCUMENT", 9L, 7L, 4))
            .thenThrow(new TeamCollabException(
                TeamCollabException.Code.EDIT_LEASE_HELD, "另一位成员正在编辑该资产"));

        TeamCollabEditLease active = new TeamCollabEditLease();
        active.setTeamId(3L);
        active.setAssetType("DOCUMENT");
        active.setAssetId(9L);
        active.setUserId(8L);
        active.setExpiresAt(Date.from(Instant.now().plusSeconds(60)));
        when(leaseService.current("DOCUMENT", 9L)).thenReturn(active);
        when(userService.getById(8L)).thenReturn(user(8L, "队友甲"));

        ResponseEntity<Result<?>> response =
            controller.acquireLease(3L, "DOCUMENT", 9L, session);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        Result<?> body = response.getBody();
        assertNotNull(body);
        assertEquals(409, body.getCode());
        assertEquals("EDIT_LEASE_HELD", body.getExtra().get("errorCode"));
        assertTrue(body.getData() instanceof Map);
        Map<?, ?> data = (Map<?, ?>) body.getData();
        assertEquals(true, data.get("readOnly"));
        assertTrue(data.get("activeLease") instanceof Map);
        assertEquals("队友甲",
            ((Map<?, ?>) data.get("activeLease")).get("displayName"));
    }

    private User user(Long id, String name) {
        User user = new User();
        user.setId(id);
        user.setRealName(name);
        return user;
    }
}
