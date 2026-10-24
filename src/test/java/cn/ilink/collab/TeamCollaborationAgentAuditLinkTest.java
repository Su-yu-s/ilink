package cn.ilink.collab;

import cn.ilink.entity.TeamCollabDocument;
import cn.ilink.entity.TeamCollabDocumentVersion;
import cn.ilink.entity.TeamCollabEvent;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabDocumentVersionMapper;
import cn.ilink.mapper.TeamCollabEventMapper;
import cn.ilink.service.UserService;
import cn.ilink.service.kg.KgIndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An Agent write has to stay traceable. The collaboration event produced by the
 * business service must carry the agent action row id as its request id, record
 * the AGENT save kind, and never leak document body or lease material into the
 * audit trail. These tests wire the real event service and the real document
 * service together so the link is proven end to end rather than asserted on a mock.
 */
class TeamCollaborationAgentAuditLinkTest {
    private TeamCollaborationAuthorization authorization;
    private TeamCollaborationLeaseService leaseService;
    private TeamCollaborationAgentCommands commands;
    private TeamCollabEventMapper eventMapper;
    private TeamCollabDocumentMapper documentMapper;
    private TeamCollabDocumentVersionMapper versionMapper;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
            new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TeamCollabDocument.class);
        TableInfoHelper.initTableInfo(assistant, TeamCollabDocumentVersion.class);
        TableInfoHelper.initTableInfo(assistant, TeamCollabEvent.class);
    }

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        authorization = mock(TeamCollaborationAuthorization.class);
        leaseService = mock(TeamCollaborationLeaseService.class);
        eventMapper = mock(TeamCollabEventMapper.class);
        documentMapper = mock(TeamCollabDocumentMapper.class);
        versionMapper = mock(TeamCollabDocumentVersionMapper.class);
        UserService userService = mock(UserService.class);
        when(userService.getById(anyLong())).thenReturn(user(7L, "沈行"));

        TeamCollaborationEventService eventService = new TeamCollaborationEventService(
            eventMapper, objectMapper, mock(SimpMessagingTemplate.class));
        TeamDocumentService documentService = new TeamDocumentService(
            documentMapper, versionMapper, authorization, leaseService, eventService,
            mock(KgIndexService.class), userService, objectMapper);

        commands = new TeamCollaborationAgentCommands(
            authorization,
            leaseService,
            mock(TeamCollaborationAgentTools.class),
            documentService,
            mock(TeamSpreadsheetService.class),
            mock(TeamMaterialMatrixService.class),
            mock(TeamMaterialEvidenceValidator.class),
            objectMapper);

        TeamDemand team = new TeamDemand();
        team.setId(20L);
        team.setStatus("TEAMING");
        TeamCollaborationAuthorization.TeamContext teamContext =
            new TeamCollaborationAuthorization.TeamContext(team, 7L,
                TeamCollaborationAuthorization.TeamRole.STUDENT, true, true);
        when(authorization.requireTeam(20L, 7L)).thenReturn(teamContext);
        when(authorization.requireEditableAsset(20L, "DOCUMENT", 9L, 7L))
            .thenReturn(new TeamCollaborationAuthorization.AssetContext(teamContext,
                "DOCUMENT", 9L, TeamCollabDocument.VISIBILITY_TEAM, 6,
                TeamCollaborationAuthorization.Permission.MANAGE, true, true));
        when(authorization.requireAsset(eq(20L), eq("DOCUMENT"), eq(9L), eq(7L),
            eq(TeamCollaborationAuthorization.Permission.VIEW)))
            .thenReturn(new TeamCollaborationAuthorization.AssetContext(teamContext,
                "DOCUMENT", 9L, TeamCollabDocument.VISIBILITY_TEAM, 6,
                TeamCollaborationAuthorization.Permission.EDIT, true, false));
        when(leaseService.acquireForAgentWrite(eq(20L), eq("DOCUMENT"), eq(9L), eq(7L),
            anyInt(), isNull()))
            .thenReturn(new TeamCollaborationLeaseService.AgentWriteLease("temp-token", true));
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(versionMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void anAppendRecordsTheAgentActionRowIdOnTheCollaborationEvent() {
        when(documentMapper.selectById(9L)).thenReturn(
            document(6, "旧正文"), document(7, "旧正文\n\n新章节"));
        when(documentMapper.selectByIdForUpdate(9L)).thenReturn(document(6, "旧正文"));
        when(documentMapper.update(any(), any())).thenReturn(1);

        Map<String, Object> result = commands.execute(
            TeamCollaborationAgentCommands.APPEND_DOCUMENT, 20L, 7L, 9L, 6,
            Map.of("appendMarkdown", "新章节"), 88L);

        assertEquals(7, result.get("version"));
        TeamCollabEvent event = capturedEvent();
        assertEquals("agent-88-append", event.getRequestId());
        assertEquals("DOCUMENT_SAVED", event.getEventType());
        assertEquals(Integer.valueOf(7), event.getVersion());
        assertEquals(Long.valueOf(20L), event.getTeamId());
        assertEquals("DOCUMENT", event.getAssetType());
        assertEquals(Long.valueOf(9L), event.getAssetId());
        assertEquals(Long.valueOf(7L), event.getActorUserId());
        assertNotNull(event.getMetadataJson());
        assertTrue(event.getMetadataJson().contains("AGENT"), event.getMetadataJson());
        // The audit row must not carry the document body or the lease secret.
        assertFalse(event.getMetadataJson().contains("新章节"), event.getMetadataJson());
        assertFalse(event.getMetadataJson().toLowerCase().contains("token"),
            event.getMetadataJson());
        verify(leaseService).release(20L, "DOCUMENT", 9L, 7L, "temp-token");
    }

    @Test
    void anUndoRecordsTheSameActionRowIdOnTheRestoreEvent() {
        when(documentMapper.selectById(9L)).thenReturn(
            document(7, "旧正文\n\n新章节"), document(8, "旧正文"));
        when(documentMapper.selectByIdForUpdate(9L)).thenReturn(document(7, "旧正文\n\n新章节"));
        when(documentMapper.update(any(), any())).thenReturn(1);
        when(versionMapper.selectOne(any())).thenReturn(restoredSnapshot());

        Map<String, Object> result = commands.undo(
            TeamCollaborationAgentCommands.APPEND_DOCUMENT, 20L, 7L, 9L,
            Map.of("version", 6),
            Map.of("documentId", 9L, "previousVersion", 6, "version", 7), 88L);

        assertEquals(8, result.get("version"));
        TeamCollabEvent event = capturedEvent();
        assertEquals("agent-88-undo", event.getRequestId());
        assertEquals("DOCUMENT_VERSION_RESTORED", event.getEventType());
        assertEquals(Integer.valueOf(8), event.getVersion());
        assertTrue(event.getMetadataJson().contains("restoredVersion"),
            event.getMetadataJson());
    }

    private TeamCollabEvent capturedEvent() {
        ArgumentCaptor<TeamCollabEvent> captor = ArgumentCaptor.forClass(TeamCollabEvent.class);
        verify(eventMapper).insert(captor.capture());
        return captor.getValue();
    }

    private TeamCollabDocument document(int version, String markdown) {
        TeamCollabDocument row = new TeamCollabDocument();
        row.setId(9L);
        row.setTeamId(20L);
        row.setTitle("方案");
        row.setContentMarkdown(markdown);
        row.setVisibility(TeamCollabDocument.VISIBILITY_TEAM);
        row.setStatus(TeamCollabDocument.STATUS_ACTIVE);
        row.setVersion(version);
        row.setContentHash(TeamCollabMarkdown.hash(markdown));
        row.setCreatorUserId(7L);
        row.setLastEditorUserId(7L);
        row.setCreatedAt(Date.from(Instant.parse("2026-10-23T00:00:00Z")));
        row.setUpdatedAt(Date.from(Instant.parse("2026-10-23T00:00:00Z")));
        return row;
    }

    private TeamCollabDocumentVersion restoredSnapshot() {
        TeamCollabDocumentVersion row = new TeamCollabDocumentVersion();
        row.setDocumentId(9L);
        row.setTeamId(20L);
        row.setVersion(6);
        row.setTitle("方案");
        row.setContentMarkdown("旧正文");
        row.setContentHash(TeamCollabMarkdown.hash("旧正文"));
        row.setSaveKind("AGENT");
        row.setActorUserId(7L);
        row.setCreatedAt(Date.from(Instant.parse("2026-10-23T00:00:00Z")));
        return row;
    }

    private User user(Long id, String realName) {
        User user = new User();
        user.setId(id);
        user.setRealName(realName);
        return user;
    }
}
