package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selects a high-confidence title slogan from a requested source document.
 *
 * <p>A repeated, short phrase next to the document title is a syntactic property of the source,
 * not an evaluation answer. Requiring the same phrase twice and requiring the question to contain
 * a distinctive part of the chunk title keeps arbitrary short sentences out of deterministic answers.
 */
final class SourcePhraseSelector {
    private static final Pattern EXACT_PHRASE_QUESTION = Pattern.compile(
        "哪(?:句|一?句话)|什么话|原话|原文(?:称|写|说|表述)|怎么表述|如何表述");
    private static final Pattern LEADING_REPEATED_PHRASE = Pattern.compile(
        "^.{0,40}?[—–\\-:：]\\s*([\\p{L}\\p{N}·]{2,20})(?:\\s+|。|，|,)\\s*\\1(?:[。！？!?]|$)");

    record Selection(Long chunkId, String phrase, String title) { }

    private SourcePhraseSelector() { }

    static Optional<Selection> select(String question, EvidenceLedger ledger) {
        if (ledger == null) return Optional.empty();
        return select(question, ledger.chunks());
    }

    static Optional<Selection> select(String question, Iterable<EvidenceLedger.ChunkFact> chunks) {
        if (question == null || chunks == null || !EXACT_PHRASE_QUESTION.matcher(question).find())
            return Optional.empty();
        String normalizedQuestion = QueryIntent.normalize(question);
        Set<String> phrases = new LinkedHashSet<>();
        List<Selection> selections = new ArrayList<>();
        for (EvidenceLedger.ChunkFact chunk : chunks) {
            if (chunk == null || chunk.text() == null || chunk.title() == null) continue;
            String normalizedTitle = QueryIntent.normalize(chunk.title());
            String sharedToken = longestCommonToken(normalizedQuestion, chunk.title());
            if (sharedToken.length() < 3 && !(sharedToken.length() >= 2
                && normalizedTitle.contains(sharedToken))) continue;
            Matcher slogan = LEADING_REPEATED_PHRASE.matcher(chunk.text().trim());
            if (!slogan.find()) continue;
            String phrase = slogan.group(1).trim();
            phrases.add(phrase);
            selections.add(new Selection(chunk.id(), phrase, chunk.title()));
        }
        if (phrases.size() != 1) return Optional.empty();
        String phrase = phrases.iterator().next();
        return selections.stream().filter(item -> phrase.equals(item.phrase()))
            .sorted(java.util.Comparator.comparing(Selection::chunkId,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
            .findFirst();
    }

    static boolean quotesSelection(String answer, Selection selection) {
        if (answer == null || selection == null) return false;
        return QueryIntent.normalize(answer).contains(QueryIntent.normalize(selection.phrase()));
    }

    private static String longestCommonToken(String question, String title) {
        String normalizedQuestion = QueryIntent.normalize(question);
        String normalizedTitle = QueryIntent.normalize(title);
        String best = "";
        for (String token : title.split("[\\s—–\\-_:：/|·]+")) {
            String normalized = QueryIntent.normalize(token);
            if (normalized.length() > best.length() && normalizedQuestion.contains(normalized))
                best = normalized;
        }
        if (best.length() >= 2 || normalizedTitle.length() < 2) return best;
        for (int start = 0; start < normalizedTitle.length(); start++) {
            for (int end = start + 2; end <= normalizedTitle.length(); end++) {
                String token = normalizedTitle.substring(start, end);
                if (token.length() > best.length() && normalizedQuestion.contains(token)) best = token;
            }
        }
        return best;
    }
}
