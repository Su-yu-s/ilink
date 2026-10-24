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
                             boolean windowComplete, String matchedAlias,
                             QueryIntent.AliasTier matchTier, List<Long> competingEntityIds) {
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
                row.put("matchedAlias", subject.matchedAlias());
                row.put("matchTier", subject.matchTier() == null ? null : subject.matchTier().name());
                row.put("competingEntityIds", subject.competingEntityIds());
                row.put("windowComplete", subject.windowComplete());
                rows.add(row);
            }
            result.put("subjects", List.copyOf(rows));
            return result;
        }
    }

    /**
     * A temporal question may name a group of records by a distinctive title fragment rather than
     * by one exact entity name. Keep that interpretation separate from exact subject identity:
     * record-scoped lists are allowed only for explicit positive list questions, one complete
     * window query for every selected record, and one unambiguous permission-checked record for
     * every requested group. They never authorize absence, counts, binary checks or exhaustive
     * collection claims.
     */
    record RecordGroup(String requested, String matchedCore,
                       List<EvidenceLedger.EntityFact> members) {
        RecordGroup {
            members = List.copyOf(members);
        }
        boolean completeFor(List<KgTimeRange> windows, EvidenceLedger ledger) {
            return !members.isEmpty() && windows.stream().allMatch(window -> members.stream()
                .allMatch(entity -> ledger.completeTemporal(entity.id(), window)));
        }
    }

    record RecordScope(List<RecordGroup> groups, List<KgTimeRange> windows, boolean allWindowsComplete,
                       boolean requestedRecordsConfirmed, Map<Long, Boolean> memberCoverage) {
        RecordScope {
            groups = List.copyOf(groups);
            windows = List.copyOf(windows);
            memberCoverage = memberCoverage == null ? Map.of() : Map.copyOf(memberCoverage);
        }

        List<Long> missingCompleteMemberIds() {
            return memberCoverage.entrySet().stream()
                .filter(entry -> !Boolean.TRUE.equals(entry.getValue()))
                .map(Map.Entry::getKey).sorted().toList();
        }

        boolean hasIncompleteMembers() {
            return memberCoverage.values().stream()
                .anyMatch(complete -> !Boolean.TRUE.equals(complete));
        }

        Map<String, Object> metadata() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (RecordGroup group : groups) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("requested", group.requested());
                row.put("matchedCore", group.matchedCore());
                row.put("memberEntityIds", group.members().stream()
                    .map(EvidenceLedger.EntityFact::id).toList());
                row.put("memberEntityNames", group.members().stream()
                    .map(EvidenceLedger.EntityFact::name).toList());
                row.put("memberWindowCoverage", group.members().stream().collect(
                    java.util.stream.Collectors.toMap(
                        EvidenceLedger.EntityFact::id,
                        entity -> windows.stream().map(window -> {
                            Map<String, Object> coverage = new LinkedHashMap<>();
                            coverage.put("startInclusive", window.startInclusive().toString());
                            coverage.put("endExclusive", window.endExclusive().toString());
                            coverage.put("complete", Boolean.TRUE.equals(memberCoverage.get(entity.id())));
                            return coverage;
                        }).toList(),
                        (left, right) -> left, LinkedHashMap::new)));
                row.put("completeMembers", group.members().stream()
                    .filter(entity -> Boolean.TRUE.equals(memberCoverage.get(entity.id())))
                    .map(EvidenceLedger.EntityFact::id).toList());
                row.put("missingCompleteMembers", group.members().stream()
                    .filter(entity -> !Boolean.TRUE.equals(memberCoverage.get(entity.id())))
                    .map(EvidenceLedger.EntityFact::id).toList());
                rows.add(row);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("policy", "temporal-record-group-v1");
            result.put("allWindowsComplete", allWindowsComplete);
            result.put("requestedRecordsConfirmed", requestedRecordsConfirmed);
            result.put("missingCompleteMemberIds", missingCompleteMemberIds());
            result.put("groups", rows);
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
    private static final Pattern RECORD_TAIL = Pattern.compile(
        "(?:(?:相关|有关)的?)?(?:记录|日志|报告|文档|帖子)(?:相关)?(?:还提到|提到)?\\s*$");
    private static final Pattern RECORD_GROUP_CONNECTOR = Pattern.compile("\\s*(?:与|和|及)\\s*");

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
            List<QueryIntent.ExactSubjectMatch> exactMatches =
                // 括号同位语只用于名称对齐（iLink 平台（Agentic GraphRAG 项目）→ iLink Agentic GraphRAG）。
                QueryIntent.bestExactMatches(requested, ledger.entities());
            List<EvidenceLedger.EntityFact> exact = exactMatches.stream()
                .map(QueryIntent.ExactSubjectMatch::entity)
                .sorted(Comparator.comparing(EvidenceLedger.EntityFact::id)).toList();
            List<EvidenceLedger.EntityFact> matches = exact.isEmpty() ? ledger.entities().stream()
                .filter(entity -> new QueryIntent(List.of(requested), "", false).matchesSubject(entity.name()))
                .sorted(Comparator.comparing(EvidenceLedger.EntityFact::id)).toList() : exact;
            Resolution resolution = exact.size() == 1 ? Resolution.EXACT
                : matches.size() > 1 ? Resolution.AMBIGUOUS
                : matches.isEmpty() ? Resolution.UNRESOLVED : Resolution.FUZZY_UNCONFIRMED;
            EvidenceLedger.EntityFact anchor = matches.size() == 1 ? matches.get(0) : null;
            QueryIntent.ExactSubjectMatch selectedMatch = resolution == Resolution.EXACT
                ? exactMatches.get(0) : exactMatches.stream().findFirst().orElse(null);
            List<Long> competingEntityIds = resolution == Resolution.EXACT
                ? exact.stream().filter(entity -> !Objects.equals(entity.id(), anchor.id()))
                    .map(EvidenceLedger.EntityFact::id).toList()
                : matches.stream().map(EvidenceLedger.EntityFact::id).toList();
            boolean complete = anchor != null && !windows.isEmpty()
                && windows.stream().allMatch(window -> ledger.completeTemporal(anchor.id(), window));
            subjects.add(new SubjectResolution(requested, resolution, anchor == null ? null : anchor.id(),
                anchor == null ? null : anchor.name(), anchor == null ? null : anchor.type(),
                matches.stream().map(EvidenceLedger.EntityFact::id).toList(), complete,
                selectedMatch == null ? null : selectedMatch.matchedAlias(),
                selectedMatch == null ? null : selectedMatch.tier(), competingEntityIds));
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

    static RecordScope recordScope(String question, QueryEvidencePlan plan, EvidenceLedger ledger,
                                   List<EvidenceLedger.EntityFact> selected) {
        List<KgTimeRange> windows = plan.timeRanges().isEmpty()
            ? plan.timeRange().map(List::of).orElseGet(List::of) : plan.timeRanges();
        List<RecordGroup> groups = new ArrayList<>();
        List<String> requestedGroups = requestedRecordGroups(question);
        for (String requested : requestedGroups) {
            String core = QueryIntent.normalize(
                RECORD_TAIL.matcher(requested.trim()).replaceFirst("").trim());
            if (core.length() < 4) continue;
            List<EvidenceLedger.EntityFact> candidates = selected.stream()
                .filter(entity -> entity != null && entity.name() != null && !"SKILL".equals(entity.type()))
                .filter(entity -> QueryIntent.normalize(entity.name()).contains(core))
                .sorted(Comparator.comparing(EvidenceLedger.EntityFact::id))
                .toList();
            if (!candidates.isEmpty()) {
                groups.add(new RecordGroup(requested.trim(), core, candidates));
            }
        }
        Map<Long, Boolean> memberCoverage = new LinkedHashMap<>();
        for (RecordGroup group : groups) {
            for (EvidenceLedger.EntityFact member : group.members()) {
                boolean complete = !windows.isEmpty() && windows.stream()
                    .allMatch(window -> ledger.completeTemporal(member.id(), window));
                memberCoverage.put(member.id(), complete);
            }
        }
        boolean allWindowsComplete = !groups.isEmpty() && !windows.isEmpty()
            && groups.stream().allMatch(group -> group.completeFor(windows, ledger));
        boolean positiveRecordsQuestion = question != null
            && plan.requiresTemporal() && !plan.historicalStatus()
            && !REQUIRES_CONFIRMED_SUBJECT.matcher(question).find()
            && POSITIVE_COMPETITION_LIST.matcher(question).find();
        boolean recordsComplete = positiveRecordsQuestion && !groups.isEmpty()
            && requestedGroups.size() == groups.size();
        return new RecordScope(groups, windows, allWindowsComplete,
            recordsComplete && allWindowsComplete, memberCoverage);
    }

    static RecordScope recordScopeFromGroups(String question, QueryEvidencePlan plan,
                                             EvidenceLedger ledger,
                                             List<RecordGroup> explicitGroups) {
        List<KgTimeRange> windows = plan.timeRanges().isEmpty()
            ? plan.timeRange().map(List::of).orElseGet(List::of) : plan.timeRanges();
        List<RecordGroup> groups = explicitGroups == null ? List.of() : explicitGroups.stream()
            .filter(group -> group != null && !group.members().isEmpty())
            .toList();
        Map<Long, Boolean> memberCoverage = new LinkedHashMap<>();
        for (RecordGroup group : groups) {
            for (EvidenceLedger.EntityFact member : group.members()) {
                memberCoverage.put(member.id(), !windows.isEmpty() && windows.stream()
                    .allMatch(window -> ledger.completeTemporal(member.id(), window)));
            }
        }
        boolean allWindowsComplete = !groups.isEmpty() && !windows.isEmpty()
            && groups.stream().allMatch(group -> group.completeFor(windows, ledger));
        boolean positiveRecordsQuestion = question != null
            && plan.requiresTemporal() && !plan.historicalStatus()
            && !REQUIRES_CONFIRMED_SUBJECT.matcher(question).find()
            && POSITIVE_COMPETITION_LIST.matcher(question).find();
        return new RecordScope(groups, windows, allWindowsComplete,
            positiveRecordsQuestion && allWindowsComplete, memberCoverage);
    }

    /**
     * A record group can be named by a descriptive title fragment ("团队空间", "视觉重构")
     * rather than by the exact full entity name. The caller passes only permission-checked
     * candidates; membership remains textual and completeness is checked per member.
     */
    static List<EvidenceLedger.EntityFact> titleGroupMembers(String key,
                                                              List<EvidenceLedger.EntityFact> selected) {
        String normalized = QueryIntent.normalize(key);
        if (normalized.isBlank()) return List.of();
        return selected.stream()
            .filter(entity -> entity != null && entity.name() != null
                && !"SKILL".equals(entity.type()))
            .filter(entity -> QueryIntent.normalize(entity.name()).contains(normalized))
            .sorted(Comparator.comparing(EvidenceLedger.EntityFact::id))
            .toList();
    }


    private static List<String> requestedRecordGroups(String question) {
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        for (String subject : QueryIntent.subjectRequests(question).subjects()) {
            for (String requested : RECORD_GROUP_CONNECTOR.split(subject)) {
                String trimmed = requested.trim();
                String core = RECORD_TAIL.matcher(trimmed).replaceFirst("").trim();
                if (QueryIntent.normalize(core).length() >= 4) groups.add(trimmed);
            }
        }
        return List.copyOf(groups);
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
