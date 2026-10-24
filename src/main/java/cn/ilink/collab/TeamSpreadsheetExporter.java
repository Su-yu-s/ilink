package cn.ilink.collab;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** RFC 4180 CSV and non-macro XLSX export with formula-injection defense. */
public final class TeamSpreadsheetExporter {
    private TeamSpreadsheetExporter() {
    }

    public static byte[] csv(List<TeamSpreadsheetSchema.Column> columns,
                             List<TeamSpreadsheetSchema.Row> rows) {
        StringBuilder out = new StringBuilder();
        out.append('\uFEFF');
        appendCsvRow(out, columns.stream().map(TeamSpreadsheetSchema.Column::label).toList());
        for (TeamSpreadsheetSchema.Row row : rows) {
            appendCsvRow(out, columns.stream()
                .map(column -> escapeFormula(row.cells().getOrDefault(column.key(), "")))
                .toList());
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] xlsx(String title, List<TeamSpreadsheetSchema.Column> columns,
                              List<TeamSpreadsheetSchema.Row> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(safeSheetName(title));
            sheet.createFreezePane(0, 1);
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(columns.get(i).label());
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, Math.max(10, Math.min(60, columns.get(i).width() / 7)) * 256);
            }
            for (int r = 0; r < rows.size(); r++) {
                Row excelRow = sheet.createRow(r + 1);
                for (int c = 0; c < columns.size(); c++) {
                    TeamSpreadsheetSchema.Column column = columns.get(c);
                    String value = rows.get(r).cells().getOrDefault(column.key(), "");
                    Cell cell = excelRow.createCell(c);
                    if ("NUMBER".equals(column.type()) && !value.isBlank()) {
                        cell.setCellValue(Double.parseDouble(value));
                    } else if ("CHECKBOX".equals(column.type())) {
                        cell.setCellValue("true".equals(value));
                    } else {
                        cell.setCellValue(escapeFormula(value));
                    }
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new TeamCollabException(TeamCollabException.Code.EXPORT_FAILED,
                "表格导出失败");
        }
    }

    public static String escapeFormula(String value) {
        if (value == null || value.isEmpty()) return "";
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
            || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    private static void appendCsvRow(StringBuilder out, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            String value = values.get(i) == null ? "" : values.get(i);
            boolean quote = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
            if (quote) out.append('"').append(value.replace("\"", "\"\"")).append('"');
            else out.append(value);
        }
        out.append("\r\n");
    }

    private static String safeSheetName(String title) {
        String value = title == null ? "表格" : title.replaceAll("[\\\\/:*?\\[\\]]", "_").trim();
        if (value.isEmpty()) value = "表格";
        return value.substring(0, Math.min(31, value.length()));
    }
}
