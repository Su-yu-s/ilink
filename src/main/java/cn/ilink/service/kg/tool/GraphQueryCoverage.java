package cn.ilink.service.kg.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Completeness of a bounded query, counted only after relation and endpoint permission checks. */
final class GraphQueryCoverage {
    private GraphQueryCoverage() {
    }

    static Map<String, Object> of(Map<String, Object> scope, int scanLimit, boolean scanLimited,
                                  int scanned, int candidate, int visible, int returned, int returnLimit) {
        boolean hasMore = visible > returned;
        boolean complete = !scanLimited && !hasMore;
        List<String> reasons = new ArrayList<>();
        if (scanLimited) {
            reasons.add("SCAN_LIMIT");
        }
        if (hasMore) {
            reasons.add("RETURN_LIMIT");
        }
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("scope", scope);
        coverage.put("countSemantics", "PERMISSION_CHECKED_RELATION_ROWS");
        coverage.put("scanLimit", scanLimit);
        coverage.put("returnLimit", returnLimit);
        coverage.put("scanned", scanned);
        coverage.put("candidate", candidate);
        coverage.put("visible", visible);
        coverage.put("returned", returned);
        coverage.put("countsComplete", !scanLimited);
        coverage.put("hasMore", hasMore);
        // A scan cap can hide further permitted rows. False is not an absence assertion in that case.
        coverage.put("hasMoreKnown", hasMore || !scanLimited);
        coverage.put("truncated", !complete);
        coverage.put("complete", complete);
        coverage.put("truncationReasons", reasons);
        if (returned == 0) {
            coverage.put("emptyResultMeaning", complete
                ? "NO_VISIBLE_MATCH_IN_QUERY_SCOPE" : "NO_VISIBLE_MATCH_IN_SCANNED_PREFIX");
        }
        return coverage;
    }
}
