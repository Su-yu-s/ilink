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
 * -DkgPackingAudit=true -DkgPackingAuditQuestion="the user's question" -DkgPackingAuditUser=8,
 * plus the independent clone URL and kgStageDatabase required by StageUrlGuard.
 * Actual graph tools and the actual ledger factory remain in use. Retrieval can query the clone
 * and invoke supplier embeddings or other external services; their usage is not accounted here.
 * Only this request's chat client and trace mapper are mocked: chat stops before provider dispatch,
 * and trace inserts reach a mock. This is a packing diagnostic, not a model answer or dev20 result.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"dev", "local"})
@ContextConfiguration(initializers = KgStageRebuildTest.StageUrlGuard.class)
@EnabledIfSystemProperty(named = "kgPackingAudit", matches = "true")
class AuthorizedClassificationPackingAuditTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STOP_REASON = "PACKING_AUDIT_BEFORE_CHAT";
    private static final int DEFAULT_PACKING_BUDGET = 2000;
    private static final int HARD_PACKING_BUDGET = 3000;
    private static final List<String> MEMBER_SETS = List.of("sentMentionCandidateIds",
        "sentRelevantCandidateIds", "sentContextCandidateIds", "sentBoundCandidateIds");

    @Autowired private GraphRagService fastRag;
    @Autowired private List<GraphTool> graphTools;
    @Autowired private EvidenceLedgerFactory realLedgerFactory;

    @Test
    void auditCurrentAuthorizedPackingBeforeAnyProviderChat() throws Exception {
        String question = System.getProperty("kgPackingAuditQuestion", "");
        String configuredUser = System.getProperty("kgPackingAuditUser", "");
        assertFalse(question.isBlank(), "Set -DkgPackingAuditQuestion to the user's classification question.");
        assertTrue(configuredUser.matches("[1-9]\\d{0,17}"), "Set a positive -DkgPackingAuditUser, for example 8.");
        long userId = Long.parseLong(configuredUser);
        Path outputDirectory = checkedOutputDirectory(Path.of("target/kg-packing-live-audit"));
        UUID runId = UUID.randomUUID();
        Path output = outputDirectory.resolve("authorized-packing-" + runId + ".json");
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
        List<String> failures = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sampleKind", "CURRENT_AUTHORIZED_PACKING_AUDIT_BEFORE_CHAT");
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
        report.put("manualQualityGate", "HUMAN_REVIEW_REQUIRED; no coverage gain or quality verdict is required "
            + "by this structural test. Stop paid dev20 and return to packing audit when sourceGapCount is "
            + "nonzero or actual C2 loses same-ledger Stage B candidate 2 proof-selector Relevant members. "
            + "This compares only the old packing policy, NOT_BASELINE_B2_GRAPHRAG or its answers, "
            + "and does not authorize dev20.");

        boolean c2Stage = rawTrace.isArray() && answer.trace.stream()
            .anyMatch(row -> "C-stage-2".equals(row.get("strategyCandidate")));
        if (!c2Stage) failures.add("The actual trace did not enter C-stage-2.");
        if (selections.size() != 1) failures.add("Expected exactly one actual classificationSelection; no recovery is synthesized.");
        if (catalogs.size() != 1) failures.add("Expected exactly one actual classificationCatalog.");
        if (evidencePackets.size() != 1) failures.add("Expected exactly one actual generationEvidence packet.");
        if (ledgerCreations.get() != 1 || capturedLedger.get() == null)
            failures.add("Expected exactly one captured real request ledger.");
        if (chatAttempts.get() != 1) failures.add("Expected one stopped mock chat attempt after packing.");
        if (EvidenceContext.DEFAULT_BUDGET != DEFAULT_PACKING_BUDGET
                || EvidenceContext.MAX_BUDGET != HARD_PACKING_BUDGET)
            failures.add("The frozen default/maximum evidence budgets must remain 2000/3000.");

        if (selections.size() == 1) {
            report.put("classificationSelection", selections.get(0));
            try {
                report.put("packingChecks", packingChecks(selections.get(0),
                    evidencePackets.size() == 1 ? evidencePackets.get(0) : null));
            } catch (RuntimeException invalid) {
                failures.add("Actual packing snapshot: " + invalid.getClass().getSimpleName() + ": " + invalid.getMessage());
            }
        }
        // Comparisons never replace a missing real selection or fetch/complete any source facts.
        if (c2Stage && selections.size() == 1 && catalogs.size() == 1 && evidencePackets.size() == 1
                && ledgerCreations.get() == 1 && capturedLedger.get() != null) {
            try {
                report.put("sameLedgerComparisons", sameLedgerComparisons(question, capturedLedger.get(),
                    catalogs.get(0), evidencePackets.get(0), selections.get(0)));
            } catch (RuntimeException invalid) {
                failures.add("Same-ledger comparison unavailable: " + invalid.getClass().getSimpleName() + ": " + invalid.getMessage());
            }
        }
        report.put("packingGateFailures", failures);
        report.put("structuralPackingGate", failures.isEmpty() ? "PASS" : "FAIL");
        report.put("limitations", "One current authorized request on an explicit independent clone; "
            + "not an isolated unit test, dev20, holdout, scoring, model quality, a complete supplier usage "
            + "ledger, or semantic verification. Local comparisons do not change actual C2 packing. "
            + "No new source retrieval, classification decisions, or automatic missing-proof disposition "
            + "is performed after query returns.");
        checkedAncestors(output);
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),
            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("Current authorized packing audit report: " + output);
        // Preserve the raw report before failing a missing-selection/budget/preservation gate.
        assertEquals(List.of(), failures, "Packing audit failed; review the saved raw report: " + output);
    }

    private static Map<String, Object> packingChecks(JsonNode selected, JsonNode evidence) {
        require(selected.isObject(), "classificationSelection must be an object.");
        require(ClassificationCoverageProofs.POLICY_MONOTONE_COVERAGE.equals(selected.path("packingPolicy").asText()),
            "Actual classificationSelection must use the C2 monotone packing policy.");
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
        JsonNode actual = adopted ? expanded : initial;
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("defaultMemberSets", memberSets(initial));
        if (attempted) checks.put("expandedMemberSets", memberSets(expanded));
        checks.put("actualMemberSets", memberSets(selected));
        Map<String, Object> losses = new LinkedHashMap<>();
        for (String key : MEMBER_SETS) {
            Set<Integer> actualSet = memberIds(selected, key);
            require(actualSet.equals(memberIds(actual, key)), "Actual selection differs from adopted/default snapshot: " + key);
            if (adopted) {
                List<Integer> lost = difference(memberIds(initial, key), memberIds(expanded, key));
                losses.put(key, lost);
                require(lost.isEmpty(), "Adopted expansion lost default coverage in " + key + ": " + lost);
            }
        }
        List<String> actualIds = proofIds(actual, "proofIds");
        require(actualIds.equals(proofIds(selected, "selectedProofIds")), "Actual proof IDs differ from the chosen snapshot.");
        require(evidence != null && evidence.isObject(), "The actual generationEvidence packet is required.");
        require(actualIds.equals(proofIds(evidence, "proofIds")), "Generation proof IDs differ from actual selection.");
        int tokens = requiredInt(selected, "tokenCount");
        require(tokens == requiredInt(actual, "tokenCount"), "Actual token count differs from the chosen snapshot.");
        require(tokens == requiredInt(evidence, "tokenCount"), "Generation and selection token counts differ.");
        require(requiredBoolean(evidence, "withinBudget"), "Generation packet is over budget.");
        require(evidence.path("text").isTextual(), "Actual generation evidence text is missing.");
        require(tokens == EvidenceContext.tokens(evidence.path("text").textValue()), "Actual evidence token accounting differs.");
        checks.put("adoptedExpansionLostMembers", losses);
        checks.put("actualEvidenceTokens", tokens);
        checks.put("hardBudget", HARD_PACKING_BUDGET);
        checks.put("withinBudget", true);
        return checks;
    }

    private static Map<String, Object> sameLedgerComparisons(String question, EvidenceLedger ledger,
            JsonNode actualCatalog, JsonNode evidence, JsonNode actualC2) {
        var catalog = GraphClassificationContract.prepare(question, QueryEvidencePlan.fromQuestion(question), ledger);
        require(JSON.valueToTree(catalog.metadata()).equals(actualCatalog), "Local catalog does not equal the actual trace catalog.");
        require(evidence.path("text").isTextual() && evidence.path("text").textValue().startsWith(catalog.text()),
            "The actual evidence does not retain this same-ledger catalog prefix.");
        var prepared = ClassificationCoverageProofs.prepare(question, catalog, ledger);
        var stageB2 = prepared.select(catalog.text(), HARD_PACKING_BUDGET);
        var c1Default = prepared.selectMemberCoverage(catalog.text(), DEFAULT_PACKING_BUDGET);
        var c1Maximum = prepared.selectMemberCoverage(catalog.text(), HARD_PACKING_BUDGET);
        List<String> sent = proofIds(evidence, "proofIds");
        JsonNode stageB2Coverage = JSON.valueToTree(prepared.metadata(stageB2.proofIds()));
        JsonNode c1DefaultCoverage = JSON.valueToTree(prepared.memberSelectionMetadata(c1Default.selection().proofIds()));
        JsonNode c1MaximumCoverage = JSON.valueToTree(prepared.memberSelectionMetadata(c1Maximum.selection().proofIds()));
        JsonNode actualCoverage = JSON.valueToTree(prepared.monotoneSelectionMetadata(sent));
        for (String key : MEMBER_SETS)
            require(memberIds(actualCoverage, key).equals(memberIds(actualC2, key)), "Actual C2 raw sets disagree with its same-ledger proof IDs: " + key);
        List<Integer> sourceGaps = new ArrayList<>();
        require(actualCoverage.path("memberProofMapping").isArray(), "Same-ledger member/source mapping is missing.");
        for (JsonNode member : actualCoverage.path("memberProofMapping")) {
            require(member.path("availableProofIds").isArray(), "Actual available source IDs are missing.");
            if (member.path("availableProofIds").size() == 0) sourceGaps.add(requiredInt(member, "candidateId"));
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
        comparisons.put("C1_2000", Map.of("selection", c1Default.selection().metadata(),
            "diagnostics", c1Default.diagnostics(), "coverage", c1DefaultCoverage));
        comparisons.put("C1_3000", Map.of("selection", c1Maximum.selection().metadata(),
            "diagnostics", c1Maximum.diagnostics(), "coverage", c1MaximumCoverage));
        comparisons.put("actualC2", Map.of("snapshot", actualC2, "coverage", actualCoverage));
        comparisons.put("actualC2VersusStageBCandidate2_3000", setDifferences(stageB2Coverage, actualC2));
        comparisons.put("actualC2VersusC1_2000", setDifferences(c1DefaultCoverage, actualC2));
        comparisons.put("actualC2VersusC1_3000", setDifferences(c1MaximumCoverage, actualC2));
        comparisons.put("sourceGapCandidateIds", sourceGaps);
        comparisons.put("sourceGapCount", sourceGaps.size());
        comparisons.put("actualC2MissingStageBCandidate2RelevantIds", difference(
            memberIds(stageB2Coverage, "sentRelevantCandidateIds"), memberIds(actualC2, "sentRelevantCandidateIds")));
        comparisons.put("coverageMeaning", "Raw representation and member/source mappings only; not gold labels, "
            + "semantic support, or guaranteed classification quality. No gain is required to pass this audit.");
        comparisons.put("manualQualityReview", "REQUIRED_BEFORE_PAID_DEV20; source gaps or Relevant losses "
            + "against the same-ledger Stage B candidate 2 proof selector require stopping dev20 and "
            + "returning to packing audit without synthesizing sources or decisions. "
            + "This old packing-policy comparison is NOT_BASELINE_B2_GRAPHRAG or answer evaluation.");
        return comparisons;
    }

    private static Map<String, Object> setDifferences(JsonNode reference, JsonNode actual) {
        Map<String, Object> dimensions = new LinkedHashMap<>();
        for (String key : MEMBER_SETS) {
            Set<Integer> previous = memberIds(reference, key), current = memberIds(actual, key);
            dimensions.put(key, Map.of("referenceMembers", previous.stream().sorted().toList(),
                "actualC2Members", current.stream().sorted().toList(),
                "referenceOnlyCandidateIds", difference(previous, current),
                "actualC2OnlyCandidateIds", difference(current, previous)));
        }
        return dimensions;
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
                "src/test/java/cn/ilink/service/kg/AuthorizedClassificationPackingAuditTest.java")) {
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
