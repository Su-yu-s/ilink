package cn.ilink.service.kg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in source-coverage replay on unchanged historical authorized tool observations. Only source
 * selection, exact provenance, inventory retention and budget accounting are checked. No model,
 * database, new permission checks, classification decisions, scorer or original artifact writes.
 */
@EnabledIfSystemProperty(named = "kgCoverageReplay", matches = "true")
class SavedClassificationCoverageReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROOF_HEADING = "【授权原文证明；p#仅是出处，不自动证明分类】";
    private static final String COVERAGE_HEADING = "【授权连续原文；编号只标出处，不证明类别】";
    private static final Set<String> FACT_TOOLS = Set.of("search_entities", "filter_entities",
        "expand_neighbors", "get_relations", "query_temporal", "search_documents", "get_document");

    @Test
    void unchangedHistoricalM7RetainsInventoryAndExactSourceProvenanceWithinFrozenBudget() throws Exception {
        String configured = System.getProperty("kgCoverageReplayFile", "");
        assertFalse(configured.isBlank(), "Set -DkgCoverageReplayFile to the saved round1/batch8.json.");
        Path source = checkedExistingPath(Path.of(configured));
        assertEquals("batch8.json", source.getFileName().toString(), "Use the original saved batch8.json.");
        Path manifest = checkedExistingPath(source.getParent().resolve("manifest.json"));
        byte[] sourceBefore = Files.readAllBytes(source);
        byte[] manifestBefore = Files.readAllBytes(manifest);
        JsonNode batch = JSON.readTree(sourceBefore);
        assertTrue(batch.isArray(), "The original saved evaluation batch must be an array.");
        List<JsonNode> matches = new ArrayList<>();
        batch.forEach(row -> { if ("M7".equals(row.path("id").asText())) matches.add(row); });
        assertEquals(1, matches.size(), "Exactly one saved M7 row is required.");
        JsonNode row = matches.get(0);
        assertEquals(GraphClassificationContract.DECISION_PROTOCOL, requiredText(row, "b3_classificationContract"),
            "Use the Stage B closed-decision batch, not an earlier free-text protocol batch.");
        String question = requiredText(row, "question");
        JsonNode historicalEvidence = row.path("b3_generationEvidence");
        assertTrue(historicalEvidence.isObject(), "The actual saved generation evidence is required.");
        String historicalText = requiredText(historicalEvidence, "text");
        String sourceHeading = historicalText.contains(PROOF_HEADING) ? PROOF_HEADING : COVERAGE_HEADING;
        int heading = historicalText.indexOf(sourceHeading);
        assertTrue(heading > 0, "Saved candidate inventory and proof heading are required.");
        assertEquals(heading, historicalText.lastIndexOf(sourceHeading), "The proof boundary must be unambiguous.");
        // Keep the actual sent inventory verbatim; no golden answer text is used to rebuild candidates.
        String inventory = historicalText.substring(0, heading);
        GraphClassificationContract.Catalog catalog = savedCatalog(row.path("b3_classificationCatalog"),
            question, inventory);
        SavedFacts facts = SavedFacts.read(row.path("b3_trace"));
        EvidenceLedger ledger = facts.ledger();
        validateCandidateBindings(catalog, facts);

        List<ClassificationProofCatalog.ProofSpan> historicalSpans = savedProofSpans(
            historicalEvidence.path("proofSpans"));
        ClassificationProofCatalog.ProofCatalog historicalProofs = ClassificationProofCatalog
            .fromAuthorizedSpans(historicalSpans, Map.of());
        List<String> historicalIds = requiredProofIds(historicalEvidence.path("proofIds"));
        assertEquals(new LinkedHashSet<>(historicalIds), historicalSpans.stream()
            .map(ClassificationProofCatalog.ProofSpan::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
            "The saved proof IDs and saved source coordinates must correspond exactly.");
        assertTrue(historicalProofs.validate(ledger, historicalIds).isEmpty(),
            "Historical sent spans must recover verbatim from the saved tool sources.");

        ClassificationCoverageProofs.Prepared prepared = ClassificationCoverageProofs.prepare(question, catalog, ledger);
        String selectionMode = System.getProperty("kgCoverageReplayPolicy", "legacy");
        assertTrue(Set.of("legacy", "member", "monotone").contains(selectionMode),
            "Use kgCoverageReplayPolicy=legacy, member or monotone.");
        boolean monotoneMode = "monotone".equals(selectionMode);
        boolean memberMode = "member".equals(selectionMode) || monotoneMode;
        var defaultMemberSelection = memberMode
            ? monotoneMode ? prepared.selectMonotoneCoverage(catalog.text(), EvidenceContext.DEFAULT_BUDGET)
                : prepared.selectMemberCoverage(catalog.text(), EvidenceContext.DEFAULT_BUDGET) : null;
        ClassificationProofCatalog.Selection defaultSelection = memberMode ? defaultMemberSelection.selection()
            : prepared.select(catalog.text(), EvidenceContext.DEFAULT_BUDGET);
        boolean expanded = prepared.needsExpansion(defaultSelection);
        ClassificationProofCatalog.Selection selection = defaultSelection;
        var memberSelection = defaultMemberSelection;
        boolean expansionAdopted = false;
        if (expanded) {
            var largerMember = memberMode ? monotoneMode
                ? prepared.extendMonotoneCoverage(catalog.text(), defaultMemberSelection, EvidenceContext.MAX_BUDGET)
                : prepared.selectMemberCoverage(catalog.text(), EvidenceContext.MAX_BUDGET) : null;
            var larger = memberMode ? largerMember.selection() : prepared.select(catalog.text(), EvidenceContext.MAX_BUDGET);
            if (!memberMode || (larger.withinBudget() && prepared.preservesMemberCoverage(defaultSelection, larger))) {
                selection = larger; memberSelection = largerMember; expansionAdopted = true;
            }
        }
        int selectedBudget = expansionAdopted ? EvidenceContext.MAX_BUDGET : EvidenceContext.DEFAULT_BUDGET;
        assertTrue(selection.withinBudget(), "Selected evidence must remain within the original hard budget.");
        assertTrue(selection.tokenCount() <= selectedBudget);
        assertEquals(EvidenceContext.tokens(selection.text()), selection.tokenCount(),
            "The candidate prefix, source labels and selection notice must all be counted.");
        assertTrue(selection.text().startsWith(catalog.text()), "No candidate may be discarded to make evidence fit.");
        assertEquals(inventory, catalog.text(), "The actual historical inventory must remain unchanged.");
        assertEquals(new LinkedHashSet<>(selection.proofIds()).size(), selection.proofIds().size(),
            "A selected proof ID must not be repeated.");
        List<String> coordinateFailures = prepared.proofs().validate(ledger, selection.proofIds());
        assertTrue(coordinateFailures.isEmpty(), "Every new proof must recover from the saved ledger: " + coordinateFailures);
        List<ClassificationProofCatalog.ProofSpan> selectedSpans = selection.proofIds().stream()
            .map(id -> prepared.proofs().span(id).orElseThrow()).toList();
        Set<List<Object>> coordinates = new LinkedHashSet<>();
        for (ClassificationProofCatalog.ProofSpan span : selectedSpans) {
            assertTrue(coordinates.add(List.of(span.chunkId(), span.start(), span.end())),
                "Exact selected source coordinates must not repeat.");
        }
        assertEquals(0, coordinateStatistics(selectedSpans).get("repeatedUtf16Units"),
            "The new selector must not spend its context budget on overlapping same-chunk spans.");
        // A deterministic selector must reproduce its own selection on the same immutable ledger.
        var repeatedDefault = monotoneMode ? prepared.selectMonotoneCoverage(catalog.text(), EvidenceContext.DEFAULT_BUDGET) : null;
        assertEquals(selection, monotoneMode ? (expansionAdopted
            ? prepared.extendMonotoneCoverage(catalog.text(), repeatedDefault, selectedBudget).selection()
            : repeatedDefault.selection()) : memberMode ? prepared.selectMemberCoverage(catalog.text(), selectedBudget).selection()
                : prepared.select(catalog.text(), selectedBudget));
        if (memberMode && expansionAdopted)
            assertTrue(prepared.preservesMemberCoverage(defaultSelection, selection), "Expansion cannot lose initial member coverage.");

        String sourceBeforeHash = sha256(sourceBefore);
        String manifestBeforeHash = sha256(manifestBefore);
        assertEquals(sourceBeforeHash, sha256(Files.readAllBytes(source)), "The original batch was modified.");
        assertEquals(manifestBeforeHash, sha256(Files.readAllBytes(manifest)), "The original manifest was modified.");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sampleKind", "HISTORICAL_AUTHORIZED_SOURCE_SELECTION_REPLAY");
        report.put("selectionMode", selectionMode);
        report.put("sampleId", "M7");
        report.put("sourcePath", source.toString());
        report.put("originalBatchSha256Before", sourceBeforeHash);
        report.put("originalBatchSha256After", sha256(Files.readAllBytes(source)));
        report.put("originalManifestSha256Before", manifestBeforeHash);
        report.put("originalManifestSha256After", sha256(Files.readAllBytes(manifest)));
        report.put("originalSourceHash", requiredText(JSON.readTree(manifestBefore), "sourceHash"));
        report.put("selectorSourceSha256", sha256(Files.readAllBytes(checkedExistingPath(Path.of(
            "src/main/java/cn/ilink/service/kg/ClassificationCoverageProofs.java")))));
        report.put("proofCatalogSourceSha256", sha256(Files.readAllBytes(checkedExistingPath(Path.of(
            "src/main/java/cn/ilink/service/kg/ClassificationProofCatalog.java")))));
        report.put("catalogPointer", "/M7/b3_classificationCatalog/candidates");
        report.put("sourceObservationPointers", facts.observationPointers);
        report.put("entityCount", facts.entities.size());
        report.put("relationCount", facts.relations.size());
        report.put("chunkCount", facts.chunks.size());
        report.put("savedChunkSources", facts.chunks.values().stream().map(chunk -> Map.of(
            "chunkId", chunk.id(), "docType", Objects.toString(chunk.docType(), ""),
            "docId", Objects.toString(chunk.docId(), ""), "title", Objects.toString(chunk.title(), ""),
            "textUtf16Units", Objects.toString(chunk.text(), "").length())).toList());
        report.put("relationsWithoutSavedFactText", facts.relations.values().stream()
            .filter(relation -> Objects.toString(relation.factText(), "").isBlank()).count());
        report.put("relationsWithoutSavedSourceChunkId", facts.relations.values().stream()
            .filter(relation -> relation.sourceChunkId() == null).count());
        report.put("historicalLedgerCompleteness", "RAW_OBSERVATIONS_ONLY; database-enriched factText/sourceChunkId "
            + "not present in saved observations remain absent and are not synthesized from historical proof or model text");
        report.put("unknownCoverage", "Actual historical database-enriched relation binding coverage and "
            + "semantic per-candidate attribute support are unknown; source-name/context mappings do not fill this gap");
        report.put("candidateCount", catalog.candidates().size());
        report.put("queryComplete", catalog.queryComplete());
        report.put("inventoryComplete", catalog.inventoryComplete());
        report.put("inventorySha256", sha256(inventory.getBytes(StandardCharsets.UTF_8)));
        report.put("candidateIdentities", catalog.metadata().get("candidates"));
        report.put("historicalSelection", savedSelectionReport(historicalEvidence, historicalSpans, catalog));
        report.put("defaultSelection", defaultSelection.metadata());
        report.put("expandedToMaximumBudget", expanded);
        report.put("budgetExpansionAdopted", expansionAdopted);
        report.put("selectedEvidenceBudget", selectedBudget);
        if (memberMode) report.put("memberSelectionDiagnostics", memberSelection.diagnostics());
        if (monotoneMode) {
            List<Map<String, Object>> profiles = new ArrayList<>();
            var chained = defaultMemberSelection;
            for (int auditBudget : List.of(2000, 2500, 3000)) {
                if (auditBudget > 2000) {
                    var preceding = chained;
                    chained = prepared.extendMonotoneCoverage(catalog.text(), preceding, auditBudget);
                    assertTrue(prepared.preservesMemberCoverage(preceding.selection(), chained.selection()),
                        "Every continuation budget must preserve actual preceding member sets.");
                }
                var independentC1 = prepared.selectMemberCoverage(catalog.text(), auditBudget);
                profiles.add(Map.of("budget", auditBudget, "c2Continuation", chained.diagnostics(),
                    "c2ProofIds", chained.selection().proofIds(), "c1Independent", independentC1.diagnostics(),
                    "c1ProofIds", independentC1.selection().proofIds(),
                    "c1PreservesIts2000Selection", prepared.preservesMemberCoverage(
                        prepared.selectMemberCoverage(catalog.text(), 2000).selection(), independentC1.selection())));
            }
            report.put("budgetProfiles", List.copyOf(profiles));
            report.put("budgetProfileScope", "SAME_RECONSTRUCTED_RAW_LEDGER; not historical production enriched ledger");
        }
        Map<String, Object> selectedReport = new LinkedHashMap<>(selection.metadata());
        selectedReport.put("packingPolicy", monotoneMode ? ClassificationCoverageProofs.POLICY_MONOTONE_COVERAGE
            : memberMode ? ClassificationCoverageProofs.POLICY_MEMBER_COVERAGE
            : ClassificationCoverageProofs.POLICY);
        selectedReport.put("coverage", monotoneMode ? prepared.monotoneSelectionMetadata(selection.proofIds())
            : memberMode ? prepared.memberSelectionMetadata(selection.proofIds())
            : prepared.metadata(selection.proofIds()));
        selectedReport.put("proofSpans", prepared.proofs().usedMetadata(selection.proofIds()));
        selectedReport.put("sourceCoordinates", coordinateStatistics(selectedSpans));
        selectedReport.put("candidateNumbersWithDeclaredRelationBinding", boundCandidateNumbers(selectedSpans, catalog));
        selectedReport.put("coordinateValidationFailures", coordinateFailures);
        selectedReport.put("textSha256", sha256(selection.text().getBytes(StandardCharsets.UTF_8)));
        report.put("newSelection", selectedReport);
        report.put("evidenceTextTokenAccounting", "PINNED_QWEN_LOCAL_TEXT_TOKENS; not supplier request usage or spending");
        report.put("coverageMeaning", "Source coordinates, lexical mention/context and declared relation bindings are "
            + "representation diagnostics; proof count or their coverage does not establish semantic classification support.");
        report.put("semanticValidation", "NOT_RUN; no classification proposal or verdict is constructed");
        report.put("aiInvocations", 0);
        report.put("aiInvocationAccounting", "NO_AI_CLIENT_CONSTRUCTED; source selector and provenance APIs only");
        report.put("permissionValidation", "NOT_RUN; replay uses historical authorized tool outputs only");
        report.put("limitations", "Not current permission validation, new retrieval, model quality, scoring, "
            + "a complete production ledger/classification sample, or a new dev20 run. Selection on missing "
            + "database-enriched relation fields need not reproduce historical production selection. "
            + "No coverage gain is required to pass.");
        Path outputDirectory = checkedOutputDirectory(Path.of("target/kg-coverage-replay"));
        Path output = outputDirectory.resolve("saved-M7-" + System.currentTimeMillis() + ".json");
        assertFalse(output.equals(source) || output.equals(manifest), "Replay output must be separate.");
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),
            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("Historical classification coverage replay report: " + output);
        assertEquals(sourceBeforeHash, sha256(Files.readAllBytes(source)), "Original batch changed after report write.");
        assertEquals(manifestBeforeHash, sha256(Files.readAllBytes(manifest)), "Original manifest changed after report write.");
    }

    private static Map<String, Object> savedSelectionReport(JsonNode node,
            List<ClassificationProofCatalog.ProofSpan> spans, GraphClassificationContract.Catalog catalog) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("packingPolicy", requiredText(node, "packingPolicy"));
        report.put("tokenCount", requiredPositiveInt(node, "tokenCount"));
        report.put("withinBudget", requiredBoolean(node, "withinBudget"));
        report.put("proofIds", requiredProofIds(node.path("proofIds")));
        report.put("sentProofCount", spans.size());
        report.put("proofSpans", spans.stream().map(ClassificationProofCatalog.ProofSpan::metadata).toList());
        report.put("sourceCoordinates", coordinateStatistics(spans));
        report.put("candidateNumbersWithDeclaredRelationBinding", boundCandidateNumbers(spans, catalog));
        report.put("semanticCoverage", "NOT_ESTIMATED; saved proof count is not a semantic coverage rate");
        return report;
    }

    /** Count recoverable UTF-16 source coordinates; overlap is representation repetition, not falsehood. */
    private static Map<String, Object> coordinateStatistics(List<ClassificationProofCatalog.ProofSpan> spans) {
        int represented = spans.stream().mapToInt(span -> span.end() - span.start()).sum();
        int unique = 0;
        Set<Long> chunks = new LinkedHashSet<>();
        for (ClassificationProofCatalog.ProofSpan span : spans) chunks.add(span.chunkId());
        for (Long chunk : chunks) {
            List<ClassificationProofCatalog.ProofSpan> ordered = spans.stream()
                .filter(span -> Objects.equals(chunk, span.chunkId()))
                .sorted(Comparator.comparingInt(ClassificationProofCatalog.ProofSpan::start)
                    .thenComparingInt(ClassificationProofCatalog.ProofSpan::end)).toList();
            int coveredUntil = -1;
            for (ClassificationProofCatalog.ProofSpan span : ordered) {
                unique += Math.max(0, span.end() - Math.max(span.start(), coveredUntil));
                coveredUntil = Math.max(coveredUntil, span.end());
            }
        }
        return Map.of("spanCount", spans.size(), "chunkCount", chunks.size(), "representedUtf16Units", represented,
            "uniqueUtf16Units", unique, "repeatedUtf16Units", represented - unique,
            "coordinateBasis", "CHUNK_LOCAL_UTF16_OFFSETS; no cross-chunk/global-document de-duplication claim");
    }

    private static List<Integer> boundCandidateNumbers(List<ClassificationProofCatalog.ProofSpan> spans,
                                                       GraphClassificationContract.Catalog catalog) {
        Set<Long> relations = new LinkedHashSet<>();
        for (ClassificationProofCatalog.ProofSpan span : spans) relations.addAll(span.relationIds());
        return catalog.candidates().stream().filter(candidate -> candidate.relationIds().stream()
            .anyMatch(relations::contains)).map(GraphClassificationContract.Candidate::number).toList();
    }

    private static List<ClassificationProofCatalog.ProofSpan> savedProofSpans(JsonNode node) {
        assertTrue(node.isArray(), "Saved proof coordinates are required.");
        List<ClassificationProofCatalog.ProofSpan> spans = new ArrayList<>();
        for (JsonNode span : node) {
            String id = requiredText(span, "proofId");
            assertTrue(id.matches("p[1-9]\\d{0,8}"), "Malformed saved proof ID.");
            int start = requiredNonNegativeInt(span, "start");
            int end = requiredPositiveInt(span, "end");
            assertTrue(end > start);
            JsonNode relationNode = span.path("relationIds");
            assertTrue(relationNode.isArray());
            Set<Long> relations = new LinkedHashSet<>();
            for (JsonNode relation : relationNode) {
                assertTrue(relation.isIntegralNumber() && relation.canConvertToLong() && relation.longValue() > 0);
                assertTrue(relations.add(relation.longValue()), "Saved proof relation IDs must not repeat.");
            }
            spans.add(new ClassificationProofCatalog.ProofSpan(id, requiredPositiveLong(span, "chunkId"),
                requiredText(span, "docType"), requiredPositiveLong(span, "docId"), requiredText(span, "title"),
                start, end, requiredText(span, "text"), relations));
        }
        assertFalse(spans.isEmpty(), "This replay requires actual historical sent proof spans.");
        return spans;
    }

    private static GraphClassificationContract.Catalog savedCatalog(JsonNode node, String question, String inventory) {
        assertTrue(node.isObject(), "The actual saved classification catalog is required.");
        assertEquals("graph-classification-catalog-v1", requiredText(node, "policy"));
        assertTrue(requiredBoolean(node, "requested"));
        assertTrue(node.path("candidates").isArray());
        List<GraphClassificationContract.Candidate> candidates = new ArrayList<>();
        Set<List<Object>> identities = new LinkedHashSet<>();
        for (JsonNode candidate : node.path("candidates")) {
            int number = requiredPositiveInt(candidate, "number");
            assertEquals(candidates.size() + 1, number, "Saved candidate numbering must remain contiguous.");
            long anchor = requiredPositiveLong(candidate, "anchorId");
            long other = requiredPositiveLong(candidate, "otherId");
            String direction = requiredText(candidate, "direction");
            assertTrue(Set.of("OUT", "IN").contains(direction));
            String relationType = requiredText(candidate, "relationType");
            assertTrue(identities.add(List.of(anchor, other, relationType, direction)), "Saved candidate identity repeated.");
            Set<Long> relations = new LinkedHashSet<>();
            assertTrue(candidate.path("relationIds").isArray());
            for (JsonNode relation : candidate.path("relationIds")) {
                assertTrue(relation.isIntegralNumber() && relation.canConvertToLong() && relation.longValue() > 0);
                assertTrue(relations.add(relation.longValue()), "Saved candidate relation ID repeated.");
            }
            assertFalse(relations.isEmpty());
            candidates.add(new GraphClassificationContract.Candidate(number, anchor, other,
                requiredText(candidate, "anchorName"), requiredText(candidate, "name"),
                requiredText(candidate, "entityType"), relationType, direction, List.copyOf(relations)));
        }
        assertEquals(requiredPositiveInt(node, "candidateCount"), candidates.size());
        assertEquals(requiredPositiveInt(node, "shownCount"), candidates.size());
        boolean queryComplete = requiredBoolean(node, "queryComplete");
        boolean inventoryComplete = requiredBoolean(node, "inventoryComplete");
        assertTrue(inventoryComplete, "The historical sample must contain the complete inventory.");
        Map<String, Object> metadata = JSON.convertValue(node,
            new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() { });
        return new GraphClassificationContract.Catalog(true, "", candidates, inventory, metadata,
            queryComplete, inventoryComplete, QueryIntent.fromQuestion(question).focusText());
    }

    private static void validateCandidateBindings(GraphClassificationContract.Catalog catalog, SavedFacts facts) {
        for (GraphClassificationContract.Candidate candidate : catalog.candidates()) {
            EvidenceLedger.EntityFact anchor = facts.entities.get(candidate.anchorId());
            EvidenceLedger.EntityFact other = facts.entities.get(candidate.otherId());
            assertNotNull(anchor, "Saved raw observations must contain the typed anchor entity.");
            assertNotNull(other, "Saved raw observations must contain the typed candidate entity.");
            assertEquals(anchor.name(), candidate.anchorName());
            assertEquals(other.name(), candidate.name());
            assertEquals(other.type(), candidate.entityType());
            for (Long id : candidate.relationIds()) {
                EvidenceLedger.RelationFact relation = facts.relations.get(id);
                assertNotNull(relation, "Saved raw observations must contain each candidate relation.");
                assertEquals(candidate.relationType(), relation.type());
                assertEquals("OUT".equals(candidate.direction()) ? candidate.anchorId() : candidate.otherId(), relation.sourceId());
                assertEquals("OUT".equals(candidate.direction()) ? candidate.otherId() : candidate.anchorId(), relation.targetId());
            }
        }
    }

    private static List<String> requiredProofIds(JsonNode node) {
        assertTrue(node.isArray(), "Saved proof IDs must be an array.");
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode id : node) {
            assertTrue(id.isTextual() && id.textValue().matches("p[1-9]\\d{0,8}"));
            assertTrue(ids.add(id.textValue()), "Saved proof ID repeated.");
        }
        return List.copyOf(ids);
    }

    private static String requiredText(JsonNode node, String field) {
        assertTrue(node.path(field).isTextual(), "Missing saved textual field: " + field);
        String text = node.path(field).textValue();
        assertFalse(text.isBlank(), "Blank saved field: " + field);
        return text;
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        assertTrue(node.path(field).isBoolean(), "Missing saved boolean field: " + field);
        return node.path(field).booleanValue();
    }

    private static int requiredPositiveInt(JsonNode node, String field) {
        int value = requiredNonNegativeInt(node, field);
        assertTrue(value > 0, "Missing positive saved number: " + field);
        return value;
    }

    private static int requiredNonNegativeInt(JsonNode node, String field) {
        assertTrue(node.path(field).isIntegralNumber() && node.path(field).canConvertToInt()
            && node.path(field).intValue() >= 0, "Missing non-negative saved number: " + field);
        return node.path(field).intValue();
    }

    private static long requiredPositiveLong(JsonNode node, String field) {
        assertTrue(node.path(field).isIntegralNumber() && node.path(field).canConvertToLong()
            && node.path(field).longValue() > 0, "Missing positive saved ID: " + field);
        return node.path(field).longValue();
    }

    private static Path checkedExistingPath(Path path) throws Exception {
        Path normalized = path.toAbsolutePath().normalize();
        rejectSealedPath(normalized);
        assertTrue(Files.isRegularFile(normalized), "Saved artifact does not exist: " + normalized);
        Path real = normalized.toRealPath();
        rejectSealedPath(real);
        return real;
    }

    private static Path checkedOutputDirectory(Path path) throws Exception {
        Path normalized = path.toAbsolutePath().normalize();
        rejectSealedPath(normalized);
        Path ancestor = normalized;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
            assertNotNull(ancestor, "Cannot resolve a safe replay output ancestor.");
        }
        // Check the junction/symlink target before creating any missing directory beneath it.
        rejectSealedPath(ancestor.toRealPath());
        Files.createDirectories(normalized);
        Path real = normalized.toRealPath();
        rejectSealedPath(real);
        return real;
    }

    private static void rejectSealedPath(Path path) {
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        assertFalse(normalized.contains("/holdout-v9-custody/private"), "Sealed holdout files must not be accessed.");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class SavedFacts {
        private final Map<Long, EvidenceLedger.EntityFact> entities = new LinkedHashMap<>();
        private final Map<Long, EvidenceLedger.RelationFact> relations = new LinkedHashMap<>();
        private final Map<Long, EvidenceLedger.ChunkFact> chunks = new LinkedHashMap<>();
        private final Set<String> successfulTools = new LinkedHashSet<>();
        private final List<String> observationPointers = new ArrayList<>();

        private static SavedFacts read(JsonNode trace) throws Exception {
            assertTrue(trace.isArray(), "Raw saved tool observations are required.");
            SavedFacts facts = new SavedFacts();
            String pendingTool = null;
            for (int i = 0; i < trace.size(); i++) {
                JsonNode step = trace.get(i);
                if (step.hasNonNull("action")) pendingTool = step.path("action").asText();
                if (pendingTool == null || !step.hasNonNull("observation")) continue;
                String tool = pendingTool;
                pendingTool = null;
                if (!FACT_TOOLS.contains(tool) || !step.path("observation").isTextual()) continue;
                String raw = step.path("observation").textValue();
                if (!raw.stripLeading().startsWith("{")) continue;
                JsonNode observed = JSON.readTree(raw);
                if (!observed.isObject() || observed.has("error")) continue;
                facts.successfulTools.add(tool);
                facts.observationPointers.add("/b3_trace/" + i + "/observation [" + tool + "]");
                for (JsonNode entity : observed.path("entities")) facts.addEntity(entity);
                for (JsonNode neighbor : observed.path("neighbors")) {
                    facts.addEntity(neighbor); facts.addRelation(neighbor);
                }
                for (JsonNode relation : observed.path("relations")) facts.addRelation(relation);
                for (JsonNode chunk : observed.path("chunks")) facts.addChunk(chunk);
            }
            for (EvidenceLedger.RelationFact relation : facts.relations.values()) {
                assertTrue(facts.entities.containsKey(relation.sourceId()), "Missing raw typed relation source.");
                assertTrue(facts.entities.containsKey(relation.targetId()), "Missing raw typed relation target.");
            }
            assertFalse(facts.entities.isEmpty());
            assertFalse(facts.relations.isEmpty());
            assertFalse(facts.chunks.isEmpty());
            return facts;
        }

        private void addEntity(JsonNode row) {
            Long id = number(row, "entityId");
            if (id == null || !row.hasNonNull("name") || !row.hasNonNull("entityType")) return;
            EvidenceLedger.EntityFact fact = new EvidenceLedger.EntityFact(id, row.path("name").asText(),
                row.path("entityType").asText(), row.path("description").asText(null));
            EvidenceLedger.EntityFact previous = entities.putIfAbsent(id, fact);
            if (previous != null) {
                assertEquals(previous.name(), fact.name(), "Conflicting raw entity name for #" + id);
                assertEquals(previous.type(), fact.type(), "Conflicting raw entity type for #" + id);
            }
        }

        private void addRelation(JsonNode row) {
            Long id = number(row, "relationId");
            Long source = number(row, "sourceId");
            Long target = number(row, "targetId");
            if (id == null || source == null || target == null) return;
            String type = row.path("relationType").asText(row.path("relation").asText());
            assertFalse(type.isBlank(), "Missing raw relation type for r#" + id);
            EvidenceLedger.RelationFact fact = new EvidenceLedger.RelationFact(id, source, target, type,
                date(row, "validFromMillis", "validFrom"), date(row, "validToMillis", "validTo"),
                row.path("sourceDocId").asText(null), number(row, "sourceChunkId"), row.path("factText").asText(null));
            EvidenceLedger.RelationFact previous = relations.putIfAbsent(id, fact);
            if (previous != null) assertEquals(previous, fact, "Conflicting raw relation r#" + id);
        }

        private void addChunk(JsonNode row) {
            Long id = number(row, "chunkId");
            if (id == null || !row.path("text").isTextual()) return; // Model prose cannot fill missing original text.
            EvidenceLedger.ChunkFact fact = new EvidenceLedger.ChunkFact(id, row.path("docType").asText(),
                number(row, "docId"), row.path("title").asText(), row.path("text").textValue(),
                integer(row, "startOffset"), integer(row, "endOffset"));
            EvidenceLedger.ChunkFact previous = chunks.putIfAbsent(id, fact);
            if (previous != null) assertEquals(previous, fact, "Conflicting raw source c#" + id);
        }

        private EvidenceLedger ledger() {
            EvidenceLedger ledger = mock(EvidenceLedger.class);
            when(ledger.entities()).thenReturn(List.copyOf(entities.values()));
            when(ledger.relations()).thenReturn(List.copyOf(relations.values()));
            when(ledger.chunks()).thenReturn(List.copyOf(chunks.values()));
            when(ledger.entity(any())).thenAnswer(call -> Optional.ofNullable(entities.get(call.getArgument(0))));
            when(ledger.relation(any())).thenAnswer(call -> Optional.ofNullable(relations.get(call.getArgument(0))));
            when(ledger.chunk(any())).thenAnswer(call -> Optional.ofNullable(chunks.get(call.getArgument(0))));
            when(ledger.containsEntity(anyLong())).thenAnswer(call -> entities.containsKey(call.getArgument(0)));
            when(ledger.containsChunk(anyLong())).thenAnswer(call -> chunks.containsKey(call.getArgument(0)));
            when(ledger.hasToolResult(anyString())).thenAnswer(call -> successfulTools.contains(call.getArgument(0)));
            return ledger;
        }

        private static Long number(JsonNode row, String field) {
            return row.hasNonNull(field) && row.path(field).canConvertToLong() ? row.path(field).longValue() : null;
        }

        private static Integer integer(JsonNode row, String field) {
            return row.hasNonNull(field) && row.path(field).canConvertToInt() ? row.path(field).intValue() : null;
        }

        private static Date date(JsonNode row, String millis, String iso) {
            Long value = number(row, millis);
            if (value != null) return new Date(value);
            if (!row.hasNonNull(iso)) return null;
            String text = row.path(iso).asText();
            return text.length() == 10 ? Date.from(java.time.LocalDate.parse(text)
                .atStartOfDay(KgTimeRange.ZONE).toInstant()) : Date.from(Instant.parse(text));
        }
    }
}
