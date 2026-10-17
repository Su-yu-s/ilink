package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Per-request-subject coverage, built only from permission-checked tool facts. */
final class SubjectScopeLedger {
    enum Resolution { EXACT, UNRESOLVED, AMBIGUOUS, FUZZY_UNCONFIRMED }

    record SubjectScope(String requestedName, Resolution resolution, Long entityId, String entityName,
                        List<Long> matchingEntityIds, boolean queryComplete, List<Long> relationIds,
                        Set<String> relationTypes) {
        boolean exact() { return resolution == Resolution.EXACT; }
    }

    record Analysis(List<SubjectScope> subjects, boolean subjectIdentificationComplete, String identificationReason) {
        Analysis { subjects = List.copyOf(subjects); }

        boolean queryComplete() {
            return subjectIdentificationComplete && !subjects.isEmpty()
                && subjects.stream().allMatch(subject -> subject.exact() && subject.queryComplete());
        }

        Map<String, Object> metadata() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (SubjectScope subject : subjects) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("requestedName", subject.requestedName());
                row.put("resolution", subject.resolution().name());
                row.put("entityId", subject.entityId());
                row.put("entityName", subject.entityName());
                row.put("matchingEntityIds", subject.matchingEntityIds());
                row.put("queryComplete", subject.queryComplete());
                row.put("relationIds", subject.relationIds());
                row.put("relationTypes", subject.relationTypes().stream().sorted().toList());
                rows.add(row);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("policy", "per-subject-authorized-scope-v1");
            result.put("subjectIdentificationComplete", subjectIdentificationComplete);
            result.put("identificationReason", identificationReason);
            result.put("requestedCount", subjects.size());
            result.put("queryComplete", queryComplete());
            result.put("subjects", List.copyOf(rows));
            return result;
        }

        String instruction() {
            StringBuilder text = new StringBuilder("【逐主体授权查询范围】主体识别完整=")
                .append(subjectIdentificationComplete).append("；");
            for (SubjectScope subject : subjects) {
                text.append(subject.requestedName()).append("=>").append(subject.resolution());
                if (subject.entityId() != null) text.append(" #").append(subject.entityId());
                text.append(" 查询完整=").append(subject.queryComplete()).append("；");
            }
            return text + "父主体关系不代表子主体，同名候选不能默认合并。未完整查询的主体只能说本次证据未展示。"
                + "即使查完，也只能说明当前用户授权可见范围，不得断言图谱全局不存在或未收录。";
        }

        /** Reject absence claims; quotation and instructions are not assistant assertions. */
        List<String> absenceViolations(String answer) {
            if (answer == null || answer.isBlank()) return List.of();
            List<String> failures = new ArrayList<>();
            String body = answer.replaceAll("(?s)```.*?```", "");
            for (String line : body.split("\\R")) {
                if (line.stripLeading().startsWith(">")) continue;
                for (String clause : line.split("[。；;！？!?]")) {
                    String assertion = QUOTATION.matcher(clause).replaceAll("");
                    for (String local : ASSERTION_SEPARATOR.split(assertion)) {
                        java.util.regex.Matcher absence = ABSENCE.matcher(local);
                        while (absence.find()) {
                            if (isInstruction(local.substring(0, absence.start()))) continue;
                            List<SubjectScope> named = namedSubjects(local, subjects);
                            if (named.isEmpty()) named = namedSubjects(clause, subjects);
                            List<SubjectScope> affected = named.isEmpty() ? subjects : named;
                            if (!AUTHORIZED_RANGE.matcher(local).find() || GLOBAL_EXPANSION.matcher(local).find()) {
                                failures.add("GRAPH_ABSENCE_GLOBAL_SCOPE: " + local.trim());
                            } else if (!subjectIdentificationComplete || affected.isEmpty()
                                || affected.stream().anyMatch(subject -> !subject.exact() || !subject.queryComplete())) {
                                failures.add("GRAPH_ABSENCE_UNQUERIED_SUBJECT: " + local.trim());
                            } else if (affected.stream().anyMatch(subject -> contradictsAbsence(subject, local))) {
                                failures.add("GRAPH_ABSENCE_CONTRADICTED: " + local.trim());
                            }
                        }
                    }
                }
            }
            return List.copyOf(failures);
        }
    }

    private static final Pattern QUOTATION = Pattern.compile("[“\"]([^”\"]*)[”\"]|「[^」]*」|《[^》]*》");
    private static final Pattern ABSENCE = Pattern.compile(
        "(?:知识)?图谱(?:中|里)?(?:并)?(?:无|没有|不存在|未收录|未包含|不包含)"
            + "|(?:无|没有|不存在)(?:直接)?图谱关系(?:边|记录)?"
            + "|(?:无|没有|不存在)(?:直接|主办|承办|提及|关联|组织|\\s*[A-Z][A-Z_]+\\s*)?关系(?:边|记录)?"
            + "|(?:未发现|未找到).{0,35}(?:关系|关系边|主办|承办)"
            + "|(?:HOSTED_BY|MENTIONS|USES|IMPLEMENTED_BY|主办|承办)关系(?:边)?(?:不存在|未收录)");
    private static final Pattern INSTRUCTION = Pattern.compile(
        "(?:不能|不得|不应(?:当)?|禁止|避免|无法).{0,18}(?:认为|认定|证明|确定|判断|断言|声称|说|表述为)");
    private static final Pattern AUTHORIZED_RANGE = Pattern.compile(
        "当前(?:用户)?(?:授权|可见)(?:范围|图谱)?|当前用户可见图谱|本次(?:完整|已)?查询的(?:授权|可见)(?:图谱|范围)");
    private static final Pattern GLOBAL_EXPANSION = Pattern.compile("全局|整个图谱|全平台|所有(?:用户|团队)|任何(?:用户|团队)");
    // Entity acronyms (for example a title's acronym) are not relation-type filters.
    private static final Pattern RELATION_TYPE = Pattern.compile(
        "\\b(?:REQUIRES|TEACHES|HOSTED_BY|RELATED_TO|MENTIONS)\\b");
    private static final Pattern ASSERTION_SEPARATOR = Pattern.compile("然而|可是|不过|但是|但|而|[,，]");

    private SubjectScopeLedger() { }

    private static boolean isInstruction(String prefix) {
        String local = prefix.replaceAll("(?s)^.*(?:但|而|可是|不过|然而|[,，；;])", "");
        String nearby = local.substring(Math.max(0, local.length() - 35));
        return INSTRUCTION.matcher(nearby).find();
    }

    static Analysis assess(String question, EvidenceLedger ledger) {
        QueryIntent.SubjectRequests requested = QueryIntent.subjectRequests(question);
        List<SubjectScope> scopes = new ArrayList<>();
        java.util.Date currentAt = KgTimeRange.now();
        for (String subject : requested.subjects()) {
            List<EvidenceLedger.EntityFact> exact = ledger.entities().stream()
                .filter(entity -> QueryIntent.sameSubjectName(subject, entity.name()))
                .sorted(Comparator.comparing(EvidenceLedger.EntityFact::id)).toList();
            List<EvidenceLedger.EntityFact> matching = exact.isEmpty() ? ledger.entities().stream()
                .filter(entity -> new QueryIntent(List.of(subject), "", false).matchesSubject(entity.name()))
                .sorted(Comparator.comparing(EvidenceLedger.EntityFact::id)).toList() : exact;
            Resolution resolution = exact.size() == 1 ? Resolution.EXACT
                : matching.size() > 1 ? Resolution.AMBIGUOUS
                : matching.isEmpty() ? Resolution.UNRESOLVED : Resolution.FUZZY_UNCONFIRMED;
            EvidenceLedger.EntityFact anchor = resolution == Resolution.EXACT ? exact.get(0) : null;
            List<EvidenceLedger.RelationFact> activeRelations = anchor == null ? List.of() : ledger.relations().stream()
                .filter(edge -> Objects.equals(edge.sourceId(), anchor.id()) || Objects.equals(edge.targetId(), anchor.id()))
                .filter(edge -> (edge.validFrom() == null || !edge.validFrom().after(currentAt))
                    && (edge.validTo() == null || edge.validTo().after(currentAt)))
                .sorted(Comparator.comparing(EvidenceLedger.RelationFact::id)).toList();
            scopes.add(new SubjectScope(subject, resolution, anchor == null ? null : anchor.id(),
                anchor == null ? null : anchor.name(), matching.stream().map(EvidenceLedger.EntityFact::id).toList(),
                anchor != null && ledger.completeRelations("expand_neighbors", anchor.id()),
                activeRelations.stream().map(EvidenceLedger.RelationFact::id).toList(),
                Set.copyOf(activeRelations.stream().map(EvidenceLedger.RelationFact::type).toList())));
        }
        return new Analysis(scopes, requested.complete(), requested.reason());
    }

    private static List<SubjectScope> namedSubjects(String text, List<SubjectScope> subjects) {
        String normalized = QueryIntent.normalize(text);
        List<SubjectScope> named = new ArrayList<>();
        for (SubjectScope subject : subjects) {
            String name = QueryIntent.normalize(subject.requestedName());
            if (name.isEmpty()) continue;
            for (int start = normalized.indexOf(name); start >= 0; start = normalized.indexOf(name, start + 1)) {
                final int position = start;
                boolean nestedName = subjects.stream().anyMatch(other -> {
                    String longer = QueryIntent.normalize(other.requestedName());
                    if (longer.length() <= name.length()) return false;
                    for (int at = normalized.indexOf(longer); at >= 0; at = normalized.indexOf(longer, at + 1))
                        if (at <= position && at + longer.length() >= position + name.length()) return true;
                    return false;
                });
                if (!nestedName) {
                    named.add(subject);
                    break;
                }
            }
        }
        return List.copyOf(named);
    }

    private static boolean contradictsAbsence(SubjectScope subject, String assertion) {
        // Type-specific negatives are permitted only when that type is absent from a complete subject query.
        List<String> requestedTypes = new ArrayList<>();
        java.util.regex.Matcher type = RELATION_TYPE.matcher(assertion);
        while (type.find()) requestedTypes.add(type.group());
        if (assertion.contains("主办")) requestedTypes = List.of("HOSTED_BY");
        if (!requestedTypes.isEmpty()) return requestedTypes.stream().anyMatch(subject.relationTypes()::contains);
        return !subject.relationIds().isEmpty();
    }
}
