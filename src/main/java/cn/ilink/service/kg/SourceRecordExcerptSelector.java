package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a source-scoped answer for one unambiguously named record.
 *
 * <p>This is the last structured exit before free generation. It is intentionally narrower than a
 * generic document search: the question must name one record, that record must resolve to exactly
 * one authorised document identity, and at least one of its chunks must match the requested focus.
 * The answer quotes those authorised chunks and makes no graph-edge or collection-completeness
 * claim. A missing graph edge therefore does not force a useful source answer into an excerpt or a
 * clarification response.
 */
final class SourceRecordExcerptSelector {
    private static final int MAX_CHUNKS = 6;
    private static final int MAX_TOKENS = 2600;

    record Selection(String title, String docType, Long docId, List<EvidenceLedger.ChunkFact> chunks,
                     String text, int focusMatches) {
        Selection {
            chunks = List.copyOf(chunks);
        }
    }

    private record RecordKey(String docType, Long docId) { }

    private record Candidate(RecordKey key, String title, List<EvidenceLedger.ChunkFact> chunks,
                             int focusMatches) { }

    private SourceRecordExcerptSelector() { }

    static Optional<Selection> select(String question, EvidenceLedger ledger) {
        if (question == null || ledger == null || ledger.chunks().isEmpty()) return Optional.empty();
        QueryIntent intent = QueryIntent.fromQuestion(question);
        if (!intent.graphRequested() || intent.primarySubject() == null || intent.focusText().isBlank())
            return Optional.empty();
        SubjectScopeLedger.Analysis scopes = SubjectScopeLedger.assess(question, ledger);
        // Exact graph subjects continue through the graph/classification paths. This fallback is for
        // requests whose named record is not itself an anchored graph entity.
        if (scopes.subjects().stream().anyMatch(SubjectScopeLedger.SubjectScope::exact))
            return Optional.empty();

        Set<String> focusTerms = focusTerms(intent.focusText());
        if (focusTerms.isEmpty()) return Optional.empty();
        int minimumMatches = focusTerms.size() <= 2 ? 1 : 2;

        Map<RecordKey, List<EvidenceLedger.ChunkFact>> records = new LinkedHashMap<>();
        for (EvidenceLedger.ChunkFact chunk : ledger.chunks().stream()
            .sorted(Comparator.comparing(EvidenceLedger.ChunkFact::id,
                Comparator.nullsLast(Comparator.naturalOrder()))).toList()) {
            if (chunk == null || chunk.docType() == null || chunk.docId() == null
                || chunk.title() == null || chunk.text() == null || chunk.text().isBlank()) continue;
            if (!titleMatches(intent, chunk.title()) || !recordTypeMatches(question, chunk)) continue;
            records.computeIfAbsent(new RecordKey(chunk.docType(), chunk.docId()),
                ignored -> new ArrayList<>()).add(chunk);
        }
        if (records.isEmpty()) return Optional.empty();

        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<RecordKey, List<EvidenceLedger.ChunkFact>> entry : records.entrySet()) {
            List<EvidenceLedger.ChunkFact> relevant = entry.getValue().stream()
                .filter(chunk -> common(focusTerms, chunk.text()) >= minimumMatches)
                .toList();
            if (relevant.isEmpty()) continue;
            Set<String> matched = new LinkedHashSet<>();
            for (EvidenceLedger.ChunkFact chunk : relevant) matched.addAll(grams(chunk.text()));
            matched.retainAll(focusTerms);
            String title = entry.getValue().stream().map(EvidenceLedger.ChunkFact::title)
                .filter(Objects::nonNull).min(Comparator.comparingInt(String::length)).orElse("");
            candidates.add(new Candidate(entry.getKey(), title, relevant, matched.size()));
        }
        if (candidates.isEmpty()) return Optional.empty();
        candidates.sort(Comparator.comparingInt(Candidate::focusMatches).reversed()
            .thenComparing(Candidate::title));
        if (candidates.size() > 1
            && candidates.get(0).focusMatches() == candidates.get(1).focusMatches())
            return Optional.empty();

        Candidate selected = candidates.get(0);
        List<EvidenceLedger.ChunkFact> chunks = selected.chunks().stream()
            .limit(MAX_CHUNKS).toList();
        String text = render(selected, chunks);
        if (EvidenceContext.tokens(text) > MAX_TOKENS) return Optional.empty();
        return Optional.of(new Selection(selected.title(), selected.key().docType(), selected.key().docId(),
            chunks, text, selected.focusMatches()));
    }

    private static String render(Candidate selected, List<EvidenceLedger.ChunkFact> chunks) {
        StringBuilder answer = new StringBuilder("依据《").append(selected.title()).append("》（")
            .append(selected.key().docType()).append(':').append(selected.key().docId())
            .append("）的本次授权原文：\n");
        for (EvidenceLedger.ChunkFact chunk : chunks)
            answer.append("- c#").append(chunk.id()).append("：").append(chunk.text().strip()).append('\n');
        return answer.toString().stripTrailing();
    }

    private static Set<String> grams(String text) {
        String normalized = QueryIntent.normalize(text);
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index + 1 < normalized.length(); index++)
            result.add(normalized.substring(index, index + 2));
        return result;
    }

    /**
     * Do not form a two-character gram across two unrelated focus words. In particular, "技能 代理"
     * must not create the bridge "能代", which would match unrelated prose such as "不能代替".
     */
    private static Set<String> focusTerms(String focusText) {
        Set<String> ignored = Set.of("技能", "技术栈", "图谱", "关联", "相关", "记录",
            "文档", "帖子", "内容", "明细", "开发日志", "有哪些", "什么");
        Set<String> result = new LinkedHashSet<>();
        for (String segment : Objects.toString(focusText, "").split("[\\s，,。；;、]+")) {
            String normalized = QueryIntent.normalize(segment);
            if (normalized.length() < 2 || ignored.contains(normalized)) continue;
            result.addAll(grams(normalized));
        }
        return result.isEmpty() ? grams(QueryIntent.normalize(focusText)) : result;
    }

    private static int common(Set<String> terms, String text) {
        Set<String> matches = grams(text);
        matches.retainAll(terms);
        return matches.size();
    }

    private static boolean titleMatches(QueryIntent intent, String title) {
        if (intent.matchesSubject(title)) return true;
        String requested = QueryIntent.normalize(intent.primarySubject());
        String candidate = QueryIntent.normalize(title);
        return requested.length() >= 4 && candidate.contains(requested);
    }

    private static boolean recordTypeMatches(String question, EvidenceLedger.ChunkFact chunk) {
        if (question.contains("日志"))
            return chunk.title().contains("日志");
        if (question.contains("帖子"))
            return "POST".equals(chunk.docType());
        return true;
    }
}
