package cn.ilink.service.kg;

import cn.ilink.service.ai.ChatRequestBudget;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zero-model replay of the v14 M7 budget stop. It proves the saved rewrite omitted only candidate
 * 49, the saved closure merged it, and the bounded closure pack leaves the final verifier slot
 * structurally reserved before CLOSURE spends anything.
 */
@EnabledIfSystemProperty(named = "kgV14M7Replay", matches = "true")
class SavedV14M7ClosureBudgetReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void savedClosureCompletesAndItsBoundedPackFitsAfterProtectingFinalVerification()
            throws Exception {
        Path source = requiredPath("kgV14M7ReplayFile");
        byte[] before = Files.readAllBytes(source);
        JsonNode row = onlyCase(source, "M7");
        String question = row.path("question").asText();
        JsonNode catalogNode = traceStep(row, "classificationCatalog").path("classificationCatalog");
        GraphClassificationContract.Catalog catalog = catalog(catalogNode, question);
        assertEquals(88, catalog.candidates().size());

        EvidenceLedger ledger = SavedV13FoundationReplayTest.replayLedger(row);
        JsonNode generationEvidence = traceStep(row, "generationEvidence").path("generationEvidence");
        ClassificationProofCatalog.ProofCatalog proofs = proofCatalog(generationEvidence);
        Set<String> sent = strings(generationEvidence.path("proofIds"));
        assertEquals(24, sent.size());
        QueryEvidencePlan plan = QueryEvidencePlan.fromQuestion(question);

        var rewrite = GraphClassificationContract.parseDecisions(
            traceStep(row, "rewriteResult").path("rewriteResult").path("draft").asText(), catalog);
        assertEquals(List.of(49), rewrite.missingCandidateIds());
        assertTrue(rewrite.onlyOmittedCandidates());

        Set<Integer> missing = Set.copyOf(rewrite.missingCandidateIds());
        var closure = GraphClassificationContract.parseDecisionClosure(
            traceStep(row, "classificationClosureResult").path("classificationClosureResult")
                .path("draft").asText(), catalog, missing);
        assertTrue(closure.complete(), closure.failures().toString());
        var actualFinal = GraphClassificationContract.mergeDecisionClosure(
            rewrite, closure, catalog, missing);
        assertTrue(actualFinal.complete(), actualFinal.failures().toString());

        Map<Integer, Map<String, Object>> diagnostics = new LinkedHashMap<>();
        for (Map<String, Object> member : ClassificationEvidenceVerifier.memberEvidenceDiagnostics(
                catalog, rewrite, proofs, sent, ledger)) {
            diagnostics.put(((Number) member.get("candidateId")).intValue(), member);
        }
        @SuppressWarnings("unchecked")
        List<String> relevant = (List<String>) diagnostics.get(49).get("sentRelevantProofIds");
        assertEquals(Set.of("p56", "p58", "p77", "p92", "p93", "p106"), Set.copyOf(relevant));

        var packed = proofs.packClosure(Map.of(49, relevant),
            GraphClassificationFlow.CLOSURE_PROOF_BUDGET, ledger);
        assertTrue(packed.withinBudget(), packed.metadata().toString());
        assertEquals(List.of(49), packed.coveredCandidateIds());
        assertTrue(sent.containsAll(packed.proofIds()));
        assertTrue(packed.omittedProofIds().stream().allMatch(relevant::contains));
        assertTrue(packed.tokenCount() <= 1_000);

        var projection = ClassificationModelContext.project(catalog);
        var verifier = new ClassificationEvidenceVerifier();
        int originalFinal = verifier.reservationBound(question, plan, ledger, catalog, actualFinal,
            proofs, sent, projection);
        assertEquals(5_329, originalFinal,
            "The saved v14 final verifier reservation must reproduce the recorded 248-token deficit.");

        List<GraphClassificationContract.DecisionGroup> hypothetical =
            new ArrayList<>(rewrite.groups());
        hypothetical.add(new GraphClassificationContract.DecisionGroup(
            GraphClassificationContract.State.I, GraphClassificationContract.Reason.MATCH,
            List.of(49), packed.proofIds()));
        var proofMaximal = new GraphClassificationContract.DecisionParsed(
            hypothetical, List.of(), true, 0);
        int packedFinal = verifier.reservationBound(question, plan, ledger, catalog, proofMaximal,
            proofs, sent, projection);
        assertTrue(packedFinal <= originalFinal,
            "Bounded closure proofs must not enlarge the final verifier reservation: "
                + originalFinal + " -> " + packedFinal);

        StringBuilder inventory = new StringBuilder();
        GraphClassificationContract.Candidate candidate = catalog.candidate(49);
        inventory.append("候选49 name=").append(candidate.name())
            .append(" type=").append(candidate.entityType())
            .append(" anchor=").append(candidate.anchorName())
            .append(" relation=").append(candidate.relationType())
            .append(" direction=").append(candidate.direction())
            .append(" sentRelevantProofIds=").append(relevant).append('\n');
        List<Map<String, String>> messages = GraphClassificationFlow.closureMessages(
            question, List.of(49), inventory.toString(), packed.text());
        int closurePrompt = ChatRequestBudget.qwenPromptReservationBound(messages);
        int closureReservation = closurePrompt + ChatRequestBudget.CLOSURE_COMPLETION_LIMIT;
        int protectedFinal = packedFinal + GraphClassificationFlow.FINAL_VERIFIER_GUARD_PADDING;

        List<Map<String, Object>> repairHints = JSON.convertValue(
            traceStep(row, "classificationRepairHints").path("classificationRepairHints"),
            new TypeReference<List<Map<String, Object>>>() { });
        String reasons = traceStep(row, "classificationDisposition")
            .path("classificationDisposition").path("failures").toString();
        var rewriteRequest = GraphClassificationFlow.rewriteRequest(
            question, catalog, traceStep(row, "generationResult").path("generationResult")
                .path("draft").asText(),
            List.of(reasons), rewrite, repairHints, proofs, sent, ledger);
        int rewritePrompt = ChatRequestBudget.qwenPromptReservationBound(rewriteRequest.messages());
        assertTrue(rewritePrompt < 5_000,
            "Repair-only REWRITE must avoid repeating the complete 2,998-token generation evidence: "
                + rewritePrompt);

        ChatRequestBudget budget = new ChatRequestBudget();
        try (var scope = budget.bind()) {
            replayKnownStage(budget, row, "GENERATE");
            // Reserve and spend the entire REWRITE prompt bound plus completion cap. This is a
            // deliberately worst-case path, stronger than relying on a lucky lower usage sample.
            try (var stage = ChatRequestBudget.stage(ChatRequestBudget.Stage.REWRITE);
                 var rewriteReservation = budget.reserve(rewritePrompt, 1200)) {
                rewriteReservation.settle(rewritePrompt, 1200);
            }
            int beforeClosure = budget.remainingTokens();
            assertTrue(budget.canProtect(protectedFinal),
                "Final verifier protection must fit: before=" + beforeClosure
                    + " protected=" + protectedFinal);
            var protection = budget.protect(protectedFinal, "FINAL_CLASSIFICATION_VERIFIER");
            try (var stage = ChatRequestBudget.stage(ChatRequestBudget.Stage.CLOSURE)) {
                assertTrue(budget.canReserve(closurePrompt, ChatRequestBudget.CLOSURE_COMPLETION_LIMIT),
                    "CLOSURE and the protected final verifier must coexist: before=" + beforeClosure
                        + " protected=" + protectedFinal + " closure=" + closureReservation);
                try (var closureReservationScope = budget.reserve(closurePrompt,
                        ChatRequestBudget.CLOSURE_COMPLETION_LIMIT)) {
                    closureReservationScope.settle(closurePrompt,
                        ChatRequestBudget.CLOSURE_COMPLETION_LIMIT);
                }
            }
            protection.release();
            assertTrue(budget.canReserve(packedFinal - AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET,
                AnswerEvidenceVerifier.VERDICT_COMPLETION_BUDGET));
        }

        assertFalse(originalFinal + GraphClassificationFlow.FINAL_VERIFIER_GUARD_PADDING
            <= budgetAfterKnownStages(row), "The saved v14 path could not protect the unbounded final verifier.");
        assertArrayEquals(before, Files.readAllBytes(source), "The saved input batch must not change");
    }

    private static GraphClassificationContract.Catalog catalog(JsonNode node, String question) {
        List<GraphClassificationContract.Candidate> candidates = new ArrayList<>();
        for (JsonNode item : node.path("candidates")) {
            candidates.add(new GraphClassificationContract.Candidate(
                item.path("number").asInt(), item.path("anchorId").asLong(),
                item.path("otherId").asLong(), item.path("anchorName").asText(),
                item.path("name").asText(), item.path("entityType").asText(),
                item.path("relationType").asText(), item.path("direction").asText(),
                longs(item.path("relationIds"))));
        }
        return new GraphClassificationContract.Catalog(
            node.path("requested").asBoolean(), node.path("skipReason").asText(),
            candidates, node.path("text").asText(),
            JSON.convertValue(node.path("metadata").isObject() ? node.path("metadata")
                    : JSON.createObjectNode(),
                new TypeReference<Map<String, Object>>() { }),
            node.path("queryComplete").asBoolean(), node.path("inventoryComplete").asBoolean(),
            QueryIntent.fromQuestion(question).focusText());
    }

    private static ClassificationProofCatalog.ProofCatalog proofCatalog(JsonNode evidence) {
        List<ClassificationProofCatalog.ProofSpan> spans = new ArrayList<>();
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (JsonNode item : evidence.path("proofSpans")) {
            String id = item.path("proofId").asText();
            spans.add(new ClassificationProofCatalog.ProofSpan(
                id, item.path("chunkId").asLong(), item.path("docType").asText(),
                item.path("docId").asLong(), item.path("title").asText(),
                item.path("start").asInt(), item.path("end").asInt(), item.path("text").asText(),
                Set.copyOf(longs(item.path("relationIds")))));
            scores.put(id, 1);
        }
        return ClassificationProofCatalog.fromAuthorizedSpans(spans, scores);
    }

    private static void replayKnownStage(ChatRequestBudget budget, JsonNode row, String stage) {
        JsonNode usage = java.util.stream.StreamSupport.stream(
                traceStep(row, "budgetStop").path("budgetStop")
                    .path("usageRecords").spliterator(), false)
            .filter(item -> stage.equals(item.path("stage").asText())).findFirst().orElseThrow();
        try (var scope = ChatRequestBudget.stage(ChatRequestBudget.Stage.valueOf(stage));
             var reservation = budget.reserve(usage.path("promptReservation").asInt(),
                 usage.path("completionLimit").asInt())) {
            reservation.settle(usage.path("promptTokens").asInt(),
                usage.path("completionTokens").asInt());
        }
    }

    private static int budgetAfterKnownStages(JsonNode row) {
        ChatRequestBudget budget = new ChatRequestBudget();
        try (var scope = budget.bind()) {
            replayKnownStage(budget, row, "GENERATE");
            replayKnownStage(budget, row, "REWRITE");
            replayKnownStage(budget, row, "CLOSURE");
            return budget.remainingTokens();
        }
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> values = new ArrayList<>();
        for (JsonNode item : array) values.add(item.asLong());
        return values;
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : array) values.add(item.asText());
        return values;
    }

    private static JsonNode onlyCase(Path source, String id) throws Exception {
        JsonNode root = JSON.readTree(Files.readAllBytes(source));
        assertTrue(root.isArray());
        List<JsonNode> matches = java.util.stream.StreamSupport.stream(root.spliterator(), false)
            .filter(row -> id.equals(row.path("id").asText())).toList();
        assertEquals(1, matches.size());
        return matches.get(0);
    }

    private static JsonNode traceStep(JsonNode row, String key) {
        return java.util.stream.StreamSupport.stream(row.path("b3_trace").spliterator(), false)
            .filter(step -> step.hasNonNull(key)).findFirst().orElseThrow();
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property, "");
        assertFalse(value.isBlank(), "Set -D" + property + " to the saved v14 batch8.json");
        Path path = Path.of(value).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(path), "Saved batch does not exist: " + path);
        return path;
    }
}
