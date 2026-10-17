package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded source acquisition for confirmed graph members; never assigns a category. */
final class ClassificationSourceAcquisition {
    static final String TOOL = "read_relation_source";
    static final int MAX_REQUESTS = 2;

    record Need(int candidateId, Long relationId, Long memberEntityId, String sourceDocId) {
        Map<String, Object> parameters() {
            return Map.of("relation_id", relationId, "member_entity_id", memberEntityId);
        }
        Map<String, Object> metadata() {
            return Map.of("candidateId", candidateId, "relationId", relationId,
                "memberEntityId", memberEntityId, "sourceDocId", sourceDocId);
        }
    }

    record Plan(String status, List<Integer> missingCandidateIds, List<Need> requests,
                List<Integer> noDeclaredSourceCandidateIds, int candidateCount) {
        Plan {
            missingCandidateIds = List.copyOf(missingCandidateIds);
            requests = List.copyOf(requests);
            noDeclaredSourceCandidateIds = List.copyOf(noDeclaredSourceCandidateIds);
        }
        Map<String, Object> metadata() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("policy", "classification-declared-source-acquisition-v1");
            result.put("status", status);
            result.put("candidateCount", candidateCount);
            result.put("missingCandidateIds", missingCandidateIds);
            result.put("requests", requests.stream().map(Need::metadata).toList());
            result.put("noDeclaredSourceCandidateIds", noDeclaredSourceCandidateIds);
            result.put("maximumRequests", MAX_REQUESTS);
            result.put("semanticSupportEstablished", false);
            return result;
        }
    }

    static Plan plan(GraphClassificationContract.Catalog catalog, EvidenceLedger ledger,
                     ClassificationCoverageProofs.Prepared prepared, boolean toolAvailable, int remainingTools) {
        if (!catalog.requested() || !catalog.skipReason().isEmpty())
            return new Plan("NOT_GRAPH_CLASSIFICATION", List.of(), List.of(), List.of(), catalog.candidates().size());
        if (!catalog.inventoryComplete() || !catalog.queryComplete())
            return new Plan("INVENTORY_UNCONFIRMED", List.of(), List.of(), List.of(), catalog.candidates().size());
        List<Integer> missing = new ArrayList<>(), noSource = new ArrayList<>();
        List<Need> needs = new ArrayList<>();
        int limit = toolAvailable ? Math.min(MAX_REQUESTS, Math.max(0, remainingTools)) : 0;
        for (var candidate : catalog.candidates().stream()
                .sorted(Comparator.comparingInt(GraphClassificationContract.Candidate::number)).toList()) {
            if (prepared.proofs().spans().stream().anyMatch(proof ->
                    ClassificationCoverageProofs.relevantToMember(candidate, proof, ledger))) continue;
            missing.add(candidate.number());
            var source = candidate.relationIds().stream().sorted().map(ledger::relation)
                .flatMap(java.util.Optional::stream)
                .filter(edge -> memberMatches(candidate, edge) && validSource(edge.sourceDocId()))
                .findFirst().orElse(null);
            if (source == null) noSource.add(candidate.number());
            else if (needs.size() < limit) needs.add(new Need(candidate.number(), source.id(),
                candidate.otherId(), source.sourceDocId()));
        }
        String status = missing.isEmpty() ? "NO_SOURCE_GAP" : !toolAvailable ? "TOOL_UNAVAILABLE"
            : remainingTools <= 0 ? "TOOL_BUDGET_EXHAUSTED" : needs.isEmpty() ? "NO_DECLARED_SOURCE"
            : "BOUNDED_SOURCE_REQUESTS";
        return new Plan(status, missing, needs, noSource, catalog.candidates().size());
    }

    static boolean hasProof(GraphClassificationContract.Catalog catalog, int candidateId,
                            ClassificationCoverageProofs.Prepared prepared, EvidenceLedger ledger) {
        var member = catalog.candidate(candidateId);
        return member != null && prepared.proofs().spans().stream()
            .anyMatch(proof -> ClassificationCoverageProofs.relevantToMember(member, proof, ledger));
    }

    private static boolean validSource(String source) {
        return source != null && source.matches("(?:ASSET|POST|COMPETITION|TASK):[1-9]\\d{0,17}");
    }

    private static boolean memberMatches(GraphClassificationContract.Candidate candidate,
                                         EvidenceLedger.RelationFact edge) {
        return Objects.equals(edge.sourceId(), "OUT".equals(candidate.direction()) ? candidate.anchorId() : candidate.otherId())
            && Objects.equals(edge.targetId(), "OUT".equals(candidate.direction()) ? candidate.otherId() : candidate.anchorId())
            && Objects.equals(edge.type(), candidate.relationType());
    }
}
