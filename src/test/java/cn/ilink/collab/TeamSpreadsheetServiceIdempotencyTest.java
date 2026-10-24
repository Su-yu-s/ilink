package cn.ilink.collab;

import cn.ilink.entity.TeamCollabEvent;
import cn.ilink.entity.TeamCollabSpreadsheet;
import cn.ilink.entity.TeamCollabSpreadsheetVersion;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetVersionMapper;
import cn.ilink.service.UserService;
import cn.ilink.service.kg.KgIndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamSpreadsheetServiceIdempotencyTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private TeamCollabSpreadsheetMapper spreadsheetMapper;
    private TeamCollabSpreadsheetVersionMapper versionMapper;
    private TeamCollaborationEventService eventService;
    private TeamSpreadsheetService service;

    @BeforeEach
    void setUp() {
        spreadsheetMapper = mock(TeamCollabSpreadsheetMapper.class);
        versionMapper = mock(TeamCollabSpreadsheetVersionMapper.class);
        eventService = mock(TeamCollaborationEventService.class);
        TeamCollaborationAuthorization authorization =
            mock(TeamCollaborationAuthorization.class);
        UserService userService = mock(UserService.class);
        when(userService.getById(anyLong())).thenReturn(user());
        TeamDemand team = new TeamDemand();
        team.setId(20L);
        team.setStatus("TEAMING");
        TeamCollaborationAuthorization.TeamContext teamContext =
            new TeamCollaborationAuthorization.TeamContext(team, 7L,
                TeamCollaborationAuthorization.TeamRole.LEADER, true, true);
        when(authorization.requireEditableAsset(20L, "SPREADSHEET", 9L, 7L))
            .thenReturn(new TeamCollaborationAuthorization.AssetContext(
                teamContext, "SPREADSHEET", 9L, "TEAM", 2,
                TeamCollaborationAuthorization.Permission.MANAGE, true, true));
        service = new TeamSpreadsheetService(
            spreadsheetMapper,
            versionMapper,
            authorization,
            mock(TeamCollaborationLeaseService.class),
            eventService,
            mock(KgIndexService.class),
            userService,
            objectMapper);
    }

    @Test
    void aRetryAfterAnUnknownResponseReturnsTheCommittedSheet() {
        TeamCollabSpreadsheet current = sheet(2);
        when(spreadsheetMapper.selectByIdForUpdate(9L)).thenReturn(current);
        TeamCollabEvent previous = new TeamCollabEvent();
        previous.setMetadataJson("{\"requestHash\":\""
            + fingerprint("进度", "TEAM", 1, "方案", "已完成") + "\"}");
        when(eventService.findRequest(20L, "SPREADSHEET", 9L, "request-1"))
            .thenReturn(previous);

        TeamSpreadsheetService.SheetView view = service.update(20L, 9L, 7L,
            request("进度", "TEAM", 1, "方案", "已完成", "request-1"));

        assertEquals(2, view.version());
        verify(spreadsheetMapper, never()).update(any(), any());
        verify(versionMapper, never()).insert(any(TeamCollabSpreadsheetVersion.class));
    }

    @Test
    void reusingARequestIdWithDifferentSheetContentIsRejected() {
        when(spreadsheetMapper.selectByIdForUpdate(9L)).thenReturn(sheet(2));
        TeamCollabEvent previous = new TeamCollabEvent();
        previous.setMetadataJson("{\"requestHash\":\""
            + fingerprint("进度", "TEAM", 1, "方案", "旧值") + "\"}");
        when(eventService.findRequest(20L, "SPREADSHEET", 9L, "request-1"))
            .thenReturn(previous);

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> service.update(20L, 9L, 7L,
                request("进度", "TEAM", 1, "方案", "新值", "request-1")));

        assertEquals(TeamCollabException.Code.REQUEST_ID_REUSED, error.code());
        verify(spreadsheetMapper, never()).update(any(), any());
    }

    private TeamSpreadsheetService.SaveRequest request(String title, String visibility,
                                                       int baseVersion, String task,
                                                       String status, String requestId) {
        ObjectNode columns = objectMapper.createObjectNode();
        ArrayNode columnArray = columns.putArray("columns");
        columnArray.addObject()
            .put("id", "col_task").put("key", "task")
            .put("label", "任务").put("type", "TEXT").put("width", 160);
        columnArray.addObject()
            .put("id", "col_status").put("key", "status")
            .put("label", "状态").put("type", "TEXT").put("width", 160);
        ArrayNode rows = objectMapper.createArrayNode();
        ObjectNode row = rows.addObject();
        row.put("id", "row_1");
        ObjectNode cells = row.putObject("cells");
        cells.put("task", task);
        cells.put("status", status);
        return new TeamSpreadsheetService.SaveRequest(title, columns, rows, visibility,
            baseVersion, "lease", requestId, "MANUAL", "保存");
    }

    private String fingerprint(String title, String visibility, int baseVersion,
                               String task, String status) {
        String columns = "{\"columns\":[{\"id\":\"col_task\",\"key\":\"task\","
            + "\"label\":\"任务\",\"type\":\"TEXT\",\"width\":160},"
            + "{\"id\":\"col_status\",\"key\":\"status\",\"label\":\"状态\","
            + "\"type\":\"TEXT\",\"width\":160}]}";
        String rows = "[{\"id\":\"row_1\",\"cells\":{\"task\":\"" + task
            + "\",\"status\":\"" + status + "\"}}]";
        return sha256(title.trim() + "\u0000" + visibility + "\u0000" + columns
            + "\u0000" + rows + "\u0000" + baseVersion);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TeamCollabSpreadsheet sheet(int version) {
        TeamCollabSpreadsheet row = new TeamCollabSpreadsheet();
        row.setId(9L);
        row.setTeamId(20L);
        row.setTitle("进度");
        row.setVisibility("TEAM");
        row.setStatus("ACTIVE");
        row.setVersion(version);
        row.setCreatorUserId(7L);
        row.setLastEditorUserId(7L);
        row.setCreatedAt(new Date());
        row.setUpdatedAt(new Date());
        TeamSpreadsheetSchema.Snapshot snapshot = TeamSpreadsheetSchema
            .validateAndCanonicalize(objectMapper, canonicalColumns(), canonicalRows());
        row.setColumnsJson(snapshot.columnsJson());
        row.setRowsJson(snapshot.rowsJson());
        row.setContentHash(snapshot.contentHash());
        return row;
    }

    private ArrayNode canonicalColumns() {
        ArrayNode columns = objectMapper.createArrayNode();
        columns.addObject()
            .put("id", "col_task").put("key", "task")
            .put("label", "任务").put("type", "TEXT").put("width", 160);
        columns.addObject()
            .put("id", "col_status").put("key", "status")
            .put("label", "状态").put("type", "TEXT").put("width", 160);
        return columns;
    }

    private ArrayNode canonicalRows() {
        ArrayNode rows = objectMapper.createArrayNode();
        ObjectNode row = rows.addObject();
        row.put("id", "row_1");
        ObjectNode cells = row.putObject("cells");
        cells.put("task", "方案");
        cells.put("status", "已完成");
        return rows;
    }

    private User user() {
        User user = new User();
        user.setId(7L);
        user.setRealName("测试成员");
        return user;
    }
}
