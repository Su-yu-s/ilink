package cn.ilink.service.kg;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An exhaustive disposition protocol for authorized graph candidates. Set coverage is not a
 * classification proof: the caller must validate the actually supplied proof IDs and semantics
 * before publishing a rendered answer. This component never queries a database or a model.
 */
final class GraphClassificationContract {
    static final String DECISION_PROTOCOL = "candidate-decisions-v3";
    private static final int INVENTORY_BUDGET = 1200;
    private static final int ROLE_LIMIT = 24;
    private static final int REASON_LIMIT = 80;
    private static final int RAW_LIMIT = 32768;
    private static final Pattern CLASSIFICATION = Pattern.compile(
        "分类|归类|区分|筛选|属于|不属|生态|技术栈|技能|前端|后端|数据库|基础设施");
    private static final Pattern UNCERTAIN = Pattern.compile(
        "不确定|无法(?:确认|确定|判断|判定)|尚不能|不能(?:确认|确定|判断|判定)|未能|未证实|未确认"
            + "|待(?:确认|核验|判断)|缺少|不足|未知|需(?:要)?(?:补充|确认|核验|证据)|暂不");
    private static final Pattern FORBIDDEN_PROOF_ID = Pattern.compile(
        "(?<![A-Za-z0-9_])p\\d+(?![A-Za-z0-9_])");
    private static final Pattern FORBIDDEN_TEXT = Pattern.compile(
        "[\\r\\n<>`]|[rc]#\\d+|https?://");
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    enum State { I, E, U }

    /** Closed decision reasons describe the user's predicate, never a model-authored taxonomy. */
    enum Reason { MATCH, OUTSIDE_ATTRIBUTE, OUTSIDE_TYPE, INSUFFICIENT }

    /** A number is local to this catalog; identity never depends on the generated name. */
    record Candidate(int number, Long anchorId, Long otherId, String anchorName, String name,
                     String entityType, String relationType, String direction, List<Long> relationIds) {
        Candidate { relationIds = List.copyOf(relationIds); }
    }

    record Catalog(boolean requested, String skipReason, List<Candidate> candidates, String text,
                   Map<String, Object> metadata, boolean queryComplete, boolean inventoryComplete,
                   String focusText) {
        Catalog {
            candidates = List.copyOf(candidates);
            metadata = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }

        Candidate candidate(int number) {
            return number > 0 && number <= candidates.size() ? candidates.get(number - 1) : null;
        }
    }

    record Group(State state, String role, List<Integer> ids, List<String> proofs, String why) {
        Group { ids = List.copyOf(ids); proofs = List.copyOf(proofs); }
    }

    /** Complete means valid exhaustive syntax, never that the proposed classification is true. */
    record Parsed(List<Group> groups, List<String> failures, boolean complete, int uncertainCount) {
        Parsed { groups = List.copyOf(groups); failures = List.copyOf(failures); }

        /** Proposed U assignments are not verified uncertainty facts, especially in an invalid partition. */
        Map<String, Object> diagnosticsMetadata() { return diagnosticsMetadata(false); }

        Map<String, Object> diagnosticsMetadata(boolean semanticVerdictAccepted) {
            Map<Integer, List<Integer>> owners = new LinkedHashMap<>();
            Map<Integer, List<String>> states = new LinkedHashMap<>();
            Set<Integer> uncertainIds = new LinkedHashSet<>();
            for (int index = 0; index < groups.size(); index++) {
                Group group = groups.get(index);
                for (int id : group.ids()) {
                    owners.computeIfAbsent(id, ignored -> new ArrayList<>()).add(index + 1);
                    states.computeIfAbsent(id, ignored -> new ArrayList<>()).add(group.state().name());
                    if (group.state() == State.U) uncertainIds.add(id);
                }
            }
            List<Map<String, Object>> duplicates = new ArrayList<>();
            List<Integer> ambiguousUncertainIds = new ArrayList<>();
            for (Map.Entry<Integer, List<Integer>> owner : owners.entrySet()) {
                if (owner.getValue().size() < 2) continue;
                duplicates.add(Map.of("candidateId", owner.getKey(), "groupIndexes", List.copyOf(owner.getValue()),
                    "states", List.copyOf(states.get(owner.getKey())), "assignmentCount", owner.getValue().size()));
                if (uncertainIds.contains(owner.getKey())) ambiguousUncertainIds.add(owner.getKey());
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("partitionValid", complete);
            boolean unavailable = failures.contains("CLASSIFICATION_INVALID_JSON")
                || failures.contains("CLASSIFICATION_INVALID_SCHEMA") || failures.contains("CLASSIFICATION_TOO_MANY_GROUPS");
            boolean partialGroups = failures.contains("CLASSIFICATION_INVALID_GROUP")
                || failures.contains("CLASSIFICATION_UNKNOWN_STATE");
            metadata.put("proposalParseStatus", unavailable ? "UNAVAILABLE"
                : partialGroups ? "RECOGNIZED_GROUPS_ONLY" : "PARSED_PROPOSAL");
            metadata.put("proposedUncertainCountAvailable", !unavailable);
            metadata.put("proposedUncertainCount", uncertainCount);
            metadata.put("proposedUncertainDistinctCount", uncertainIds.size());
            metadata.put("proposedUncertainCandidateIds", uncertainIds.stream().sorted().toList());
            metadata.put("proposedUncertainAmbiguousIds", ambiguousUncertainIds.stream().sorted().toList());
            metadata.put("semanticVerdictAccepted", complete && semanticVerdictAccepted);
            // The packet includes referenced spans, not all generation evidence for unproven U members.
            // An accepted semantic verdict cannot certify that no other supplied span resolves a U.
            metadata.put("uncertaintySemanticallyVerified", false);
            metadata.put("duplicateCandidateOwnership", List.copyOf(duplicates));
            metadata.put("groupIndexBasis", "recognized-parsed-groups");
            metadata.put("groupDiagnostics", failures.stream()
                .filter(failure -> failure.startsWith("CLASSIFICATION_GROUP:")).toList());
            return java.util.Collections.unmodifiableMap(metadata);
        }
    }

    record Rendered(String answer, String coverage, String coverageReason) { }

    /** Stage B decisions contain no model-authored labels, explanations, entity names or citations. */
    record DecisionGroup(State state, Reason reason, List<Integer> ids, List<String> proofs) {
        DecisionGroup { ids = List.copyOf(ids); proofs = List.copyOf(proofs); }
    }

    /** Valid exhaustive syntax is not evidence that a decision, or a proposed U, is true. */
    record DecisionParsed(List<DecisionGroup> groups, List<String> failures,
                          boolean complete, int uncertainCount) {
        DecisionParsed { groups = List.copyOf(groups); failures = List.copyOf(failures); }

        List<Integer> missingCandidateIds() {
            return diagnosticIds(failures, "CLASSIFICATION_OMITTED_CANDIDATE:");
        }

        /**
         * A rewrite can be structurally valid for every recognised group and still omit only a small
         * number of candidates. Only that shape may enter the bounded closure stage; duplicate IDs,
         * invalid proofs and unknown states keep the existing fail-closed path.
         */
        boolean onlyOmittedCandidates() {
            return !groups.isEmpty() && !missingCandidateIds().isEmpty()
                && failures.stream().allMatch(failure ->
                    failure.startsWith("CLASSIFICATION_OMITTED_CANDIDATE:"));
        }

        Map<String, Object> diagnosticsMetadata() { return diagnosticsMetadata(false); }

        Map<String, Object> diagnosticsMetadata(boolean semanticVerdictAccepted) {
            // Reuse historical diagnostic meanings without exposing the historical free-text protocol.
            List<Group> diagnosticGroups = groups.stream().map(group -> new Group(group.state(), "",
                group.ids(), group.proofs(), "")).toList();
            Map<String, Object> result = new LinkedHashMap<>(new Parsed(diagnosticGroups, failures,
                complete, uncertainCount).diagnosticsMetadata(semanticVerdictAccepted));
            if (failures.contains("CLASSIFICATION_UNKNOWN_REASON"))
                result.put("proposalParseStatus", "RECOGNIZED_GROUPS_ONLY");
            result.put("protocol", DECISION_PROTOCOL);
            result.put("missingCandidateIds", diagnosticIds(failures, "CLASSIFICATION_OMITTED_CANDIDATE:"));
            result.put("unknownCandidateIds", diagnosticIds(failures, "CLASSIFICATION_UNKNOWN_CANDIDATE:"));
            result.put("reasonCodes", groups.stream().map(group -> group.reason().name()).toList());
            // Query completeness belongs to Catalog. Neither syntax nor semantic acceptance certifies it.
            result.put("queryCompletenessCertifiedByPartition", false);
            return java.util.Collections.unmodifiableMap(result);
        }
    }

    private record Identity(Long anchorId, Long otherId, String relationType, String direction) { }
    private GraphClassificationContract() { }

    static String skipReason(String question, QueryEvidencePlan plan) {
        String q = question == null ? "" : question;
        QueryEvidencePlan actual = plan == null ? QueryEvidencePlan.fromQuestion(q) : plan;
        if (actual.requiresTemporal()) return "TEMPORAL_QUERY";
        if (RecommendationEvidencePolicy.isRecommendationQuery(q)) return "RECOMMENDATION_QUERY";
        QueryIntent intent = QueryIntent.fromQuestion(q);
        if (!intent.graphRequested() || !actual.requiresRelation()
            || !CLASSIFICATION.matcher(intent.focusText()).find()) return "NOT_GRAPH_CLASSIFICATION";
        return "";
    }

    static Catalog prepare(String question, QueryEvidencePlan plan, EvidenceLedger ledger) {
        String skipped = skipReason(question, plan);
        QueryIntent intent = QueryIntent.fromQuestion(question);
        if (!skipped.isEmpty()) return new Catalog(false, skipped, List.of(), "",
            Map.of("policy", "graph-classification-catalog-v1", "requested", false,
                "skipReason", skipped), true, true, intent.focusText());
        SubjectScopeLedger.Analysis scopes = SubjectScopeLedger.assess(question, ledger);
        Set<Long> anchors = new LinkedHashSet<>();
        for (SubjectScopeLedger.SubjectScope scope : scopes.subjects())
            if (scope.exact()) anchors.add(scope.entityId());
        String targetType = intent.requestedEntityType();
        Map<Identity, Set<Long>> relationMap = new LinkedHashMap<>();
        java.util.Date at = KgTimeRange.now();
        for (EvidenceLedger.RelationFact edge : ledger.relations().stream()
            .sorted(Comparator.comparing(EvidenceLedger.RelationFact::id)).toList()) {
            if (edge.validFrom() != null && edge.validFrom().after(at)
                || edge.validTo() != null && !edge.validTo().after(at)) continue;
            for (String direction : List.of("OUT", "IN")) {
                Long anchorId = "OUT".equals(direction) ? edge.sourceId() : edge.targetId();
                Long otherId = "OUT".equals(direction) ? edge.targetId() : edge.sourceId();
                if (!anchors.contains(anchorId)) continue;
                EvidenceLedger.EntityFact other = ledger.entity(otherId).orElse(null);
                if (other == null || targetType != null && !targetType.equals(other.type())) continue;
                Identity identity = new Identity(anchorId, otherId, edge.type(), direction);
                relationMap.computeIfAbsent(identity, ignored -> new LinkedHashSet<>()).add(edge.id());
            }
        }
        List<Identity> identities = relationMap.keySet().stream().sorted(
            Comparator.comparing(Identity::anchorId).thenComparing(Identity::direction)
                .thenComparing(Identity::relationType).thenComparing(Identity::otherId)).toList();
        List<Candidate> candidates = new ArrayList<>();
        for (Identity identity : identities) {
            EvidenceLedger.EntityFact anchor = ledger.entity(identity.anchorId()).orElseThrow();
            EvidenceLedger.EntityFact other = ledger.entity(identity.otherId()).orElseThrow();
            candidates.add(new Candidate(candidates.size() + 1, anchor.id(), other.id(), anchor.name(),
                other.name(), other.type(), identity.relationType(), identity.direction(),
                relationMap.get(identity).stream().sorted().toList()));
        }
        StringBuilder inventory = new StringBuilder("【授权候选编号；OUT=主体→候选，IN=候选→主体】\n")
            .append("查询完整=").append(scopes.queryComplete()).append("；仅当前授权范围。\n");
        int shown = 0;
        String previous = null;
        for (Candidate candidate : candidates) {
            String group = "主体#" + candidate.anchorId() + " " + candidate.anchorName() + " "
                + candidate.direction() + " " + candidate.relationType() + " " + candidate.entityType();
            String refs = String.join(",", candidate.relationIds().stream().map(id -> "r#" + id).toList());
            String row = (group.equals(previous) ? "" : "【" + group + "】\n")
                + candidate.number() + ":" + candidate.name() + " " + refs + "\n";
            if (EvidenceContext.tokens(inventory + row) > INVENTORY_BUDGET) break;
            inventory.append(row);
            previous = group;
            shown++;
        }
        boolean inventoryComplete = shown == candidates.size();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Candidate candidate : candidates) rows.add(candidateMetadata(candidate));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("policy", "graph-classification-catalog-v1");
        metadata.put("requested", true);
        metadata.put("skipReason", "");
        metadata.put("identity", "anchorId+otherId+relationType+direction");
        metadata.put("targetType", targetType);
        metadata.put("queryComplete", scopes.queryComplete());
        metadata.put("inventoryComplete", inventoryComplete);
        metadata.put("inventoryBudget", INVENTORY_BUDGET);
        metadata.put("inventoryTokens", EvidenceContext.tokens(inventory.toString()));
        metadata.put("candidateCount", candidates.size());
        metadata.put("shownCount", shown);
        metadata.put("subjectScopes", scopes.metadata());
        metadata.put("candidates", List.copyOf(rows));
        return new Catalog(true, "", candidates, inventory.toString(), metadata,
            scopes.queryComplete(), inventoryComplete, intent.focusText());
    }

    private static Map<String, Object> candidateMetadata(Candidate candidate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("number", candidate.number()); row.put("anchorId", candidate.anchorId());
        row.put("otherId", candidate.otherId()); row.put("anchorName", candidate.anchorName());
        row.put("name", candidate.name()); row.put("entityType", candidate.entityType());
        row.put("relationType", candidate.relationType()); row.put("direction", candidate.direction());
        row.put("relationIds", candidate.relationIds());
        return java.util.Collections.unmodifiableMap(row);
    }

    static Parsed parse(String raw, Catalog catalog) {
        Set<String> failures = new LinkedHashSet<>();
        if (!catalog.requested()) failures.add("CLASSIFICATION_NOT_REQUESTED");
        if (!catalog.inventoryComplete()) failures.add("CLASSIFICATION_INVENTORY_INCOMPLETE");
        if (raw == null || raw.isBlank() || raw.length() > RAW_LIMIT)
            return failed(failures, "CLASSIFICATION_INVALID_JSON");
        JsonNode root;
        try { root = JSON.readTree(raw); }
        catch (Exception ignored) { return failed(failures, "CLASSIFICATION_INVALID_JSON"); }
        if (root == null || !root.isObject() || !keys(root).equals(Set.of("groups"))
            || !root.path("groups").isArray()) return failed(failures, "CLASSIFICATION_INVALID_SCHEMA");
        if (root.path("groups").size() > catalog.candidates().size() + 1)
            return failed(failures, "CLASSIFICATION_TOO_MANY_GROUPS");
        List<Group> groups = new ArrayList<>();
        Set<Integer> assigned = new LinkedHashSet<>();
        int uncertainCount = 0;
        int groupIndex = 0;
        for (JsonNode row : root.path("groups")) {
            groupIndex++;
            if (!row.isObject() || !keys(row).equals(Set.of("s", "role", "ids", "proofs", "why"))) {
                failures.add("CLASSIFICATION_INVALID_GROUP"); continue;
            }
            State state;
            try { state = State.valueOf(row.path("s").textValue()); }
            catch (Exception ignored) { failures.add("CLASSIFICATION_UNKNOWN_STATE"); continue; }
            List<String> roleFailures = shortTextFailures(row.get("role"), ROLE_LIMIT);
            List<String> whyFailures = shortTextFailures(row.get("why"), REASON_LIMIT);
            String role = roleFailures.isEmpty() ? row.get("role").textValue().strip() : null;
            String why = whyFailures.isEmpty() ? row.get("why").textValue().strip() : null;
            if (role == null) {
                failures.add("CLASSIFICATION_INVALID_ROLE");
                for (String reason : roleFailures)
                    failures.add("CLASSIFICATION_GROUP:" + groupIndex + ":ROLE:" + reason);
            }
            if (why == null) {
                failures.add("CLASSIFICATION_INVALID_REASON");
                for (String reason : whyFailures)
                    failures.add("CLASSIFICATION_GROUP:" + groupIndex + ":WHY:" + reason);
            }
            List<Integer> ids = parseIds(row.get("ids"), catalog.candidates().size(), failures);
            List<String> proofs = parseProofs(row.get("proofs"), failures, groupIndex);
            if (ids.isEmpty()) failures.add("CLASSIFICATION_EMPTY_GROUP");
            if (state != State.U && proofs.isEmpty()) {
                failures.add("CLASSIFICATION_PROOF_REQUIRED");
                failures.add("CLASSIFICATION_GROUP:" + groupIndex + ":PROOFS:NONEMPTY_VALID_IDS_REQUIRED");
            }
            if (state == State.U && (role == null || why == null || !uncertainText(role)
                || !uncertainText(why))) {
                failures.add("CLASSIFICATION_UNCERTAIN_ASSERTION");
                if (role != null && !uncertainText(role))
                    failures.add("CLASSIFICATION_GROUP:" + groupIndex + ":ROLE:UNCERTAINTY_MARKER_REQUIRED_PER_CLAUSE");
                if (why != null && !uncertainText(why))
                    failures.add("CLASSIFICATION_GROUP:" + groupIndex + ":WHY:UNCERTAINTY_MARKER_REQUIRED_PER_CLAUSE");
            }
            for (int id : ids) {
                if (!assigned.add(id)) failures.add("CLASSIFICATION_DUPLICATE_CANDIDATE:" + id);
                if (state == State.U) uncertainCount++;
            }
            groups.add(new Group(state, role == null ? "" : role, ids, proofs, why == null ? "" : why));
        }
        for (Candidate candidate : catalog.candidates())
            if (!assigned.contains(candidate.number()))
                failures.add("CLASSIFICATION_OMITTED_CANDIDATE:" + candidate.number());
        return new Parsed(groups, List.copyOf(failures), failures.isEmpty(), uncertainCount);
    }

    private static Parsed failed(Set<String> failures, String reason) {
        failures.add(reason);
        return new Parsed(List.of(), List.copyOf(failures), false, 0);
    }

    /** Stage B production parser. It never repairs a proposal or assigns omitted members to U. */
    static DecisionParsed parseDecisions(String raw, Catalog catalog) {
        return parseDecisions(raw, catalog, null);
    }

    /**
     * Parse one bounded closure proposal. Every returned ID must belong to {@code expectedIds}, and
     * every expected ID must be disposed of exactly once. IDs already assigned by the first rewrite
     * are unexpected here, which prevents a closure call from silently replacing an existing group.
     */
    static DecisionParsed parseDecisionClosure(String raw, Catalog catalog, Set<Integer> expectedIds) {
        Set<String> failures = new LinkedHashSet<>();
        if (expectedIds == null || expectedIds.isEmpty()) {
            failures.add("CLASSIFICATION_CLOSURE_EMPTY_REQUEST");
            return failedDecisions(failures, "CLASSIFICATION_CLOSURE_INVALID_SCOPE");
        }
        for (int id : expectedIds) {
            if (catalog.candidate(id) == null) {
                failures.add("CLASSIFICATION_CLOSURE_INVALID_SCOPE");
                failures.add("CLASSIFICATION_UNKNOWN_CANDIDATE:" + id);
            }
        }
        if (!failures.isEmpty()) return failedDecisions(failures, "CLASSIFICATION_CLOSURE_INVALID_SCOPE");
        return parseDecisions(raw, catalog, Set.copyOf(expectedIds));
    }

    private static DecisionParsed parseDecisions(String raw, Catalog catalog, Set<Integer> expectedIds) {
        Set<String> failures = new LinkedHashSet<>();
        if (!catalog.requested()) failures.add("CLASSIFICATION_NOT_REQUESTED");
        if (!catalog.inventoryComplete()) failures.add("CLASSIFICATION_INVENTORY_INCOMPLETE");
        if (raw == null || raw.isBlank() || raw.length() > RAW_LIMIT)
            return failedDecisions(failures, "CLASSIFICATION_INVALID_JSON");
        JsonNode root;
        try { root = JSON.readTree(raw); }
        catch (Exception ignored) { return failedDecisions(failures, "CLASSIFICATION_INVALID_JSON"); }
        if (root == null || !root.isObject() || !keys(root).equals(Set.of("groups"))
            || !root.path("groups").isArray())
            return failedDecisions(failures, "CLASSIFICATION_INVALID_SCHEMA");
        // Partition cardinality is bounded by the candidate count, not by the closed reason codes.
        // A reason code says WHY a candidate was disposed of; it is not a partition slot, so the
        // same reason may legitimately cover several disjoint groups (M5 split four MATCH groups
        // plus one OUTSIDE_TYPE group over seven candidates and was rejected purely for that).
        // The real integrity guards below are untouched: every candidate exactly once, no unknown
        // numbers, and every I/E group must carry valid proofs.
        int scopeSize = expectedIds == null ? catalog.candidates().size() : expectedIds.size();
        if (root.path("groups").size() > scopeSize + 1)
            return failedDecisions(failures, "CLASSIFICATION_TOO_MANY_GROUPS");
        List<DecisionGroup> groups = new ArrayList<>();
        Set<Integer> assigned = new LinkedHashSet<>();
        int uncertainCount = 0;
        int groupIndex = 0;
        for (JsonNode row : root.path("groups")) {
            groupIndex++;
            if (!row.isObject() || !keys(row).equals(Set.of("s", "reason", "ids", "proofs"))) {
                failures.add("CLASSIFICATION_INVALID_GROUP");
                failures.add("CLASSIFICATION_GROUP:" + groupIndex + ":SCHEMA:CLOSED_DECISION_FIELDS_REQUIRED");
                continue;
            }
            State state;
            try { state = State.valueOf(row.path("s").textValue()); }
            catch (Exception ignored) {
                failures.add("CLASSIFICATION_UNKNOWN_STATE");
                failures.add("CLASSIFICATION_GROUP:" + groupIndex + ":STATE:CLOSED_CODE_REQUIRED");
                continue;
            }
            Reason reason;
            try { reason = Reason.valueOf(row.path("reason").textValue()); }
            catch (Exception ignored) {
                failures.add("CLASSIFICATION_UNKNOWN_REASON");
                failures.add("CLASSIFICATION_GROUP:" + groupIndex + ":REASON:CLOSED_CODE_REQUIRED");
                continue;
            }
            if (!compatible(state, reason)) {
                failures.add("CLASSIFICATION_STATE_REASON_MISMATCH");
                failures.add("CLASSIFICATION_GROUP:" + groupIndex + ":REASON:STATE_MISMATCH:" + state + ":" + reason);
            }
            List<Integer> ids = parseIds(row.get("ids"), catalog.candidates().size(), failures);
            List<String> proofs = parseProofs(row.get("proofs"), failures, groupIndex);
            if (ids.isEmpty()) {
                failures.add("CLASSIFICATION_EMPTY_GROUP");
                failures.add("CLASSIFICATION_GROUP:" + groupIndex + ":IDS:NONEMPTY_REQUIRED");
            }
            if (state != State.U && proofs.isEmpty()) {
                failures.add("CLASSIFICATION_PROOF_REQUIRED");
                failures.add("CLASSIFICATION_GROUP:" + groupIndex + ":PROOFS:NONEMPTY_VALID_IDS_REQUIRED");
            }
            for (int id : ids) {
                if (expectedIds != null && !expectedIds.contains(id))
                    failures.add("CLASSIFICATION_UNEXPECTED_CANDIDATE:" + id);
                if (!assigned.add(id)) failures.add("CLASSIFICATION_DUPLICATE_CANDIDATE:" + id);
                if (state == State.U) uncertainCount++;
            }
            groups.add(new DecisionGroup(state, reason, ids, proofs));
        }
        if (expectedIds == null) {
            for (Candidate candidate : catalog.candidates())
                if (!assigned.contains(candidate.number()))
                    failures.add("CLASSIFICATION_OMITTED_CANDIDATE:" + candidate.number());
        } else {
            expectedIds.stream().sorted().filter(id -> !assigned.contains(id))
                .forEach(id -> failures.add("CLASSIFICATION_OMITTED_CANDIDATE:" + id));
        }
        return new DecisionParsed(groups, List.copyOf(failures), failures.isEmpty(), uncertainCount);
    }

    /** Merge a validated closure with the recognised rewrite without repairing either proposal. */
    static DecisionParsed mergeDecisionClosure(DecisionParsed proposal,
            DecisionParsed closure, Catalog catalog, Set<Integer> expectedIds) {
        Set<String> failures = new LinkedHashSet<>();
        if (proposal == null || closure == null || catalog == null || expectedIds == null
            || expectedIds.isEmpty()) {
            failures.add("CLASSIFICATION_CLOSURE_MERGE_INVALID");
            return failedDecisions(failures, "CLASSIFICATION_CLOSURE_MERGE_INVALID");
        }
        if (!closure.complete())
            failures.addAll(closure.failures());
        Set<Integer> expected = new LinkedHashSet<>(expectedIds);
        Set<Integer> closureIds = new LinkedHashSet<>();
        for (DecisionGroup group : closure.groups()) for (int id : group.ids()) {
            if (!expected.contains(id)) failures.add("CLASSIFICATION_UNEXPECTED_CANDIDATE:" + id);
            if (!closureIds.add(id)) failures.add("CLASSIFICATION_DUPLICATE_CANDIDATE:" + id);
        }
        expected.stream().filter(id -> !closureIds.contains(id))
            .forEach(id -> failures.add("CLASSIFICATION_OMITTED_CANDIDATE:" + id));

        List<DecisionGroup> groups = new ArrayList<>(proposal.groups());
        groups.addAll(closure.groups());
        Set<Integer> allAssigned = new LinkedHashSet<>();
        int uncertain = 0;
        for (DecisionGroup group : groups) for (int id : group.ids()) {
            if (!allAssigned.add(id)) failures.add("CLASSIFICATION_DUPLICATE_CANDIDATE:" + id);
            if (group.state() == State.U) uncertain++;
        }
        for (Candidate candidate : catalog.candidates())
            if (!allAssigned.contains(candidate.number()))
                failures.add("CLASSIFICATION_OMITTED_CANDIDATE:" + candidate.number());
        return new DecisionParsed(groups, List.copyOf(failures), failures.isEmpty(), uncertain);
    }

    private static DecisionParsed failedDecisions(Set<String> failures, String reason) {
        failures.add(reason);
        return new DecisionParsed(List.of(), List.copyOf(failures), false, 0);
    }

    private static boolean compatible(State state, Reason reason) {
        return switch (state) {
            case I -> reason == Reason.MATCH;
            case E -> reason == Reason.OUTSIDE_ATTRIBUTE || reason == Reason.OUTSIDE_TYPE;
            case U -> reason == Reason.INSUFFICIENT;
        };
    }

    private static List<Integer> diagnosticIds(List<String> failures, String prefix) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (String failure : failures) {
            if (!failure.startsWith(prefix)) continue;
            try { ids.add(Integer.parseInt(failure.substring(prefix.length()))); }
            catch (NumberFormatException ignored) { /* A malformed range is kept in the failure text. */ }
        }
        return ids.stream().sorted().toList();
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static List<String> shortTextFailures(JsonNode node, int maximum) {
        if (node == null || !node.isTextual()) return List.of("TYPE");
        String text = node.textValue().strip();
        List<String> failures = new ArrayList<>();
        if (text.isBlank()) failures.add("BLANK");
        int length = text.codePointCount(0, text.length());
        if (length > maximum) failures.add("CODEPOINT_LIMIT:" + length + ">" + maximum);
        if (FORBIDDEN_PROOF_ID.matcher(text).find()) failures.add("FORBIDDEN_PROOF_ID");
        if (FORBIDDEN_TEXT.matcher(text).find()) failures.add("FORBIDDEN_MARKUP_LINK_OR_CITATION");
        return List.copyOf(failures);
    }

    private static boolean uncertainText(String text) {
        for (String clause : text.split("[，,。；;]"))
            if (!clause.isBlank() && !UNCERTAIN.matcher(clause).find()) return false;
        return UNCERTAIN.matcher(text).find();
    }

    private static List<Integer> parseIds(JsonNode node, int candidateCount, Set<String> failures) {
        List<Integer> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode value : node) {
                if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                    failures.add("CLASSIFICATION_INVALID_CANDIDATE_IDS"); continue;
                }
                addId(value.intValue(), candidateCount, result, failures);
            }
        } else if (node != null && node.isTextual()) {
            String text = node.textValue();
            if (!text.matches("[1-9]\\d*(?:-[1-9]\\d*)?(?:,[1-9]\\d*(?:-[1-9]\\d*)?)*")) {
                failures.add("CLASSIFICATION_INVALID_CANDIDATE_IDS"); return result;
            }
            for (String range : text.split(",")) {
                String[] ends = range.split("-");
                try {
                    int start = Integer.parseInt(ends[0]);
                    int end = ends.length == 1 ? start : Integer.parseInt(ends[1]);
                    if (end < start || end - (long) start > candidateCount
                        || start > candidateCount || end > candidateCount) {
                        failures.add("CLASSIFICATION_UNKNOWN_CANDIDATE:" + range); continue;
                    }
                    for (int id = start; id <= end; id++) addId(id, candidateCount, result, failures);
                } catch (NumberFormatException ignored) {
                    failures.add("CLASSIFICATION_INVALID_CANDIDATE_IDS");
                }
            }
        } else failures.add("CLASSIFICATION_INVALID_CANDIDATE_IDS");
        return List.copyOf(result);
    }

    private static void addId(int id, int candidateCount, List<Integer> result, Set<String> failures) {
        if (id < 1 || id > candidateCount) failures.add("CLASSIFICATION_UNKNOWN_CANDIDATE:" + id);
        else result.add(id);
    }

    private static List<String> parseProofs(JsonNode node, Set<String> failures, int groupIndex) {
        if (node == null || !node.isArray()) {
            failures.add("CLASSIFICATION_INVALID_PROOFS");
            failures.add("CLASSIFICATION_GROUP:" + groupIndex + ":PROOFS:ARRAY_REQUIRED");
            return List.of();
        }
        Set<String> proofs = new LinkedHashSet<>();
        int elementIndex = 0;
        for (JsonNode value : node) {
            elementIndex++;
            if (!value.isTextual() || !value.textValue().matches("p[1-9]\\d{0,8}")) {
                failures.add("CLASSIFICATION_INVALID_PROOF_ID");
                failures.add("CLASSIFICATION_GROUP:" + groupIndex + ":PROOFS:" + elementIndex
                    + (value.isTextual() ? ":FORMAT_EXPECTED_SINGLE_P_ID" : ":TYPE_EXPECTED_STRING"));
            } else if (!proofs.add(value.textValue())) {
                failures.add("CLASSIFICATION_DUPLICATE_PROOF_ID");
                failures.add("CLASSIFICATION_GROUP:" + groupIndex + ":PROOFS:" + elementIndex + ":DUPLICATE_ID");
            }
        }
        return List.copyOf(proofs);
    }

    /** Compact proposed assertions, without repeating the candidate names or source passages. */
    static String dispositions(Catalog catalog, Parsed parsed) {
        if (!catalog.requested() || !parsed.complete()) return "";
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Group group : parsed.groups()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("s", group.state().name()); row.put("role", group.role());
            row.put("ids", compactIds(group.ids())); row.put("proofs", group.proofs());
            row.put("why", group.why()); rows.add(row);
        }
        try { return JSON.writeValueAsString(Map.of("groups", rows)); }
        catch (Exception ignored) { throw new IllegalStateException("Cannot serialize classification dispositions"); }
    }

    private static String compactIds(List<Integer> ids) {
        List<Integer> ordered = ids.stream().sorted().toList();
        List<String> ranges = new ArrayList<>();
        for (int index = 0; index < ordered.size();) {
            int first = ordered.get(index), last = first;
            while (++index < ordered.size() && ordered.get(index) == last + 1) last = ordered.get(index);
            ranges.add(first == last ? String.valueOf(first) : first + "-" + last);
        }
        return String.join(",", ranges);
    }

    /** Compact Stage B decisions; no generated prose enters the verification packet. */
    static String decisionDispositions(Catalog catalog, DecisionParsed parsed) {
        if (!catalog.requested() || !parsed.complete()) return "";
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DecisionGroup group : parsed.groups()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("s", group.state().name()); row.put("reason", group.reason().name());
            row.put("ids", compactIds(group.ids())); row.put("proofs", group.proofs());
            rows.add(row);
        }
        try { return JSON.writeValueAsString(Map.of("groups", rows)); }
        catch (Exception ignored) { throw new IllegalStateException("Cannot serialize classification decisions"); }
    }

    /** U only preserves a candidate's graph binding and makes no classification claim. */
    static List<RelationClaimVerifier.Claim> decisionClaims(Catalog catalog, DecisionParsed parsed) {
        if (!catalog.requested() || !parsed.complete()) return List.of();
        List<RelationClaimVerifier.Claim> claims = new ArrayList<>();
        for (DecisionGroup group : parsed.groups()) {
            if (group.state() == State.U) continue;
            for (int id : group.ids()) {
                Candidate candidate = catalog.candidate(id);
                claims.add(new RelationClaimVerifier.Claim("candidate-" + id,
                    RelationClaimVerifier.ClaimType.CLASSIFICATION, candidate.anchorId(), candidate.otherId(),
                    Set.copyOf(candidate.relationIds()), group.state() + ":" + group.reason() + ":"
                        + reasonText(group.reason())));
            }
        }
        return List.copyOf(claims);
    }

    private static String reasonText(Reason reason) {
        return switch (reason) {
            case MATCH -> "符合所问筛选条件";
            case OUTSIDE_ATTRIBUTE -> "不符合所问属性";
            case OUTSIDE_TYPE -> "不属于所问对象范围";
            case INSUFFICIENT -> "待确认";
        };
    }

    /** Caller must first validate proof availability and semantics; only the program renders prose. */
    static Rendered renderDecisions(Catalog catalog, DecisionParsed parsed, EvidenceLedger ledger,
                                    ClassificationProofCatalog.ProofCatalog proofs) {
        if (!catalog.requested() || !catalog.inventoryComplete() || !parsed.complete())
            return new Rendered("现有证据不足以给出可靠的完整分类。", "PARTIAL", "CLASSIFICATION_PROTOCOL_INCOMPLETE");
        StringBuilder answer = new StringBuilder();
        boolean partial = !catalog.queryComplete() || parsed.uncertainCount() > 0 || catalog.candidates().isEmpty();
        boolean multipleSubjects = catalog.candidates().stream().map(Candidate::anchorId).distinct().count() > 1;
        answer.append(partial ? "以下是部分分类结果；尚不能完整回答所问分类：\n" : "按所问筛选条件分类如下：\n");
        for (DecisionGroup group : parsed.groups()) {
            if (!compatible(group.state(), group.reason()))
                return new Rendered("现有证据不足以给出可靠的完整分类。", "PARTIAL", "CLASSIFICATION_PROTOCOL_INCOMPLETE");
            String state = switch (group.state()) { case I -> "符合"; case E -> "排除"; case U -> "待确认"; };
            answer.append("- ").append(state).append("（").append(reasonText(group.reason())).append("）：");
            List<String> names = new ArrayList<>();
            for (int id : group.ids()) {
                Candidate candidate = catalog.candidate(id);
                if (candidate == null)
                    return new Rendered("现有证据不足以给出可靠的完整分类。", "PARTIAL", "CLASSIFICATION_BINDING_INVALID");
                EvidenceLedger.EntityFact anchor = ledger.entity(candidate.anchorId()).orElse(null);
                EvidenceLedger.EntityFact other = ledger.entity(candidate.otherId()).orElse(null);
                if (anchor == null || other == null || !Objects.equals(candidate.entityType(), other.type())
                    || candidate.relationIds().isEmpty() || candidate.relationIds().stream().anyMatch(relationId -> {
                        EvidenceLedger.RelationFact edge = ledger.relation(relationId).orElse(null);
                        return edge == null || !bound(candidate, edge);
                    }))
                    return new Rendered("现有证据不足以给出可靠的完整分类。", "PARTIAL", "CLASSIFICATION_BINDING_INVALID");
                String refs = String.join(" ", candidate.relationIds().stream().map(ref -> "r#" + ref).toList());
                names.add((multipleSubjects ? "《" + anchor.name() + "》：" : "") + other.name() + " [" + refs + "]");
            }
            answer.append(String.join("、", names));
            if (proofs != null && !group.proofs().isEmpty()) {
                if (group.proofs().stream().anyMatch(id -> proofs.span(id).isEmpty()))
                    return new Rendered("现有证据不足以给出可靠的完整分类。", "PARTIAL", "CLASSIFICATION_PROOF_BINDING_INVALID");
                List<String> sources = group.proofs().stream().map(proofs::span).flatMap(java.util.Optional::stream)
                    .map(proof -> "c#" + proof.chunkId()).distinct().toList();
                if (!sources.isEmpty()) answer.append("；分类依据：").append(String.join("、", sources));
            }
            answer.append('\n');
        }
        if (catalog.candidates().isEmpty()) answer.append("现有证据尚未展示可分类项目，暂不能确认所问分类。\n");
        String reason = !catalog.queryComplete() ? "CLASSIFICATION_QUERY_INCOMPLETE"
            : parsed.uncertainCount() > 0 ? "CLASSIFICATION_UNCERTAIN_CANDIDATES"
            : catalog.candidates().isEmpty() ? "CLASSIFICATION_NO_CANDIDATES" : "";
        return new Rendered(answer.toString().stripTrailing(), partial ? "PARTIAL" : "COMPLETE", reason);
    }

    /** Bind every proposed disposition to graph endpoints; U carries no positive/negative claim. */
    static List<RelationClaimVerifier.Claim> claims(Catalog catalog, Parsed parsed) {
        if (!catalog.requested() || !parsed.complete()) return List.of();
        List<RelationClaimVerifier.Claim> claims = new ArrayList<>();
        for (Group group : parsed.groups()) {
            if (group.state() == State.U) continue;
            for (int id : group.ids()) {
                Candidate candidate = catalog.candidate(id);
                claims.add(new RelationClaimVerifier.Claim("candidate-" + id,
                    RelationClaimVerifier.ClaimType.CLASSIFICATION, candidate.anchorId(), candidate.otherId(),
                    Set.copyOf(candidate.relationIds()), group.state() + ":" + group.role() + ":" + group.why()));
            }
        }
        return List.copyOf(claims);
    }

    /** Caller must first pass proof availability, binding and semantic checks. */
    static Rendered render(Catalog catalog, Parsed parsed, EvidenceLedger ledger) {
        return render(catalog, parsed, ledger, null);
    }

    static Rendered render(Catalog catalog, Parsed parsed, EvidenceLedger ledger,
                           ClassificationProofCatalog.ProofCatalog proofs) {
        if (!catalog.requested() || !catalog.inventoryComplete() || !parsed.complete())
            return new Rendered("现有证据不足以给出可靠的完整分类。", "PARTIAL", "CLASSIFICATION_PROTOCOL_INCOMPLETE");
        StringBuilder answer = new StringBuilder();
        boolean partial = !catalog.queryComplete() || parsed.uncertainCount() > 0 || catalog.candidates().isEmpty();
        boolean multipleSubjects = catalog.candidates().stream().map(Candidate::anchorId).distinct().count() > 1;
        answer.append(partial ? "以下是部分分类结果；尚不能完整回答所问分类：\n" : "按所问属性分类如下：\n");
        for (Group group : parsed.groups()) {
            String state = switch (group.state()) { case I -> "符合"; case E -> "排除"; case U -> "待确认"; };
            answer.append("- ").append(state).append("（").append(group.role()).append("）：");
            List<String> names = new ArrayList<>();
            for (int id : group.ids()) {
                Candidate candidate = catalog.candidate(id);
                EvidenceLedger.EntityFact anchor = ledger.entity(candidate.anchorId()).orElse(null);
                EvidenceLedger.EntityFact other = ledger.entity(candidate.otherId()).orElse(null);
                if (anchor == null || other == null || candidate.relationIds().stream().anyMatch(relationId -> {
                    EvidenceLedger.RelationFact edge = ledger.relation(relationId).orElse(null);
                    return edge == null || !bound(candidate, edge);
                })) return new Rendered("现有证据不足以给出可靠的完整分类。", "PARTIAL", "CLASSIFICATION_BINDING_INVALID");
                String refs = String.join(" ", candidate.relationIds().stream().map(ref -> "r#" + ref).toList());
                names.add((multipleSubjects ? "《" + anchor.name() + "》：" : "") + other.name() + " [" + refs + "]");
            }
            answer.append(String.join("、", names));
            if (proofs != null && !group.proofs().isEmpty()) {
                List<String> sources = group.proofs().stream().map(proofs::span).flatMap(java.util.Optional::stream)
                    .map(proof -> "c#" + proof.chunkId()).distinct().toList();
                if (!sources.isEmpty()) answer.append("；分类依据：").append(String.join("、", sources));
            }
            answer.append('\n');
        }
        if (catalog.candidates().isEmpty()) answer.append("现有证据尚未展示可分类项目，暂不能确认所问分类。\n");
        String reason = !catalog.queryComplete() ? "CLASSIFICATION_QUERY_INCOMPLETE"
            : parsed.uncertainCount() > 0 ? "CLASSIFICATION_UNCERTAIN_CANDIDATES"
            : catalog.candidates().isEmpty() ? "CLASSIFICATION_NO_CANDIDATES" : "";
        return new Rendered(answer.toString().stripTrailing(), partial ? "PARTIAL" : "COMPLETE", reason);
    }

    private static boolean bound(Candidate candidate, EvidenceLedger.RelationFact edge) {
        Long source = "OUT".equals(candidate.direction()) ? candidate.anchorId() : candidate.otherId();
        Long target = "OUT".equals(candidate.direction()) ? candidate.otherId() : candidate.anchorId();
        return Objects.equals(edge.sourceId(), source) && Objects.equals(edge.targetId(), target)
            && Objects.equals(edge.type(), candidate.relationType());
    }
}
