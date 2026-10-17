package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Lossless proof packing over the already permission-checked ledger; no candidate filtering. */
final class VerificationEvidenceContext {
    record Packet(String text, int tokenCount, int expandedTokenCount, int relationCount,
                  int packedSourceSpanCount, String packingPolicy) {
        Map<String, Object> metadata() {
            return Map.of("text", text, "tokenCount", tokenCount, "expandedTokenCount", expandedTokenCount,
                "relationCount", relationCount, "packedSourceSpanCount", packedSourceSpanCount,
                "budget", EvidenceContext.MAX_BUDGET, "withinBudget", tokenCount <= EvidenceContext.MAX_BUDGET,
                "packingPolicy", packingPolicy);
        }
    }
    private record Span(int start, int end) { }
    private record SourceKey(String document, Long chunk, String text) { }
    private record Group(Long source, String type, java.util.Date from, java.util.Date to, String document) { }

    private VerificationEvidenceContext() { }

    /** A2 only: keep all dispositions, while source text is limited to the actually cited exact spans. */
    static Packet packClaims(EvidenceLedger ledger, QueryEvidencePlan plan,
                             AnswerEvidenceVerifier.VerificationResult checked,
                             String candidateCatalogText, String dispositionText,
                             ClassificationProofCatalog.ProofCatalog proofCatalog,
                             Collection<String> usedProofIds) {
        if (plan != null && plan.requiresTemporal())
            throw new IllegalArgumentException("CLASSIFICATION_SKIPPED:TEMPORAL_QUERY");
        List<String> invalid = proofCatalog.validate(ledger, usedProofIds);
        if (!invalid.isEmpty()) throw new IllegalArgumentException(String.join(";", invalid));
        String inventory = Objects.toString(candidateCatalogText, "");
        StringBuilder prefix = new StringBuilder(inventory);
        if (!inventory.isEmpty() && !inventory.endsWith("\n")) prefix.append('\n');
        prefix.append("【全部候选处置及逐断言证明请求】\n")
            .append(Objects.toString(dispositionText, "")).append('\n');
        // The candidate catalog already contains directed endpoints and relation IDs. Add only missing bindings.
        for (Long id : checked.usedRelationIds().stream().sorted().toList()) {
            if (Pattern.compile("r#" + id + "(?!\\d)").matcher(inventory).find()) continue;
            var edge = ledger.relation(id).orElseThrow(() -> new IllegalArgumentException("UNKNOWN_RELATION:r#" + id));
            prefix.append("r#").append(id).append(" #").append(edge.sourceId()).append(' ')
                .append(ledger.entity(edge.sourceId()).map(EvidenceLedger.EntityFact::name).orElse(""))
                .append(" -[").append(edge.type()).append("]-> #").append(edge.targetId()).append(' ')
                .append(ledger.entity(edge.targetId()).map(EvidenceLedger.EntityFact::name).orElse(""))
                .append(" 来源=").append(Objects.toString(edge.sourceDocId(), "未记录")).append('\n');
        }

        Map<Long, List<ClassificationProofCatalog.ProofSpan>> byChunk = new LinkedHashMap<>();
        for (String id : usedProofIds == null ? List.<String>of() : usedProofIds) {
            var proof = proofCatalog.span(id).orElseThrow();
            List<ClassificationProofCatalog.ProofSpan> existing = byChunk.computeIfAbsent(proof.chunkId(), key -> new ArrayList<>());
            if (existing.stream().noneMatch(item -> item.id().equals(id))) existing.add(proof);
        }
        StringBuilder packed = new StringBuilder(prefix);
        int sourceSpans = 0;
        for (Long chunkId : byChunk.keySet().stream().sorted().toList()) {
            var chunk = ledger.chunk(chunkId).orElseThrow();
            List<ClassificationProofCatalog.ProofSpan> proofs = byChunk.get(chunkId);
            for (Span span : merge(proofs.stream().map(proof -> new Span(proof.start(), proof.end())).toList())) {
                String ids = proofs.stream().filter(proof -> proof.start() >= span.start() && proof.end() <= span.end())
                    .map(ClassificationProofCatalog.ProofSpan::id).distinct().sorted(Comparator
                        .comparingInt(value -> Integer.parseInt(value.substring(1))))
                    .collect(java.util.stream.Collectors.joining(","));
                packed.append("【").append(ids).append(" 的同片段原文跨度合并；不新增断言】\n");
                appendChunk(packed, chunk, span);
                sourceSpans++;
            }
        }
        String expanded = prefix + proofCatalog.render(usedProofIds);
        String text = packed.toString();
        return new Packet(text, EvidenceContext.tokens(text), EvidenceContext.tokens(expanded),
            checked.usedRelationIds().size(), sourceSpans, "classification-claim-spans-v1");
    }

    static Packet pack(EvidenceLedger ledger, QueryEvidencePlan plan,
                       AnswerEvidenceVerifier.VerificationResult checked, GraphAnswerScope.Scope scope,
                       String expandedText) {
        StringBuilder text = new StringBuilder();
        for (Long id : checked.usedEntityIds()) ledger.entity(id).ifPresent(fact ->
            text.append('#').append(id).append(' ').append(fact.name()).append(" [").append(fact.type())
                .append("] 描述=").append(abbreviate(fact.description(), 240)).append('\n'));

        Map<Long, EvidenceLedger.RelationFact> relations = new LinkedHashMap<>();
        for (Long id : checked.usedRelationIds()) ledger.relation(id).ifPresent(fact -> relations.put(id, fact));
        if (plan != null && plan.requiresTemporal()) {
            for (var fact : ledger.relations()) {
                if (fact.sourceChunkId() != null && checked.usedChunkIds().contains(fact.sourceChunkId()))
                    relations.putIfAbsent(fact.id(), fact);
            }
        }
        Map<Group, List<EvidenceLedger.RelationFact>> groups = new LinkedHashMap<>();
        for (var fact : relations.values()) groups.computeIfAbsent(new Group(fact.sourceId(), fact.type(),
            fact.validFrom(), fact.validTo(), fact.sourceDocId()), key -> new ArrayList<>()).add(fact);

        Map<Long, List<Span>> spans = new LinkedHashMap<>();
        Map<SourceKey, String> standalone = new LinkedHashMap<>();
        for (var entry : groups.entrySet()) {
            Group group = entry.getKey();
            text.append("【#").append(group.source()).append(' ')
                .append(ledger.entity(group.source()).map(EvidenceLedger.EntityFact::name).orElse(""))
                .append(" -[").append(group.type()).append("]-> 有效期=[")
                .append(date(group.from())).append(',').append(group.to() == null ? "无已记录终止时间" : date(group.to()))
                .append(") 来源=").append(Objects.toString(group.document(), "未记录"));
            appendWindows(text, group, plan);
            text.append("】\n");
            for (var fact : entry.getValue()) {
                text.append("r#").append(fact.id()).append(" -> #").append(fact.targetId()).append(' ')
                    .append(ledger.entity(fact.targetId()).map(EvidenceLedger.EntityFact::name).orElse(""));
                if (fact.sourceChunkId() != null) text.append(" 来源片段=c#").append(fact.sourceChunkId());
                text.append(" 原文=").append(sourcePointer(fact, ledger, checked, spans, standalone)).append('\n');
            }
        }

        int sourceSpans = 0;
        for (Long id : checked.usedChunkIds()) {
            var chunk = ledger.chunk(id).orElse(null);
            if (chunk == null) continue;
            String body = Objects.toString(chunk.text(), "");
            appendChunk(text, chunk, new Span(0, body.length()));
            sourceSpans++;
        }
        for (var entry : spans.entrySet()) {
            var chunk = ledger.chunk(entry.getKey()).orElseThrow();
            for (Span span : merge(entry.getValue())) {
                appendChunk(text, chunk, span);
                sourceSpans++;
            }
        }
        for (var entry : standalone.entrySet()) {
            SourceKey source = entry.getKey();
            text.append(entry.getValue()).append(" [来源=").append(Objects.toString(source.document(), "未记录"));
            if (source.chunk() != null) text.append(" 来源片段=c#").append(source.chunk());
            text.append("] ").append(source.text()).append('\n');
            sourceSpans++;
        }
        // Keep the whole independent candidate inventory, including every uncited item.
        if (scope.requested()) text.append(scope.text()).append(scope.instruction()).append('\n');
        String packed = text.toString();
        int expandedTokens = EvidenceContext.tokens(expandedText);
        int packedTokens = EvidenceContext.tokens(packed);
        // Packing is an optimization of representation only; never make a small proof larger.
        boolean usePacked = packedTokens < expandedTokens;
        return new Packet(usePacked ? packed : expandedText, usePacked ? packedTokens : expandedTokens,
            expandedTokens, relations.size(), sourceSpans, usePacked ? "exact-source-spans-v1" : "expanded-v1");
    }

    private static String sourcePointer(EvidenceLedger.RelationFact fact, EvidenceLedger ledger,
                                        AnswerEvidenceVerifier.VerificationResult checked,
                                        Map<Long, List<Span>> spans, Map<SourceKey, String> standalone) {
        String body = Objects.toString(fact.factText(), "");
        if (body.isBlank()) return "未记录原文";
        // Use exact substring matching within the SAME source document. No fuzzy joining or new lookup.
        for (var chunk : ledger.chunks().stream().sorted(Comparator.comparing(EvidenceLedger.ChunkFact::id)).toList()) {
            if (fact.sourceDocId() == null || !fact.sourceDocId().equals(chunk.docType() + ":" + chunk.docId())
                || fact.sourceChunkId() != null && !fact.sourceChunkId().equals(chunk.id()) || chunk.text() == null)
                continue;
            int start = chunk.text().indexOf(body);
            if (start < 0) continue;
            Span span = new Span(start, start + body.length());
            if (!checked.usedChunkIds().contains(chunk.id()))
                spans.computeIfAbsent(chunk.id(), key -> new ArrayList<>()).add(span);
            return "c#" + chunk.id() + "@" + span.start() + ":" + span.end();
        }
        SourceKey source = new SourceKey(fact.sourceDocId(), fact.sourceChunkId(), body);
        return standalone.computeIfAbsent(source, key -> "原文段" + (standalone.size() + 1));
    }

    private static List<Span> merge(List<Span> input) {
        List<Span> merged = new ArrayList<>();
        for (Span span : input.stream().sorted(Comparator.comparingInt(Span::start).thenComparingInt(Span::end)).toList()) {
            if (merged.isEmpty() || merged.get(merged.size() - 1).end() < span.start()) merged.add(span);
            else {
                Span previous = merged.remove(merged.size() - 1);
                merged.add(new Span(previous.start(), Math.max(previous.end(), span.end())));
            }
        }
        return merged;
    }

    private static void appendChunk(StringBuilder text, EvidenceLedger.ChunkFact chunk, Span span) {
        String body = Objects.toString(chunk.text(), "");
        text.append("c#").append(chunk.id()).append(" [").append(chunk.docType()).append(':').append(chunk.docId())
            .append(" 段内偏移=").append(span.start()).append(':').append(span.end()).append("] ")
            .append(chunk.title()).append("：").append(body, span.start(), span.end()).append('\n');
    }

    private static void appendWindows(StringBuilder text, Group group, QueryEvidencePlan plan) {
        if (plan == null) return;
        for (KgTimeRange range : plan.timeRanges()) {
            boolean overlaps = group.from() != null && group.from().getTime() < range.endDate().getTime()
                && (group.to() == null || group.to().getTime() > range.startDate().getTime());
            boolean covers = group.from() != null && group.from().getTime() <= range.startDate().getTime()
                && (group.to() == null || group.to().getTime() >= range.endDate().getTime());
            text.append("；程序日期判定：用户窗口[").append(date(range.startDate())).append(',')
                .append(date(range.endDate())).append(")，重叠=").append(overlaps).append("，覆盖整个窗口=").append(covers);
        }
    }

    private static String date(java.util.Date value) {
        return value == null ? "未记录起始时间" : java.time.Instant.ofEpochMilli(value.getTime())
            .atZone(KgTimeRange.ZONE).toLocalDate().toString();
    }
    private static String abbreviate(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
