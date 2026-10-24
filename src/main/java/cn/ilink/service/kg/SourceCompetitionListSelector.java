package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selects an explicit quoted competition list from the unique authorised source document that
 * contains the requested subject.
 *
 * <p>This exit does not classify names and does not infer competition entities. It restates only
 * quoted items that occur together in the same sentence and have an explicit competition-list
 * context. Conflicting lists, multiple matching documents, or a single quoted item keep the
 * ordinary evidence flow.
 */
final class SourceCompetitionListSelector {
    private static final Pattern QUESTION = Pattern.compile(
        "(?:哪些|哪个|列出|列举).{0,40}(?:竞赛|赛事|比赛|竞赛目录)"
            + "|(?:竞赛|赛事|比赛).{0,20}(?:有哪些|列出|列举)");
    private static final Pattern QUOTED_ITEM = Pattern.compile(
        "[「《“\\\"]([^」》”\\\"]{2,40})[」》”\\\"]");
    private static final Pattern COMPETITION_CONTEXT = Pattern.compile(
        "(?i)competition|竞赛|赛事|比赛|大赛|赛事目录|竞赛目录");
    private static final Pattern LIST_CONTEXT = Pattern.compile(
        "竞赛目录|赛事目录|赛事|竞赛|比赛|大赛");
    private static final Pattern FRAGMENT = Pattern.compile("[^。；;\\n]+[。；;]?");

    record Selection(long chunkId, String title, List<String> items, String sourceSentence) {
        Selection {
            items = List.copyOf(items);
        }
    }

    private SourceCompetitionListSelector() { }

    static Optional<Selection> select(String question, EvidenceLedger ledger) {
        if (question == null || ledger == null || ledger.chunks().isEmpty()
            || !QUESTION.matcher(question).find()) {
            return Optional.empty();
        }
        QueryIntent intent = QueryIntent.fromQuestion(question);
        if (intent.subjects().size() != 1) return Optional.empty();
        String requested = intent.primarySubject();
        if (requested == null || QueryIntent.normalize(requested).length() < 3) return Optional.empty();

        Map<String, List<EvidenceLedger.ChunkFact>> byRecord = new LinkedHashMap<>();
        for (EvidenceLedger.ChunkFact chunk : ledger.chunks().stream()
            .sorted(java.util.Comparator.comparing(EvidenceLedger.ChunkFact::id)).toList()) {
            if (chunk == null || chunk.text() == null || chunk.docType() == null) continue;
            String title = chunk.title() == null ? "" : chunk.title();
            String source = title + "\n" + chunk.text();
            if (!sourceSupportsSubject(question, source)) continue;
            byRecord.computeIfAbsent(chunk.docType() + ":" + chunk.docId(),
                ignored -> new ArrayList<>()).add(chunk);
        }
        List<Selection> candidates = new ArrayList<>();
        for (List<EvidenceLedger.ChunkFact> recordChunks : byRecord.values()) {
            for (EvidenceLedger.ChunkFact chunk : recordChunks) {
                Matcher fragments = FRAGMENT.matcher(chunk.text());
                while (fragments.find()) {
                    String sentence = fragments.group().strip();
                    if (!LIST_CONTEXT.matcher(sentence).find()) continue;
                    List<String> items = quotedItems(sentence);
                    if (items.size() >= 2) {
                        candidates.add(new Selection(chunk.id(), chunk.title(), items, sentence));
                    }
                }
            }
        }
        if (candidates.isEmpty()) return Optional.empty();

        Map<String, List<Selection>> byList = new LinkedHashMap<>();
        for (Selection candidate : candidates) {
            String key = candidate.items().stream().map(SourceCompetitionListSelector::normalize)
                .collect(java.util.stream.Collectors.joining("\u0000"));
            byList.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
        }
        if (byList.size() != 1) return Optional.empty();
        return byList.values().iterator().next().stream()
            .min(java.util.Comparator.comparingLong(Selection::chunkId));
    }

    private static List<String> quotedItems(String sentence) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        Matcher matcher = QUOTED_ITEM.matcher(sentence);
        while (matcher.find()) {
            String item = matcher.group(1).strip();
            // A quote adjacent to another quote is a list item. A whole quoted sentence is not.
            if (!item.isEmpty() && !item.matches("(?s).*[，,。；;:：].*")) items.add(item);
        }
        return List.copyOf(items);
    }

    private static boolean sourceSupportsSubject(String question, String source) {
        String normalizedQuestion = QueryIntent.normalize(question);
        String normalizedSource = QueryIntent.normalize(source);
        if (normalizedQuestion.isEmpty() || normalizedSource.isEmpty()) return false;
        for (String subject : QueryIntent.subjectRequests(question).subjects()) {
            for (String alias : QueryIntent.aliasForms(subject)) {
                if (alias.length() >= 3 && normalizedSource.contains(alias)) return true;
            }
        }
        // Titles in source documents may omit the same generic suffix as the question.
        String requested = QueryIntent.subjectRequests(question).subjects().stream()
            .map(SourceCompetitionListSelector::descriptiveCore)
            .filter(value -> value.length() >= 3)
            .findFirst().orElse("");
        if (!requested.isEmpty() && normalizedSource.contains(requested)) return true;
        for (String token : source.split("[\\s—–\\-_:：/|·（）()]+")) {
            String normalized = QueryIntent.normalize(token);
            if (normalized.length() >= 4 && normalizedQuestion.contains(normalized)
                && !COMPETITION_CONTEXT.matcher(token).find()) return true;
        }
        return false;
    }

    private static String descriptiveCore(String subject) {
        return QueryIntent.normalize(subject)
            .replaceFirst("(?:平台本身|平台|系统|项目|实体|记录|文档|帖子)$", "");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
