package cn.ilink.collab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Validates and canonicalizes the stable column/row schema used by team tables. */
public final class TeamSpreadsheetSchema {
    public static final int MAX_COLUMNS = 50;
    public static final int MAX_ROWS = 5000;
    public static final int MAX_JSON_BYTES = 1024 * 1024;
    public static final int MAX_SELECT_OPTIONS = 50;
    public static final Set<String> TYPES = Set.of(
        "TEXT", "NUMBER", "DATE", "CHECKBOX", "SELECT");

    private TeamSpreadsheetSchema() {
    }

    public record Snapshot(List<Column> columns, List<Row> rows,
                           String columnsJson, String rowsJson, String contentHash) {
    }

    public record Column(String id, String key, String label, String type,
                         int width, List<String> options) {
    }

    public record Row(String id, java.util.Map<String, String> cells) {
    }

    public static Snapshot validateAndCanonicalize(ObjectMapper mapper,
                                                   JsonNode columnsNode,
                                                   JsonNode rowsNode) {
        if (columnsNode == null || !columnsNode.isArray()
            || columnsNode.size() < 1 || columnsNode.size() > MAX_COLUMNS) {
            fail("表格必须有 1-50 列");
        }
        if (rowsNode == null || !rowsNode.isArray() || rowsNode.size() > MAX_ROWS) {
            fail("表格行数不能超过 5000");
        }
        List<Column> columns = new ArrayList<>();
        Set<String> columnIds = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode node : columnsNode) {
            if (node == null || !node.isObject()) fail("列定义必须是对象");
            String id = requiredId(node, "id", "列 ID");
            if (!columnIds.add(id)) fail("列 ID 不能重复");
            String key = requiredText(node, "key", 80, "列键");
            String normalizedKey = key.trim().toLowerCase(Locale.ROOT);
            if (!normalizedKey.matches("[a-z][a-z0-9_]{0,79}")) {
                fail("列键必须为小写字母开头的标识符");
            }
            if (!keys.add(normalizedKey)) fail("列键不能重复");
            String label = requiredText(node, "label", 80, "列名");
            String type = requiredText(node, "type", 16, "列类型").toUpperCase(Locale.ROOT);
            if (!TYPES.contains(type)) fail("列类型仅支持 TEXT/NUMBER/DATE/CHECKBOX/SELECT");
            int width = node.path("width").isInt() ? node.path("width").asInt() : 160;
            width = Math.max(72, Math.min(480, width));
            List<String> options = new ArrayList<>();
            if ("SELECT".equals(type)) {
                JsonNode optionsNode = node.get("options");
                if (optionsNode == null || !optionsNode.isArray()
                    || optionsNode.size() < 1 || optionsNode.size() > MAX_SELECT_OPTIONS) {
                    fail("SELECT 列必须有 1-50 个选项");
                }
                for (JsonNode option : optionsNode) {
                    if (!option.isTextual()) fail("下拉选项必须是文本");
                    String value = option.asText().trim();
                    if (value.isEmpty() || value.length() > 80 || options.contains(value)) {
                        fail("下拉选项为空、过长或重复");
                    }
                    options.add(value);
                }
            } else if (node.has("options")) {
                fail("只有 SELECT 列可以包含 options");
            }
            columns.add(new Column(id, normalizedKey, label, type, width, List.copyOf(options)));
        }
        List<Row> rows = new ArrayList<>();
        Set<String> rowIds = new HashSet<>();
        for (JsonNode node : rowsNode) {
            if (node == null || !node.isObject()) fail("行必须是对象");
            String id = requiredId(node, "id", "行 ID");
            if (!rowIds.add(id)) fail("行 ID 不能重复");
            JsonNode cellsNode = node.get("cells");
            if (cellsNode == null || !cellsNode.isObject()) fail("行必须包含 cells");
            java.util.Map<String, String> cells = new java.util.LinkedHashMap<>();
            for (Column column : columns) {
                JsonNode value = cellsNode.get(column.key());
                String text = value == null || value.isNull() ? "" : scalarText(value);
                if (text.length() > 4000) fail("单元格内容不能超过 4000 字符");
                text = validateCell(column, text);
                cells.put(column.key(), text);
            }
            var names = cellsNode.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                if (columns.stream().noneMatch(column -> column.key().equals(key))) {
                    fail("单元格包含不存在的列: " + key);
                }
            }
            rows.add(new Row(id, java.util.Collections.unmodifiableMap(cells)));
        }
        ObjectNode canonicalColumns = mapper.createObjectNode();
        ArrayNode columnArray = canonicalColumns.putArray("columns");
        for (Column column : columns) {
            ObjectNode item = columnArray.addObject();
            item.put("id", column.id());
            item.put("key", column.key());
            item.put("label", column.label());
            item.put("type", column.type());
            item.put("width", column.width());
            if ("SELECT".equals(column.type())) {
                ArrayNode options = item.putArray("options");
                column.options().forEach(options::add);
            }
        }
        ArrayNode rowArray = mapper.createArrayNode();
        for (Row row : rows) {
            ObjectNode item = rowArray.addObject();
            item.put("id", row.id());
            ObjectNode cells = item.putObject("cells");
            row.cells().forEach(cells::put);
        }
        try {
            String columnsJson = mapper.writeValueAsString(columnArray);
            String rowsJson = mapper.writeValueAsString(rowArray);
            int bytes = columnsJson.getBytes(StandardCharsets.UTF_8).length
                + rowsJson.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_JSON_BYTES) {
                throw new TeamCollabException(TeamCollabException.Code.CONTENT_TOO_LARGE,
                    "表格内容不能超过 1 MiB");
            }
            String hash = sha256("iLink/team-collab/spreadsheet/v1\u0000"
                + columnsJson + "\u0000" + rowsJson);
            return new Snapshot(List.copyOf(columns), List.copyOf(rows),
                columnsJson, rowsJson, hash);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("表格内容无法序列化", e);
        }
    }

    private static String validateCell(Column column, String value) {
        if (value.isEmpty()) return value;
        return switch (column.type()) {
            case "NUMBER" -> {
                try {
                    String normalized = new java.math.BigDecimal(value.trim())
                        .stripTrailingZeros().toPlainString();
                    yield normalized;
                } catch (NumberFormatException e) {
                    fail("「" + column.label() + "」必须是数字");
                    yield value;
                }
            }
            case "DATE" -> {
                try {
                    yield LocalDate.parse(value.trim()).toString();
                } catch (RuntimeException e) {
                    fail("「" + column.label() + "」必须是 YYYY-MM-DD 日期");
                    yield value;
                }
            }
            case "CHECKBOX" -> {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (!Set.of("true", "false", "1", "0", "yes", "no").contains(normalized)) {
                    fail("「" + column.label() + "」必须是勾选值");
                }
                yield Set.of("true", "1", "yes").contains(normalized) ? "true" : "false";
            }
            case "SELECT" -> {
                if (!column.options().contains(value)) {
                    fail("「" + column.label() + "」的值不在下拉选项中");
                }
                yield value;
            }
            default -> value;
        };
    }

    private static String scalarText(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isNumber() || node.isBoolean()) return node.asText();
        fail("单元格值只能是文本、数字或布尔值");
        return "";
    }

    private static String requiredId(JsonNode node, String field, String label) {
        String value = requiredText(node, field, 64, label);
        if (!value.matches("[A-Za-z0-9_-]{1,64}")) fail(label + "格式不合法");
        return value;
    }

    private static String requiredText(JsonNode node, String field, int max, String label) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) {
            fail(label + "不能为空");
        }
        String text = value.asText().trim();
        if (text.length() > max) fail(label + "过长");
        return text;
    }

    private static void fail(String message) {
        throw new TeamCollabException(TeamCollabException.Code.SPREADSHEET_SCHEMA_INVALID,
            message);
    }

    private static String sha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
