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
import java.util.Optional;
import java.util.Set;

/** Recoverable source spans from the permission-checked ledger, never model-authored evidence. */
final class ClassificationProofCatalog {
    record ProofSpan(String id, Long chunkId, String docType, Long docId, String title,
                     int start, int end, String text, Set<Long> relationIds) {
        ProofSpan { relationIds = Set.copyOf(relationIds); }

        Map<String, Object> metadata() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("proofId", id); result.put("chunkId", chunkId);
            result.put("docType", docType); result.put("docId", docId);
            result.put("title", title);
            result.put("start", start); result.put("end", end); result.put("text", text);
            result.put("relationIds", relationIds.stream().sorted().toList());
            return result;
        }
    }

    record Selection(String text, int tokenCount, List<String> proofIds,
                     int omittedSpanCount, boolean withinBudget) {
        Selection { proofIds = List.copyOf(proofIds); }
        Map<String, Object> metadata() {
            return Map.of("packingPolicy", "classification-source-spans-v1", "tokenCount", tokenCount,
                "proofIds", proofIds, "omittedSpanCount", omittedSpanCount,
                "withinBudget", withinBudget);
        }
    }

    static final class ProofCatalog {
        private final List<ProofSpan> spans;
        private final Map<String, ProofSpan> byId;
        private final Map<String, Integer> relevance;

        private ProofCatalog(List<ProofSpan> spans, Map<String, Integer> relevance) {
            this.spans = List.copyOf(spans);
            this.relevance = Map.copyOf(relevance);
            Map<String, ProofSpan> index = new LinkedHashMap<>();
            for (ProofSpan span : spans) index.put(span.id(), span);
            this.byId = Map.copyOf(index);
        }

        List<ProofSpan> spans() { return spans; }
        Optional<ProofSpan> span(String id) { return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id)); }

        /** Recheck immutable span coordinates against the currently authorized ledger. */
        List<String> validate(EvidenceLedger ledger, Collection<String> proofIds) {
            Set<String> reasons = new LinkedHashSet<>();
            for (String id : safeIds(proofIds)) {
                ProofSpan proof = id == null ? null : byId.get(id);
                if (proof == null) { reasons.add("UNKNOWN_PROOF:" + id); continue; }
                var chunk = ledger.chunk(proof.chunkId()).orElse(null);
                if (chunk == null) { reasons.add("UNAUTHORIZED_PROOF:" + id); continue; }
                String body = Objects.toString(chunk.text(), "");
                if (!Objects.equals(chunk.docType(), proof.docType())
                    || !Objects.equals(chunk.docId(), proof.docId()) || !Objects.equals(chunk.title(), proof.title())) {
                    reasons.add("PROOF_DOCUMENT_MISMATCH:" + id); continue;
                }
                if (proof.start() < 0 || proof.end() <= proof.start() || proof.end() > body.length()) {
                    reasons.add("PROOF_OFFSET_INVALID:" + id); continue;
                }
                if (!body.substring(proof.start(), proof.end()).equals(proof.text()))
                    reasons.add("PROOF_TEXT_MISMATCH:" + id);
            }
            return List.copyOf(reasons);
        }

        /** Prefix contains the complete candidate inventory; it is never removed to fit evidence. */
        Selection select(String prefixText, int totalBudget) {
            int budget = Math.max(0, Math.min(totalBudget, EvidenceContext.MAX_BUDGET));
            String prefix = Objects.toString(prefixText, "");
            List<ProofSpan> selected = new ArrayList<>();
            List<ProofSpan> ordered = spans.stream().sorted(Comparator
                .comparingInt((ProofSpan proof) -> relevance.getOrDefault(proof.id(), 0)).reversed()
                .thenComparingInt(proof -> numericId(proof.id()))).toList();
            // Whole spans only. A long high-score sentence never forces truncation of another sentence.
            for (ProofSpan proof : ordered) {
                // A supplied containing span already gives the same exact source text; don't repeat it.
                if (selected.stream().anyMatch(existing -> Objects.equals(existing.chunkId(), proof.chunkId())
                    && existing.start() <= proof.start() && existing.end() >= proof.end())) continue;
                List<ProofSpan> trial = new ArrayList<>(selected); trial.add(proof);
                if (EvidenceContext.tokens(renderSelection(prefix, trial, spans.size())) <= budget)
                    selected.add(proof);
            }
            selected.sort(Comparator.comparingInt(proof -> numericId(proof.id())));
            String text = renderSelection(prefix, selected, spans.size());
            int tokens = EvidenceContext.tokens(text);
            return new Selection(text, tokens, selected.stream().map(ProofSpan::id).toList(),
                spans.size() - selected.size(), tokens <= budget);
        }

        String render(Collection<String> proofIds) {
            StringBuilder result = new StringBuilder();
            for (ProofSpan proof : usedSpans(proofIds)) appendProof(result, proof);
            return result.toString();
        }

        List<Map<String, Object>> usedMetadata(Collection<String> proofIds) {
            return usedSpans(proofIds).stream().map(ProofSpan::metadata).toList();
        }

        private List<ProofSpan> usedSpans(Collection<String> ids) {
            Set<String> requested = new LinkedHashSet<>(safeIds(ids));
            return spans.stream().filter(span -> requested.contains(span.id())).toList();
        }
    }

    private record Range(int start, int end) { }
    private record Key(Long chunkId, int start, int end) { }

    private ClassificationProofCatalog() { }

    /** Factory for an alternative selector; provenance is rechecked against the ledger by callers. */
    static ProofCatalog fromAuthorizedSpans(List<ProofSpan> spans, Map<String, Integer> relevance) {
        Set<String> ids = new LinkedHashSet<>();
        for (ProofSpan span : spans) {
            if (span == null || span.id() == null || !ids.add(span.id()) || !span.id().matches("p[1-9]\\d{0,8}"))
                throw new IllegalArgumentException("Invalid or duplicate source proof identity");
        }
        return new ProofCatalog(spans, relevance);
    }

    static ProofCatalog prepare(String question, EvidenceLedger ledger, Collection<Long> candidateEntityIds) {
        Set<Long> candidates = candidateEntityIds == null ? Set.of() : new LinkedHashSet<>(candidateEntityIds);
        List<String> names = candidates.stream().map(id -> ledger.entity(id).map(EvidenceLedger.EntityFact::name)
            .orElse("")).filter(name -> !name.isBlank()).sorted().toList();
        Set<String> queryTerms = grams(question);
        Set<String> attributeTerms = grams(QueryIntent.fromQuestion(question).focusText());
        Map<Key, Set<Long>> ranges = new LinkedHashMap<>();
        List<EvidenceLedger.ChunkFact> chunks = ledger.chunks().stream()
            .sorted(Comparator.comparing(EvidenceLedger.ChunkFact::id)).toList();
        Map<Long, List<Range>> segments = new LinkedHashMap<>();
        for (var chunk : chunks) {
            List<Range> sentences = segment(Objects.toString(chunk.text(), ""));
            segments.put(chunk.id(), sentences);
            for (Range sentence : sentences)
                ranges.put(new Key(chunk.id(), sentence.start(), sentence.end()), new LinkedHashSet<>());
        }
        // A relation may bind only to an exact occurrence in its declared source document/chunk.
        for (var relation : ledger.relations().stream().sorted(Comparator.comparing(EvidenceLedger.RelationFact::id)).toList()) {
            String fact = Objects.toString(relation.factText(), "");
            if (fact.isBlank() || relation.sourceDocId() == null) continue;
            for (var chunk : chunks) {
                if (!relation.sourceDocId().equals(chunk.docType() + ":" + chunk.docId())
                    || relation.sourceChunkId() != null && !relation.sourceChunkId().equals(chunk.id())) continue;
                String body = Objects.toString(chunk.text(), "");
                int position = body.indexOf(fact);
                if (position < 0) continue;
                int end = position + fact.length();
                List<Range> containing = segments.get(chunk.id()).stream()
                    .filter(range -> range.end() > position && range.start() < end).toList();
                if (containing.isEmpty()) continue;
                int from = Math.min(position, containing.get(0).start());
                int to = Math.max(end, containing.get(containing.size() - 1).end());
                ranges.computeIfAbsent(new Key(chunk.id(), from, to), key -> new LinkedHashSet<>()).add(relation.id());
                break;
            }
        }
        List<Key> ordered = ranges.keySet().stream().sorted(Comparator.comparing(Key::chunkId)
            .thenComparingInt(Key::start).thenComparingInt(Key::end)).toList();
        Map<Long, EvidenceLedger.ChunkFact> byChunk = new LinkedHashMap<>();
        for (var chunk : chunks) byChunk.put(chunk.id(), chunk);
        List<ProofSpan> spans = new ArrayList<>();
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (Key key : ordered) {
            var chunk = byChunk.get(key.chunkId());
            String body = chunk.text().substring(key.start(), key.end());
            String id = "p" + (spans.size() + 1);
            Set<Long> bound = ranges.get(key);
            ProofSpan proof = new ProofSpan(id, chunk.id(), chunk.docType(), chunk.docId(), chunk.title(),
                key.start(), key.end(), body, bound);
            spans.add(proof);
            int nameMatches = (int) names.stream().filter(name -> containsName(body, name)).count();
            int candidateBindings = (int) bound.stream().map(edge -> ledger.relation(edge).orElse(null))
                .filter(Objects::nonNull).filter(edge -> candidates.contains(edge.sourceId())
                    || candidates.contains(edge.targetId())).count();
            scores.put(id, common(attributeTerms, body) * 8 + common(queryTerms, body) * 2
                + (QueryIntent.fromQuestion(question).matchesSubject(chunk.title()) ? 60 : 0)
                + nameMatches * 24 + candidateBindings * 12);
        }
        return new ProofCatalog(spans, scores);
    }

    private static List<Range> segment(String body) {
        List<Range> result = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < body.length(); index++) {
            char value = body.charAt(index);
            boolean boundary = value == '\n' || value == '\r' || value == '。' || value == '！' || value == '？'
                || (value == '.' || value == '!' || value == '?')
                    && (index + 1 == body.length() || Character.isWhitespace(body.charAt(index + 1)));
            if (!boundary) continue;
            int end = value == '\n' || value == '\r' ? index : index + 1;
            addRange(result, body, start, end);
            start = index + 1;
        }
        addRange(result, body, start, body.length());
        return result;
    }

    private static void addRange(List<Range> result, String body, int start, int end) {
        while (start < end && Character.isWhitespace(body.charAt(start))) start++;
        while (end > start && Character.isWhitespace(body.charAt(end - 1))) end--;
        if (end > start) result.add(new Range(start, end));
    }

    private static String renderSelection(String prefix, List<ProofSpan> selected, int available) {
        StringBuilder text = new StringBuilder(prefix);
        if (!prefix.isEmpty() && !prefix.endsWith("\n")) text.append('\n');
        text.append("【授权原文证明；p#仅是出处，不自动证明分类】\n");
        selected.stream().sorted(Comparator.comparingInt(proof -> numericId(proof.id())))
            .forEach(proof -> appendProof(text, proof));
        return text.append("【原文选择范围】").append(selected.size()).append('/').append(available)
            .append("；未展示原文不证明候选应排除或属性不存在。\n").toString();
    }

    private static void appendProof(StringBuilder text, ProofSpan proof) {
        text.append(proof.id()).append("=c#").append(proof.chunkId()).append('@').append(proof.start())
            .append(':').append(proof.end()).append(" [").append(proof.docType()).append(':')
            .append(proof.docId()).append("] ").append(proof.title()).append("：").append(proof.text()).append('\n');
    }

    private static Collection<String> safeIds(Collection<String> ids) { return ids == null ? List.of() : ids; }
    private static int numericId(String id) { return Integer.parseInt(id.substring(1)); }
    private static boolean containsName(String text, String name) {
        if (name.length() < 2) return false;
        return text.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT));
    }
    private static Set<String> grams(String input) {
        String text = Objects.toString(input, "").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i + 1 < text.length(); i++) result.add(text.substring(i, i + 2));
        return result;
    }
    private static int common(Set<String> terms, String text) {
        Set<String> matches = grams(text); matches.retainAll(terms); return matches.size();
    }
}
