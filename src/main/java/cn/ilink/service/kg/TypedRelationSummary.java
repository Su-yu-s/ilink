package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic rendering for a relation-list question with one exact, completely queried subject.
 *
 * <p>The requested entity type is a filter over the other endpoint. A zero-target result is not a
 * complete answer: the requested entity may be attached to a different alias or subject that was
 * not resolved. The caller must continue exploration instead of publishing a confident negative.
 */
final class TypedRelationSummary {
    record RelationRow(long relationId, long anchorId, long otherId, String otherName,
                       String otherType, String relationType, String direction) { }

    record Result(String subjectName, long subjectId, String targetType,
                  List<RelationRow> targetRelations, List<RelationRow> nonTargetRelations,
                  String text) {
        Result {
            targetRelations = List.copyOf(targetRelations);
            nonTargetRelations = List.copyOf(nonTargetRelations);
        }

        int targetRelationCount() { return targetRelations.size(); }

        int nonTargetRelationCount() { return nonTargetRelations.size(); }
    }

    private TypedRelationSummary() { }

    static Optional<Result> build(String question, EvidenceLedger ledger,
                                  SubjectScopeLedger.Analysis scopes) {
        if (ledger == null || scopes == null || !scopes.queryComplete()
            || !scopes.subjectIdentificationComplete() || scopes.subjects().size() != 1) {
            return Optional.empty();
        }
        SubjectScopeLedger.SubjectScope scope = scopes.subjects().get(0);
        if (!scope.exact() || scope.entityId() == null) return Optional.empty();

        QueryIntent intent = QueryIntent.fromQuestion(question);
        String targetType = intent.requestedEntityType();
        if (targetType == null) return Optional.empty();

        EvidenceLedger.EntityFact anchor = ledger.entity(scope.entityId()).orElse(null);
        if (anchor == null) return Optional.empty();

        Date now = KgTimeRange.now();
        List<RelationRow> target = new ArrayList<>();
        List<RelationRow> nonTarget = new ArrayList<>();
        for (EvidenceLedger.RelationFact relation : ledger.relations().stream()
            .sorted(Comparator.comparing(EvidenceLedger.RelationFact::id)).toList()) {
            if (relation.validFrom() != null && relation.validFrom().after(now)
                || relation.validTo() != null && !relation.validTo().after(now)) {
                continue;
            }
            boolean outgoing = anchor.id().equals(relation.sourceId());
            boolean incoming = anchor.id().equals(relation.targetId());
            if (!outgoing && !incoming) continue;
            long otherId = outgoing ? relation.targetId() : relation.sourceId();
            EvidenceLedger.EntityFact other = ledger.entity(otherId).orElse(null);
            if (other == null) continue;
            RelationRow row = new RelationRow(relation.id(), anchor.id(), other.id(),
                cleanName(other.name()), Objects.toString(other.type(), "UNKNOWN"),
                Objects.toString(relation.type(), "UNKNOWN"), outgoing ? "OUT" : "IN");
            if (targetType.equals(other.type())) target.add(row);
            else nonTarget.add(row);
        }

        // Zero target rows are deliberately not a publishable deterministic summary. The source
        // question may use a different alias or subject; returning null keeps the evidence loop
        // available for disambiguation and source lookup.
        if (target.isEmpty()) return Optional.empty();

        StringBuilder text = new StringBuilder();
        text.append("本次完整查询的授权图谱范围内，主体《").append(cleanName(anchor.name()))
            .append("》（#").append(anchor.id()).append("）的目标类型 ").append(targetType);
        text.append(" 关系记录共 ").append(target.size()).append(" 条：\n");
        appendRows(text, target);
        return Optional.of(new Result(cleanName(anchor.name()), anchor.id(), targetType,
            target, nonTarget, text.toString().stripTrailing()));
    }

    private static void appendRows(StringBuilder text, List<RelationRow> rows) {
        int index = 0;
        for (RelationRow row : rows) {
            text.append(++index).append(". ").append(row.otherName())
                .append("（#").append(row.otherId()).append("，")
                .append(row.otherType()).append("，")
                .append(row.relationType()).append("，")
                .append(row.direction()).append("，r#")
                .append(row.relationId()).append("）\n");
        }
    }

    private static String cleanName(String value) {
        return Objects.toString(value, "").replaceAll("\\R", " ").strip();
    }
}
