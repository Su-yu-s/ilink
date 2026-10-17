package cn.ilink.service.kg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Advisory output costing only. A stress partition never assigns actual candidate categories. */
final class ClassificationDecisionShape {
    static final int COMPLETION_LIMIT = 1200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ClassificationDecisionShape() { }

    static Map<String, Object> assess(GraphClassificationContract.Catalog catalog, Set<String> sentProofIds) {
        String arrayShape = stressPartition(catalog, sentProofIds, false);
        String stringShape = stressPartition(catalog, sentProofIds, true);
        int arrayTokens = EvidenceContext.tokens(arrayShape), stringTokens = EvidenceContext.tokens(stringShape);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("protocol", GraphClassificationContract.DECISION_PROTOCOL);
        metadata.put("candidateCount", catalog.candidates().size());
        metadata.put("sentProofCount", sentProofIds.size());
        metadata.put("maxGroups", 4);
        metadata.put("completionLimit", COMPLETION_LIMIT);
        metadata.put("estimateKind", "MINIFIED_FULL_PARTITION_FOUR_REASON_STRESS");
        metadata.put("arrayIdsEstimateTokens", arrayTokens);
        metadata.put("stringIdsEstimateTokens", stringTokens);
        metadata.put("minifiedShapeEstimateTokens", Math.max(arrayTokens, stringTokens));
        metadata.put("estimateWithinLimit", Math.max(arrayTokens, stringTokens) <= COMPLETION_LIMIT);
        metadata.put("syntacticStressSampleOnly", true);
        metadata.put("semanticProposal", false);
        metadata.put("hardOutputGuarantee", false);
        metadata.put("providerSchemaEnforced", false);
        metadata.put("decodingMode", "PROMPT_AND_STRICT_PARSER");
        metadata.put("budgetUse", "OBSERVATION_ONLY; supplier usage and finishReason remain authoritative");
        return java.util.Collections.unmodifiableMap(metadata);
    }

    /**
     * Dispersed integer ids avoid optimistic range compression; each id occurs once. All sent
     * proofs repeat in every group, including U, which the protocol permits. This exercises all
     * four reason headers for N>=4. It costs a legal minified shape, not all whitespace/encoding
     * possibilities and not a supplier-token upper bound or a factual classification proposal.
     */
    static String stressPartition(GraphClassificationContract.Catalog catalog, Set<String> sentProofIds,
                                  boolean stringIds) {
        int groupCount = Math.min(4, catalog.candidates().size());
        List<List<Integer>> members = new ArrayList<>();
        for (int i = 0; i < groupCount; i++) members.add(new ArrayList<>());
        for (int i = 0; i < catalog.candidates().size(); i++)
            members.get(i % groupCount).add(catalog.candidates().get(i).number());
        List<String> proofs = sentProofIds.stream().sorted().toList();
        List<Map<String, Object>> groups = new ArrayList<>();
        var reasons = GraphClassificationContract.Reason.values();
        for (int i = 0; i < groupCount; i++) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("s", i == 0 ? "I" : i == 3 ? "U" : "E");
            group.put("reason", reasons[i].name());
            group.put("ids", stringIds ? String.join(",", members.get(i).stream().map(String::valueOf).toList())
                : members.get(i));
            group.put("proofs", proofs);
            groups.add(group);
        }
        try { return JSON.writeValueAsString(Map.of("groups", groups)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize decision shape", e); }
    }
}
