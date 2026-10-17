package cn.ilink.service.kg;

import cn.ilink.service.ai.QwenTokenCounter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Select complete source sentences and graph facts; never cut serialized evidence mid-fact. */
final class EvidenceContext {
    // Text-token budget uses the pinned Qwen vocabulary; whole chunks are preferred.
    static final int DEFAULT_BUDGET = 2000, MAX_BUDGET = 3000;
    private static final Pattern SENTENCE = Pattern.compile("[^。！？；\\n]+[。！？；]?|[^\\n]+$");
    private record Unit(String key, String text, int score, int order) { }
    record Selection(String text, int tokenCount, int selected, int available) { }

    static Selection select(String question, EvidenceLedger ledger) {
        QueryIntent intent = QueryIntent.fromQuestion(question);
        GraphAnswerScope.Scope scope = GraphAnswerScope.describe(question, ledger);
        String inventory = scope.requested() ? scope.text() + scope.instruction() + "\n" : "";
        int inventoryTokens = tokens(inventory);
        String coverage = compactCoverage(ledger);
        // Reserve the query scope and the selection notice inside the same evidence budget.
        int reserved = inventoryTokens + tokens(coverage) + 96;
        int defaultLimit = Math.max(0, DEFAULT_BUDGET - reserved);
        int maximumLimit = Math.max(0, MAX_BUDGET - reserved);
        Set<String> terms = grams(question);
        Set<String> focus = grams(intent.focusText());
        for (String subject : intent.subjects()) terms.removeAll(grams(subject));
        Set<String> slots = slots(question);
        List<Unit> units = new ArrayList<>();
        int order = 0;
        for (var chunk : ledger.chunks()) {
            String body = Objects.toString(chunk.text(), "");
            String heading = "c#" + chunk.id() + " [" + chunk.docType() + ":" + chunk.docId()
                + " 段内偏移=0:" + body.length() + "] " + chunk.title() + "：";
            if (tokens(heading + body) <= defaultLimit) {
                units.add(new Unit("c" + chunk.id(), heading + body + "\n",
                    relevance(focus, body) * 12 + relevance(terms, body) * 3
                        + (intent.matchesSubject(chunk.title()) ? 60 : 0) + slotScore(slots, body), order++));
            } else {
                Matcher matcher = SENTENCE.matcher(body);
                while (matcher.find()) {
                    String sentence = matcher.group();
                    String label = "c#" + chunk.id() + " [" + chunk.docType() + ":" + chunk.docId()
                        + " 段内偏移=" + matcher.start() + ":" + matcher.end() + "] " + chunk.title() + "：";
                    units.add(new Unit("c" + chunk.id() + ":" + matcher.start(), label + sentence + "\n",
                        relevance(focus, sentence) * 12 + relevance(terms, sentence) * 3
                            + (intent.matchesSubject(chunk.title()) ? 60 : 0) + slotScore(slots, sentence), order++));
                }
            }
        }
        for (var relation : ledger.relations()) {
            String source = ledger.entity(relation.sourceId()).map(EvidenceLedger.EntityFact::name).orElse("");
            String target = ledger.entity(relation.targetId()).map(EvidenceLedger.EntityFact::name).orElse("");
            String text = "r#" + relation.id() + " #" + relation.sourceId() + " " + source
                + " -> #" + relation.targetId() + " " + target + " " + relation.type()
                + " 有效期=[" + date(relation.validFrom(), "未明确") + ","
                + date(relation.validTo(), "开放结束") + ") 原文="
                + Objects.toString(relation.factText(), "") + "\n";
            int anchor = intent.matchesSubject(source) || intent.matchesSubject(target) ? 80 : 0;
            units.add(new Unit("r" + relation.id(), text,
                anchor + relevance(focus, text) * 12 + relevance(terms, text) * 3, order++));
        }
        for (var entity : ledger.entities()) {
            String text = "#" + entity.id() + " " + entity.name() + " [" + entity.type() + "]\n";
            units.add(new Unit("e" + entity.id(), text,
                (intent.matchesSubject(entity.name()) ? 30 : 0)
                    + relevance(focus, entity.name()) * 2, order++));
        }
        units.sort(Comparator.comparingInt(Unit::score).reversed().thenComparingInt(Unit::order));
        List<Unit> selected = new ArrayList<>();
        int used = 0;
        for (Unit unit : units) {
            int size = tokens(unit.text());
            int limit = unit.score() > 0 ? maximumLimit : defaultLimit;
            if (used + size <= limit) { selected.add(unit); used += size; }
        }
        selected.sort(Comparator.comparingInt(Unit::order));
        String text = render(inventory, coverage, selected, units.size());
        while (tokens(text) > MAX_BUDGET && !selected.isEmpty()) {
            Unit lowest = selected.stream().min(Comparator.comparingInt(Unit::score)
                .thenComparing(Comparator.comparingInt(Unit::order).reversed())).orElseThrow();
            selected.remove(lowest);
            text = render(inventory, coverage, selected, units.size());
        }
        return new Selection(text, tokens(text), selected.size(), units.size());
    }

    private static String render(String inventory, String coverage, List<Unit> selected, int available) {
        StringBuilder text = new StringBuilder(inventory);
        for (Unit unit : selected) text.append(unit.text());
        return text.append(coverage).append("\n【证据选择范围】").append(selected.size()).append('/')
            .append(available).append("；未展示项不表示不存在，不得据此宣称全集或全局无结果。\n").toString();
    }

    private static String compactCoverage(EvidenceLedger ledger) {
        if (ledger.coverage().isEmpty()) return "";
        StringBuilder text = new StringBuilder("\n【工具授权查询范围及完整性；分页完整性以候选清单说明为准】\n");
        for (EvidenceLedger.ToolCoverage item : ledger.coverage()) {
            text.append(item.tool()).append(" 主体=").append(item.entityId() == null ? "授权全局" : "#" + item.entityId());
            if (item.startInclusive() != null || item.endExclusive() != null)
                text.append(" 窗口=[").append(item.startInclusive()).append(',').append(item.endExclusive()).append(')');
            text.append(" 本次关系数=").append(item.relationIds().size()).append(" 完整=").append(item.complete())
                .append(" 截断=").append(item.truncated()).append('\n');
        }
        return text.toString();
    }

    private static String date(Date value, String absent) {
        return value == null ? absent : java.time.Instant.ofEpochMilli(value.getTime())
            .atZone(KgTimeRange.ZONE).toLocalDate().toString();
    }

    static int tokens(String text) { return QwenTokenCounter.count(text); }
    private static int relevance(Set<String> terms, String text) {
        Set<String> present = grams(text);
        present.retainAll(terms);
        return present.size();
    }
    /**
     * 问题要求的事实槽：数字/尺寸、路径、颜色、字段名。通用二元组打分会把
     * “{bizType}/yyyy/MM/dd/”“桌面端 72px” 这类唯一能答题的片段排到预算之外。
     */
    // 中文里“高度72px”没有空格，前置断言不能排除汉字。
    private static final Pattern SLOT_NUMBER = Pattern.compile(
        "(?i)\\d+(?:\\.\\d+)?\\s*(?:px|%|ms|MB|GB|kb|人|个|条|项|组|列|天|分钟|小时)(?![\\p{L}\\p{N}])");
    private static final Pattern SLOT_PATH = Pattern.compile(
        "[\\w{}./-]*/[\\w{}./-]*/|\\{[\\w]+\\}");
    private static final Pattern SLOT_COLOR = Pattern.compile("(?i)#[0-9a-f]{3,8}(?![0-9a-z])");
    private static final Pattern SLOT_FIELD = Pattern.compile("[A-Za-z]+[A-Z][A-Za-z]*");

    private static Set<String> slots(String question) {
        Set<String> result = new LinkedHashSet<>();
        String text = Objects.toString(question, "");
        for (Pattern pattern : List.of(SLOT_NUMBER, SLOT_PATH, SLOT_COLOR, SLOT_FIELD)) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) result.add(matcher.group().toLowerCase(Locale.ROOT).replaceAll("\\s+", ""));
        }
        // 问题没写具体值时，按它要求的“槽类型”给对应证据加权。
        if (text.contains("px") || text.contains("像素") || text.contains("高度") || text.contains("宽度")
            || text.contains("尺寸") || text.contains("大小") || text.contains("列宽"))
            result.add("__size__");
        if (text.contains("目录") || text.contains("路径") || text.contains("子目录")) result.add("__path__");
        if (text.contains("颜色") || text.contains("底色") || text.contains("文字色") || text.contains("色值"))
            result.add("__color__");
        return result;
    }

    private static int slotScore(Set<String> slots, String text) {
        if (slots.isEmpty()) return 0;
        String body = Objects.toString(text, "");
        int score = 0;
        if (slots.contains("__size__") && SLOT_NUMBER.matcher(body).find()) score += 40;
        if (slots.contains("__path__") && SLOT_PATH.matcher(body).find()) score += 40;
        if (slots.contains("__color__") && SLOT_COLOR.matcher(body).find()) score += 40;
        for (String slot : slots) {
            if (slot.startsWith("__")) continue;
            if (body.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").contains(slot)) score += 60;
        }
        return score;
    }

    private static Set<String> grams(String value) {
        String text = Objects.toString(value, "").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 1 < text.length(); i++) result.add(text.substring(i, i + 2));
        return result;
    }
}
