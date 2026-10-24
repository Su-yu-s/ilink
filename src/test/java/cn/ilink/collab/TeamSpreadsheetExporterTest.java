package cn.ilink.collab;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamSpreadsheetExporterTest {

    @Test
    void csvUsesUtf8BomRfc4180EscapingAndFormulaDefense() {
        List<TeamSpreadsheetSchema.Column> columns = List.of(
            new TeamSpreadsheetSchema.Column("c1", "task", "任务", "TEXT", 180, List.of()),
            new TeamSpreadsheetSchema.Column("c2", "note", "备注", "TEXT", 180, List.of())
        );
        List<TeamSpreadsheetSchema.Row> rows = List.of(
            new TeamSpreadsheetSchema.Row("r1", Map.of(
                "task", "写, \"方案\"\n第二行",
                "note", "=SUM(A1:A2)"
            ))
        );

        String csv = new String(TeamSpreadsheetExporter.csv(columns, rows), StandardCharsets.UTF_8);

        assertEquals("\uFEFF任务,备注\r\n"
            + "\"写, \"\"方案\"\"\n第二行\",'=SUM(A1:A2)\r\n", csv);
    }

    @Test
    void everySpreadsheetFormulaPrefixIsNeutralized() {
        for (String value : List.of(
            "=SUM(A1:A2)", "+1+1", "-2+3", "@SUM(A1:A2)", "\t=cmd", "\r=cmd")) {
            assertEquals("'" + value, TeamSpreadsheetExporter.escapeFormula(value));
        }
        assertEquals("普通文本", TeamSpreadsheetExporter.escapeFormula("普通文本"));
        assertEquals("", TeamSpreadsheetExporter.escapeFormula(null));
    }

    @Test
    void xlsxWritesSafeSheetNameHeadersAndNeutralizedText() throws Exception {
        List<TeamSpreadsheetSchema.Column> columns = List.of(
            new TeamSpreadsheetSchema.Column("c1", "task", "任务", "TEXT", 180, List.of()),
            new TeamSpreadsheetSchema.Column("c2", "hours", "工时", "NUMBER", 120, List.of()),
            new TeamSpreadsheetSchema.Column("c3", "done", "完成", "CHECKBOX", 100, List.of())
        );
        List<TeamSpreadsheetSchema.Row> rows = List.of(
            new TeamSpreadsheetSchema.Row("r1", Map.of(
                "task", "=HYPERLINK(\"https://example.invalid\")",
                "hours", "12.5",
                "done", "true"
            ))
        );
        String title = "  季度/预算:*?[最终]\\版本-".repeat(5);

        byte[] bytes = TeamSpreadsheetExporter.xlsx(title, columns, rows);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertFalse(sheet.getSheetName().isBlank());
            assertTrue(sheet.getSheetName().length() <= 31);
            assertFalse(sheet.getSheetName().matches(".*[\\\\/:*?\\[\\]].*"));
            assertEquals("任务", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("工时", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("完成", sheet.getRow(0).getCell(2).getStringCellValue());
            Row data = sheet.getRow(1);
            assertEquals("'=HYPERLINK(\"https://example.invalid\")",
                data.getCell(0).getStringCellValue());
            Cell hours = data.getCell(1);
            assertEquals(CellType.NUMERIC, hours.getCellType());
            assertEquals(12.5, hours.getNumericCellValue());
            Cell done = data.getCell(2);
            assertEquals(CellType.BOOLEAN, done.getCellType());
            assertTrue(done.getBooleanCellValue());
        }
    }
}
