package cn.ilink.collab;

import cn.ilink.entity.TeamCollabDocument;
import cn.ilink.entity.TeamCollabEvent;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabDocumentVersionMapper;
import cn.ilink.service.UserService;
import cn.ilink.service.kg.KgIndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamDocumentServiceIdempotencyTest {
    private TeamCollabDocumentMapper documentMapper;
    private TeamCollaborationEventService eventService;
    private TeamCollaborationAuthorization authorization;
    private TeamDocumentService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        documentMapper = mock(TeamCollabDocumentMapper.class);
        eventService = mock(TeamCollaborationEventService.class);
        authorization = mock(TeamCollaborationAuthorization.class);
        objectMapper = new ObjectMapper();
        UserService userService = mock(UserService.class);
        when(userService.getById(anyLong())).thenReturn(user(7L));
        service = new TeamDocumentService(
            documentMapper,
            mock(TeamCollabDocumentVersionMapper.class),
            authorization,
            mock(TeamCollaborationLeaseService.class),
            eventService,
            mock(KgIndexService.class),
            userService,
            objectMapper);
        TeamDemand team = new TeamDemand();
        team.setId(20L);
        team.setStatus("TEAMING");
        TeamCollaborationAuthorization.TeamContext teamContext =
            new TeamCollaborationAuthorization.TeamContext(team, 7L,
                TeamCollaborationAuthorization.TeamRole.LEADER, true, true);
        when(authorization.requireEditableAsset(20L, "DOCUMENT", 9L, 7L))
            .thenReturn(new TeamCollaborationAuthorization.AssetContext(
                teamContext, "DOCUMENT", 9L, "TEAM", 2,
                TeamCollaborationAuthorization.Permission.MANAGE, true, true));
    }

    @Test
    void aRetryAfterAnUnknownResponseSucceedsBeforeTheVersionCheck() {
        TeamCollabDocument current = document(2);
        when(documentMapper.selectByIdForUpdate(9L)).thenReturn(current);
        TeamCollabEvent previous = new TeamCollabEvent();
        previous.setMetadataJson("{\"requestHash\":\""
            + fingerprint("方案", "正文", "TEAM", 1) + "\"}");
        when(eventService.findRequest(20L, "DOCUMENT", 9L, "request-1"))
            .thenReturn(previous);

        TeamDocumentService.DocumentView view = service.update(20L, 9L, 7L,
            request("方案", "正文", "TEAM", 1, "request-1"));

        assertEquals(2, view.version());
        verify(documentMapper, never()).update(any(), any());
    }

    @Test
    void reusingARequestIdWithDifferentContentIsRejected() {
        when(documentMapper.selectByIdForUpdate(9L)).thenReturn(document(2));
        TeamCollabEvent previous = new TeamCollabEvent();
        previous.setMetadataJson("{\"requestHash\":\""
            + fingerprint("方案", "旧正文", "TEAM", 1) + "\"}");
        when(eventService.findRequest(20L, "DOCUMENT", 9L, "request-1"))
            .thenReturn(previous);

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> service.update(20L, 9L, 7L,
                request("方案", "新正文", "TEAM", 1, "request-1")));

        assertEquals(TeamCollabException.Code.REQUEST_ID_REUSED, error.code());
    }

    private TeamDocumentService.SaveRequest request(String title, String body,
                                                    String visibility, int baseVersion,
                                                    String requestId) {
        return new TeamDocumentService.SaveRequest(title, body, visibility,
            baseVersion, "lease", requestId, "MANUAL", "保存");
    }

    private String fingerprint(String title, String markdown, String visibility,
                               int baseVersion) {
        String canonical = title.trim() + "\u0000" + TeamCollabMarkdown.normalize(markdown)
            + "\u0000" + visibility + "\u0000" + baseVersion;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TeamCollabDocument document(int version) {
        TeamCollabDocument row = new TeamCollabDocument();
        row.setId(9L);
        row.setTeamId(20L);
        row.setTitle("方案");
        row.setContentMarkdown("正文");
        row.setContentHash(TeamCollabMarkdown.hash("正文"));
        row.setVisibility("TEAM");
        row.setStatus("ACTIVE");
        row.setVersion(version);
        row.setCreatorUserId(7L);
        row.setLastEditorUserId(7L);
        row.setCreatedAt(new Date());
        row.setUpdatedAt(new Date());
        return row;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setRealName("测试成员");
        return user;
    }
}
