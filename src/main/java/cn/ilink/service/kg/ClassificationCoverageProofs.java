package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Candidate-aware retrieval over authorized exact source coordinates; never a classifier. */
final class ClassificationCoverageProofs {
    static final String POLICY = "classification-candidate-context-v2";
    static final String POLICY_MEMBER_COVERAGE = "classification-member-coverage-v1";
    static final String POLICY_MONOTONE_COVERAGE = "classification-member-monotone-v2";
    static final String POLICY_SOURCE_PACKING = "classification-source-blocks-v1";
    static final String POLICY_MODEL_CONTEXT = "classification-model-context-v1";
    private static final String MEMBER_OBJECTIVE = "MAX_RELEVANT_MEMBERS_THEN_CONTEXT_THEN_BOUND";
    private static final Pattern CONTEXT = Pattern.compile(
        "语言|运行时|依赖|生态|前端|后端|客户端|服务端|架构|技术栈|框架|数据库|缓存|存储|队列|协议|部署|独立|版本|模块|文件|环境变量");
    private record Range(int start, int end) { }
    private record Key(Long chunkId, int start, int end) { }
    private record Coverage(Set<Integer> mentions, Set<Integer> bound, Set<Integer> context, Set<Integer> relevant) { }
    record MemberSelection(ClassificationProofCatalog.Selection selection, Map<String, Object> diagnostics) {
        MemberSelection { diagnostics = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics)); }
    }
    private record MemberTrial(ClassificationProofCatalog.ProofSpan proof,
            List<ClassificationProofCatalog.ProofSpan> selected, Coverage coverage,
            int tokenCount, int incrementalTokens, int relevantGain, int contextGain, int boundGain,
            List<String> removedProofIds) { }

    static final class Prepared {
        private final GraphClassificationContract.Catalog catalog;
        private final ClassificationProofCatalog.ProofCatalog proofs;
        private final Map<String, Coverage> coverage;
        private final int removedSymbolSpanCount;

        private Prepared(GraphClassificationContract.Catalog catalog,
                ClassificationProofCatalog.ProofCatalog proofs, Map<String, Coverage> coverage,
                int removedSymbolSpanCount) {
            this.catalog = catalog; this.proofs = proofs;
            this.coverage = Map.copyOf(coverage); this.removedSymbolSpanCount = removedSymbolSpanCount;
        }

        ClassificationProofCatalog.ProofCatalog proofs() { return proofs; }

        /** Inventory, source spans, member mapping and scope notice all share this hard budget. */
        ClassificationProofCatalog.Selection select(String prefixText, int requestedBudget) {
            int budget = Math.max(0, Math.min(requestedBudget, EvidenceContext.MAX_BUDGET));
            String prefix = Objects.toString(prefixText, "");
            List<ClassificationProofCatalog.ProofSpan> selected = new ArrayList<>();
            Set<Integer> mentioned = new LinkedHashSet<>(), bound = new LinkedHashSet<>(), context = new LinkedHashSet<>();
            Set<String> considered = new LinkedHashSet<>();
            while (true) {
                ClassificationProofCatalog.ProofSpan best = null;
                double bestUtility = -1;
                int currentCost = EvidenceContext.tokens(render(prefix, selected));
                for (var proof : proofs.spans()) {
                    if (considered.contains(proof.id()) || selected.stream().anyMatch(existing -> overlap(existing, proof))) continue;
                    Coverage item = coverage.get(proof.id());
                    int gain = additional(item.mentions(), mentioned) * 30 + additional(item.bound(), bound) * 18
                        + additional(item.context(), context) * 32;
                    if (gain == 0) continue;
                    List<ClassificationProofCatalog.ProofSpan> trial = new ArrayList<>(selected); trial.add(proof);
                    int cost = EvidenceContext.tokens(render(prefix, trial));
                    if (cost > budget) continue;
                    double utility = (double) gain / Math.max(1, cost - currentCost);
                    // Source order breaks ties; a long summary cannot win solely for repeated names.
                    if (utility > bestUtility) { best = proof; bestUtility = utility; }
                }
                if (best == null) break;
                selected.add(best); considered.add(best.id());
                Coverage item = coverage.get(best.id());
                mentioned.addAll(item.mentions()); bound.addAll(item.bound()); context.addAll(item.context());
            }
            selected.sort(sourceOrder());
            String text = render(prefix, selected);
            int tokens = EvidenceContext.tokens(text);
            return new ClassificationProofCatalog.Selection(text, tokens,
                selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList(),
                proofs.spans().size() - selected.size(), tokens <= budget);
        }

        /** Stage C: cover missing members first, then improve their retrieval context and exact binding. */
        MemberSelection selectMemberCoverage(String prefixText, int requestedBudget) {
            int budget = Math.max(0, Math.min(requestedBudget, EvidenceContext.MAX_BUDGET));
            String prefix = Objects.toString(prefixText, "");
            List<ClassificationProofCatalog.ProofSpan> selected = new ArrayList<>();
            Coverage current = aggregate(selected);
            int fixedTokens = EvidenceContext.tokens(renderMemberCoverage(prefix, selected));
            int currentTokens = fixedTokens;
            int stepLimit = (int) Math.min(Integer.MAX_VALUE, 2L * proofs.spans().size());
            int steps = 0, replacements = 0;
            List<Map<String, Object>> history = new ArrayList<>();
            String termination = fixedTokens > budget ? "FIXED_TEXT_OVER_BUDGET"
                : proofs.spans().isEmpty() ? "NO_AVAILABLE_PROOF" : "STEP_LIMIT_REACHED";
            if (fixedTokens <= budget && !proofs.spans().isEmpty()) while (steps < stepLimit) {
                MemberTrial bestRelevant = null, bestAttributes = null;
                Set<String> selectedIds = selected.stream().map(ClassificationProofCatalog.ProofSpan::id)
                    .collect(java.util.stream.Collectors.toSet());
                for (var proof : proofs.spans()) {
                    if (selectedIds.contains(proof.id())) continue;
                    // One new window may displace only selected ranges that overlap it in the same chunk.
                    List<ClassificationProofCatalog.ProofSpan> trial = new ArrayList<>();
                    List<String> removedIds = new ArrayList<>();
                    for (var existing : selected) {
                        if (overlap(existing, proof)) removedIds.add(existing.id());
                        else trial.add(existing);
                    }
                    trial.add(proof); trial.sort(sourceOrder());
                    Coverage next = aggregate(trial);
                    // Preserve all four member sets, including existing literal mentions.
                    if (!next.mentions().containsAll(current.mentions())
                        || !next.relevant().containsAll(current.relevant())
                        || !next.context().containsAll(current.context())
                        || !next.bound().containsAll(current.bound())) continue;
                    int relevantGain = additional(next.relevant(), current.relevant());
                    int contextGain = additional(next.context(), current.context());
                    int boundGain = additional(next.bound(), current.bound());
                    // Proof count and repeated mentions provide no independent gain.
                    if (relevantGain == 0 && contextGain == 0 && boundGain == 0) continue;
                    int tokens = EvidenceContext.tokens(renderMemberCoverage(prefix, trial));
                    if (tokens > budget) continue;
                    MemberTrial candidate = new MemberTrial(proof, List.copyOf(trial), next, tokens,
                        Math.max(1, tokens - currentTokens), relevantGain, contextGain, boundGain,
                        List.copyOf(removedIds));
                    if (relevantGain > 0) {
                        if (betterMemberTrial(candidate, bestRelevant, true)) bestRelevant = candidate;
                    } else if (betterMemberTrial(candidate, bestAttributes, false)) bestAttributes = candidate;
                }
                MemberTrial best = bestRelevant != null ? bestRelevant : bestAttributes;
                if (best == null) {
                    Coverage available = aggregate(proofs.spans());
                    termination = current.relevant().equals(available.relevant())
                        && current.context().equals(available.context()) && current.bound().equals(available.bound())
                        ? "OBJECTIVE_COVERAGE_COMPLETE" : "NO_FEASIBLE_COVERAGE_GAIN";
                    break;
                }
                int incrementalTokens = best.tokenCount() - currentTokens;
                selected = new ArrayList<>(best.selected()); current = best.coverage();
                currentTokens = best.tokenCount(); steps++;
                if (!best.removedProofIds().isEmpty()) replacements++;
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("step", steps); step.put("phase", best.relevantGain() > 0 ? "RELEVANT" : "CONTEXT_BOUND");
                step.put("proofId", best.proof().id()); step.put("removedProofIds", best.removedProofIds());
                step.put("relevantGain", best.relevantGain()); step.put("contextGain", best.contextGain());
                step.put("boundGain", best.boundGain()); step.put("tokenCount", best.tokenCount());
                step.put("incrementalTokens", incrementalTokens);
                step.put("utilityTokenDenominator", best.incrementalTokens());
                step.put("relevantCandidateIds", sorted(current.relevant()));
                step.put("mentionCandidateIds", sorted(current.mentions()));
                step.put("contextCandidateIds", sorted(current.context())); step.put("boundCandidateIds", sorted(current.bound()));
                history.add(java.util.Collections.unmodifiableMap(step));
            }
            String text = renderMemberCoverage(prefix, selected);
            int tokens = EvidenceContext.tokens(text);
            var selection = new ClassificationProofCatalog.Selection(text, tokens,
                selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList(),
                proofs.spans().size() - selected.size(), tokens <= budget);
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("packingPolicy", POLICY_MEMBER_COVERAGE); diagnostics.put("selectionObjective", MEMBER_OBJECTIVE);
            diagnostics.put("requestedBudget", requestedBudget); diagnostics.put("budget", budget);
            diagnostics.put("fixedTextTokens", fixedTokens); diagnostics.put("tokenCount", tokens);
            diagnostics.put("withinBudget", selection.withinBudget()); diagnostics.put("availableProofCount", proofs.spans().size());
            diagnostics.put("stepLimit", stepLimit); diagnostics.put("selectionSteps", steps);
            diagnostics.put("replacementSteps", replacements); diagnostics.put("terminationReason", termination);
            diagnostics.put("terminationScope", "RELEVANT_CONTEXT_BOUND_OBJECTIVE");
            diagnostics.put("sentRelevantCandidateIds", sorted(current.relevant()));
            diagnostics.put("sentMentionCandidateIds", sorted(current.mentions()));
            diagnostics.put("sentContextCandidateIds", sorted(current.context())); diagnostics.put("sentBoundCandidateIds", sorted(current.bound()));
            diagnostics.put("selectionHistory", List.copyOf(history)); diagnostics.put("retrievalOnly", true);
            diagnostics.put("semanticSupportNotEstablished", true);
            return new MemberSelection(selection, diagnostics);
        }

        /** C2 starts with a compact complete mapping; C1 remains available as its frozen negative result. */
        MemberSelection selectMonotoneCoverage(String prefixText, int requestedBudget) {
            return packMonotoneCoverage(Objects.toString(prefixText, ""), requestedBudget, null);
        }

        /** Stage D: preserve the C2 seed while removing repeated source identity from its presentation. */
        MemberSelection selectSourceBlocks(String prefixText, int requestedBudget) {
            return selectSourceBlocks(prefixText, selectMonotoneCoverage(prefixText, requestedBudget), requestedBudget);
        }

        /** A caller may supply its actual default/extended C2 seed; this method never reruns that selection. */
        MemberSelection selectSourceBlocks(String prefixText, MemberSelection seed, int requestedBudget) {
            String prefix = Objects.toString(prefixText, "");
            int budget = Math.max(0, Math.min(requestedBudget, EvidenceContext.MAX_BUDGET));
            if (seed == null || seed.selection() == null) throw new IllegalArgumentException("SOURCE_BLOCK_SEED_REQUIRED");
            if (!POLICY_MONOTONE_COVERAGE.equals(seed.diagnostics().get("packingPolicy")))
                throw new IllegalArgumentException("SOURCE_BLOCK_SEED_POLICY_MISMATCH");
            if (!sha256(prefix).equals(seed.diagnostics().get("prefixSha256"))
                || !monotoneSourceFingerprint().equals(seed.diagnostics().get("sourceCatalogSha256")))
                throw new IllegalArgumentException("SOURCE_BLOCK_SEED_IDENTITY_MISMATCH");
            List<ClassificationProofCatalog.ProofSpan> selected = new ArrayList<>();
            Set<String> unique = new LinkedHashSet<>();
            for (String id : seed.selection().proofIds()) {
                if (!unique.add(id)) throw new IllegalArgumentException("SOURCE_BLOCK_SEED_DUPLICATE_PROOF");
                var proof = proofs.span(id).orElseThrow(() -> new IllegalArgumentException("SOURCE_BLOCK_SEED_UNKNOWN_PROOF:" + id));
                if (selected.stream().anyMatch(existing -> overlap(existing, proof)))
                    throw new IllegalArgumentException("SOURCE_BLOCK_SEED_OVERLAPPING_PROOF");
                selected.add(proof);
            }
            selected.sort(sourceOrder());
            Coverage seedCoverage = aggregate(selected);
            String original = renderMonotoneCoverage(prefix, selected);
            if (!original.equals(seed.selection().text()) || EvidenceContext.tokens(original) != seed.selection().tokenCount()
                || !selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList().equals(seed.selection().proofIds())
                || seed.selection().omittedSpanCount() != proofs.spans().size() - selected.size())
                throw new IllegalArgumentException("SOURCE_BLOCK_SEED_RENDER_MISMATCH");
            for (String dimension : List.of("Mention", "Relevant", "Context", "Bound"))
                if (!sorted(dimensionMembers(seedCoverage, dimension)).equals(seed.diagnostics().get("sent" + dimension + "CandidateIds")))
                    throw new IllegalArgumentException("SOURCE_BLOCK_SEED_COVERAGE_MISMATCH");
            if (budget < seedInteger(seed, "budget")) throw new IllegalArgumentException("SOURCE_BLOCK_BUDGET_TOO_SMALL");
            int stepLimit = (int) Math.min(Integer.MAX_VALUE, 2L * proofs.spans().size());
            int seedSteps = seedInteger(seed, "selectionSteps");
            if (seedSteps > stepLimit || seedInteger(seed, "stepLimit") != stepLimit)
                throw new IllegalArgumentException("SOURCE_BLOCK_SEED_STEP_LIMIT_INVALID");
            if (seedInteger(seed, "tokenCount") != seed.selection().tokenCount()
                || seedInteger(seed, "currentTokenCount") != seed.selection().tokenCount()
                || seedInteger(seed, "availableProofCount") != proofs.spans().size()
                || seedInteger(seed, "fixedTextTokens") != EvidenceContext.tokens(renderMonotoneCoverage(prefix, List.of()))
                || !Objects.equals(seed.diagnostics().get("withinBudget"), seed.selection().withinBudget())
                || seed.selection().withinBudget() != (seed.selection().tokenCount() <= seedInteger(seed, "budget")))
                throw new IllegalArgumentException("SOURCE_BLOCK_SEED_ACCOUNTING_MISMATCH");
            Object seedHistoryValue = seed.diagnostics().get("selectionHistory");
            if (!(seedHistoryValue instanceof List<?> seedHistory) || seedHistory.size() != seedSteps)
                throw new IllegalArgumentException("SOURCE_BLOCK_SEED_HISTORY_INVALID");
            List<Map<String, Object>> seedHistoryCopy = new ArrayList<>();
            for (Object item : seedHistory) {
                if (!(item instanceof Map<?, ?> raw)) throw new IllegalArgumentException("SOURCE_BLOCK_SEED_HISTORY_INVALID");
                Map<String, Object> copy = new LinkedHashMap<>();
                for (var entry : raw.entrySet()) {
                    if (!(entry.getKey() instanceof String key) || entry.getValue() == null)
                        throw new IllegalArgumentException("SOURCE_BLOCK_SEED_HISTORY_INVALID");
                    copy.put(key, entry.getValue());
                }
                seedHistoryCopy.add(java.util.Collections.unmodifiableMap(copy));
            }
            validateMonotoneHistory(prefix, seedHistoryCopy, selected, seedInteger(seed, "budget"), seedInteger(seed, "replacementSteps"));
            String compactSeed = renderSourceBlocks(prefix, selected);
            int compactSeedTokens = EvidenceContext.tokens(compactSeed);
            int fixedTokens = EvidenceContext.tokens(renderSourceBlocks(prefix, List.of()));
            boolean adopted = seed.selection().withinBudget() && compactSeedTokens <= budget
                && compactSeedTokens <= seed.selection().tokenCount();
            String termination = !seed.selection().withinBudget() ? "SEED_NOT_WITHIN_BUDGET"
                : compactSeedTokens > budget ? "SOURCE_BLOCK_PRESENTATION_OVER_BUDGET"
                : compactSeedTokens > seed.selection().tokenCount() ? "SOURCE_BLOCK_PRESENTATION_COST_INCREASE"
                : "STEP_LIMIT_REACHED";
            Coverage current = seedCoverage;
            int steps = seedSteps, replacements = 0, currentTokens = compactSeedTokens;
            List<Map<String, Object>> history = new ArrayList<>();
            if (adopted) while (steps < stepLimit) {
                Set<String> selectedIds = selected.stream().map(ClassificationProofCatalog.ProofSpan::id)
                    .collect(java.util.stream.Collectors.toSet());
                MemberTrial bestRelevant = null, bestAttributes = null;
                for (var proof : proofs.spans()) {
                    if (selectedIds.contains(proof.id())) continue;
                    List<ClassificationProofCatalog.ProofSpan> trial = new ArrayList<>();
                    List<String> removedIds = new ArrayList<>();
                    for (var existing : selected) {
                        if (overlap(existing, proof)) removedIds.add(existing.id()); else trial.add(existing);
                    }
                    trial.add(proof); trial.sort(sourceOrder());
                    Coverage next = aggregate(trial);
                    if (!next.mentions().containsAll(current.mentions()) || !next.relevant().containsAll(current.relevant())
                        || !next.context().containsAll(current.context()) || !next.bound().containsAll(current.bound())) continue;
                    int relevantGain = additional(next.relevant(), current.relevant());
                    int contextGain = additional(next.context(), current.context()), boundGain = additional(next.bound(), current.bound());
                    if (relevantGain == 0 && contextGain == 0 && boundGain == 0) continue;
                    int tokens = EvidenceContext.tokens(renderSourceBlocks(prefix, trial));
                    if (tokens > budget) continue;
                    MemberTrial candidate = new MemberTrial(proof, List.copyOf(trial), next, tokens,
                        Math.max(1, tokens - currentTokens), relevantGain, contextGain, boundGain, List.copyOf(removedIds));
                    if (relevantGain > 0) {
                        if (betterMemberTrial(candidate, bestRelevant, true)) bestRelevant = candidate;
                    } else if (betterMemberTrial(candidate, bestAttributes, false)) bestAttributes = candidate;
                }
                MemberTrial best = bestRelevant != null ? bestRelevant : bestAttributes;
                if (best == null) {
                    Coverage available = aggregate(proofs.spans());
                    termination = current.relevant().equals(available.relevant()) && current.context().equals(available.context())
                        && current.bound().equals(available.bound()) ? "OBJECTIVE_COVERAGE_COMPLETE" : "NO_FEASIBLE_COVERAGE_GAIN";
                    break;
                }
                int delta = best.tokenCount() - currentTokens;
                selected = new ArrayList<>(best.selected()); current = best.coverage(); currentTokens = best.tokenCount(); steps++;
                if (!best.removedProofIds().isEmpty()) replacements++;
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("step", steps); step.put("phase", best.relevantGain() > 0 ? "RELEVANT" : "CONTEXT_BOUND");
                step.put("proofId", best.proof().id()); step.put("removedProofIds", best.removedProofIds());
                step.put("selectedProofIds", selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList());
                step.put("relevantGain", best.relevantGain()); step.put("contextGain", best.contextGain()); step.put("boundGain", best.boundGain());
                step.put("tokenCount", currentTokens); step.put("incrementalTokens", delta);
                step.put("mentionCandidateIds", sorted(current.mentions())); step.put("relevantCandidateIds", sorted(current.relevant()));
                step.put("contextCandidateIds", sorted(current.context())); step.put("boundCandidateIds", sorted(current.bound()));
                history.add(java.util.Collections.unmodifiableMap(step));
            }
            String text = adopted ? renderSourceBlocks(prefix, selected) : seed.selection().text();
            int tokens = EvidenceContext.tokens(text);
            List<String> selectedIds = selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList();
            var selection = adopted ? new ClassificationProofCatalog.Selection(text, tokens, selectedIds,
                proofs.spans().size() - selected.size(), tokens <= budget) : seed.selection();
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("packingPolicy", POLICY_SOURCE_PACKING);
            diagnostics.put("prefixSha256", sha256(prefix)); diagnostics.put("sourceCatalogSha256", monotoneSourceFingerprint());
            diagnostics.put("selectedPackingPolicy", adopted ? POLICY_SOURCE_PACKING : POLICY_MONOTONE_COVERAGE);
            diagnostics.put("sourceBlockAdopted", adopted); diagnostics.put("seedPolicy", POLICY_MONOTONE_COVERAGE);
            diagnostics.put("seedTokens", seed.selection().tokenCount()); diagnostics.put("seedTokenCount", seed.selection().tokenCount());
            diagnostics.put("seedProofIds", seed.selection().proofIds()); diagnostics.put("sourceBlockSeedTokens", compactSeedTokens);
            diagnostics.put("sourceBlockTokens", tokens); diagnostics.put("representationSaving", seed.selection().tokenCount() - compactSeedTokens);
            diagnostics.put("representationSavingAdopted", adopted ? seed.selection().tokenCount() - compactSeedTokens : 0);
            diagnostics.put("rawSourceCharacters", selected.stream().mapToInt(proof -> proof.text().length()).sum());
            diagnostics.put("sourceCharacterAccounting", "JAVA_UTF16_CODE_UNITS; exact source text is never trimmed or rewritten");
            diagnostics.put("requestedBudget", requestedBudget); diagnostics.put("budget", budget); diagnostics.put("fixedTextTokens", fixedTokens);
            diagnostics.put("tokenCount", tokens); diagnostics.put("withinBudget", selection.withinBudget());
            diagnostics.put("selectedProofIds", selection.proofIds()); diagnostics.put("availableProofCount", proofs.spans().size());
            diagnostics.put("availableProofCatalog", proofs.spans().stream()
                .map(proof -> java.util.Collections.unmodifiableMap(proof.metadata())).toList());
            diagnostics.put("seedSelectionSteps", seedSteps); diagnostics.put("selectionSteps", steps);
            diagnostics.put("continuationSteps", steps - seedSteps); diagnostics.put("stepLimit", stepLimit);
            diagnostics.put("replacementSteps", replacements); diagnostics.put("sourceBlockSelectionHistory", List.copyOf(history));
            diagnostics.put("seedSelectionHistory", List.copyOf(seedHistoryCopy));
            diagnostics.put("stoppedReason", termination); diagnostics.put("terminationReason", termination);
            diagnostics.put("mappingFormat", "IDENTICAL_PROOF_SET_MEMBER_RANGES_V1"); diagnostics.put("selectionObjective", MEMBER_OBJECTIVE);
            for (String dimension : List.of("Mention", "Relevant", "Context", "Bound")) {
                Set<Integer> before = dimensionMembers(seedCoverage, dimension), after = dimensionMembers(current, dimension);
                diagnostics.put("seed" + dimension + "CandidateIds", sorted(before));
                diagnostics.put("lostSeed" + dimension + "CandidateIds", difference(before, after));
                diagnostics.put("sent" + dimension + "CandidateIds", sorted(after));
            }
            diagnostics.put("retrievalOnly", true); diagnostics.put("semanticSupportNotEstablished", true);
            return new MemberSelection(selection, diagnostics);
        }

        /** Stage D2 keeps every actual D1 proof and appends evidence to a compact, independently checked model view. */
        MemberSelection selectModelContext(ClassificationModelContext.Projection projection,
                MemberSelection actualD1Seed, int requestedBudget) {
            if (projection == null) throw new IllegalArgumentException("MODEL_CONTEXT_PROJECTION_REQUIRED");
            List<String> projectionFailures = projection.validate(catalog);
            if (!projectionFailures.isEmpty() || !projection.complete())
                throw new IllegalArgumentException("MODEL_CONTEXT_PROJECTION_INVALID:" + projectionFailures);
            int budget = Math.max(0, Math.min(requestedBudget, EvidenceContext.MAX_BUDGET));
            List<ClassificationProofCatalog.ProofSpan> selected = validateModelSeed(actualD1Seed, budget);
            Coverage seedCoverage = aggregate(selected), current = seedCoverage;
            List<String> seedIds = selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList();
            String seedText = renderModelContext(projection, selected);
            int seedTokens = EvidenceContext.tokens(seedText), currentTokens = seedTokens;
            int stepLimit = proofs.spans().size(), steps = 0;
            String termination = seedTokens > budget ? "MODEL_CONTEXT_SEED_OVER_BUDGET"
                : proofs.spans().isEmpty() ? "NO_AVAILABLE_PROOF" : "STEP_LIMIT_REACHED";
            List<Map<String, Object>> history = new ArrayList<>();
            if (seedTokens <= budget) while (steps < stepLimit) {
                Set<String> selectedIds = new LinkedHashSet<>(selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList());
                MemberTrial bestRelevant = null, bestAttributes = null;
                for (var proof : proofs.spans()) {
                    if (selectedIds.contains(proof.id())) continue;
                    // Union presentation deduplicates overlapping text; the typed proof IDs are never replaced.
                    List<ClassificationProofCatalog.ProofSpan> trial = new ArrayList<>(selected);
                    trial.add(proof); trial.sort(sourceOrder());
                    Coverage next = aggregate(trial);
                    int relevantGain = additional(next.relevant(), current.relevant());
                    int contextGain = additional(next.context(), current.context()), boundGain = additional(next.bound(), current.bound());
                    if (relevantGain == 0 && contextGain == 0 && boundGain == 0) continue;
                    int tokens = EvidenceContext.tokens(renderModelContext(projection, trial));
                    if (tokens > budget) continue;
                    MemberTrial candidate = new MemberTrial(proof, List.copyOf(trial), next, tokens,
                        Math.max(1, tokens - currentTokens), relevantGain, contextGain, boundGain, List.of());
                    if (relevantGain > 0) {
                        if (betterMemberTrial(candidate, bestRelevant, true)) bestRelevant = candidate;
                    } else if (betterMemberTrial(candidate, bestAttributes, false)) bestAttributes = candidate;
                }
                MemberTrial best = bestRelevant != null ? bestRelevant : bestAttributes;
                if (best == null) {
                    Coverage available = aggregate(proofs.spans());
                    termination = current.relevant().equals(available.relevant()) && current.context().equals(available.context())
                        && current.bound().equals(available.bound()) ? "OBJECTIVE_COVERAGE_COMPLETE" : "NO_FEASIBLE_COVERAGE_GAIN";
                    break;
                }
                int delta = best.tokenCount() - currentTokens;
                selected = new ArrayList<>(best.selected()); current = best.coverage(); currentTokens = best.tokenCount(); steps++;
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("step", steps); step.put("phase", best.relevantGain() > 0 ? "RELEVANT" : "CONTEXT_BOUND");
                step.put("proofId", best.proof().id()); step.put("removedProofIds", List.of());
                step.put("selectedProofIds", selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList());
                step.put("relevantGain", best.relevantGain()); step.put("contextGain", best.contextGain()); step.put("boundGain", best.boundGain());
                step.put("tokenCount", currentTokens); step.put("incrementalTokens", delta);
                step.put("utilityTokenDenominator", best.incrementalTokens());
                step.put("mentionCandidateIds", sorted(current.mentions())); step.put("relevantCandidateIds", sorted(current.relevant()));
                step.put("contextCandidateIds", sorted(current.context())); step.put("boundCandidateIds", sorted(current.bound()));
                history.add(java.util.Collections.unmodifiableMap(step));
            }
            String text = renderModelContext(projection, selected);
            int tokens = EvidenceContext.tokens(text);
            List<String> selectedIds = selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList();
            var selection = new ClassificationProofCatalog.Selection(text, tokens, selectedIds,
                proofs.spans().size() - selected.size(), tokens <= budget);
            var sources = ClassificationModelContext.sources(proofs, selectedIds);
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("packingPolicy", POLICY_MODEL_CONTEXT); diagnostics.put("selectedPackingPolicy", POLICY_MODEL_CONTEXT);
            diagnostics.put("prefixSha256", sha256(projection.text())); diagnostics.put("sourceCatalogSha256", monotoneSourceFingerprint());
            diagnostics.put("projection", projection.metadata()); diagnostics.put("typedCatalogSha256", projection.typedCatalogSha256());
            diagnostics.put("seedPolicy", actualD1Seed.diagnostics().get("packingPolicy"));
            diagnostics.put("seedProofIds", seedIds); diagnostics.put("seedTokenCount", actualD1Seed.selection().tokenCount());
            diagnostics.put("modelContextSeedTokens", seedTokens); diagnostics.put("representationSaving", actualD1Seed.selection().tokenCount() - seedTokens);
            diagnostics.put("requestedBudget", requestedBudget); diagnostics.put("budget", budget);
            diagnostics.put("fixedTextTokens", EvidenceContext.tokens(renderModelContext(projection, List.of())));
            diagnostics.put("tokenCount", tokens); diagnostics.put("withinBudget", selection.withinBudget());
            diagnostics.put("selectedProofIds", selectedIds); diagnostics.put("availableProofCount", proofs.spans().size());
            diagnostics.put("availableProofCatalog", proofs.spans().stream().map(proof -> java.util.Collections.unmodifiableMap(proof.metadata())).toList());
            diagnostics.put("sources", sources.metadata()); diagnostics.put("rawSourceCharacters", sources.sourceCharacters());
            diagnostics.put("sourceCharacterAccounting", "UNION_UTF16_CODE_UNITS; individual proof coordinates remain recoverable");
            diagnostics.put("seedSelectionSteps", actualD1Seed.diagnostics().get("selectionSteps"));
            diagnostics.put("selectionSteps", steps); diagnostics.put("continuationSteps", steps); diagnostics.put("stepLimit", stepLimit);
            diagnostics.put("replacementSteps", 0); diagnostics.put("modelContextSelectionHistory", List.copyOf(history));
            diagnostics.put("stoppedReason", termination); diagnostics.put("terminationReason", termination);
            diagnostics.put("mappingFormat", "PROOF_TO_RELEVANT_MEMBER_RANGES_V1"); diagnostics.put("selectionObjective", MEMBER_OBJECTIVE);
            for (String dimension : List.of("Mention", "Relevant", "Context", "Bound")) {
                Set<Integer> before = dimensionMembers(seedCoverage, dimension), after = dimensionMembers(current, dimension);
                diagnostics.put("seed" + dimension + "CandidateIds", sorted(before));
                diagnostics.put("lostSeed" + dimension + "CandidateIds", difference(before, after));
                diagnostics.put("sent" + dimension + "CandidateIds", sorted(after));
            }
            Set<Integer> allCandidates = catalog.candidates().stream().map(GraphClassificationContract.Candidate::number)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            diagnostics.put("availableSourceNotSentCandidateIds", difference(aggregate(proofs.spans()).relevant(), current.relevant()));
            diagnostics.put("noAvailableSourceCandidateIds", difference(allCandidates, aggregate(proofs.spans()).relevant()));
            diagnostics.put("retrievalOnly", true); diagnostics.put("semanticSupportNotEstablished", true);
            return new MemberSelection(selection, diagnostics);
        }

        private String renderModelContext(ClassificationModelContext.Projection projection,
                List<ClassificationProofCatalog.ProofSpan> selected) {
            List<String> ids = selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList();
            StringBuilder text = new StringBuilder(projection.text());
            if (!projection.text().endsWith("\n")) text.append('\n');
            text.append(ClassificationModelContext.sources(proofs, ids).text());
            text.append("【原文选择范围】").append(selected.size()).append('/').append(proofs.spans().size())
                .append("；未展示或未映射不证明排除、类别或不存在。\n");
            text.append("【证明对应成员；编号区间逐成员展开；仅此次已展示】\n")
                .append("对应仅用于原文定位，不证明分类；模型须逐成员选择支持其分类的证明，不能自动赋予U/I/E。\n");
            Set<Integer> delivered = new LinkedHashSet<>();
            for (var proof : selected.stream().sorted(sourceOrder()).toList()) {
                Set<Integer> members = coverage.get(proof.id()).relevant(); delivered.addAll(members);
                text.append(proof.id()).append("->").append(compact(members)).append('\n');
            }
            Set<Integer> missing = catalog.candidates().stream().map(GraphClassificationContract.Candidate::number)
                .filter(number -> !delivered.contains(number)).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            text.append("none->").append(missing.isEmpty() ? "[]" : compact(missing))
                .append("；未送不等于全局无来源。\n");
            return text.toString();
        }

        /** Validate the saved D1/C2 provenance by reconstructing its presentations and recorded transitions, never rerunning selection. */
        private List<ClassificationProofCatalog.ProofSpan> validateModelSeed(MemberSelection seed, int budget) {
            if (seed == null || seed.selection() == null) throw new IllegalArgumentException("MODEL_CONTEXT_SEED_REQUIRED");
            String policy = Objects.toString(seed.diagnostics().get("packingPolicy"), "");
            boolean sourcePolicy = POLICY_SOURCE_PACKING.equals(policy);
            if (!sourcePolicy && !POLICY_MONOTONE_COVERAGE.equals(policy))
                throw new IllegalArgumentException("MODEL_CONTEXT_SEED_POLICY_MISMATCH");
            String prefix = Objects.toString(catalog.text(), "");
            if (!sha256(prefix).equals(seed.diagnostics().get("prefixSha256"))
                || !monotoneSourceFingerprint().equals(seed.diagnostics().get("sourceCatalogSha256")))
                throw new IllegalArgumentException("MODEL_CONTEXT_SEED_IDENTITY_MISMATCH");
            int seedBudget = seedInteger(seed, "budget");
            if (seedBudget > EvidenceContext.MAX_BUDGET || budget < seedBudget)
                throw new IllegalArgumentException("MODEL_CONTEXT_BUDGET_TOO_SMALL");
            List<ClassificationProofCatalog.ProofSpan> selected = modelSeedProofs(seed.selection().proofIds());
            String selectedPolicy = sourcePolicy ? Objects.toString(seed.diagnostics().get("selectedPackingPolicy"), "") : policy;
            boolean sourceAdopted = sourcePolicy && Boolean.TRUE.equals(seed.diagnostics().get("sourceBlockAdopted"));
            if (sourcePolicy && !(seed.diagnostics().get("sourceBlockAdopted") instanceof Boolean))
                throw new IllegalArgumentException("MODEL_CONTEXT_SEED_ADOPTION_FLAG_INVALID");
            if (!(sourceAdopted ? POLICY_SOURCE_PACKING : POLICY_MONOTONE_COVERAGE).equals(selectedPolicy))
                throw new IllegalArgumentException("MODEL_CONTEXT_SEED_SELECTED_POLICY_MISMATCH");
            String original = sourceAdopted ? renderSourceBlocks(prefix, selected) : renderMonotoneCoverage(prefix, selected);
            int originalTokens = EvidenceContext.tokens(original);
            if (!original.equals(seed.selection().text()) || originalTokens != seed.selection().tokenCount()
                || originalTokens != seedInteger(seed, "tokenCount")
                || seed.selection().omittedSpanCount() != proofs.spans().size() - selected.size()
                || seedInteger(seed, "availableProofCount") != proofs.spans().size()
                || !seed.selection().withinBudget() || originalTokens > seedBudget
                || !Boolean.TRUE.equals(seed.diagnostics().get("withinBudget")))
                throw new IllegalArgumentException("MODEL_CONTEXT_SEED_ACCOUNTING_MISMATCH");
            Coverage finalCoverage = aggregate(selected);
            for (String dimension : List.of("Mention", "Relevant", "Context", "Bound"))
                if (!sorted(dimensionMembers(finalCoverage, dimension)).equals(seed.diagnostics().get("sent" + dimension + "CandidateIds")))
                    throw new IllegalArgumentException("MODEL_CONTEXT_SEED_COVERAGE_MISMATCH");
            int expectedLimit = (int) Math.min(Integer.MAX_VALUE, 2L * proofs.spans().size());
            if (seedInteger(seed, "stepLimit") != expectedLimit || seedInteger(seed, "selectionSteps") > expectedLimit)
                throw new IllegalArgumentException("MODEL_CONTEXT_SEED_STEP_LIMIT_INVALID");
            if (!sourcePolicy) {
                if (seedInteger(seed, "currentTokenCount") != originalTokens
                    || seedInteger(seed, "fixedTextTokens") != EvidenceContext.tokens(renderMonotoneCoverage(prefix, List.of()))
                    || !seed.selection().proofIds().equals(seed.diagnostics().get("selectedProofIds")))
                    throw new IllegalArgumentException("MODEL_CONTEXT_SEED_C2_ACCOUNTING_MISMATCH");
                var history = modelSeedHistory(seed, "selectionHistory");
                if (history.size() != seedInteger(seed, "selectionSteps")) throw new IllegalArgumentException("MODEL_CONTEXT_SEED_HISTORY_INVALID");
                validateMonotoneHistory(prefix, history, selected, seedBudget, seedInteger(seed, "replacementSteps"));
                return selected;
            }
            if (!POLICY_MONOTONE_COVERAGE.equals(seed.diagnostics().get("seedPolicy"))
                || seedInteger(seed, "sourceBlockTokens") != originalTokens
                || seedInteger(seed, "rawSourceCharacters") != selected.stream().mapToInt(proof -> proof.text().length()).sum()
                || seedInteger(seed, "fixedTextTokens") != EvidenceContext.tokens(renderSourceBlocks(prefix, List.of()))
                || !seed.selection().proofIds().equals(seed.diagnostics().get("selectedProofIds"))
                || !proofs.spans().stream().map(ClassificationProofCatalog.ProofSpan::metadata).toList().equals(seed.diagnostics().get("availableProofCatalog")))
                throw new IllegalArgumentException("MODEL_CONTEXT_SEED_D1_ACCOUNTING_MISMATCH");
            var c2Ids = modelSeedIds(seed.diagnostics().get("seedProofIds"));
            var c2 = modelSeedProofs(c2Ids); var c2History = modelSeedHistory(seed, "seedSelectionHistory");
            int c2Tokens = EvidenceContext.tokens(renderMonotoneCoverage(prefix, c2));
            int compactTokens = EvidenceContext.tokens(renderSourceBlocks(prefix, c2));
            if (c2Tokens > seedBudget || seedInteger(seed, "seedTokens") != c2Tokens
                || seedInteger(seed, "seedTokenCount") != c2Tokens || seedInteger(seed, "sourceBlockSeedTokens") != compactTokens
                || !Objects.equals(seed.diagnostics().get("representationSaving"), c2Tokens - compactTokens)
                || !Objects.equals(seed.diagnostics().get("representationSavingAdopted"), sourceAdopted ? c2Tokens - compactTokens : 0)
                || c2History.size() != seedInteger(seed, "seedSelectionSteps")
                || sourceAdopted != (compactTokens <= c2Tokens && compactTokens <= seedBudget))
                throw new IllegalArgumentException("MODEL_CONTEXT_SEED_D1_PROVENANCE_MISMATCH");
            int c2Replacements = (int) c2History.stream().filter(step -> !List.of().equals(step.get("removedProofIds"))).count();
            validateMonotoneHistory(prefix, c2History, c2, seedBudget, c2Replacements);
            var d1History = modelSeedHistory(seed, "sourceBlockSelectionHistory");
            if (d1History.size() != seedInteger(seed, "continuationSteps")
                || seedInteger(seed, "selectionSteps") != c2History.size() + d1History.size()
                || !sourceAdopted && (!d1History.isEmpty() || !c2Ids.equals(seed.selection().proofIds())))
                throw new IllegalArgumentException("MODEL_CONTEXT_SEED_D1_HISTORY_INVALID");
            List<ClassificationProofCatalog.ProofSpan> previous = c2;
            Coverage priorCoverage = aggregate(c2); int priorTokens = compactTokens, replacements = 0;
            for (int index = 0; index < d1History.size(); index++) {
                var step = d1History.get(index); String added = Objects.toString(step.get("proofId"), "");
                var proof = proofs.span(added).orElseThrow(() -> new IllegalArgumentException("MODEL_CONTEXT_SEED_HISTORY_UNKNOWN_PROOF:" + added));
                if (previous.stream().anyMatch(item -> item.id().equals(added))) throw new IllegalArgumentException("MODEL_CONTEXT_SEED_HISTORY_REPEATED_PROOF");
                List<ClassificationProofCatalog.ProofSpan> next = new ArrayList<>(); List<String> removed = new ArrayList<>();
                for (var item : previous) { if (overlap(item, proof)) removed.add(item.id()); else next.add(item); }
                next.add(proof); next.sort(sourceOrder()); Coverage after = aggregate(next);
                int relevantGain = additional(after.relevant(), priorCoverage.relevant());
                int contextGain = additional(after.context(), priorCoverage.context()), boundGain = additional(after.bound(), priorCoverage.bound());
                int tokens = EvidenceContext.tokens(renderSourceBlocks(prefix, next));
                if (historyInteger(step, "step") != c2History.size() + index + 1 || !removed.equals(step.get("removedProofIds"))
                    || !next.stream().map(ClassificationProofCatalog.ProofSpan::id).toList().equals(step.get("selectedProofIds"))
                    || !after.mentions().containsAll(priorCoverage.mentions()) || !after.relevant().containsAll(priorCoverage.relevant())
                    || !after.context().containsAll(priorCoverage.context()) || !after.bound().containsAll(priorCoverage.bound())
                    || relevantGain == 0 && contextGain == 0 && boundGain == 0 || tokens > seedBudget
                    || historyInteger(step, "tokenCount") != tokens || historyInteger(step, "incrementalTokens") != tokens - priorTokens
                    || historyInteger(step, "relevantGain") != relevantGain || historyInteger(step, "contextGain") != contextGain
                    || historyInteger(step, "boundGain") != boundGain
                    || !Objects.equals(step.get("phase"), relevantGain > 0 ? "RELEVANT" : "CONTEXT_BOUND")
                    || !sorted(after.mentions()).equals(step.get("mentionCandidateIds"))
                    || !sorted(after.relevant()).equals(step.get("relevantCandidateIds"))
                    || !sorted(after.context()).equals(step.get("contextCandidateIds"))
                    || !sorted(after.bound()).equals(step.get("boundCandidateIds")))
                    throw new IllegalArgumentException("MODEL_CONTEXT_SEED_D1_HISTORY_TRANSITION_INVALID");
                if (!removed.isEmpty()) replacements++; previous = next; priorCoverage = after; priorTokens = tokens;
            }
            if (!previous.equals(selected) || replacements != seedInteger(seed, "replacementSteps"))
                throw new IllegalArgumentException("MODEL_CONTEXT_SEED_D1_HISTORY_FINAL_MISMATCH");
            for (String dimension : List.of("Mention", "Relevant", "Context", "Bound")) {
                Set<Integer> before = dimensionMembers(aggregate(c2), dimension), after = dimensionMembers(finalCoverage, dimension);
                if (!sorted(before).equals(seed.diagnostics().get("seed" + dimension + "CandidateIds"))
                    || !difference(before, after).equals(seed.diagnostics().get("lostSeed" + dimension + "CandidateIds")))
                    throw new IllegalArgumentException("MODEL_CONTEXT_SEED_D1_SCOPE_MISMATCH");
            }
            return selected;
        }

        private List<ClassificationProofCatalog.ProofSpan> modelSeedProofs(List<String> ids) {
            List<ClassificationProofCatalog.ProofSpan> selected = new ArrayList<>(); Set<String> unique = new LinkedHashSet<>();
            for (String id : ids) {
                if (!unique.add(id)) throw new IllegalArgumentException("MODEL_CONTEXT_SEED_DUPLICATE_PROOF");
                var proof = proofs.span(id).orElseThrow(() -> new IllegalArgumentException("MODEL_CONTEXT_SEED_UNKNOWN_PROOF:" + id));
                if (selected.stream().anyMatch(item -> overlap(item, proof))) throw new IllegalArgumentException("MODEL_CONTEXT_SEED_OVERLAPPING_PROOF");
                selected.add(proof);
            }
            selected.sort(sourceOrder());
            if (!ids.equals(selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList()))
                throw new IllegalArgumentException("MODEL_CONTEXT_SEED_PROOF_ORDER_MISMATCH");
            return selected;
        }

        private List<String> modelSeedIds(Object value) {
            if (!(value instanceof List<?> ids) || ids.stream().anyMatch(id -> !(id instanceof String)))
                throw new IllegalArgumentException("MODEL_CONTEXT_SEED_PROOF_IDS_INVALID");
            return ids.stream().map(String.class::cast).toList();
        }

        private List<Map<String, Object>> modelSeedHistory(MemberSelection seed, String key) {
            if (!(seed.diagnostics().get(key) instanceof List<?> entries)) throw new IllegalArgumentException("MODEL_CONTEXT_SEED_HISTORY_INVALID:" + key);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object value : entries) {
                if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("MODEL_CONTEXT_SEED_HISTORY_INVALID:" + key);
                Map<String, Object> copy = new LinkedHashMap<>();
                for (var entry : raw.entrySet()) {
                    if (!(entry.getKey() instanceof String name) || entry.getValue() == null)
                        throw new IllegalArgumentException("MODEL_CONTEXT_SEED_HISTORY_INVALID:" + key);
                    copy.put(name, entry.getValue());
                }
                result.add(java.util.Collections.unmodifiableMap(copy));
            }
            return List.copyOf(result);
        }

        /** Continue the same proof set and step history instead of restarting greedy selection at a larger budget. */
        MemberSelection extendMonotoneCoverage(String prefixText, MemberSelection baseline, int requestedBudget) {
            if (baseline == null || baseline.selection() == null) throw new IllegalArgumentException("MONOTONE_SEED_REQUIRED");
            return packMonotoneCoverage(Objects.toString(prefixText, ""), requestedBudget, baseline);
        }

        private MemberSelection packMonotoneCoverage(String prefix, int requestedBudget, MemberSelection baseline) {
            int budget = Math.max(0, Math.min(requestedBudget, EvidenceContext.MAX_BUDGET));
            int stepLimit = (int) Math.min(Integer.MAX_VALUE, 2L * proofs.spans().size());
            String prefixHash = sha256(prefix), sourceHash = monotoneSourceFingerprint();
            List<ClassificationProofCatalog.ProofSpan> selected = new ArrayList<>();
            List<Map<String, Object>> history = new ArrayList<>();
            int steps = 0, replacements = 0, baselineBudget = 0;
            if (baseline != null) {
                if (!POLICY_MONOTONE_COVERAGE.equals(baseline.diagnostics().get("packingPolicy")))
                    throw new IllegalArgumentException("MONOTONE_SEED_POLICY_MISMATCH");
                if (!prefixHash.equals(baseline.diagnostics().get("prefixSha256")))
                    throw new IllegalArgumentException("MONOTONE_SEED_PREFIX_MISMATCH");
                if (!sourceHash.equals(baseline.diagnostics().get("sourceCatalogSha256")))
                    throw new IllegalArgumentException("MONOTONE_SEED_SOURCE_MISMATCH");
                if (!baseline.selection().withinBudget()) throw new IllegalArgumentException("MONOTONE_SEED_NOT_WITHIN_BUDGET");
                Set<String> seen = new LinkedHashSet<>();
                for (String id : baseline.selection().proofIds()) {
                    if (!seen.add(id)) throw new IllegalArgumentException("MONOTONE_SEED_DUPLICATE_PROOF");
                    var proof = proofs.span(id).orElseThrow(() -> new IllegalArgumentException("MONOTONE_SEED_UNKNOWN_PROOF:" + id));
                    if (selected.stream().anyMatch(existing -> overlap(existing, proof)))
                        throw new IllegalArgumentException("MONOTONE_SEED_OVERLAPPING_PROOF");
                    selected.add(proof);
                }
                selected.sort(sourceOrder());
                String seedText = renderMonotoneCoverage(prefix, selected);
                int seedTokens = EvidenceContext.tokens(seedText);
                if (!seedText.equals(baseline.selection().text()) || seedTokens != baseline.selection().tokenCount())
                    throw new IllegalArgumentException("MONOTONE_SEED_RENDER_MISMATCH");
                if (baseline.selection().omittedSpanCount() != proofs.spans().size() - selected.size()
                    || !selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList().equals(baseline.selection().proofIds())
                    || seedInteger(baseline, "tokenCount") != seedTokens
                    || seedInteger(baseline, "currentTokenCount") != seedTokens
                    || seedInteger(baseline, "fixedTextTokens") != EvidenceContext.tokens(renderMonotoneCoverage(prefix, List.of()))
                    || seedInteger(baseline, "availableProofCount") != proofs.spans().size()
                    || !Boolean.TRUE.equals(baseline.diagnostics().get("withinBudget")))
                    throw new IllegalArgumentException("MONOTONE_SEED_SELECTION_MISMATCH");
                baselineBudget = seedInteger(baseline, "budget");
                if (budget < baselineBudget || seedTokens > budget)
                    throw new IllegalArgumentException("MONOTONE_EXTENSION_BUDGET_TOO_SMALL");
                if (baselineBudget > EvidenceContext.MAX_BUDGET || baselineBudget < seedTokens
                    || Math.min(seedInteger(baseline, "requestedBudget"), EvidenceContext.MAX_BUDGET) != baselineBudget)
                    throw new IllegalArgumentException("MONOTONE_SEED_BUDGET_INVALID");
                steps = seedInteger(baseline, "selectionSteps"); replacements = seedInteger(baseline, "replacementSteps");
                if (seedInteger(baseline, "stepLimit") != stepLimit || steps > stepLimit || replacements > steps)
                    throw new IllegalArgumentException("MONOTONE_SEED_STEP_LIMIT_INVALID");
                Object previousHistory = baseline.diagnostics().get("selectionHistory");
                if (!(previousHistory instanceof List<?> entries) || entries.size() != steps)
                    throw new IllegalArgumentException("MONOTONE_SEED_HISTORY_INVALID");
                for (Object entry : entries) {
                    if (!(entry instanceof Map<?, ?> raw)) throw new IllegalArgumentException("MONOTONE_SEED_HISTORY_INVALID");
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (var item : raw.entrySet()) {
                        if (!(item.getKey() instanceof String key) || item.getValue() == null)
                            throw new IllegalArgumentException("MONOTONE_SEED_HISTORY_INVALID");
                        copy.put(key, item.getValue());
                    }
                    history.add(java.util.Collections.unmodifiableMap(copy));
                }
                List<String> seedIds = selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList();
                if (!seedIds.equals(baseline.diagnostics().get("selectedProofIds"))
                    || (!history.isEmpty() && !seedIds.equals(history.get(history.size() - 1).get("selectedProofIds")))
                    || (history.isEmpty() && !seedIds.isEmpty()))
                    throw new IllegalArgumentException("MONOTONE_SEED_HISTORY_PROOFS_MISMATCH");
                Coverage seedCoverage = aggregate(selected);
                if (!sorted(seedCoverage.mentions()).equals(baseline.diagnostics().get("sentMentionCandidateIds"))
                    || !sorted(seedCoverage.relevant()).equals(baseline.diagnostics().get("sentRelevantCandidateIds"))
                    || !sorted(seedCoverage.context()).equals(baseline.diagnostics().get("sentContextCandidateIds"))
                    || !sorted(seedCoverage.bound()).equals(baseline.diagnostics().get("sentBoundCandidateIds")))
                    throw new IllegalArgumentException("MONOTONE_SEED_COVERAGE_MISMATCH");
                validateMonotoneHistory(prefix, history, selected, baselineBudget, replacements);
            }
            List<String> seedProofIds = selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList();
            Coverage seedCoverage = aggregate(selected), current = seedCoverage;
            int fixedTokens = EvidenceContext.tokens(renderMonotoneCoverage(prefix, List.of()));
            int initialTokens = EvidenceContext.tokens(renderMonotoneCoverage(prefix, selected)), currentTokens = initialTokens;
            int initialSteps = steps, initialReplacements = replacements;
            String termination = initialTokens > budget ? "FIXED_TEXT_OVER_BUDGET"
                : proofs.spans().isEmpty() ? "NO_AVAILABLE_PROOF" : "STEP_LIMIT_REACHED";
            if (initialTokens <= budget && !proofs.spans().isEmpty()) while (steps < stepLimit) {
                MemberTrial bestRelevant = null, bestAttributes = null;
                Set<String> selectedIds = selected.stream().map(ClassificationProofCatalog.ProofSpan::id)
                    .collect(java.util.stream.Collectors.toSet());
                for (var proof : proofs.spans()) {
                    if (selectedIds.contains(proof.id())) continue;
                    List<ClassificationProofCatalog.ProofSpan> trial = new ArrayList<>();
                    List<String> removedIds = new ArrayList<>();
                    for (var existing : selected) {
                        if (overlap(existing, proof)) removedIds.add(existing.id()); else trial.add(existing);
                    }
                    trial.add(proof); trial.sort(sourceOrder());
                    Coverage next = aggregate(trial);
                    if (!next.mentions().containsAll(current.mentions()) || !next.relevant().containsAll(current.relevant())
                        || !next.context().containsAll(current.context()) || !next.bound().containsAll(current.bound())) continue;
                    int relevantGain = additional(next.relevant(), current.relevant());
                    int contextGain = additional(next.context(), current.context()), boundGain = additional(next.bound(), current.bound());
                    if (relevantGain == 0 && contextGain == 0 && boundGain == 0) continue;
                    int tokens = EvidenceContext.tokens(renderMonotoneCoverage(prefix, trial));
                    if (tokens > budget) continue;
                    MemberTrial candidate = new MemberTrial(proof, List.copyOf(trial), next, tokens,
                        Math.max(1, tokens - currentTokens), relevantGain, contextGain, boundGain, List.copyOf(removedIds));
                    if (relevantGain > 0) {
                        if (betterMemberTrial(candidate, bestRelevant, true)) bestRelevant = candidate;
                    } else if (betterMemberTrial(candidate, bestAttributes, false)) bestAttributes = candidate;
                }
                MemberTrial best = bestRelevant != null ? bestRelevant : bestAttributes;
                if (best == null) {
                    Coverage available = aggregate(proofs.spans());
                    termination = current.relevant().equals(available.relevant()) && current.context().equals(available.context())
                        && current.bound().equals(available.bound()) ? "OBJECTIVE_COVERAGE_COMPLETE" : "NO_FEASIBLE_COVERAGE_GAIN";
                    break;
                }
                int delta = best.tokenCount() - currentTokens;
                selected = new ArrayList<>(best.selected()); current = best.coverage(); currentTokens = best.tokenCount(); steps++;
                if (!best.removedProofIds().isEmpty()) replacements++;
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("step", steps); step.put("phase", best.relevantGain() > 0 ? "RELEVANT" : "CONTEXT_BOUND");
                step.put("proofId", best.proof().id()); step.put("removedProofIds", best.removedProofIds());
                step.put("selectedProofIds", selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList());
                step.put("relevantGain", best.relevantGain()); step.put("contextGain", best.contextGain()); step.put("boundGain", best.boundGain());
                step.put("tokenCount", best.tokenCount()); step.put("incrementalTokens", delta); step.put("utilityTokenDenominator", best.incrementalTokens());
                step.put("relevantCandidateIds", sorted(current.relevant())); step.put("mentionCandidateIds", sorted(current.mentions()));
                step.put("contextCandidateIds", sorted(current.context())); step.put("boundCandidateIds", sorted(current.bound()));
                history.add(java.util.Collections.unmodifiableMap(step));
            }
            String text = renderMonotoneCoverage(prefix, selected);
            int tokens = EvidenceContext.tokens(text);
            List<String> selectedIds = selected.stream().map(ClassificationProofCatalog.ProofSpan::id).toList();
            var selection = new ClassificationProofCatalog.Selection(text, tokens, selectedIds,
                proofs.spans().size() - selected.size(), tokens <= budget);
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("packingPolicy", POLICY_MONOTONE_COVERAGE); diagnostics.put("selectionObjective", MEMBER_OBJECTIVE);
            diagnostics.put("mappingFormat", "IDENTICAL_PROOF_SET_MEMBER_RANGES_V1");
            diagnostics.put("runMode", baseline == null ? "INITIAL" : "CONTINUATION");
            diagnostics.put("prefixSha256", prefixHash); diagnostics.put("sourceCatalogSha256", sourceHash);
            diagnostics.put("requestedBudget", requestedBudget); diagnostics.put("budget", budget); diagnostics.put("baselineBudget", baselineBudget);
            diagnostics.put("fixedTextTokens", fixedTokens); diagnostics.put("initialTokenCount", initialTokens);
            diagnostics.put("currentTokenCount", tokens); diagnostics.put("tokenCount", tokens); diagnostics.put("withinBudget", selection.withinBudget());
            diagnostics.put("seedProofIds", seedProofIds); diagnostics.put("seedTokenCount", initialTokens);
            diagnostics.put("seedMentionCandidateIds", sorted(seedCoverage.mentions())); diagnostics.put("seedRelevantCandidateIds", sorted(seedCoverage.relevant()));
            diagnostics.put("seedContextCandidateIds", sorted(seedCoverage.context())); diagnostics.put("seedBoundCandidateIds", sorted(seedCoverage.bound()));
            diagnostics.put("availableProofCount", proofs.spans().size()); diagnostics.put("stepLimit", stepLimit);
            diagnostics.put("seedSelectionSteps", initialSteps); diagnostics.put("selectionSteps", steps); diagnostics.put("continuationSteps", steps - initialSteps);
            diagnostics.put("replacementSteps", replacements); diagnostics.put("continuationReplacementSteps", replacements - initialReplacements);
            diagnostics.put("terminationReason", termination); diagnostics.put("terminationScope", "RELEVANT_CONTEXT_BOUND_OBJECTIVE");
            diagnostics.put("selectedProofIds", selectedIds); diagnostics.put("sentMentionCandidateIds", sorted(current.mentions()));
            diagnostics.put("sentRelevantCandidateIds", sorted(current.relevant())); diagnostics.put("sentContextCandidateIds", sorted(current.context()));
            diagnostics.put("sentBoundCandidateIds", sorted(current.bound())); diagnostics.put("selectionHistory", List.copyOf(history));
            diagnostics.put("retrievalOnly", true); diagnostics.put("semanticSupportNotEstablished", true);
            return new MemberSelection(selection, diagnostics);
        }

        private int seedInteger(MemberSelection baseline, String key) {
            Object value = baseline.diagnostics().get(key);
            if (!(value instanceof Integer number) || number < 0) throw new IllegalArgumentException("MONOTONE_SEED_DIAGNOSTIC_INVALID:" + key);
            return number;
        }

        /** Reconstruct the seed's accepted transitions so continuation cannot reset or invent history. */
        private void validateMonotoneHistory(String prefix, List<Map<String, Object>> history,
                List<ClassificationProofCatalog.ProofSpan> selected, int budget, int replacements) {
            List<ClassificationProofCatalog.ProofSpan> previous = new ArrayList<>();
            Coverage current = aggregate(previous);
            int currentTokens = EvidenceContext.tokens(renderMonotoneCoverage(prefix, previous));
            int replacementCount = 0;
            for (int index = 0; index < history.size(); index++) {
                Map<String, Object> step = history.get(index);
                Object proofId = step.get("proofId");
                if (!(proofId instanceof String id) || historyInteger(step, "step") != index + 1)
                    throw new IllegalArgumentException("MONOTONE_SEED_HISTORY_INVALID");
                var proof = proofs.span(id).orElseThrow(() -> new IllegalArgumentException("MONOTONE_SEED_HISTORY_UNKNOWN_PROOF:" + id));
                if (previous.stream().anyMatch(existing -> existing.id().equals(id)))
                    throw new IllegalArgumentException("MONOTONE_SEED_HISTORY_REPEATED_PROOF");
                List<ClassificationProofCatalog.ProofSpan> next = new ArrayList<>();
                List<String> removed = new ArrayList<>();
                for (var existing : previous) {
                    if (overlap(existing, proof)) removed.add(existing.id()); else next.add(existing);
                }
                next.add(proof); next.sort(sourceOrder());
                Coverage nextCoverage = aggregate(next);
                int relevantGain = additional(nextCoverage.relevant(), current.relevant());
                int contextGain = additional(nextCoverage.context(), current.context());
                int boundGain = additional(nextCoverage.bound(), current.bound());
                int tokens = EvidenceContext.tokens(renderMonotoneCoverage(prefix, next));
                if (!removed.equals(step.get("removedProofIds"))
                    || !next.stream().map(ClassificationProofCatalog.ProofSpan::id).toList().equals(step.get("selectedProofIds"))
                    || !nextCoverage.mentions().containsAll(current.mentions())
                    || !nextCoverage.relevant().containsAll(current.relevant())
                    || !nextCoverage.context().containsAll(current.context())
                    || !nextCoverage.bound().containsAll(current.bound())
                    || relevantGain == 0 && contextGain == 0 && boundGain == 0
                    || historyInteger(step, "relevantGain") != relevantGain
                    || historyInteger(step, "contextGain") != contextGain
                    || historyInteger(step, "boundGain") != boundGain
                    || !Objects.equals(step.get("phase"), relevantGain > 0 ? "RELEVANT" : "CONTEXT_BOUND")
                    || historyInteger(step, "tokenCount") != tokens || tokens > budget
                    || historyInteger(step, "incrementalTokens") != tokens - currentTokens
                    || historyInteger(step, "utilityTokenDenominator") != Math.max(1, tokens - currentTokens)
                    || !sorted(nextCoverage.mentions()).equals(step.get("mentionCandidateIds"))
                    || !sorted(nextCoverage.relevant()).equals(step.get("relevantCandidateIds"))
                    || !sorted(nextCoverage.context()).equals(step.get("contextCandidateIds"))
                    || !sorted(nextCoverage.bound()).equals(step.get("boundCandidateIds")))
                    throw new IllegalArgumentException("MONOTONE_SEED_HISTORY_TRANSITION_INVALID");
                if (!removed.isEmpty()) replacementCount++;
                previous = next; current = nextCoverage; currentTokens = tokens;
            }
            if (!previous.equals(selected) || replacementCount != replacements)
                throw new IllegalArgumentException("MONOTONE_SEED_HISTORY_FINAL_MISMATCH");
        }

        private int historyInteger(Map<String, Object> step, String key) {
            Object value = step.get(key);
            if (!(value instanceof Integer number)) throw new IllegalArgumentException("MONOTONE_SEED_HISTORY_INVALID:" + key);
            return number;
        }

        private String monotoneSourceFingerprint() {
            StringBuilder source = new StringBuilder();
            identityPart(source, Objects.toString(catalog.text(), ""));
            for (var candidate : catalog.candidates().stream().sorted(Comparator.comparingInt(GraphClassificationContract.Candidate::number)).toList()) {
                identityPart(source, candidate.toString());
            }
            for (var proof : proofs.spans()) {
                identityPart(source, proof.id()); identityPart(source, proof.chunkId()); identityPart(source, proof.docType());
                identityPart(source, proof.docId()); identityPart(source, proof.title()); identityPart(source, proof.start());
                identityPart(source, proof.end()); identityPart(source, proof.text()); identityPart(source, proof.relationIds().stream().sorted().toList());
                Coverage item = coverage.get(proof.id());
                identityPart(source, sorted(item.mentions())); identityPart(source, sorted(item.relevant()));
                identityPart(source, sorted(item.context())); identityPart(source, sorted(item.bound()));
            }
            return sha256(source.toString());
        }

        /** Compare member identities across all four dimensions for either selector's selections. */
        boolean preservesMemberCoverage(ClassificationProofCatalog.Selection baseline,
                ClassificationProofCatalog.Selection candidate) {
            if (baseline == null || candidate == null) return false;
            for (int kind = 0; kind < 4; kind++)
                if (!members(candidate.proofIds(), kind).containsAll(members(baseline.proofIds(), kind))) return false;
            return true;
        }

        private Coverage aggregate(Collection<ClassificationProofCatalog.ProofSpan> spans) {
            Set<Integer> mentions = new LinkedHashSet<>(), bound = new LinkedHashSet<>(), context = new LinkedHashSet<>(), relevant = new LinkedHashSet<>();
            for (var proof : spans) {
                Coverage item = coverage.get(proof.id());
                mentions.addAll(item.mentions()); bound.addAll(item.bound());
                context.addAll(item.context()); relevant.addAll(item.relevant());
            }
            return new Coverage(Set.copyOf(mentions), Set.copyOf(bound), Set.copyOf(context), Set.copyOf(relevant));
        }

        private boolean betterMemberTrial(MemberTrial candidate, MemberTrial best, boolean relevantPhase) {
            if (best == null) return true;
            if (!relevantPhase && (candidate.contextGain() > 0) != (best.contextGain() > 0))
                return candidate.contextGain() > 0;
            int candidateGain = relevantPhase ? candidate.relevantGain()
                : candidate.contextGain() > 0 ? candidate.contextGain() : candidate.boundGain();
            int bestGain = relevantPhase ? best.relevantGain() : best.contextGain() > 0 ? best.contextGain() : best.boundGain();
            long left = (long) candidateGain * best.incrementalTokens();
            long right = (long) bestGain * candidate.incrementalTokens();
            if (left != right) return left > right;
            // Existing context/bound coverage is a hard constraint; ties keep more of those dimensions.
            if (candidate.contextGain() != best.contextGain()) return candidate.contextGain() > best.contextGain();
            if (candidate.boundGain() != best.boundGain()) return candidate.boundGain() > best.boundGain();
            if (candidate.removedProofIds().size() != best.removedProofIds().size())
                return candidate.removedProofIds().size() < best.removedProofIds().size();
            return sourceOrder().compare(candidate.proof(), best.proof()) < 0;
        }

        /** Expansion is driven by available candidate retrieval coverage, not omitted prose count. */
        boolean needsExpansion(ClassificationProofCatalog.Selection selected) {
            Set<String> ids = new LinkedHashSet<>(selected.proofIds());
            return !members(proofs.spans().stream().map(ClassificationProofCatalog.ProofSpan::id).toList(), 0)
                .equals(members(ids, 0))
                || !members(proofs.spans().stream().map(ClassificationProofCatalog.ProofSpan::id).toList(), 1)
                    .equals(members(ids, 1))
                || !members(proofs.spans().stream().map(ClassificationProofCatalog.ProofSpan::id).toList(), 2)
                    .equals(members(ids, 2));
        }

        Map<String, Object> metadata(Collection<String> sent) { return metadata(sent, List.of()); }

        Map<String, Object> memberSelectionMetadata(Collection<String> sent) { return memberSelectionMetadata(sent, List.of()); }

        Map<String, Object> memberSelectionMetadata(Collection<String> sent, Collection<String> cited) {
            Map<String, Object> result = new LinkedHashMap<>(metadata(sent, cited));
            result.put("packingPolicy", POLICY_MEMBER_COVERAGE); result.put("selectionObjective", MEMBER_OBJECTIVE);
            List<Map<String, Object>> rows = new ArrayList<>();
            Set<String> availableIds = safeSet(proofs.spans().stream().map(ClassificationProofCatalog.ProofSpan::id).toList());
            Set<String> sentIds = safeSet(sent);
            for (var candidate : catalog.candidates()) {
                List<String> available = memberProofs(candidate.number(), availableIds);
                List<String> delivered = memberProofs(candidate.number(), sentIds);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("candidateId", candidate.number()); row.put("anchorId", candidate.anchorId()); row.put("otherId", candidate.otherId());
                row.put("availableProofIds", available); row.put("sentProofIds", delivered);
                row.put("citedProofIds", memberProofs(candidate.number(), safeSet(cited)));
                row.put("evidenceDeliveryStatus", available.isEmpty() ? "NO_AVAILABLE_PROOF"
                    : delivered.isEmpty() ? "AVAILABLE_BUT_UNSENT" : "AVAILABLE_AND_SENT");
                row.put("retrievalOnly", true); row.put("semanticSupportNotEstablished", true);
                rows.add(java.util.Collections.unmodifiableMap(row));
            }
            result.put("memberProofMapping", List.copyOf(rows));
            return java.util.Collections.unmodifiableMap(result);
        }

        Map<String, Object> monotoneSelectionMetadata(Collection<String> sent) { return monotoneSelectionMetadata(sent, List.of()); }

        Map<String, Object> monotoneSelectionMetadata(Collection<String> sent, Collection<String> cited) {
            Map<String, Object> result = new LinkedHashMap<>(memberSelectionMetadata(sent, cited));
            result.put("packingPolicy", POLICY_MONOTONE_COVERAGE);
            result.put("mappingFormat", "IDENTICAL_PROOF_SET_MEMBER_RANGES_V1");
            return java.util.Collections.unmodifiableMap(result);
        }

        Map<String, Object> sourcePackingSelectionMetadata(Collection<String> sent) {
            return sourcePackingSelectionMetadata(sent, List.of());
        }

        Map<String, Object> sourcePackingSelectionMetadata(Collection<String> sent, Collection<String> cited) {
            Map<String, Object> result = new LinkedHashMap<>(memberSelectionMetadata(sent, cited));
            result.put("packingPolicy", POLICY_SOURCE_PACKING);
            result.put("mappingFormat", "IDENTICAL_PROOF_SET_MEMBER_RANGES_V1");
            return java.util.Collections.unmodifiableMap(result);
        }

        private Set<Integer> dimensionMembers(Coverage value, String dimension) {
            return switch (dimension) {
                case "Mention" -> value.mentions(); case "Relevant" -> value.relevant();
                case "Context" -> value.context(); case "Bound" -> value.bound();
                default -> throw new IllegalArgumentException("Unknown retrieval dimension");
            };
        }

        Map<String, Object> metadata(Collection<String> sent, Collection<String> cited) {
            Collection<String> available = proofs.spans().stream().map(ClassificationProofCatalog.ProofSpan::id).toList();
            Set<String> sentIds = safeSet(sent), citedIds = safeSet(cited);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var candidate : catalog.candidates()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("candidateId", candidate.number()); row.put("anchorId", candidate.anchorId());
                row.put("otherId", candidate.otherId());
                row.put("availableProofIds", memberProofs(candidate.number(), safeSet(available)));
                row.put("sentProofIds", memberProofs(candidate.number(), sentIds));
                row.put("citedProofIds", memberProofs(candidate.number(), citedIds));
                row.put("retrievalOnly", true); row.put("semanticSupportNotEstablished", true);
                rows.add(java.util.Collections.unmodifiableMap(row));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("packingPolicy", POLICY); result.put("candidateCount", catalog.candidates().size());
            result.put("availableProofCount", proofs.spans().size()); result.put("sentProofCount", sentIds.size());
            result.put("removedSymbolSpanCount", removedSymbolSpanCount);
            result.put("availableMentionCandidateIds", sorted(members(available, 0)));
            result.put("sentMentionCandidateIds", sorted(members(sentIds, 0)));
            result.put("citedMentionCandidateIds", sorted(members(citedIds, 0)));
            result.put("availableBoundCandidateIds", sorted(members(available, 1)));
            result.put("sentBoundCandidateIds", sorted(members(sentIds, 1)));
            result.put("citedBoundCandidateIds", sorted(members(citedIds, 1)));
            result.put("availableContextCandidateIds", sorted(members(available, 2)));
            result.put("sentContextCandidateIds", sorted(members(sentIds, 2)));
            result.put("citedContextCandidateIds", sorted(members(citedIds, 2)));
            result.put("availableButUnsentMentionCandidateIds", difference(members(available, 0), members(sentIds, 0)));
            result.put("availableButUnsentBoundCandidateIds", difference(members(available, 1), members(sentIds, 1)));
            result.put("availableButUnsentContextCandidateIds", difference(members(available, 2), members(sentIds, 2)));
            result.put("availableRelevantCandidateIds", sorted(members(available, 3)));
            result.put("sentRelevantCandidateIds", sorted(members(sentIds, 3)));
            result.put("citedRelevantCandidateIds", sorted(members(citedIds, 3)));
            result.put("availableRelevantCandidateCount", members(available, 3).size());
            result.put("sentRelevantCandidateCount", members(sentIds, 3).size());
            result.put("citedRelevantCandidateCount", members(citedIds, 3).size());
            result.put("citedProofCount", citedIds.size());
            result.put("citedCoverageScope", "PROPOSAL_GLOBAL_PROOF_POOL");
            Set<Integer> omitted = new LinkedHashSet<>(members(available, 3)); omitted.removeAll(members(sentIds, 3));
            result.put("availableButUnsentCandidateIds", sorted(omitted));
            result.put("memberProofMapping", List.copyOf(rows));
            result.put("candidateInventoryComplete", catalog.inventoryComplete());
            result.put("retrievalOnly", true); result.put("semanticSupportNotEstablished", true);
            return java.util.Collections.unmodifiableMap(result);
        }

        private Set<Integer> members(Collection<String> ids, int kind) {
            Set<Integer> result = new LinkedHashSet<>();
            for (String id : ids == null ? List.<String>of() : ids) {
                Coverage item = coverage.get(id);
                if (item != null) result.addAll(kind == 0 ? item.mentions() : kind == 1 ? item.bound() : kind == 2 ? item.context() : item.relevant());
            }
            return result;
        }

        private List<String> memberProofs(int member, Set<String> requested) {
            return proofs.spans().stream().filter(proof -> requested.contains(proof.id())
                && coverage.get(proof.id()).relevant().contains(member)).map(ClassificationProofCatalog.ProofSpan::id).toList();
        }

        private String render(String prefix, List<ClassificationProofCatalog.ProofSpan> selected) {
            StringBuilder text = new StringBuilder(prefix);
            if (!prefix.isEmpty() && !prefix.endsWith("\n")) text.append('\n');
            text.append("【授权连续原文；编号只标出处，不证明类别】\n");
            for (var proof : selected.stream().sorted(sourceOrder()).toList()) {
                text.append(proof.id()).append("=c#").append(proof.chunkId()).append('@').append(proof.start())
                    .append(':').append(proof.end()).append(" [").append(proof.docType()).append(':')
                    .append(proof.docId()).append("] ").append(proof.title()).append('：').append(proof.text()).append('\n');
                text.append("检索对应 ").append(proof.id()).append(':')
                    .append(compact(coverage.get(proof.id()).relevant())).append("；名称/关系出处检索定位，不证明类别。\n");
            }
            text.append("【原文选择范围】").append(selected.size()).append('/').append(proofs.spans().size())
                .append("；未展示或未映射不证明排除、类别或不存在。\n");
            return text.toString();
        }

        private String renderMemberCoverage(String prefix, List<ClassificationProofCatalog.ProofSpan> selected) {
            StringBuilder text = new StringBuilder(prefix);
            if (!prefix.isEmpty() && !prefix.endsWith("\n")) text.append('\n');
            String inventory = Objects.toString(catalog.text(), "");
            if (!inventory.isEmpty() && !prefix.contains(inventory)) {
                text.append(inventory); if (!inventory.endsWith("\n")) text.append('\n');
            }
            text.append("【授权连续原文；编号只标出处，不证明类别】\n");
            Set<String> ids = new LinkedHashSet<>();
            for (var proof : selected.stream().sorted(sourceOrder()).toList()) {
                ids.add(proof.id());
                text.append(proof.id()).append("=c#").append(proof.chunkId()).append('@').append(proof.start())
                    .append(':').append(proof.end()).append(" [").append(proof.docType()).append(':')
                    .append(proof.docId()).append("] ").append(proof.title()).append('：').append(proof.text()).append('\n');
            }
            text.append("【原文选择范围】").append(selected.size()).append('/').append(proofs.spans().size())
                .append("；未展示或未映射不证明排除、类别或不存在。\n");
            text.append("【成员可选证明；仅此次已展示】\n")
                .append("[]=此次无已展示对应证明，不等于全局无证据；检索对应不证明类别，不能自动赋予U/I/E。")
                .append("模型须逐成员选择支持其分类组的已送证明。\n");
            for (var candidate : catalog.candidates().stream().sorted(Comparator.comparingInt(GraphClassificationContract.Candidate::number)).toList()) {
                List<String> related = memberProofs(candidate.number(), ids);
                text.append(candidate.number()).append("->").append(related.isEmpty() ? "[]" : String.join(",", related)).append('\n');
            }
            return text.toString();
        }

        private String renderMonotoneCoverage(String prefix, List<ClassificationProofCatalog.ProofSpan> selected) {
            StringBuilder text = new StringBuilder(prefix);
            if (!prefix.isEmpty() && !prefix.endsWith("\n")) text.append('\n');
            String inventory = Objects.toString(catalog.text(), "");
            if (!inventory.isEmpty() && !prefix.contains(inventory)) {
                text.append(inventory); if (!inventory.endsWith("\n")) text.append('\n');
            }
            text.append("【授权连续原文；编号只标出处，不证明类别】\n");
            Set<String> ids = new LinkedHashSet<>();
            for (var proof : selected.stream().sorted(sourceOrder()).toList()) {
                ids.add(proof.id());
                text.append(proof.id()).append("=c#").append(proof.chunkId()).append('@').append(proof.start())
                    .append(':').append(proof.end()).append(" [").append(proof.docType()).append(':')
                    .append(proof.docId()).append("] ").append(proof.title()).append('：').append(proof.text()).append('\n');
            }
            text.append("【原文选择范围】").append(selected.size()).append('/').append(proofs.spans().size())
                .append("；未展示或未映射不证明排除、类别或不存在。\n");
            text.append("【成员可选证明；编号区间逐成员共用右侧证明；仅此次已展示】\n")
                .append("[]=此次无已展示对应证明，不等于全局无证据；检索对应不证明类别，不能自动赋予U/I/E。")
                .append("模型须逐成员选择支持其分类组的已送证明。\n");
            Map<List<String>, Set<Integer>> buckets = new LinkedHashMap<>();
            for (var candidate : catalog.candidates().stream().sorted(Comparator.comparingInt(GraphClassificationContract.Candidate::number)).toList())
                buckets.computeIfAbsent(memberProofs(candidate.number(), ids), ignored -> new LinkedHashSet<>()).add(candidate.number());
            for (var entry : buckets.entrySet()) text.append(compact(entry.getValue())).append("->")
                .append(entry.getKey().isEmpty() ? "[]" : String.join(",", entry.getKey())).append('\n');
            return text.toString();
        }

        /** Length-delimited raw records keep newlines and Unicode source text recoverable without escaping it. */
        private String renderSourceBlocks(String prefix, List<ClassificationProofCatalog.ProofSpan> selected) {
            StringBuilder text = new StringBuilder(prefix);
            if (!prefix.isEmpty() && !prefix.endsWith("\n")) text.append('\n');
            String inventory = Objects.toString(catalog.text(), "");
            if (!inventory.isEmpty() && !prefix.contains(inventory)) {
                text.append(inventory); if (!inventory.endsWith("\n")) text.append('\n');
            }
            text.append("【授权连续原文；块内p#共享c#出处；长度为UTF16字符数；原文不改写】\n");
            Long activeChunk = null;
            Set<String> ids = new LinkedHashSet<>();
            for (var proof : selected.stream().sorted(sourceOrder()).toList()) {
                ids.add(proof.id());
                if (!Objects.equals(activeChunk, proof.chunkId())) {
                    activeChunk = proof.chunkId();
                    text.append("c#").append(proof.chunkId()).append(" [").append(proof.docType()).append(':')
                        .append(proof.docId()).append("] title=").append(escapeSourceHeader(proof.title())).append('\n');
                }
                text.append(proof.id()).append('@').append(proof.start()).append(':').append(proof.end())
                    .append(" chars=").append(proof.text().length()).append('\n').append(proof.text()).append('\n');
            }
            text.append("【原文选择范围】").append(selected.size()).append('/').append(proofs.spans().size())
                .append("；未展示或未映射不证明排除、类别或不存在。\n");
            text.append("【成员可选证明；编号区间逐成员共用右侧证明；仅此次已展示】\n")
                .append("[]=此次无已展示对应证明，不等于全局无证据；检索对应不证明类别，不能自动赋予U/I/E。")
                .append("模型须逐成员选择支持其分类组的已送证明。\n");
            Map<List<String>, Set<Integer>> buckets = new LinkedHashMap<>();
            for (var candidate : catalog.candidates().stream().sorted(Comparator.comparingInt(GraphClassificationContract.Candidate::number)).toList())
                buckets.computeIfAbsent(memberProofs(candidate.number(), ids), ignored -> new LinkedHashSet<>()).add(candidate.number());
            for (var entry : buckets.entrySet()) text.append(compact(entry.getValue())).append("->")
                .append(entry.getKey().isEmpty() ? "[]" : String.join(",", entry.getKey())).append('\n');
            return text.toString();
        }

        private String escapeSourceHeader(String value) {
            return Objects.toString(value, "").replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
        }
    }

    private ClassificationCoverageProofs() { }

    static Prepared prepare(String question, GraphClassificationContract.Catalog catalog, EvidenceLedger ledger) {
        Map<Key, Set<Long>> ranges = new LinkedHashMap<>();
        Map<Long, EvidenceLedger.ChunkFact> chunks = new LinkedHashMap<>();
        int removed = 0;
        for (var chunk : ledger.chunks().stream().sorted(Comparator.comparing(EvidenceLedger.ChunkFact::id)).toList()) {
            chunks.put(chunk.id(), chunk);
            String body = Objects.toString(chunk.text(), "");
            List<Range> units = segment(body);
            for (int index = 0; index < units.size(); index++) {
                Range unit = units.get(index);
                String text = body.substring(unit.start(), unit.end());
                if (!meaningful(text)) { removed++; continue; }
                ranges.putIfAbsent(new Key(chunk.id(), unit.start(), unit.end()), new LinkedHashSet<>());
                // Ordinary neighbouring sentences are independent; only structural context creates a window.
                int from = index;
                while (from > 0 && index - from < 2) {
                    Range previous = units.get(from - 1);
                    String previousText = body.substring(previous.start(), previous.end());
                    if (!meaningful(previousText)) { from--; continue; }
                    if (!structural(previousText)) break;
                    from--;
                }
                if (from < index) {
                    int start = units.get(from).start();
                    // Keep exact continuous source, including intervening table borders; no invented join.
                    ranges.putIfAbsent(new Key(chunk.id(), start, unit.end()), new LinkedHashSet<>());
                }
            }
        }
        for (var edge : ledger.relations().stream().sorted(Comparator.comparing(EvidenceLedger.RelationFact::id)).toList()) {
            String fact = Objects.toString(edge.factText(), "");
            if (!meaningful(fact) || edge.sourceDocId() == null) continue;
            for (var chunk : chunks.values()) {
                if (!sourceMatches(edge, chunk.docType(), chunk.docId(), chunk.id())) continue;
                String body = Objects.toString(chunk.text(), "");
                for (int offset = body.indexOf(fact); offset >= 0; offset = body.indexOf(fact, offset + Math.max(1, fact.length()))) {
                    if (!boundedOccurrence(body, fact, offset)) continue;
                    int end = offset + fact.length();
                    ranges.computeIfAbsent(new Key(chunk.id(), offset, end), ignored -> new LinkedHashSet<>()).add(edge.id());
                    // A contextual continuous span can retain this exact binding as well.
                    for (var entry : ranges.entrySet()) if (entry.getKey().chunkId().equals(chunk.id())
                        && entry.getKey().start() <= offset && entry.getKey().end() >= end) entry.getValue().add(edge.id());
                }
            }
        }
        Set<String> focus = grams(QueryIntent.fromQuestion(question).focusText());
        List<ClassificationProofCatalog.ProofSpan> spans = new ArrayList<>();
        Map<String, Coverage> coverage = new LinkedHashMap<>();
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (Key key : ranges.keySet().stream().sorted(Comparator.comparing(Key::chunkId)
            .thenComparingInt(Key::start).thenComparingInt(Key::end)).toList()) {
            var chunk = chunks.get(key.chunkId());
            String body = Objects.toString(chunk.text(), "").substring(key.start(), key.end());
            String id = "p" + (spans.size() + 1);
            var proof = new ClassificationProofCatalog.ProofSpan(id, chunk.id(), chunk.docType(), chunk.docId(),
                chunk.title(), key.start(), key.end(), body, ranges.get(key));
            Set<Integer> mentions = new LinkedHashSet<>(), bound = new LinkedHashSet<>(), context = new LinkedHashSet<>(), relevant = new LinkedHashSet<>();
            boolean contextual = CONTEXT.matcher(body).find() || intersects(focus, grams(body));
            for (var candidate : catalog.candidates()) if (relevantToMember(candidate, proof, ledger)) {
                relevant.add(candidate.number());
                if (literalInRange(Objects.toString(chunk.text(), ""), candidate.name(), key.start(), key.end())) mentions.add(candidate.number());
                if (boundToMember(candidate, proof, ledger)) bound.add(candidate.number());
                if (contextual) context.add(candidate.number());
            }
            if (relevant.isEmpty()) continue;
            spans.add(proof);
            coverage.put(id, new Coverage(Set.copyOf(mentions), Set.copyOf(bound), Set.copyOf(context), Set.copyOf(relevant)));
            scores.put(id, mentions.size() * 30 + bound.size() * 18 + context.size() * 32);
        }
        return new Prepared(catalog, ClassificationProofCatalog.fromAuthorizedSpans(spans, scores), coverage, removed);
    }

    /** Local member/source relevance only: neither a mention nor a relation proves the requested attribute. */
    static boolean relevantToMember(GraphClassificationContract.Candidate candidate,
            ClassificationProofCatalog.ProofSpan proof, EvidenceLedger ledger) {
        if (candidate == null || proof == null || !meaningful(proof.text())) return false;
        var chunk = ledger.chunk(proof.chunkId()).orElse(null);
        if (chunk == null || !Objects.equals(proof.docType(), chunk.docType()) || !Objects.equals(proof.docId(), chunk.docId())
            || !Objects.equals(proof.title(), chunk.title()) || proof.start() < 0 || proof.end() <= proof.start()
            || proof.end() > Objects.toString(chunk.text(), "").length()
            || !Objects.toString(chunk.text(), "").substring(proof.start(), proof.end()).equals(proof.text())) return false;
        boolean documentBound = false;
        for (Long id : candidate.relationIds()) {
            var edge = ledger.relation(id).orElse(null);
            if (edge == null || !memberEndpoints(candidate, edge)
                || !Objects.equals(edge.sourceDocId(), proof.docType() + ":" + proof.docId())) continue;
            documentBound = true;
            String fact = Objects.toString(edge.factText(), "");
            if (proof.relationIds().contains(id) && sourceMatches(edge, proof.docType(), proof.docId(), proof.chunkId())
                && meaningful(fact) && sourceContainsFact(proof, fact, ledger)) return true;
        }
        if (!documentBound) {
            String anchor = QueryIntent.normalize(candidate.anchorName());
            if (anchor.isEmpty() || !anchor.equals(QueryIntent.normalize(proof.title()))) return false;
            // Identical titles with distinct subject identities cannot silently share a name-only proof.
            long identities = ledger.entities().stream().filter(entity ->
                anchor.equals(QueryIntent.normalize(entity.name()))).map(EvidenceLedger.EntityFact::id).distinct().count();
            if (identities > 1) return false;
        }
        return literalInRange(Objects.toString(chunk.text(), ""), candidate.name(), proof.start(), proof.end());
    }

    private static boolean memberEndpoints(GraphClassificationContract.Candidate candidate, EvidenceLedger.RelationFact edge) {
        return Objects.equals(edge.sourceId(), "OUT".equals(candidate.direction()) ? candidate.anchorId() : candidate.otherId())
            && Objects.equals(edge.targetId(), "OUT".equals(candidate.direction()) ? candidate.otherId() : candidate.anchorId())
            && Objects.equals(edge.type(), candidate.relationType());
    }

    private static boolean boundToMember(GraphClassificationContract.Candidate candidate,
            ClassificationProofCatalog.ProofSpan proof, EvidenceLedger ledger) {
        return candidate.relationIds().stream().filter(proof.relationIds()::contains).map(ledger::relation)
            .flatMap(java.util.Optional::stream).anyMatch(edge -> memberEndpoints(candidate, edge)
                && sourceMatches(edge, proof.docType(), proof.docId(), proof.chunkId())
                && meaningful(edge.factText()) && sourceContainsFact(proof, edge.factText(), ledger));
    }

    private static boolean sourceContainsFact(ClassificationProofCatalog.ProofSpan proof, String fact, EvidenceLedger ledger) {
        var chunk = ledger.chunk(proof.chunkId()).orElse(null);
        if (chunk == null) return false;
        String body = Objects.toString(chunk.text(), "");
        for (int offset = body.indexOf(fact, proof.start()); offset >= 0 && offset + fact.length() <= proof.end();
                offset = body.indexOf(fact, offset + Math.max(1, fact.length())))
            if (boundedOccurrence(body, fact, offset)) return true;
        return false;
    }

    private static boolean sourceMatches(EvidenceLedger.RelationFact edge, String type, Long doc, Long chunk) {
        return Objects.equals(edge.sourceDocId(), type + ":" + doc)
            && (edge.sourceChunkId() == null || Objects.equals(edge.sourceChunkId(), chunk));
    }

    private static boolean literalInRange(String text, String name, int from, int to) {
        if (name == null || name.isBlank()) return false;
        String needle = name.trim();
        // regionMatches preserves source offsets even when Unicode lowercasing changes text length.
        for (int offset = from; offset + needle.length() <= to; offset++)
            if (text.regionMatches(true, offset, needle, 0, needle.length()) && boundedOccurrence(text, needle, offset)) return true;
        return false;
    }

    private static boolean boundedOccurrence(String text, String needle, int offset) {
        boolean ascii = needle.codePoints().allMatch(point -> point < 128);
        return !ascii || (offset == 0 || !continuesBefore(text, offset - 1))
            && (offset + needle.length() == text.length() || !continuesAfter(text, offset + needle.length()));
    }

    private static boolean continuesBefore(String text, int index) {
        char value = text.charAt(index);
        if (value == '.' || value == '-') return index > 0 && tokenPart(text.charAt(index - 1));
        return tokenPart(value);
    }
    private static boolean continuesAfter(String text, int index) {
        char value = text.charAt(index);
        if (value == '.' || value == '-') return index + 1 < text.length() && tokenPart(text.charAt(index + 1));
        return tokenPart(value);
    }

    private static boolean tokenPart(char value) {
        return value < 128 && (Character.isLetterOrDigit(value) || "_+#.-".indexOf(value) >= 0);
    }
    private static boolean meaningful(String value) { return value != null && value.codePoints().anyMatch(Character::isLetterOrDigit); }
    private static boolean structural(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("#") || trimmed.endsWith(":") || trimmed.endsWith("：")
            || trimmed.contains("|") || trimmed.contains("│") || trimmed.contains("├") || trimmed.contains("└");
    }
    private static List<Range> segment(String body) {
        List<Range> result = new ArrayList<>(); int start = 0;
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (character != '\n' && character != '\r' && character != '。' && character != '！' && character != '？') continue;
            int end = character == '\n' || character == '\r' ? index : index + 1;
            addRange(result, body, start, end); start = index + 1;
        }
        addRange(result, body, start, body.length()); return result;
    }
    private static void addRange(List<Range> result, String body, int start, int end) {
        while (start < end && Character.isWhitespace(body.charAt(start))) start++;
        while (end > start && Character.isWhitespace(body.charAt(end - 1))) end--;
        if (end > start) result.add(new Range(start, end));
    }
    private static boolean overlap(ClassificationProofCatalog.ProofSpan first, ClassificationProofCatalog.ProofSpan second) {
        return first.chunkId().equals(second.chunkId()) && first.start() < second.end() && second.start() < first.end();
    }
    private static Comparator<ClassificationProofCatalog.ProofSpan> sourceOrder() {
        return Comparator.comparing(ClassificationProofCatalog.ProofSpan::chunkId)
            .thenComparingInt(ClassificationProofCatalog.ProofSpan::start).thenComparingInt(ClassificationProofCatalog.ProofSpan::end);
    }
    private static int additional(Set<Integer> values, Set<Integer> existing) {
        return (int) values.stream().filter(value -> !existing.contains(value)).count();
    }
    private static Set<String> grams(String text) {
        String value = Objects.toString(text, "").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index + 1 < value.length(); index++) result.add(value.substring(index, index + 2));
        return result;
    }
    private static boolean intersects(Set<String> first, Set<String> second) { return first.stream().anyMatch(second::contains); }
    private static Set<String> safeSet(Collection<String> values) {
        return values == null ? Set.of() : new LinkedHashSet<>(values);
    }
    private static List<Integer> sorted(Collection<Integer> values) { return values.stream().sorted().toList(); }
    private static List<Integer> difference(Set<Integer> available, Set<Integer> sent) {
        return available.stream().filter(member -> !sent.contains(member)).sorted().toList();
    }
    private static String compact(Set<Integer> values) {
        List<Integer> ordered = sorted(values); List<String> ranges = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            int from = ordered.get(index), to = from;
            while (index + 1 < ordered.size() && ordered.get(index + 1) == to + 1) to = ordered.get(++index);
            ranges.add(from == to ? Integer.toString(from) : from + "-" + to);
        }
        return String.join(",", ranges);
    }
    private static void identityPart(StringBuilder target, Object value) {
        String text = Objects.toString(value, ""); target.append(text.length()).append(':').append(text);
    }
    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
