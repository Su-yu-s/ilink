package cn.ilink.collab;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamLibraryFileMapper;
import cn.ilink.service.kg.KgDocumentChunkService;
import cn.ilink.service.kg.KgScopeService;
import cn.ilink.teamagent.TeamAgentScope;
import cn.ilink.teamagent.TeamAgentScopeContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Agent-facing read tools must clamp every caller-supplied numeric bound so
 * a model cannot turn a legitimate question into an unbounded dump of a team's
 * collaboration assets or graph chunks.
 */
class TeamCollaborationAgentToolsLimitsTest {
    private TeamDocumentService documentService;
    private TeamSpreadsheetService spreadsheetService;
    private KgDocumentChunkService chunkService;
    private KgScopeService scopeService;
    private TeamCollaborationAgentTools tools;
    private TeamAgentScopeContext.BoundScope boundScope;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        documentService = mock(TeamDocumentService.class);
        spreadsheetService = mock(TeamSpreadsheetService.class);
        chunkService = mock(KgDocumentChunkService.class);
        scopeService = mock(KgScopeService.class);
        tools = new TeamCollaborationAgentTools(
            mock(TeamCollaborationAuthorization.class),
            documentService,
            spreadsheetService,
            mock(TeamMaterialMatrixService.class),
            mock(TeamCollaborationLeaseService.class),
            mock(TeamCollabDocumentMapper.class),
            mock(TeamCollabSpreadsheetMapper.class),
            mock(TeamLibraryFileMapper.class),
            chunkService,
            scopeService);
        boundScope = TeamAgentScopeContext.bind(new TeamAgentScope(7L, 20L, "系统队",
            "TEAMING", TeamAgentScope.TeamRole.STUDENT,
            TeamAgentScope.TaskReadMode.RELATION_ONLY, 3, "嵌入式竞赛", null,
            Date.from(Instant.parse("2026-12-31T00:00:00Z")), Instant.now()));
    }

    @AfterEach
    void clearScope() {
        if (boundScope != null) boundScope.close();
    }

    @Test
    void documentVersionLimitIsClampedToTwenty() {
        when(documentService.get(20L, 9L, 7L)).thenReturn(documentView());
        when(documentService.versions(eq(20L), eq(9L), eq(7L), anyInt(), eq(0)))
            .thenReturn(List.of());

        tools.getDocument(9L, 5000);

        verify(documentService).versions(20L, 9L, 7L, 20, 0);
    }

    @Test
    void aNegativeVersionLimitStillReturnsAtLeastOneVersion() {
        when(documentService.get(20L, 9L, 7L)).thenReturn(documentView());
        when(documentService.versions(eq(20L), eq(9L), eq(7L), anyInt(), eq(0)))
            .thenReturn(List.of());

        tools.getDocument(9L, -40);

        verify(documentService).versions(20L, 9L, 7L, 1, 0);
    }

    @Test
    void aNonPositiveDocumentIdIsRejectedBeforeAnyLookup() {
        assertThrows(IllegalArgumentException.class, () -> tools.getDocument(0L, 5));
        assertThrows(IllegalArgumentException.class, () -> tools.getDocument(-3L, 5));
        verify(documentService, org.mockito.Mockito.never())
            .get(anyLong(), anyLong(), anyLong());
    }

    @Test
    void spreadsheetRowLimitIsClampedToTwoHundred() throws Exception {
        when(spreadsheetService.get(20L, 11L, 7L)).thenReturn(sheetView(400));

        Map<String, Object> result = tools.getSpreadsheet(11L, 100000);

        assertEquals(200, result.get("rowLimit"));
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) result.get("rows");
        assertEquals(200, rows.size());
    }

    @Test
    void aNegativeRowLimitStillReturnsAtLeastOneRow() throws Exception {
        when(spreadsheetService.get(20L, 11L, 7L)).thenReturn(sheetView(5));

        Map<String, Object> result = tools.getSpreadsheet(11L, -9);

        assertEquals(1, result.get("rowLimit"));
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) result.get("rows");
        assertEquals(1, rows.size());
    }

    @Test
    void searchTopKIsClampedAndBlankQueriesAreRejected() {
        when(chunkService.retrieve(eq("报名表"), eq(7L), anyInt())).thenReturn(List.of());

        tools.search("报名表", 9999);

        verify(chunkService).retrieve("报名表", 7L, 60);
        assertThrows(IllegalArgumentException.class, () -> tools.search("   ", 5));
    }

    @Test
    void chunksOutsideTheCollaborationAssetTypesAreDropped() {
        KgDocumentChunk collab = chunk("TEAM_DOCUMENT", 9L);
        KgDocumentChunk unrelated = chunk("COMMUNITY_ARTICLE", 6L);
        List<KgDocumentChunk> retrieved = new ArrayList<>(List.of(collab, unrelated));
        when(chunkService.retrieve(eq("方案"), eq(7L), anyInt())).thenReturn(retrieved);
        when(scopeService.canSeeChunk(any(), anyLong())).thenReturn(true);

        Map<String, Object> result = tools.search("方案", 8);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("chunks");
        assertEquals(1, rows.size());
        assertEquals("TEAM_DOCUMENT", rows.get(0).get("docType"));
    }

    private TeamDocumentService.DocumentView documentView() {
        return new TeamDocumentService.DocumentView(9L, "方案", "正文", "TEAM", "ACTIVE",
            3, "hash", "沈行", "沈行", new Date(), new Date(), "VIEW", false, false, null);
    }

    private TeamSpreadsheetService.SheetView sheetView(int rowCount) throws Exception {
        ObjectNode column = mapper.createObjectNode();
        column.put("id", "col_task");
        column.put("key", "task");
        column.put("label", "任务");
        column.put("type", "TEXT");
        ArrayNode columns = mapper.createArrayNode().add(column);
        ArrayNode rows = mapper.createArrayNode();
        for (int i = 0; i < rowCount; i++) {
            ObjectNode cells = mapper.createObjectNode();
            cells.put("task", "任务 " + i);
            ObjectNode row = rows.addObject();
            row.put("id", "row_" + i);
            row.set("cells", cells);
        }
        return new TeamSpreadsheetService.SheetView(11L, "分工表", columns, rows, "TEAM",
            "ACTIVE", 4, "hash", "沈行", "沈行", new Date(), new Date(),
            "VIEW", false, false, null);
    }

    private KgDocumentChunk chunk(String docType, Long docId) {
        KgDocumentChunk row = new KgDocumentChunk();
        row.setId(1L);
        row.setDocType(docType);
        row.setDocId(docId);
        row.setTitle("标题");
        row.setContent("正文");
        row.setScope("TEAM");
        row.setTeamId(20L);
        return row;
    }
}
