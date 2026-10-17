package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Temporal query coverage and requested-subject identity are deliberately separate. */
final class TemporalAnswerScope {
    enum Resolution { EXACT, UNRESOLVED, AMBIGUOUS, FUZZY_UNCONFIRMED }

    record SubjectResolution(String requestedName, Resolution resolution, Long entityId,
                             String entityName, String entityType, List<Long> matchingEntityIds,
                             boolean windowComplete) {
        boolean exact() { return resolution == Resolution.EXACT; }
    }

    record Analysis(boolean globalTemporal, boolean subjectIdentificationComplete, String identificationReason,
                    List<KgTimeRange> windows, List<SubjectResolution> subjects,
                    boolean resolvedWindowComplete, boolean conditionalDocumentListAllowed,
                    String resolvedTitle) {
        Analysis {
            windows = List.copyOf(windows);
            subjects = List.copyOf(subjects);
        }

        boolean requestedSubjectsConfirmed() {
            return globalTemporal || subjectIdentificationComplete && !subjects.isEmpty()
                && subjects.stream().allMatch(SubjectResolution::exact);
        }

        boolean requestedScopeComplete() {
            return requestedSubjectsConfirmed() && resolvedWindowComplete;
        }

        Map<String, Object> metadata() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("policy", "temporal-subject-and-window-v1");
            result.put("globalTemporal", globalTemporal);
            result.put("subjectIdentificationComplete", subjectIdentificationComplete);
            result.put("identificationReason", identificationReason);
            result.put("requestedSubjectsConfirmed", requestedSubjectsConfirmed());
            result.put("resolvedWindowComplete", resolvedWindowComplete);
            result.put("requestedScopeComplete", requestedScopeComplete());
            result.put("conditionalDocumentListAllowed", conditionalDocumentListAllowed);
            result.put("resolvedTitle", resolvedTitle);
            result.put("windows", windows.stream().map(window -> Map.of(
                "startInclusive", window.startInclusive().toString(),
                "endExclusive", window.endExclusive().toString())).toList());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (SubjectResolution subject : subjects) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("requestedName", subject.requestedName());
                row.put("resolution", subject.resolution().name());
                row.put("entityId", subject.entityId());
                row.put("entityName", subject.entityName());
                row.put("entityType", subject.entityType());
                row.put("matchingEntityIds", subject.matchingEntityIds());
                row.put("windowComplete", subject.windowComplete());
                rows.add(row);
            }
            result.put("subjects", List.copyOf(rows));
            return result;
        }
    }

    // An approximate document title can support a conditional positive list, never absence or an exhaustive count.
    private static final Pattern REQUIRES_CONFIRMED_SUBJECT = Pattern.compile(
        "没有|不存在|无关|未关联|不(?:关联|相关)|无(?:关联|关系|竞赛|赛事)"
            + "|(?:^|[^\\d])0\\s*(?:个|条|项)|零(?:个|条|项)"
            + "|是否|是不是|有无|有没有|关联吗|有关联吗|有关系吗|有效吗"
            + "|总数|总共|一共|数量|计数|数目|几个|多少|全部|所有|完整|全集|每个|各个|均|都");
    private static final Pattern POSITIVE_COMPETITION_LIST = Pattern.compile(
        "(?:哪个|哪些|什么|列出|列举).*(?:竞赛|赛事)|(?:竞赛|赛事).*(?:有哪些|列出|列举)");
    private static final Pattern ABSENCE = Pattern.compile(
        "(?:没有|不存在|未发现|未找到|未检索到|找不到|无)"
            + "(?:任何|其他|直接的?|相关的?|该主体的|该记录的|该文档的|所问主体的|该|上述|所问"
            + "|匹配的?|符合(?:所问)?条件的?|符合(?:所问)?条件且仍有效的?){0,3}"
            + "(?:竞赛|赛事)?(?:关联|关系)(?:记录|边)?"
            + "|(?:没有|未)(?:关联|提到)(?:任何|其他)?(?:竞赛|赛事)"
            + "|(?:竞赛|赛事)?(?:关联|关系)(?:记录|边)?(?:不存在|未收录|为零|为0)"
            + "|(?:^|[^\\d])(?:0|零)\\s*(?:条|个|项)(?:竞赛|赛事)?(?:关联|关系)");
    private static final Pattern QUOTATION = Pattern.compile(
        "“[^”]*”|\"[^\"]*\"|‘[^’]*’|「[^」]*」|《[^》]*》");
    private static final Pattern ASSERTION_SEPARATOR = Pattern.compile("然而|可是|不过|但是|但|而|却|[,，]");
    private static final Pattern NON_ASSERTIVE_PREFIX = Pattern.compile(
        "(?:不能|无法|尚不能|尚未|未能|不应|不得|禁止|避免).{0,30}"
            + "(?:证明|确认|证实|确定|认定|判断|断言|声称|认为|说|表述)"
            + "|(?:没有|缺少|缺乏)证据.{0,20}(?:证明|确认|证实|断言)");

    private TemporalAnswerScope() { }

    /** A partial or unresolved subject/window cannot support an assistant assertion of no matching relations. */
    static List<String> absenceViolations(String question, QueryEvidencePlan plan,
                                          EvidenceLedger ledger, String answer) {
        if (question == null || question.isBlank() || plan == null || !plan.requiresTemporal()
            || answer == null || answer.isBlank()) return List.of();
        if (assess(question, plan, ledger).requestedScopeComplete()) return List.of();
        LinkedHashSet<String> failures = new LinkedHashSet<>();
        String body = answer.replaceAll("(?s)```.*?(?:```|$)", "").replaceAll("`[^`]*`", "");
        for (String line : body.split("\\R")) {
            if (line.stripLeading().startsWith(">")) continue;
            for (String sentence : line.split("[。；;！？!?]")) {
                String assertion = QUOTATION.matcher(sentence).replaceAll("");
                for (String local : ASSERTION_SEPARATOR.split(assertion)) {
                    java.util.regex.Matcher absence = ABSENCE.matcher(local);
                    while (absence.find()) {
                        String prefix = local.substring(Math.max(0, absence.start() - 70), absence.start());
                        if (!NON_ASSERTIVE_PREFIX.matcher(prefix).find()) {
                            failures.add("TEMPORAL_ABSENCE_SCOPE_UNCONFIRMED: " + local.trim());
                            break;
                        }
                    }
                }
            }
        }
        return List.copyOf(failures);
    }

    static Analysis assess(String question, QueryEvidencePlan plan, EvidenceLedger ledger) {
        List<KgTimeRange> windows = plan.timeRanges().isEmpty()
            ? plan.timeRange().map(List::of).orElseGet(List::of) : plan.timeRanges();
        if (plan.globalTemporal()) {
            boolean complete = !windows.isEmpty()
                && windows.stream().allMatch(window -> ledger.completeTemporal(null, window));
            return new Analysis(true, true, "GLOBAL_AUTHORIZED_WINDOW", windows, List.of(), complete, false, "");
        }
        QueryIntent.SubjectRequests request = QueryIntent.subjectRequests(question);
        List<SubjectResolution> subjects = new ArrayList<>();
        for (String requested : request.subjects()) {
            List<EvidenceLedger.EntityFact> exact = ledger.entities().stream()
                // 括号同位语只用于名称对齐（iLink 平台（Agentic GraphRAG 项目）→ iLink Agentic GraphRAG）。
                .filter(entity -> QueryIntent.sameSubjectName(requested, entity.name()))
                .sorted(Comparator.comparing(EvidenceLedger.EntityFact::id)).toList();
            List<EvidenceLedger.EntityFact> matches = exact.isEmpty() ? ledger.entities().stream()
                .filter(entity -> new QueryIntent(List.of(requested), "", false).matchesSubject(entity.name()))
                .sorted(Comparator.comparing(EvidenceLedger.EntityFact::id)).toList() : exact;
            Resolution resolution = exact.size() == 1 ? Resolution.EXACT
                : matches.size() > 1 ? Resolution.AMBIGUOUS
                : matches.isEmpty() ? Resolution.UNRESOLVED : Resolution.FUZZY_UNCONFIRMED;
            EvidenceLedger.EntityFact anchor = matches.size() == 1 ? matches.get(0) : null;
            boolean complete = anchor != null && !windows.isEmpty()
                && windows.stream().allMatch(window -> ledger.completeTemporal(anchor.id(), window));
            subjects.add(new SubjectResolution(requested, resolution, anchor == null ? null : anchor.id(),
                anchor == null ? null : anchor.name(), anchor == null ? null : anchor.type(),
                matches.stream().map(EvidenceLedger.EntityFact::id).toList(), complete));
        }
        boolean windowComplete = request.complete() && !subjects.isEmpty()
            && subjects.stream().allMatch(subject -> subject.entityId() != null && subject.windowComplete());
        boolean conditional = false;
        String title = "";
        if (plan.requiresTemporal() && !plan.historicalStatus() && !plan.temporalUnresolved()
            && request.complete() && subjects.size() == 1 && windows.size() == 1 && windowComplete
            && POSITIVE_COMPETITION_LIST.matcher(question == null ? "" : question).find()
            && !REQUIRES_CONFIRMED_SUBJECT.matcher(question == null ? "" : question).find()) {
            SubjectResolution subject = subjects.get(0);
            if (subject.resolution() == Resolution.FUZZY_UNCONFIRMED && "POST".equals(subject.entityType())
                && abbreviatedTitle(subject.requestedName(), subject.entityName())
                && ledger.chunks().stream().anyMatch(chunk -> QueryIntent.normalize(subject.entityName())
                    .equals(QueryIntent.normalize(chunk.title())))
                && hasPositiveCompetitionMatch(subject.entityId(), windows, ledger)) {
                conditional = true;
                title = subject.entityName();
            }
        }
        return new Analysis(false, request.complete(), request.reason(), windows, subjects,
            windowComplete, conditional, title);
    }

    private static boolean abbreviatedTitle(String requested, String canonical) {
        String expected = QueryIntent.normalize(requested);
        String full = QueryIntent.normalize(canonical);
        if (expected.length() < 3 || expected.length() >= full.length()
            || expected.length() * 5 < full.length() * 3) return false;
        int position = 0;
        for (int i = 0; i < full.length() && position < expected.length(); i++)
            if (full.charAt(i) == expected.charAt(position)) position++;
        return position == expected.length();
    }

    private static boolean hasPositiveCompetitionMatch(Long anchor, List<KgTimeRange> windows,
                                                        EvidenceLedger ledger) {
        return ledger.relations().stream().anyMatch(edge -> {
            Long other = Objects.equals(anchor, edge.sourceId()) ? edge.targetId()
                : Objects.equals(anchor, edge.targetId()) ? edge.sourceId() : null;
            if (other == null || !ledger.entity(other).map(entity -> "COMPETITION".equals(entity.type())).orElse(false)
                || edge.validFrom() == null) return false;
            return windows.stream().anyMatch(window -> edge.validFrom().before(window.endDate())
                && (edge.validTo() == null || edge.validTo().after(window.startDate())));
        });
    }
}
