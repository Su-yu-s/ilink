package cn.ilink.collab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamSpreadsheetSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void canonicalSchemaNormalisesKeysWidthsAndCellOrder() throws Exception {
        ArrayNode columns = mapper.createArrayNode();
        columns.addObject().put("id", "c1").put("key", "Task").put("label", "任务")
            .put("type", "text").put("width", 10);
        ArrayNode rows = mapper.createArrayNode();
        rows.addObject().put("id", "r1").putObject("cells").put("task", "  写方案  ");

        TeamSpreadsheetSchema.Snapshot snapshot =
            TeamSpreadsheetSchema.validateAndCanonicalize(mapper, columns, rows);

        JsonNode canonicalColumn = mapper.readTree(snapshot.columnsJson()).get(0);
        assertEquals("task", canonicalColumn.path("key").asText());
        assertEquals("TEXT", canonicalColumn.path("type").asText());
        assertEquals(72, canonicalColumn.path("width").asInt());
        JsonNode canonicalRow = mapper.readTree(snapshot.rowsJson()).get(0);
        assertEquals("  写方案  ", canonicalRow.path("cells").path("task").asText());
        assertEquals(64, snapshot.contentHash().length());
    }

    @Test
    void duplicateKeysAreRejected() {
        ArrayNode columns = mapper.createArrayNode();
        columns.addObject().put("id", "c1").put("key", "task").put("label", "任务").put("type", "TEXT");
        columns.addObject().put("id", "c2").put("key", "TASK").put("label", "重复").put("type", "TEXT");

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> TeamSpreadsheetSchema.validateAndCanonicalize(
                mapper, columns, mapper.createArrayNode()));

        assertEquals(TeamCollabException.Code.SPREADSHEET_SCHEMA_INVALID, error.code());
    }

    @Test
    void cellsOutsideTheColumnSchemaAreRejected() {
        ArrayNode columns = mapper.createArrayNode();
        columns.addObject().put("id", "c1").put("key", "task").put("label", "任务").put("type", "TEXT");
        ArrayNode rows = mapper.createArrayNode();
        rows.addObject().put("id", "r1").putObject("cells").put("owner", "张三");

        assertThrows(TeamCollabException.class,
            () -> TeamSpreadsheetSchema.validateAndCanonicalize(mapper, columns, rows));
    }

    @Test
    void rowsNeedStableIdentifiers() {
        ArrayNode columns = mapper.createArrayNode();
        columns.addObject().put("id", "c1").put("key", "task").put("label", "任务").put("type", "TEXT");
        ArrayNode rows = mapper.createArrayNode();
        rows.addObject().putObject("cells").put("task", "缺行 ID");

        assertThrows(TeamCollabException.class,
            () -> TeamSpreadsheetSchema.validateAndCanonicalize(mapper, columns, rows));
    }

    @Test
    void selectColumnsRequireOptions() {
        ArrayNode columns = mapper.createArrayNode();
        columns.addObject().put("id", "c1").put("key", "status").put("label", "状态")
            .put("type", "SELECT");

        assertThrows(TeamCollabException.class,
            () -> TeamSpreadsheetSchema.validateAndCanonicalize(
                mapper, columns, mapper.createArrayNode()));
    }

    @Test
    void numberCellsMustStayNumeric() {
        ArrayNode columns = mapper.createArrayNode();
        columns.addObject().put("id", "c1").put("key", "hours").put("label", "工时")
            .put("type", "NUMBER");
        ArrayNode rows = mapper.createArrayNode();
        rows.addObject().put("id", "r1").putObject("cells").put("hours", "很多");

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> TeamSpreadsheetSchema.validateAndCanonicalize(mapper, columns, rows));
        assertTrue(error.getMessage().contains("数字"));
    }
}
