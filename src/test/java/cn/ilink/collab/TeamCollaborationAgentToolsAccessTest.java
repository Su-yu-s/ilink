package cn.ilink.collab;

import cn.ilink.entity.TeamCollabDocument;
import cn.ilink.entity.TeamCollabSpreadsheet;
import cn.ilink.entity.TeamDemand;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamLibraryFileMapper;
import cn.ilink.service.kg.KgDocumentChunkService;
import cn.ilink.service.kg.KgScopeService;
import cn.ilink.teamagent.TeamAgentScope;
import cn.ilink.teamagent.TeamAgentScopeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamCollaborationAgentToolsAccessTest {
    private TeamAgentScopeContext.BoundScope boundScope;

    @AfterEach
    void clearScope() {
        if (boundScope != null) boundScope.close();
    }

    @Test
    void restrictedAssetsAreExcludedFromAgentFactsWithoutAGrant() {
        TeamCollabDocumentMapper documents = mock(TeamCollabDocumentMapper.class);
        TeamCollabSpreadsheetMapper sheets = mock(TeamCollabSpreadsheetMapper.class);
        TeamCollaborationAuthorization authorization = mock(TeamCollaborationAuthorization.class);
        TeamMaterialMatrixService materials = mock(TeamMaterialMatrixService.class);
        TeamCollabDocument restrictedDoc = document(1L, "RESTRICTED");
        TeamCollabDocument visibleDoc = document(2L, "TEAM");
        TeamCollabSpreadsheet restrictedSheet = sheet(3L, "RESTRICTED");
        TeamCollabSpreadsheet visibleSheet = sheet(4L, "TEAM");
        when(documents.selectList(any())).thenReturn(List.of(restrictedDoc, visibleDoc));
        when(sheets.selectList(any())).thenReturn(List.of(restrictedSheet, visibleSheet));
        when(authorization.canViewAsset("DOCUMENT", 1L, 20L, 7L)).thenReturn(false);
        when(authorization.canViewAsset("DOCUMENT", 2L, 20L, 7L)).thenReturn(true);
        when(authorization.canViewAsset("SPREADSHEET", 3L, 20L, 7L)).thenReturn(false);
        when(authorization.canViewAsset("SPREADSHEET", 4L, 20L, 7L)).thenReturn(true);
        TeamMaterialMatrixService.MatrixView emptyMatrix =
            new TeamMaterialMatrixService.MatrixView(List.of(), Map.of("total", 0L),
                0, 0, Map.of());
        when(materials.matrix(anyLong(), anyLong(), any(), any(), any()))
            .thenReturn(emptyMatrix);
        TeamCollaborationAgentTools tools = new TeamCollaborationAgentTools(
            authorization,
            mock(TeamDocumentService.class),
            mock(TeamSpreadsheetService.class),
            materials,
            mock(TeamCollaborationLeaseService.class),
            documents,
            sheets,
            mock(TeamLibraryFileMapper.class),
            mock(KgDocumentChunkService.class),
            mock(KgScopeService.class));
        boundScope = TeamAgentScopeContext.bind(new TeamAgentScope(7L, 20L, "系统队", "TEAMING",
            TeamAgentScope.TeamRole.STUDENT, TeamAgentScope.TaskReadMode.RELATION_ONLY,
            3, "嵌入式竞赛", null,
            Date.from(Instant.parse("2026-12-31T00:00:00Z")), Instant.now()));

        Map<String, Object> result = tools.listAssets();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assets = (List<Map<String, Object>>) result.get("assets");
        assertTrue(assets.stream().anyMatch(row -> Long.valueOf(2L).equals(row.get("id"))));
        assertTrue(assets.stream().anyMatch(row -> Long.valueOf(4L).equals(row.get("id"))));
        assertFalse(assets.stream().anyMatch(row -> Long.valueOf(1L).equals(row.get("id"))));
        assertFalse(assets.stream().anyMatch(row -> Long.valueOf(3L).equals(row.get("id"))));
    }

    private TeamCollabDocument document(Long id, String visibility) {
        TeamCollabDocument row = new TeamCollabDocument();
        row.setId(id);
        row.setTeamId(20L);
        row.setTitle("文档 " + id);
        row.setVisibility(visibility);
        row.setStatus("ACTIVE");
        row.setVersion(1);
        row.setUpdatedAt(new Date());
        return row;
    }

    private TeamCollabSpreadsheet sheet(Long id, String visibility) {
        TeamCollabSpreadsheet row = new TeamCollabSpreadsheet();
        row.setId(id);
        row.setTeamId(20L);
        row.setTitle("表格 " + id);
        row.setVisibility(visibility);
        row.setStatus("ACTIVE");
        row.setVersion(1);
        row.setUpdatedAt(new Date());
        return row;
    }
}
