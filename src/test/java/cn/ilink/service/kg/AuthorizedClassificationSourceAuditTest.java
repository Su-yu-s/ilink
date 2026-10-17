package cn.ilink.service.kg;

import cn.ilink.mapper.KgAgentTraceMapper;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.ai.ChatRequestBudget.BudgetStopException;
import cn.ilink.service.kg.tool.GraphTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * User-run, opt-in audit of one current permission-checked classification request. Enable with
 * -DkgSourceAudit=true -DkgSourceAuditQuestion="the user's question" -DkgSourceAuditUser=8,
 * plus the independent clone URL and kgStageDatabase required by StageUrlGuard.
 * Actual graph tools and the actual ledger factory remain in use. Retrieval can query the clone
 * and invoke supplier embeddings or other external services; their usage is not accounted here.
 * Only this request's chat client and trace mapper are mocked: chat stops before provider dispatch,
 * and trace inserts reach a mock. The production request may perform bounded source acquisition;
 * local comparisons after it returns never retrieve sources. This is not a model answer or dev20.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"dev", "local"})
@ContextConfiguration(initializers = KgStageRebuildTest.StageUrlGuard.class)
@EnabledIfSystemProperty(named = "kgSourceAudit", matches = "true")
class AuthorizedClassificationSourceAuditTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STOP_REASON = "SOURCE_AUDIT_BEFORE_CHAT";
    private static final String SOURCE_BLOCK_POLICY = "classification-source-blocks-v1";
    private static final int DEFAULT_PACKING_BUDGET = 2000;
    private static final int HARD_PACKING_BUDGET = 3000;
    private static final List<String> MEMBER_SETS = List.of("sentMentionCandidateIds",
        "sentRelevantCandidateIds", "sentContextCandidateIds", "sentBoundCandidateIds");

    @Autowired private GraphRagService fastRag;
    @Autowired private List<GraphTool> graphTools;
    @Autowired private EvidenceLedgerFactory realLedgerFactory;

    @Test
    void auditCurrentAuthorizedSourcesBeforeAnyProviderChat() throws Exception {
        String question = System.getProperty("kgSourceAuditQuestion", "");
        String configuredUser = System.getProperty("kgSourceAuditUser", "");
        assertFalse(question.isBlank(), "Set -DkgSourceAuditQuestion to the user's classification question.");
        assertTrue(configuredUser.matches("[1-9]\\d{0,17}"), "Set a positive -DkgSourceAuditUser, for example 8.");
        long userId = Long.parseLong(configuredUser);
        Path outputDirectory = checkedOutputDirectory(Path.of("target/kg-source-live-audit"));
        UUID runId = UUID.randomUUID();
        Path output = outputDirectory.resolve("authorized-source-" + runId + ".json");
        Map<String, Object> sourceHashes = sourceHashes();

        AtomicInteger chatAttempts = new AtomicInteger();
        AiClient auditAi = mock(AiClient.class);
        when(auditAi.chat(anyList(), anyInt())).thenAnswer(invocation -> {
            chatAttempts.incrementAndGet();
            throw new BudgetStopException(STOP_REASON);
        });
        KgAgentTraceMapper auditTraceMapper = mock(KgAgentTraceMapper.class);
        AtomicInteger ledgerCreations = new AtomicInteger();
        AtomicReference<EvidenceLedger> capturedLedger = new AtomicReference<>();
        EvidenceLedgerFactory capturingFactory = mock(EvidenceLedgerFactory.class);
        when(capturingFactory.create()).thenAnswer(invocation -> {
            EvidenceLedger ledger = realLedgerFactory.create();
            ledgerCreations.incrementAndGet();
            capturedLedger.set(ledger);
            return ledger;
        });
        AgenticGraphRagService auditService = new AgenticGraphRagService(fastRag, auditTraceMapper,
            auditAi, graphTools, capturingFactory);
        AgenticGraphRagService.AgenticAnswer answer = auditService.query(question, userId);
        JsonNode rawTrace = JSON.valueToTree(answer.trace);
        List<JsonNode> selections = traceValues(rawTrace, "classificationSelection");
        List<JsonNode> catalogs = traceValues(rawTrace, "classificationCatalog");
        List<JsonNode> evidencePackets = traceValues(rawTrace, "generationEvidence");
        List<JsonNode> sourceAcquisitions = traceValues(rawTrace, "classificationSourceAcquisition");
        List<String> failures = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sampleKind", "CURRENT_AUTHORIZED_SOURCE_AND_PACKING_AUDIT_BEFORE_CHAT");
        report.put("formalEvaluationResult", false);
        report.put("runId", runId.toString());
        report.put("recordedAt", Instant.now().toString());
        report.put("question", question);
        report.put("userId", userId);
        report.put("stageDatabase", System.getProperty("kgStageDatabase"));
        report.put("sourceHashes", sourceHashes);
        report.put("sourceHashScope", "ON_DISK_SOURCE_AT_AUDIT_TIME; not a compiled-bytecode attestation");
        report.put("rawTrace", rawTrace);
        report.put("classificationSelections", selections);
        report.put("classificationCatalogs", catalogs);
        report.put("generationEvidencePackets", evidencePackets);
        report.put("classificationSourceAcquisitions", sourceAcquisitions);
        report.put("chatAttempts", chatAttempts.get());
        report.put("chatStopReason", chatAttempts.get() > 0 ? STOP_REASON : "NOT_REACHED");
        report.put("providerChatRequests", 0);
        report.put("providerChatAccounting", "THIS_REQUEST_USES_MOCK_CHAT_AND_STOPS_BEFORE_PROVIDER_DISPATCH");
        report.put("embeddingAccounting", "NOT_ACCOUNTED; real tool beans may make supplier embedding requests");
        report.put("externalRequestsPossible", true);
        report.put("aiUsageClaim", "PROVIDER_CHAT_ZERO_FOR_THIS_AUDITED_REQUEST; not zero AI or zero external usage");
        report.put("tracePersistence", "MOCK_MAPPER_ONLY; this request cannot insert a real kg_agent_trace row");
        report.put("mockTraceMapperInvocations", mockingDetails(auditTraceMapper).getInvocations().size());
        report.put("ledgerCreations", ledgerCreations.get());
        report.put("toolCalls", answer.toolCalls);
        report.put("answerIsAuditStopOutput", chatAttempts.get() == 1);
        report.put("evidenceTokenAccounting", "PINNED_LOCAL_TEXT_TOKENS; not supplier billing or embedding usage");
        report.put("semanticValidation", "NOT_RUN; member/source coverage does not establish classification truth");
        report.put("manualQualityGate", "STOP_UNLESS_SOURCE_GAPS_ZERO_AND_OLD_STAGE_B_RELEVANT_PROTECTED_AND_"
            + "SAME_LEDGER_C2_ALL_FOUR_MEMBER_SETS_PROTECTED_AND_ALL_AVAILABLE_RELEVANT_SOURCES_SENT. "
            + "A structural PASS is distinct from this "
            + "quality precondition. OLD_STAGE_B_CANDIDATE_2_PACKING is NOT_BASELINE_B2_GRAPHRAG. "
            + "This artifact does not authorize paid dev20 or establish classification truth.");

        boolean sourceStage = rawTrace.isArray() && answer.trace.stream()
            .anyMatch(row -> "D-stage-1".equals(row.get("strategyCandidate")));
        if (!sourceStage) failures.add("The actual trace did not enter D-stage-1.");
        if (selections.size() != 1) failures.add("Expected exactly one actual classificationSelection; no recovery is synthesized.");
        if (catalogs.size() != 1) failures.add("Expected exactly one actual classificationCatalog.");
        if (evidencePackets.size() != 1) failures.add("Expected exactly one actual generationEvidence packet.");
        if (ledgerCreations.get() != 1 || capturedLedger.get() == null)
            failures.add("Expected exactly one captured real request ledger.");
        if (chatAttempts.get() != 1) failures.add("Expected one stopped mock chat attempt after packing.");
        if (sourceAcquisitions.size() != 1) failures.add("Expected exactly one production source-acquisition diagnostic.");
        if (answer.toolCalls > 8) failures.add("The production request exceeded the frozen eight-tool limit.");
        if (EvidenceContext.DEFAULT_BUDGET != DEFAULT_PACKING_BUDGET
                || EvidenceContext.MAX_BUDGET != HARD_PACKING_BUDGET)
            failures.add("The frozen default/maximum evidence budgets must remain 2000/3000.");

        try {
            report.put("sourceExecutionChecks", sourceExecutionChecks(rawTrace, sourceAcquisitions,
                capturedLedger.get(), answer.toolCalls));
        } catch (RuntimeException invalid) {
            failures.add("Source execution snapshot: " + invalid.getClass().getSimpleName() + ": " + invalid.getMessage());
        }

        if (selections.size() == 1) {
            report.put("classificationSelection", selections.get(0));
            try {
                report.put("packingChecks", packingChecks(selections.get(0),
                    evidencePackets.size() == 1 ? evidencePackets.get(0) : null));
            } catch (RuntimeException invalid) {
                failures.add("Actual packing snapshot: " + invalid.getClass().getSimpleName() + ": " + invalid.getMessage());
            }
        }
        if (capturedLedger.get() != null) {
            report.put("authorizedLedgerEntities", JSON.valueToTree(capturedLedger.get().entities()));
            report.put("authorizedRelationFacts", JSON.valueToTree(capturedLedger.get().relations()));
            report.put("authorizedSourceChunks", JSON.valueToTree(capturedLedger.get().chunks()));
            report.put("authorizedLedgerCoverage", JSON.valueToTree(capturedLedger.get().coverage()));
            report.put("ledgerFactScope", "CAPTURED_REAL_PERMISSION_CHECKED_LEDGER_AFTER_PRODUCTION_ACQUISITION; "
                + "full original factText/sourceChunkId and chunk text/identity/coordinates; no synthesized source facts");
        }
        // Comparisons never replace a missing real selection or fetch/complete any source facts.
        if (sourceStage && selections.size() == 1 && catalogs.size() == 1 && evidencePackets.size() == 1
                && ledgerCreations.get() == 1 && capturedLedger.get() != null) {
            try {
                report.put("sameLedgerComparisons", sameLedgerComparisons(question, capturedLedger.get(),
                    catalogs.get(0), evidencePackets.get(0), selections.get(0)));
            } catch (RuntimeException invalid) {
                failures.add("Same-ledger comparison unavailable: " + invalid.getClass().getSimpleName() + ": " + invalid.getMessage());
            }
            try {
                report.put("prospectiveClaimPacketBudget", prospectiveClaimPacketBudget(question,
                    capturedLedger.get(), evidencePackets.get(0)));
            } catch (RuntimeException unavailable) {
                report.put("prospectiveClaimPacketBudget", Map.of("sampleKind", "NOT_ACTUAL_VERIFICATION",
                    "status", "UNAVAILABLE", "reason", unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage(),
                    "semanticVerdict", "NOT_RUN", "qualityGateInput", false, "actualDecisionLengthKnown", false));
            }
        }
        report.put("packingGateFailures", failures);
        report.put("structuralPackingGate", failures.isEmpty() ? "PASS" : "FAIL");
        List<String> qualityStops = qualityStops(report.get("sameLedgerComparisons"), selections);
        if (!failures.isEmpty()) qualityStops.add("STRUCTURAL_GATE_FAILED");
        report.put("qualityStopReasons", qualityStops);
        report.put("sourceAndPackingQualityPrecondition", qualityStops.isEmpty() ? "PASS" : "STOP");
        report.put("paidDev20Executed", false);
        report.put("dev20Decision", qualityStops.isEmpty() ? "HUMAN_REVIEW_REQUIRED_BEFORE_ANY_DEV20" : "DO_NOT_RUN_DEV20");
        report.put("limitations", "One current authorized request on an explicit independent clone; "
            + "not an isolated unit test, dev20, holdout, scoring, model quality, a complete supplier usage "
            + "ledger, or semantic verification. Local comparisons do not change actual D packing. "
            + "No new source retrieval, classification decisions, or automatic missing-proof disposition "
            + "is performed after query returns.");
        checkedAncestors(output);
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),
            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("Current authorized source audit report: " + output);
        // Preserve the raw report before failing a missing-selection/budget/preservation gate.
        assertEquals(List.of(), failures, "Packing audit failed; review the saved raw report: " + output);
    }

    /** Reconstruct actual dispatches from action/observation pairs, excluding cache hits and local source limits. */
    private static Map<String, Object> sourceExecutionChecks(JsonNode trace, List<JsonNode> acquisitions,
            EvidenceLedger ledger, int reportedToolCalls) {
        require(trace.isArray(), "Raw request trace is missing.");
        require(ledger != null, "The real authorized ledger is required for source provenance checks.");
        Set<String> dispatchedOrCached = new LinkedHashSet<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        int totalDispatches = 0, sourceDispatches = 0, sourceActionRows = 0, cachedSourceRows = 0;
        for (int index = 0; index < trace.size(); index++) {
            JsonNode row = trace.get(index);
            String action = row.path("action").asText("");
            if (action.isEmpty() || !row.path("actionInput").isTextual()) continue;
            boolean sourceAction = ClassificationSourceAcquisition.TOOL.equals(action);
            if (sourceAction) sourceActionRows++;
            // Rejected ReAct actions have no following tool observation and were never dispatched.
            if (index + 1 >= trace.size() || !trace.get(index + 1).path("observation").isTextual()) continue;
            JsonNode observation = parseJson(trace.get(index + 1).path("observation").textValue());
            require(observation != null, "Tool observation JSON is empty.");
            String cacheKey;
            try {
                cacheKey = action + "|" + JSON.writeValueAsString(orderedValue(
                    JSON.readValue(row.path("actionInput").textValue(), Object.class)));
            } catch (Exception invalid) { throw new IllegalStateException("Cannot recover a tool request cache key.", invalid); }
            if (!dispatchedOrCached.add(cacheKey)) {
                if (sourceAction) cachedSourceRows++;
                continue;
            }
            if (sourceAction && "SOURCE_READ_LIMIT".equals(observation.path("error").asText())) continue;
            totalDispatches++;
            if (!sourceAction) continue;
            sourceDispatches++;
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("rawTraceActionIndex", index);
            source.put("toolObservation", observation);
            source.put("actualDispatch", true);
            source.put("semanticSupportEstablished", false);
            if (!observation.has("error")) {
                JsonNode binding = observation.path("sourceBinding");
                require(binding.isObject(), "A successful source read must retain its relation binding.");
                require(binding.path("endpointsAuthorized").isBoolean() && binding.path("endpointsAuthorized").booleanValue(),
                    "Source relation endpoints were not recorded as authorized.");
                var edge = ledger.relation(binding.path("relationId").asLong()).orElseThrow(() ->
                    new IllegalStateException("Source binding relation is absent from the captured authorized ledger."));
                require(java.util.Objects.equals(edge.sourceDocId(), binding.path("sourceDocId").asText()),
                    "Source read document differs from its authorized relation's declared source.");
                require(observation.path("sourceSearch").path("semanticSupportEstablished").isBoolean()
                    && !observation.path("sourceSearch").path("semanticSupportEstablished").booleanValue(),
                    "A literal source lookup cannot claim semantic support.");
                require(observation.path("chunks").isArray(), "Successful source-read chunks are missing.");
                for (JsonNode chunk : observation.path("chunks")) {
                    String document = chunk.path("docType").asText() + ":" + chunk.path("docId").asLong();
                    require(document.equals(edge.sourceDocId()), "Returned chunk is outside the authorized relation source document.");
                    var captured = ledger.chunk(chunk.path("chunkId").asLong()).orElseThrow(() ->
                        new IllegalStateException("Returned source chunk did not enter the permission-checked ledger."));
                    require(document.equals(captured.docType() + ":" + captured.docId()),
                        "Returned source chunk identity differs from the captured ledger.");
                    JsonNode sourceTitle = JSON.valueToTree(captured.title());
                    require(sourceTitle.equals(chunk.path("title")), "Returned source title differs from the captured ledger.");
                    require(chunk.path("text").isTextual() && java.util.Objects.toString(captured.text(), "")
                        .startsWith(chunk.path("text").textValue()), "Returned source display is not an exact authorized text prefix.");
                    JsonNode sourceStart = JSON.valueToTree(captured.startOffset());
                    JsonNode sourceEnd = JSON.valueToTree(captured.endOffset());
                    require(sourceStart.equals(chunk.path("startOffset")) && sourceEnd.equals(chunk.path("endOffset")),
                        "Returned source coordinates differ from the captured ledger.");
                }
                source.put("returnedChunksMatchAuthorizedDeclaredDocument", true);
            }
            sources.add(source);
        }
        require(totalDispatches == reportedToolCalls, "Raw trace dispatch count differs from the actual request tool counter.");
        require(totalDispatches <= 8, "Raw trace exceeds the frozen eight-tool limit.");
        require(sourceDispatches <= ClassificationSourceAcquisition.MAX_REQUESTS, "Raw trace exceeds the two-source-read limit.");
        for (JsonNode acquisition : acquisitions) for (JsonNode observation : acquisition.path("observations")) {
            if (!observation.has("memberProofAvailable")) continue;
            require(observation.path("memberProofAvailable").isBoolean(), "Source acquisition availability must be a boolean.");
            require(observation.path("semanticSupportEstablished").isBoolean()
                && !observation.path("semanticSupportEstablished").booleanValue(),
                "memberProofAvailable is retrieval availability, not semantic verification.");
        }
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("actualToolDispatches", totalDispatches); checks.put("toolLimit", 8);
        checks.put("sourceActionRows", sourceActionRows); checks.put("cachedSourceRows", cachedSourceRows);
        checks.put("actualSourceDispatches", sourceDispatches); checks.put("sourceReadLimit", ClassificationSourceAcquisition.MAX_REQUESTS);
        checks.put("actualSourceReads", sources); checks.put("semanticVerdict", "NOT_RUN");
        checks.put("availabilityMeaning", "MEMBER_PROOF_AVAILABLE_IS_SOURCE_RETRIEVAL_ONLY; not a classification verdict");
        return checks;
    }

    private static JsonNode parseJson(String text) {
        try { return JSON.readTree(text); }
        catch (Exception invalid) { throw new IllegalStateException("Tool observation is not recoverable JSON.", invalid); }
    }

    private static Object orderedValue(Object value) {
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> ordered = new java.util.TreeMap<>();
            values.forEach((key, item) -> ordered.put(String.valueOf(key), orderedValue(item)));
            return ordered;
        }
        if (value instanceof List<?> values) return values.stream().map(AuthorizedClassificationSourceAuditTest::orderedValue).toList();
        return value;
    }

    /** Packet-size rehearsal only: there is no generated decision or verification result. */
    private static Map<String, Object> prospectiveClaimPacketBudget(String question, EvidenceLedger ledger, JsonNode evidence) {
        var plan = QueryEvidencePlan.fromQuestion(question);
        var catalog = GraphClassificationContract.prepare(question, plan, ledger);
        var prepared = ClassificationCoverageProofs.prepare(question, catalog, ledger);
        List<String> selectedIds = proofIds(evidence, "proofIds");
        Set<Long> relationIds = new LinkedHashSet<>(), entityIds = new LinkedHashSet<>(), chunkIds = new LinkedHashSet<>();
        for (var candidate : catalog.candidates()) {
            relationIds.addAll(candidate.relationIds()); entityIds.add(candidate.anchorId()); entityIds.add(candidate.otherId());
        }
        for (String id : selectedIds) chunkIds.add(prepared.proofs().span(id).orElseThrow().chunkId());
        // This supported flag is only a formatter input; no verifier is invoked and it is not exported as a verdict.
        var formatOnly = new AnswerEvidenceVerifier.VerificationResult(true, List.of("NON_ANSWER_BUDGET_PROBE"),
            Set.copyOf(entityIds), Set.copyOf(relationIds), Set.copyOf(chunkIds));
        String neutral = "NON_ANSWER_BUDGET_PROBE; no category decisions, no facts or coverage verdict.\n"
            + "candidateNumbers=" + catalog.candidates().stream().map(GraphClassificationContract.Candidate::number).toList()
            + "\ncurrentProofIds=" + selectedIds;
        var packet = VerificationEvidenceContext.packClaims(ledger, plan, formatOnly, catalog.text(), neutral,
            prepared.proofs(), selectedIds);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleKind", "NOT_ACTUAL_VERIFICATION"); result.put("status", "FORMAT_ONLY_SIZE_REHEARSAL");
        result.put("estimatedTokenCount", packet.tokenCount()); result.put("expandedTokenCount", packet.expandedTokenCount());
        result.put("packingPolicy", packet.packingPolicy()); result.put("withinBudget", packet.tokenCount() <= HARD_PACKING_BUDGET);
        result.put("budget", HARD_PACKING_BUDGET); result.put("candidateCount", catalog.candidates().size());
        result.put("currentProofIds", selectedIds); result.put("neutralDispositionText", neutral);
        result.put("actualDecisionLengthKnown", false); result.put("modelRequests", 0); result.put("verifierInvocations", 0);
        result.put("semanticVerdict", "NOT_RUN"); result.put("qualityGateInput", false); result.put("stopGateInput", false);
        result.put("interpretation", "Approximate claim-packet representation with all candidate relations and current proof IDs. "
            + "Actual model dispositions and their citation subset/length are unknown. This is neither a bound "
            + "on the real VERIFY request nor evidence of semantic support; it is only a budget-risk hint.");
        return result;
    }

    private static Map<String, Object> packingChecks(JsonNode selected, JsonNode evidence) {
        require(selected.isObject(), "classificationSelection must be an object.");
        boolean attempted = requiredBoolean(selected, "budgetExpansionAttempted");
        boolean adopted = requiredBoolean(selected, "budgetExpansionAdopted");
        require(!adopted || attempted, "An adopted expansion must have been attempted.");
        require(!attempted || adopted, "C2 continuation expansion was rejected; review the actual raw packing trace.");
        JsonNode initial = selected.path("defaultSelection");
        checkSnapshot(initial, DEFAULT_PACKING_BUDGET);
        JsonNode expanded = selected.path("expandedSelection");
        require(attempted == expanded.isObject(), "expandedSelection must exist exactly when expansion was attempted.");
        if (adopted) checkSnapshot(expanded, HARD_PACKING_BUDGET);
        checkSnapshot(selected, HARD_PACKING_BUDGET);
        JsonNode c2Seed = adopted ? expanded : initial;
        JsonNode sourcePacking = selected.path("sourcePacking");
        require(sourcePacking.isObject(), "Actual sourcePacking diagnostic is required.");
        checkSnapshot(sourcePacking, HARD_PACKING_BUDGET);
        boolean sourceBlockAdopted = requiredBoolean(sourcePacking, "sourceBlockAdopted");
        String actualPolicy = selected.path("packingPolicy").asText();
        require(actualPolicy.equals(sourcePacking.path("selectedPackingPolicy").asText()),
            "Actual policy differs from sourcePacking selectedPackingPolicy.");
        require((sourceBlockAdopted ? SOURCE_BLOCK_POLICY : ClassificationCoverageProofs.POLICY_MONOTONE_COVERAGE)
            .equals(actualPolicy), "Actual policy must honestly identify the adopted representation.");
        JsonNode seed = sourcePacking.path("seedSelection");
        checkSnapshot(seed, HARD_PACKING_BUDGET);
        require(proofIds(c2Seed, "proofIds").equals(proofIds(seed, "proofIds")),
            "Source-packing seed IDs differ from chosen C2 continuation snapshot.");
        require(requiredInt(c2Seed, "tokenCount") == requiredInt(seed, "tokenCount"),
            "Source-packing seed tokens differ from chosen C2 continuation snapshot.");
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("defaultMemberSets", memberSets(initial));
        if (attempted) checks.put("expandedMemberSets", memberSets(expanded));
        checks.put("actualMemberSets", memberSets(selected));
        Map<String, Object> losses = new LinkedHashMap<>();
        Map<String, Object> sourceLosses = new LinkedHashMap<>();
        for (String key : MEMBER_SETS) {
            Set<Integer> actualSet = memberIds(selected, key);
            require(actualSet.equals(memberIds(sourcePacking, key)), "Actual selection differs from sourcePacking: " + key);
            require(memberIds(seed, key).equals(memberIds(c2Seed, key)), "Source seed sets differ from actual C2 snapshot: " + key);
            List<Integer> sourceLost = difference(memberIds(seed, key), actualSet);
            sourceLosses.put(key, sourceLost);
            require(sourceLost.isEmpty(), "Source block representation lost C2 seed coverage in " + key + ": " + sourceLost);
            if (adopted) {
                List<Integer> lost = difference(memberIds(initial, key), memberIds(expanded, key));
                losses.put(key, lost);
                require(lost.isEmpty(), "Adopted expansion lost default coverage in " + key + ": " + lost);
            }
        }
        List<String> actualIds = proofIds(sourcePacking, "selectedProofIds");
        require(actualIds.equals(proofIds(selected, "selectedProofIds")), "Actual proof IDs differ from source-packing snapshot.");
        require(evidence != null && evidence.isObject(), "The actual generationEvidence packet is required.");
        require(actualIds.equals(proofIds(evidence, "proofIds")), "Generation proof IDs differ from actual selection.");
        int tokens = requiredInt(selected, "tokenCount");
        require(tokens == requiredInt(sourcePacking, "tokenCount"), "Actual token count differs from source-packing snapshot.");
        require(tokens == requiredInt(evidence, "tokenCount"), "Generation and selection token counts differ.");
        require(actualPolicy.equals(evidence.path("packingPolicy").asText()), "Generation and selection packing policies differ.");
        require(requiredBoolean(evidence, "withinBudget"), "Generation packet is over budget.");
        require(evidence.path("text").isTextual(), "Actual generation evidence text is missing.");
        require(tokens == EvidenceContext.tokens(evidence.path("text").textValue()), "Actual evidence token accounting differs.");
        checks.put("adoptedExpansionLostMembers", losses);
        checks.put("sourcePackingLostSeedMembers", sourceLosses);
        checks.put("sourceBlockAdopted", sourceBlockAdopted);
        checks.put("selectedPackingPolicy", actualPolicy);
        checks.put("sourcePackingSeedTokens", requiredInt(seed, "tokenCount"));
        checks.put("actualEvidenceTokens", tokens);
        checks.put("hardBudget", HARD_PACKING_BUDGET);
        checks.put("withinBudget", true);
        return checks;
    }

    private static Map<String, Object> sameLedgerComparisons(String question, EvidenceLedger ledger,
            JsonNode actualCatalog, JsonNode evidence, JsonNode actualD) {
        var catalog = GraphClassificationContract.prepare(question, QueryEvidencePlan.fromQuestion(question), ledger);
        require(JSON.valueToTree(catalog.metadata()).equals(actualCatalog), "Local catalog does not equal the actual trace catalog.");
        require(evidence.path("text").isTextual() && evidence.path("text").textValue().startsWith(catalog.text()),
            "The actual evidence does not retain this same-ledger catalog prefix.");
        var prepared = ClassificationCoverageProofs.prepare(question, catalog, ledger);
        var stageB2 = prepared.select(catalog.text(), HARD_PACKING_BUDGET);
        var c2Default = prepared.selectMonotoneCoverage(catalog.text(), DEFAULT_PACKING_BUDGET);
        var c2Maximum = prepared.extendMonotoneCoverage(catalog.text(), c2Default, HARD_PACKING_BUDGET);
        require(proofIds(actualD.path("defaultSelection"), "proofIds").equals(c2Default.selection().proofIds()),
            "Recorded C2 default proof IDs differ from the same-ledger recomputation.");
        require(requiredInt(actualD.path("defaultSelection"), "tokenCount") == c2Default.selection().tokenCount(),
            "Recorded C2 default token count differs from the same-ledger recomputation.");
        if (actualD.path("expandedSelection").isObject()) {
            require(proofIds(actualD.path("expandedSelection"), "proofIds").equals(c2Maximum.selection().proofIds()),
                "Recorded C2 continuation proof IDs differ from the same-ledger recomputation.");
            require(requiredInt(actualD.path("expandedSelection"), "tokenCount") == c2Maximum.selection().tokenCount(),
                "Recorded C2 continuation token count differs from the same-ledger recomputation.");
        }
        List<String> sent = proofIds(evidence, "proofIds");
        require(prepared.proofs().validate(ledger, sent).isEmpty(), "Actual D proof source/coordinates are invalid.");
        require(JSON.valueToTree(prepared.proofs().usedMetadata(sent)).equals(evidence.path("proofSpans")),
            "Actual D sent proof metadata differs from its authorized source catalog.");
        for (String id : sent) require(evidence.path("text").textValue().contains(prepared.proofs().span(id)
            .orElseThrow().text()), "Actual D evidence text omits declared source proof content: " + id);
        JsonNode sourcePacking = actualD.path("sourcePacking");
        JsonNode availableProofCatalog = JSON.valueToTree(prepared.proofs().spans().stream()
            .map(ClassificationProofCatalog.ProofSpan::metadata).toList());
        require(availableProofCatalog.equals(sourcePacking.path("availableProofCatalog")),
            "Complete sourcePacking proof catalog differs from the same authorized ledger.");
        require(prepared.proofs().validate(ledger, prepared.proofs().spans().stream()
            .map(ClassificationProofCatalog.ProofSpan::id).toList()).isEmpty(),
            "An available proof source/coordinate is invalid.");
        JsonNode stageB2Coverage = JSON.valueToTree(prepared.metadata(stageB2.proofIds()));
        JsonNode c2DefaultCoverage = JSON.valueToTree(prepared.monotoneSelectionMetadata(c2Default.selection().proofIds()));
        JsonNode c2MaximumCoverage = JSON.valueToTree(prepared.monotoneSelectionMetadata(c2Maximum.selection().proofIds()));
        JsonNode actualCoverage = JSON.valueToTree(prepared.monotoneSelectionMetadata(sent));
        for (String key : MEMBER_SETS)
            require(memberIds(actualCoverage, key).equals(memberIds(actualD, key)), "Actual D raw sets disagree with its same-ledger proof IDs: " + key);
        List<Integer> sourceGaps = new ArrayList<>(), availableSourceNotSent = new ArrayList<>();
        require(actualCoverage.path("memberProofMapping").isArray(), "Same-ledger member/source mapping is missing.");
        for (JsonNode member : actualCoverage.path("memberProofMapping")) {
            require(member.path("availableProofIds").isArray(), "Actual available source IDs are missing.");
            if (member.path("availableProofIds").size() == 0) sourceGaps.add(requiredInt(member, "candidateId"));
            else {
                require(member.path("sentProofIds").isArray(), "Actual sent source IDs are missing.");
                if (member.path("sentProofIds").size() == 0) availableSourceNotSent.add(requiredInt(member, "candidateId"));
            }
        }
        Map<String, Object> comparisons = new LinkedHashMap<>();
        comparisons.put("scope", "PURE_LOCAL_SELECTORS_ON_CAPTURED_CURRENT_AUTHORIZED_LEDGER; no new DB/tool calls");
        comparisons.put("stageBCandidate2ComparisonScope", "OLD_STAGE_B_CANDIDATE_2_PROOF_PACKING_POLICY_ONLY; "
            + "NOT_BASELINE_B2_GRAPHRAG, its algorithm, or its answers");
        comparisons.put("catalogMatchedActualTrace", true);
        comparisons.put("candidateCount", catalog.candidates().size());
        comparisons.put("ledgerEntityCount", ledger.entities().size());
        comparisons.put("ledgerRelationCount", ledger.relations().size());
        comparisons.put("ledgerChunkCount", ledger.chunks().size());
        comparisons.put("STAGE_B_CANDIDATE_2_3000", Map.of("selection", stageB2.metadata(), "coverage", stageB2Coverage));
        comparisons.put("C2_MONOTONE_2000", Map.of("selection", c2Default.selection().metadata(),
            "diagnostics", c2Default.diagnostics(), "coverage", c2DefaultCoverage));
        comparisons.put("C2_MONOTONE_2000_TO_3000", Map.of("selection", c2Maximum.selection().metadata(),
            "diagnostics", c2Maximum.diagnostics(), "coverage", c2MaximumCoverage));
        comparisons.put("actualD", Map.of("snapshot", actualD, "coverage", actualCoverage));
        comparisons.put("actualDVersusStageBCandidate2_3000", setDifferences(stageB2Coverage, actualD));
        comparisons.put("actualDVersusC2_2000", setDifferences(c2DefaultCoverage, actualD));
        comparisons.put("actualDVersusC2_2000_TO_3000", setDifferences(c2MaximumCoverage, actualD));
        comparisons.put("availableProofCatalogMatchesAuthorizedLedger", true);
        comparisons.put("allAvailableProofCoordinatesValid", true);
        comparisons.put("sourceGapCandidateIds", sourceGaps);
        comparisons.put("sourceGapCount", sourceGaps.size());
        comparisons.put("availableSourceNotSentCandidateIds", availableSourceNotSent);
        comparisons.put("availableSourceNotSentCount", availableSourceNotSent.size());
        comparisons.put("actualDMissingStageBCandidate2RelevantIds", difference(
            memberIds(stageB2Coverage, "sentRelevantCandidateIds"), memberIds(actualD, "sentRelevantCandidateIds")));
        Map<String, Object> c2Losses = new LinkedHashMap<>();
        for (String key : MEMBER_SETS) c2Losses.put(key,
            difference(memberIds(c2MaximumCoverage, key), memberIds(actualD, key)));
        comparisons.put("actualDMissingC2MemberSets", c2Losses);
        comparisons.put("coverageMeaning", "Raw representation and member/source mappings only; not gold labels, "
            + "semantic support, or guaranteed classification quality. A structural pass is not the quality precondition.");
        comparisons.put("manualQualityReview", "REQUIRED_BEFORE_PAID_DEV20; source gaps or Relevant losses "
            + "against the same-ledger Stage B candidate 2 proof selector require stopping dev20 and "
            + "so does any four-dimensional member loss against same-ledger C2 monotone continuation. "
            + "Every member with available Relevant proof must have at least one actually sent corresponding proof. "
            + "Do not synthesize sources or decisions. "
            + "This old packing-policy comparison is NOT_BASELINE_B2_GRAPHRAG or answer evaluation.");
        return comparisons;
    }

    private static Map<String, Object> setDifferences(JsonNode reference, JsonNode actual) {
        Map<String, Object> dimensions = new LinkedHashMap<>();
        for (String key : MEMBER_SETS) {
            Set<Integer> previous = memberIds(reference, key), current = memberIds(actual, key);
            dimensions.put(key, Map.of("referenceMembers", previous.stream().sorted().toList(),
                "actualDMembers", current.stream().sorted().toList(),
                "referenceOnlyCandidateIds", difference(previous, current),
                "actualDOnlyCandidateIds", difference(current, previous)));
        }
        return dimensions;
    }

    /** Quality stops are recorded independently and do not turn missing evidence into invented facts. */
    private static List<String> qualityStops(Object comparison, List<JsonNode> selections) {
        List<String> stops = new ArrayList<>();
        if (comparison == null) {
            stops.add("SAME_LEDGER_COMPARISON_UNAVAILABLE");
            return stops;
        }
        JsonNode compared = JSON.valueToTree(comparison);
        if (!compared.path("sourceGapCount").isIntegralNumber()) stops.add("SOURCE_GAP_COUNT_UNAVAILABLE");
        else if (compared.path("sourceGapCount").intValue() != 0)
            stops.add("NO_AVAILABLE_SOURCE:" + compared.path("sourceGapCandidateIds"));
        JsonNode availableUnsent = compared.path("availableSourceNotSentCandidateIds");
        if (!availableUnsent.isArray()) stops.add("AVAILABLE_SOURCE_DELIVERY_COMPARISON_UNAVAILABLE");
        else if (!availableUnsent.isEmpty()) stops.add("AVAILABLE_SOURCE_NOT_SENT:" + availableUnsent);
        JsonNode oldBLoss = compared.path("actualDMissingStageBCandidate2RelevantIds");
        if (!oldBLoss.isArray()) stops.add("OLD_STAGE_B_RELEVANT_COMPARISON_UNAVAILABLE");
        else if (!oldBLoss.isEmpty()) stops.add("LOST_OLD_STAGE_B_RELEVANT_MEMBERS:" + oldBLoss);
        JsonNode c2Loss = compared.path("actualDMissingC2MemberSets");
        for (String key : MEMBER_SETS) {
            if (!c2Loss.path(key).isArray()) stops.add("C2_MEMBER_COMPARISON_UNAVAILABLE:" + key);
            else if (!c2Loss.path(key).isEmpty()) stops.add("LOST_SAME_LEDGER_C2_MEMBERS:" + key + ":" + c2Loss.path(key));
        }
        if (selections.size() != 1 || !selections.get(0).path("sourcePacking").path("sourceBlockAdopted").asBoolean(false))
            stops.add("SOURCE_BLOCK_REPRESENTATION_NOT_ADOPTED");
        return stops;
    }

    private static Map<String, Object> memberSets(JsonNode snapshot) {
        Map<String, Object> sets = new LinkedHashMap<>();
        for (String key : MEMBER_SETS) sets.put(key, memberIds(snapshot, key).stream().sorted().toList());
        return sets;
    }

    private static void checkSnapshot(JsonNode snapshot, int budget) {
        require(snapshot.isObject(), "Selection snapshot is missing.");
        require(requiredBoolean(snapshot, "withinBudget"), "Selected snapshot is over budget.");
        require(requiredInt(snapshot, "tokenCount") <= budget, "Selected snapshot exceeds hard budget " + budget);
        memberSets(snapshot);
    }

    private static Set<Integer> memberIds(JsonNode snapshot, String key) {
        require(snapshot.path(key).isArray(), "Missing member set: " + key);
        Set<Integer> ids = new LinkedHashSet<>();
        for (JsonNode node : snapshot.path(key)) {
            require(node.isIntegralNumber() && node.canConvertToInt() && node.intValue() > 0,
                "Invalid member ID in " + key);
            require(ids.add(node.intValue()), "Repeated member ID in " + key);
        }
        return ids;
    }

    private static List<String> proofIds(JsonNode snapshot, String key) {
        require(snapshot.path(key).isArray(), "Missing proof IDs: " + key);
        List<String> ids = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode node : snapshot.path(key)) {
            require(node.isTextual() && node.textValue().matches("p[1-9]\\d{0,8}"), "Invalid proof ID in " + key);
            require(unique.add(node.textValue()), "Repeated proof ID in " + key);
            ids.add(node.textValue());
        }
        return ids;
    }

    private static int requiredInt(JsonNode node, String key) {
        require(node.path(key).isIntegralNumber() && node.path(key).canConvertToInt()
            && node.path(key).intValue() >= 0, "Missing nonnegative integer: " + key);
        return node.path(key).intValue();
    }

    private static boolean requiredBoolean(JsonNode node, String key) {
        require(node.path(key).isBoolean(), "Missing boolean: " + key);
        return node.path(key).booleanValue();
    }

    private static List<Integer> difference(Set<Integer> left, Set<Integer> right) {
        return left.stream().filter(id -> !right.contains(id)).sorted().toList();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static List<JsonNode> traceValues(JsonNode trace, String key) {
        List<JsonNode> values = new ArrayList<>();
        if (trace.isArray()) for (JsonNode row : trace) if (row.has(key)) values.add(row.get(key));
        return values;
    }

    private static Map<String, Object> sourceHashes() throws Exception {
        Map<String, Object> hashes = new LinkedHashMap<>();
        for (String relative : List.of("src/main/java/cn/ilink/service/kg/ClassificationCoverageProofs.java",
                "src/main/java/cn/ilink/service/kg/ClassificationProofCatalog.java",
                "src/main/java/cn/ilink/service/kg/GraphClassificationFlow.java",
                "src/main/java/cn/ilink/service/kg/GraphClassificationContract.java",
                "src/main/java/cn/ilink/service/kg/VerificationEvidenceContext.java",
                "src/main/java/cn/ilink/service/kg/AgenticGraphRagService.java",
                "src/main/java/cn/ilink/service/kg/ClassificationSourceAcquisition.java",
                "src/main/java/cn/ilink/service/kg/EvidenceLedger.java",
                "src/main/java/cn/ilink/service/kg/KgSourceText.java",
                "src/main/java/cn/ilink/service/kg/tool/GetDocumentTool.java",
                "src/main/java/cn/ilink/service/kg/tool/ReadRelationSourceTool.java",
                "src/test/java/cn/ilink/service/kg/AuthorizedClassificationSourceAuditTest.java")) {
            Path source = checkedExistingPath(Path.of(relative));
            hashes.put(relative, Map.of("realPath", source.toString(), "sha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)))));
        }
        return hashes;
    }

    private static Path checkedExistingPath(Path path) throws Exception {
        Path normalized = path.toAbsolutePath().normalize();
        checkedAncestors(normalized);
        assertTrue(Files.isRegularFile(normalized), "Audit source does not exist: " + normalized);
        Path real = normalized.toRealPath();
        checkedAncestors(real);
        return real;
    }

    private static Path checkedOutputDirectory(Path path) throws Exception {
        Path normalized = path.toAbsolutePath().normalize();
        checkedAncestors(normalized);
        Path ancestor = normalized;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
            assertNotNull(ancestor, "Cannot resolve a safe audit output ancestor.");
        }
        // Validate every existing ancestor and its real junction/symlink target before creation.
        checkedAncestors(ancestor.toRealPath());
        Files.createDirectories(normalized);
        Path real = normalized.toRealPath();
        checkedAncestors(real);
        return real;
    }

    private static void checkedAncestors(Path path) throws Exception {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            rejectSealedPath(current);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) rejectSealedPath(current.toRealPath());
        }
    }

    private static void rejectSealedPath(Path path) {
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        assertFalse(normalized.contains("/holdout-v9-custody/private"), "Sealed holdout paths must not be accessed.");
    }
}
