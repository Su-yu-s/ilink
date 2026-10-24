package cn.ilink.collab;

import cn.ilink.entity.TeamDemand;
import cn.ilink.teamagent.TeamAgentScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamCollaborationAgentCommandsTest {
    private TeamCollaborationAuthorization authorization;
    private TeamMaterialMatrixService materialService;
    private TeamCollaborationLeaseService leaseService;
    private TeamDocumentService documentService;
    private TeamSpreadsheetService spreadsheetService;
    private TeamCollaborationAgentCommands commands;

    @BeforeEach
    void setUp() {
        authorization = mock(TeamCollaborationAuthorization.class);
        materialService = mock(TeamMaterialMatrixService.class);
        leaseService = mock(TeamCollaborationLeaseService.class);
        documentService = mock(TeamDocumentService.class);
        spreadsheetService = mock(TeamSpreadsheetService.class);
        commands = new TeamCollaborationAgentCommands(
            authorization,
            leaseService,
            mock(TeamCollaborationAgentTools.class),
            documentService,
            spreadsheetService,
            materialService,
            mock(TeamMaterialEvidenceValidator.class),
            new ObjectMapper());

        TeamDemand team = new TeamDemand();
        team.setId(20L);
        team.setStatus("TEAMING");
        when(authorization.requireTeam(20L, 7L)).thenReturn(
            new TeamCollaborationAuthorization.TeamContext(team, 7L,
                TeamCollaborationAuthorization.TeamRole.STUDENT, true, true));
    }

    @Test
    void theAgentCannotMarkMaterialAsMet() {
        when(materialService.requirementView(20L, 5L, 7L)).thenReturn(requirement("UNCHECKED"));

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> commands.prepare(scope(), TeamCollaborationAgentCommands.SET_REVIEW, 5L,
                Map.of("status", "MET")));

        assertEquals(TeamCollabException.Code.AGENT_ACTION_CONFLICT, error.code());
    }

    @Test
    void suggestingNeedsReviewCarriesTheServerSideBeforeState() {
        when(materialService.requirementView(20L, 5L, 7L)).thenReturn(requirement("PARTIAL"));

        TeamCollaborationAgentCommands.Prepared prepared = commands.prepare(scope(),
            TeamCollaborationAgentCommands.SET_REVIEW, 5L,
            Map.of("status", "needs_review", "note", "请核对盖章页"));

        assertEquals("MATERIAL_REQUIREMENT", prepared.targetType());
        assertEquals("PARTIAL", prepared.before().get("reviewStatus"));
        assertEquals(3, prepared.before().get("reviewVersion"));
        assertEquals("NEEDS_REVIEW", prepared.after().get("status"));
        assertTrue(prepared.undoSupported());
    }

    @Test
    void anAgentCreatedRequirementIsAlwaysAnUnconfirmedSuggestion() {
        TeamCollaborationAgentCommands.Prepared prepared = commands.prepare(scope(),
            TeamCollaborationAgentCommands.CREATE_REQUIREMENT, null,
            Map.of("title", "竞赛承诺书", "required", true));

        assertEquals("AGENT_SUGGESTED", prepared.after().get("sourceType"));
        assertEquals("竞赛承诺书", prepared.after().get("title"));
    }

    @Test
    void aReadOnlyTeamBlocksEveryCollaborationWrite() {
        TeamDemand closed = new TeamDemand();
        closed.setId(20L);
        closed.setStatus("CLOSED");
        when(authorization.requireTeam(20L, 7L)).thenReturn(
            new TeamCollaborationAuthorization.TeamContext(closed, 7L,
                TeamCollaborationAuthorization.TeamRole.LEADER, false, true));

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> commands.prepare(scope(), TeamCollaborationAgentCommands.CREATE_DOCUMENT, null,
                Map.of("title", "方案")));

        assertEquals(TeamCollabException.Code.AGENT_ACTION_CONFLICT, error.code());
    }

    private TeamMaterialMatrixService.RequirementView requirement(String status) {
        return new TeamMaterialMatrixService.RequirementView(5L, null, null, "报名表",
            null, "报名", true, "MANUAL", status, 3, 4, "队长",
            null, new Date(), 7L, true, true, List.of());
    }

    private TeamAgentScope scope() {
        return new TeamAgentScope(7L, 20L, "系统队", "TEAMING",
            TeamAgentScope.TeamRole.STUDENT, TeamAgentScope.TaskReadMode.RELATION_ONLY,
            3, "嵌入式竞赛", null,
            Date.from(Instant.parse("2026-12-31T00:00:00Z")), Instant.now());
    }

    private TeamDocumentService.DocumentView document(int version, String markdown) {
        return new TeamDocumentService.DocumentView(9L, "方案", markdown, "TEAM", "ACTIVE",
            version, "hash-" + version, "沈行", "沈行", new Date(), new Date(),
            "EDIT", true, true, null);
    }

    private TeamSpreadsheetService.SheetView sheet(int version) {
        return new TeamSpreadsheetService.SheetView(11L, "分工表", null, null, "TEAM", "ACTIVE",
            version, "hash-" + version, "沈行", "沈行", new Date(), new Date(),
            "EDIT", true, true, null);
    }

    @Test
    void anAgentAppendIsUndoneWhenTheAssetStillSitsOnItsOwnVersion() {
        when(documentService.get(20L, 9L, 7L)).thenReturn(document(6, "旧正文 Agent 追加"));
        when(leaseService.acquireForAgentWrite(20L, "DOCUMENT", 9L, 7L, 6, null))
            .thenReturn(new TeamCollaborationLeaseService.AgentWriteLease("token", true));
        when(documentService.restoreVersion(20L, 9L, 5, 7L, "token", "agent-71-undo"))
            .thenReturn(document(7, "旧正文"));

        Map<String, Object> undone = commands.undo(TeamCollaborationAgentCommands.APPEND_DOCUMENT,
            20L, 7L, 9L, Map.of("version", 5),
            Map.of("documentId", 9L, "previousVersion", 5, "version", 6), 71L);

        assertEquals(7, undone.get("version"));
        verify(leaseService).release(20L, "DOCUMENT", 9L, 7L, "token");
    }

    @Test
    void anAgentAppendIsNotUndoneAfterSomebodyElseEditedTheDocument() {
        when(documentService.get(20L, 9L, 7L)).thenReturn(document(8, "人工修改"));

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> commands.undo(TeamCollaborationAgentCommands.APPEND_DOCUMENT, 20L, 7L, 9L,
                Map.of("version", 5),
                Map.of("documentId", 9L, "previousVersion", 5, "version", 6), 71L));

        assertEquals(TeamCollabException.Code.AGENT_ACTION_CONFLICT, error.code());
        verify(documentService, never()).restoreVersion(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anAgentRowUpdateIsUndoneWithoutDisturbingTheOwnerLease() {
        when(spreadsheetService.get(20L, 11L, 7L)).thenReturn(sheet(4));
        when(leaseService.acquireForAgentWrite(20L, "SPREADSHEET", 11L, 7L, 4, "browser-token"))
            .thenReturn(new TeamCollaborationLeaseService.AgentWriteLease("browser-token", false));
        when(spreadsheetService.restoreVersion(20L, 11L, 3, 7L, "browser-token", "agent-72-undo"))
            .thenReturn(sheet(5));

        Map<String, Object> undone = commands.undo(
            TeamCollaborationAgentCommands.UPDATE_TABLE_ROWS, 20L, 7L, 11L, Map.of("version", 3),
            Map.of("spreadsheetId", 11L, "previousVersion", 3, "version", 4), 72L,
            "browser-token");

        assertEquals(5, undone.get("version"));
        verify(leaseService, never()).release(any(), any(), any(), any(), any());
    }

    @Test
    void aConfirmedAppendReusesTheEditorLeaseOfTheConfirmingUser() {
        when(documentService.get(20L, 9L, 7L)).thenReturn(document(6, "旧正文"));
        when(leaseService.acquireForAgentWrite(eq(20L), eq("DOCUMENT"), eq(9L), eq(7L),
            anyInt(), eq("browser-token")))
            .thenReturn(new TeamCollaborationLeaseService.AgentWriteLease("browser-token", false));
        when(documentService.update(any(), any(), any(), any())).thenReturn(document(7, "旧正文 新章节"));

        Map<String, Object> result = commands.execute(
            TeamCollaborationAgentCommands.APPEND_DOCUMENT, 20L, 7L, 9L, 6,
            Map.of("appendMarkdown", "新章节"), 73L, "browser-token");

        assertEquals(7, result.get("version"));
        ArgumentCaptor<TeamDocumentService.SaveRequest> saved =
            ArgumentCaptor.forClass(TeamDocumentService.SaveRequest.class);
        verify(documentService).update(eq(20L), eq(9L), eq(7L), saved.capture());
        assertEquals("browser-token", saved.getValue().leaseToken());
        assertTrue(saved.getValue().contentMarkdown().endsWith("新章节"));
        verify(leaseService, never()).release(any(), any(), any(), anyLong(), any());
    }

    @Test
    void anAppendWithoutAnExistingLeaseTakesAndReleasesATemporaryOne() {
        when(documentService.get(20L, 9L, 7L)).thenReturn(document(6, "旧正文"));
        when(leaseService.acquireForAgentWrite(eq(20L), eq("DOCUMENT"), eq(9L), eq(7L),
            anyInt(), isNull()))
            .thenReturn(new TeamCollaborationLeaseService.AgentWriteLease("temp-token", true));
        when(documentService.update(any(), any(), any(), any())).thenReturn(document(7, "旧正文 新章节"));

        commands.execute(TeamCollaborationAgentCommands.APPEND_DOCUMENT, 20L, 7L, 9L, 6,
            Map.of("appendMarkdown", "新章节"), 74L);

        verify(leaseService).release(20L, "DOCUMENT", 9L, 7L, "temp-token");
    }
}
