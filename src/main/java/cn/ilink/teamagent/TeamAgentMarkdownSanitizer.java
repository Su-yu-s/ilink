package cn.ilink.teamagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes persisted Agent prose. The same policy is used for action-card
 * display text and weekly-report bodies; the browser still applies DOMPurify.
 */
public final class TeamAgentMarkdownSanitizer {
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern DANGEROUS_BLOCK = Pattern.compile(
        "(?is)<\\s*(script|style|iframe|object|embed|form|svg|math)[^>]*>.*?</\\s*\\1\\s*>");
    private static final Pattern HTML_OPEN_OR_CLOSE = Pattern.compile("</?[A-Za-z][^>]*>");
    private static final Pattern EXCESSIVE_NEWLINES = Pattern.compile("\\n{4,}");

    private TeamAgentMarkdownSanitizer() {}

    public static String sanitize(String raw, int maxChars) {
        String text = TeamAgentHashes.normalizeText(raw == null ? "" : raw);
        text = DANGEROUS_BLOCK.matcher(text).replaceAll("");
        text = IMAGE.matcher(text).replaceAll("");
        text = LINK.matcher(text).replaceAll("$1");
        text = HTML_OPEN_OR_CLOSE.matcher(text).replaceAll("");
        text = text.replace('\u0000', ' ').trim();
        text = EXCESSIVE_NEWLINES.matcher(text).replaceAll("\n\n\n");
        if (maxChars > 0 && text.length() > maxChars) {
            int cut = Math.max(0, maxChars - 1);
            text = text.substring(0, cut).stripTrailing() + "…";
        }
        return text;
    }

    public static List<Map<String, Object>> sanitizeCitations(List<Map<String, Object>> citations) {
        if (citations == null || citations.isEmpty()) return List.of();
        List<Map<String, Object>> safe = new ArrayList<>();
        for (Map<String, Object> raw : citations) {
            if (raw == null) continue;
            if (raw.containsKey("kind") || raw.containsKey("label")
                || raw.containsKey("generatedAt")) {
                Map<String, Object> structured = sanitizeStructuredCitation(raw);
                if (!structured.isEmpty()) safe.add(structured);
                if (safe.size() >= 20) break;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkId", asLong(raw.get("chunkId")));
            row.put("docType", safeCode(raw.get("docType"), 24));
            row.put("docId", asLong(raw.get("docId")));
            row.put("title", sanitize(String.valueOf(raw.getOrDefault("title", "未命名来源")), 160));
            if (row.get("chunkId") != null || row.get("docId") != null) safe.add(row);
            if (safe.size() >= 20) break;
        }
        return safe;
    }

    private static Map<String, Object> sanitizeStructuredCitation(Map<String, Object> raw) {
        String kind = allowedKind(raw.get("kind"));
        Long id = asLong(raw.get("id"));
        if (kind == null || id == null || id <= 0) return Map.of();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.put("id", id);
        String label = sanitize(String.valueOf(raw.getOrDefault("label", kind)), 160);
        row.put("label", label.isBlank() ? kind : label);
        String generatedAt = safeIsoInstant(raw.get("generatedAt"));
        if (generatedAt != null) row.put("generatedAt", generatedAt);
        return row;
    }

    private static String allowedKind(Object value) {
        String kind = safeCode(value, 32).toUpperCase(java.util.Locale.ROOT);
        return switch (kind) {
            case "TASK", "TEAM_TASK", "TEAM_MEMBER", "TEAM", "TEAM_STATE",
                "TEAM_METRIC", "WEEKLY_METRIC", "KNOWLEDGE_FRESHNESS" -> kind;
            default -> null;
        };
    }

    private static String safeIsoInstant(Object value) {
        if (value == null) return null;
        try {
            return java.time.Instant.parse(value.toString()).toString();
        } catch (java.time.format.DateTimeParseException ignored) {
            return null;
        }
    }

    private static String safeCode(Object value, int max) {
        String text = value == null ? "" : value.toString().replaceAll("[^A-Za-z0-9_-]", "");
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
