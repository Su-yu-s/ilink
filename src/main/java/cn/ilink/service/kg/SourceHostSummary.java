package cn.ilink.service.kg;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic host summary from an authorised record's explicit "主办方：" field.
 *
 * <p>This reads data that is already present in a cited source chunk. It never infers a host from a
 * similar name or from the existence of another competition.
 */
final class SourceHostSummary {
    private static final Pattern HOST_FIELD = Pattern.compile(
        "(?:主办方|主办单位)\\s*[：:]\\s*([^；;。\\n]{2,120})");

    record Entry(String subject, String host, Long chunkId, String title) { }

    record Result(List<Entry> entries, String text, Set<Long> chunkIds) {
        Result {
            entries = List.copyOf(entries);
            chunkIds = Set.copyOf(chunkIds);
        }
    }

    private SourceHostSummary() { }

    static Optional<Result> build(String question, EvidenceLedger ledger) {
        if (question == null || ledger == null
            || !(question.contains("主办") || question.contains("举办"))) return Optional.empty();
        List<String> subjects = QueryIntent.subjectRequests(question).subjects();
        if (subjects.size() < 2) return Optional.empty();
        Map<String, EvidenceLedger.ChunkFact> exact = hostChunksBySubject(subjects, ledger);
        if (exact.size() != subjects.size()) return Optional.empty();
        StringBuilder answer = new StringBuilder("依据当前授权可见赛事档案：\n");
        java.util.ArrayList<Entry> entries = new java.util.ArrayList<>();
        java.util.LinkedHashSet<Long> chunks = new java.util.LinkedHashSet<>();
        int index = 0;
        for (String subject : subjects) {
            EvidenceLedger.ChunkFact chunk = exact.get(subject);
            Matcher matcher = HOST_FIELD.matcher(chunk.text());
            if (!matcher.find()) return Optional.empty();
            String host = matcher.group(1).trim().replaceAll("[，,。；;\\s]+$", "");
            if (host.isBlank()) return Optional.empty();
            answer.append(++index).append(". 《").append(chunk.title()).append("》由 ")
                .append(host).append(" 主办（c#").append(chunk.id()).append("）。\n");
            entries.add(new Entry(subject, host, chunk.id(), chunk.title()));
            chunks.add(chunk.id());
        }
        return Optional.of(new Result(entries, answer.toString().stripTrailing(), chunks));
    }

    private static Map<String, EvidenceLedger.ChunkFact> hostChunksBySubject(
        List<String> subjects, EvidenceLedger ledger
    ) {
        Map<String, EvidenceLedger.ChunkFact> selected = new LinkedHashMap<>();
        for (String requested : subjects) {
            List<EvidenceLedger.ChunkFact> exact = ledger.chunks().stream()
                .filter(chunk -> chunk.text() != null
                    && QueryIntent.normalize(requested).equals(QueryIntent.normalize(
                        chunk.title().replaceFirst("（含多个子赛）$", "")))
                    && HOST_FIELD.matcher(chunk.text()).find())
                .sorted(java.util.Comparator.comparing(EvidenceLedger.ChunkFact::id))
                .toList();
            if (exact.size() == 1) selected.put(requested, exact.get(0));
        }
        return selected;
    }
}
