package cn.ilink.service.kg;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Model representations of immutable typed identities and recoverable authorized source text. */
final class ClassificationModelContext {
    static final String POLICY_PROJECTION = "classification-model-projection-v1";
    static final String POLICY_SOURCES = "classification-source-unions-v1";
    private static final int INVENTORY_BUDGET = 1200;

    record Projection(String text, int tokenCount, int shownCount, boolean complete,
                      String typedCatalogSha256) {
        /** A manually constructed projection cannot replace or partially display a typed catalog. */
        List<String> validate(GraphClassificationContract.Catalog catalog) {
            Projection expected;
            try { expected = project(catalog); }
            catch (IllegalArgumentException e) { return List.of("MODEL_PROJECTION_INVALID_CATALOG:" + e.getMessage()); }
            List<String> failures = new ArrayList<>();
            if (!Objects.equals(text, expected.text())) failures.add("MODEL_PROJECTION_TEXT_MISMATCH");
            if (tokenCount != expected.tokenCount()) failures.add("MODEL_PROJECTION_TOKEN_COUNT_MISMATCH");
            if (shownCount != expected.shownCount()) failures.add("MODEL_PROJECTION_SHOWN_COUNT_MISMATCH");
            if (complete != expected.complete()) failures.add("MODEL_PROJECTION_COMPLETENESS_MISMATCH");
            if (!Objects.equals(typedCatalogSha256, expected.typedCatalogSha256()))
                failures.add("MODEL_PROJECTION_CATALOG_MISMATCH");
            if (!expected.complete()) failures.add("MODEL_PROJECTION_INCOMPLETE");
            return List.copyOf(failures);
        }

        Map<String, Object> metadata() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("policy", POLICY_PROJECTION); result.put("text", text);
            result.put("tokenCount", tokenCount); result.put("shownCount", shownCount);
            result.put("complete", complete); result.put("inventoryBudget", INVENTORY_BUDGET);
            result.put("typedCatalogSha256", typedCatalogSha256);
            result.put("relationBindings", "FULL_TYPED_CATALOG_PROGRAM_VERIFIED");
            result.put("semanticSupportEstablished", false);
            return java.util.Collections.unmodifiableMap(result);
        }
    }

    record Sources(String text, int tokenCount, int sourceCharacters, int sourceSpanCount, int proofCount) {
        Map<String, Object> metadata() {
            return Map.of("packingPolicy", POLICY_SOURCES, "text", text, "tokenCount", tokenCount,
                "sourceCharacters", sourceCharacters, "sourceSpanCount", sourceSpanCount,
                "proofCount", proofCount, "sourceCharacterUnit", "UTF16",
                "semanticSupportEstablished", false);
        }
    }

    private record CandidateIdentity(Long anchor, Long other, String direction, String relationType) { }
    private record RelationIdentity(Long source, Long target, String relationType) { }
    private record CandidateGroup(Long anchor, String anchorName, String direction,
                                  String relationType, String entityType) { }
    private record SourceIdentity(Long chunk, String documentType, Long document, String title) { }
    private record SourceUnion(int start, int end, String text,
                               List<ClassificationProofCatalog.ProofSpan> proofs) { }

    private ClassificationModelContext() { }

    /** Omit repeated numeric relation labels only from the model view, never from typed bindings. */
    static Projection project(GraphClassificationContract.Catalog catalog) {
        if (catalog == null) throw new IllegalArgumentException("NULL_CATALOG");
        Set<CandidateIdentity> identities = new LinkedHashSet<>();
        Map<Long, RelationIdentity> relationIdentities = new LinkedHashMap<>();
        Map<Long, String> entityNames = new LinkedHashMap<>();
        Map<Long, String> otherTypes = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder("【授权候选编号；OUT=主体→候选，IN=候选→主体；关联不证明分类】\n")
            .append("授权范围=仅当前台账；查询完整=").append(catalog.queryComplete())
            .append("；目录完整=").append(catalog.inventoryComplete())
            .append("；目标类型=").append(quote(Objects.toString(catalog.metadata().get("targetType"), "未限定")))
            .append("；请求分类=").append(catalog.requested()).append('\n');
        CandidateGroup previous = null;
        for (int index = 0; index < catalog.candidates().size(); index++) {
            var candidate = catalog.candidates().get(index);
            if (candidate == null || candidate.number() != index + 1)
                throw new IllegalArgumentException("CANDIDATE_NUMBERS_NOT_CONTIGUOUS");
            if (candidate.anchorId() == null || candidate.anchorId() <= 0
                || candidate.otherId() == null || candidate.otherId() <= 0)
                throw new IllegalArgumentException("CANDIDATE_ENTITY_ID_INVALID:" + candidate.number());
            if (blank(candidate.anchorName()) || blank(candidate.name()) || blank(candidate.entityType())
                || blank(candidate.relationType()) || blank(candidate.direction())
                || !Set.of("IN", "OUT").contains(candidate.direction()))
                throw new IllegalArgumentException("CANDIDATE_IDENTITY_INVALID:" + candidate.number());
            if (candidate.relationIds().isEmpty() || candidate.relationIds().stream().anyMatch(id -> id == null || id <= 0)
                || new LinkedHashSet<>(candidate.relationIds()).size() != candidate.relationIds().size())
                throw new IllegalArgumentException("CANDIDATE_RELATION_IDS_INVALID:" + candidate.number());
            if (!identities.add(new CandidateIdentity(candidate.anchorId(), candidate.otherId(),
                candidate.direction(), candidate.relationType())))
                throw new IllegalArgumentException("DUPLICATE_CANDIDATE_IDENTITY:" + candidate.number());
            // The same edge can legitimately appear OUT for one requested subject and IN for another.
            RelationIdentity binding = "OUT".equals(candidate.direction())
                ? new RelationIdentity(candidate.anchorId(), candidate.otherId(), candidate.relationType())
                : new RelationIdentity(candidate.otherId(), candidate.anchorId(), candidate.relationType());
            for (Long relation : candidate.relationIds()) {
                RelationIdentity prior = relationIdentities.putIfAbsent(relation, binding);
                if (prior != null && !prior.equals(binding))
                    throw new IllegalArgumentException("RELATION_ID_BINDING_CONFLICT:" + relation);
            }
            consistent(entityNames, candidate.anchorId(), candidate.anchorName(), "NAME");
            consistent(entityNames, candidate.otherId(), candidate.name(), "NAME");
            consistent(otherTypes, candidate.otherId(), candidate.entityType(), "OTHER_TYPE");
            CandidateGroup group = new CandidateGroup(candidate.anchorId(), candidate.anchorName(),
                candidate.direction(), candidate.relationType(), candidate.entityType());
            if (!group.equals(previous)) {
                text.append("【主体#").append(group.anchor()).append(' ').append(quote(group.anchorName()))
                    .append(' ').append(group.direction()).append(' ').append(quote(group.relationType()))
                    .append(' ').append(quote(group.entityType())).append("】\n");
                previous = group;
            }
            text.append(candidate.number()).append(':').append(quote(candidate.name())).append('\n');
        }
        String rendered = text.toString();
        int tokens = EvidenceContext.tokens(rendered);
        boolean complete = catalog.requested() && catalog.inventoryComplete() && tokens <= INVENTORY_BUDGET;
        return new Projection(rendered, tokens, catalog.candidates().size(), complete, fingerprint(catalog));
    }

    /** Exact overlap/touching unions retain each proof's original local coordinates and body. */
    static Sources sources(ClassificationProofCatalog.ProofCatalog catalog, Collection<String> proofIds) {
        if (catalog == null) throw new IllegalArgumentException("NULL_PROOF_CATALOG");
        Set<String> ids = new LinkedHashSet<>();
        if (proofIds != null) ids.addAll(proofIds);
        Map<Long, SourceIdentity> chunkIdentities = new LinkedHashMap<>();
        Map<SourceIdentity, List<ClassificationProofCatalog.ProofSpan>> bySource = new LinkedHashMap<>();
        for (String id : ids) {
            var proof = catalog.span(id).orElseThrow(() -> new IllegalArgumentException("UNKNOWN_PROOF:" + id));
            if (proof.chunkId() == null || proof.chunkId() <= 0 || proof.docId() == null || proof.docId() <= 0
                || blank(proof.docType()) || proof.title() == null || proof.text() == null
                || proof.start() < 0 || proof.end() <= proof.start()
                || proof.text().length() != proof.end() - proof.start())
                throw new IllegalArgumentException("PROOF_SOURCE_IDENTITY_OR_RANGE_INVALID:" + id);
            SourceIdentity identity = new SourceIdentity(proof.chunkId(), proof.docType(), proof.docId(), proof.title());
            SourceIdentity prior = chunkIdentities.putIfAbsent(proof.chunkId(), identity);
            if (prior != null && !prior.equals(identity))
                throw new IllegalArgumentException("PROOF_CHUNK_METADATA_CONFLICT:c#" + proof.chunkId());
            bySource.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(proof);
        }
        StringBuilder text = new StringBuilder("【授权原文；长度与偏移为UTF16字符数；每个p编号仅定位原文，不证明类别】\n");
        int spans = 0, characters = 0;
        for (var entry : bySource.entrySet().stream().sorted(Comparator.comparingLong(item -> item.getKey().chunk())).toList()) {
            var source = entry.getKey();
            text.append("c#").append(source.chunk()).append(" [").append(quote(source.documentType())).append(':')
                .append(source.document()).append("] title=").append(quote(source.title())).append('\n');
            for (var union : unions(entry.getValue())) {
                text.append('@').append(union.start()).append(':').append(union.end()).append(" chars=")
                    .append(union.text().length()).append(" proofs=");
                text.append(String.join(",", union.proofs().stream().sorted(Comparator.comparingInt(proof ->
                    Integer.parseInt(proof.id().substring(1)))).map(proof -> proof.id() + "@" + proof.start() + ":" + proof.end()).toList()));
                text.append('\n').append(union.text()).append('\n');
                spans++;
                characters = Math.addExact(characters, union.text().length());
            }
        }
        String rendered = text.toString();
        return new Sources(rendered, EvidenceContext.tokens(rendered), characters, spans, ids.size());
    }

    private static List<SourceUnion> unions(List<ClassificationProofCatalog.ProofSpan> input) {
        List<SourceUnion> result = new ArrayList<>();
        for (var proof : input.stream().sorted(Comparator.comparingInt(ClassificationProofCatalog.ProofSpan::start)
            .thenComparingInt(ClassificationProofCatalog.ProofSpan::end).thenComparing(ClassificationProofCatalog.ProofSpan::id)).toList()) {
            if (result.isEmpty() || result.get(result.size() - 1).end() < proof.start()) {
                result.add(new SourceUnion(proof.start(), proof.end(), proof.text(), List.of(proof)));
                continue;
            }
            SourceUnion previous = result.remove(result.size() - 1);
            int overlapEnd = Math.min(previous.end(), proof.end());
            int overlapLength = overlapEnd - proof.start();
            if (overlapLength > 0 && !previous.text().substring(proof.start() - previous.start(),
                overlapEnd - previous.start()).equals(proof.text().substring(0, overlapLength)))
                throw new IllegalArgumentException("PROOF_OVERLAP_TEXT_CONFLICT:" + proof.id());
            List<ClassificationProofCatalog.ProofSpan> members = new ArrayList<>(previous.proofs());
            members.add(proof);
            String body = previous.text();
            if (proof.end() > previous.end()) body += proof.text().substring(previous.end() - proof.start());
            result.add(new SourceUnion(previous.start(), Math.max(previous.end(), proof.end()), body, List.copyOf(members)));
        }
        return List.copyOf(result);
    }

    private static void consistent(Map<Long, String> values, Long id, String value, String field) {
        String previous = values.putIfAbsent(id, value);
        if (previous != null && !previous.equals(value)) throw new IllegalArgumentException("ENTITY_" + field + "_CONFLICT:" + id);
    }

    private static boolean blank(String text) { return text == null || text.isBlank(); }

    private static String quote(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                case '\b' -> text.append("\\b");
                case '\f' -> text.append("\\f");
                default -> {
                    if (character < 0x20) text.append(String.format("\\u%04x", (int) character));
                    else text.append(character);
                }
            }
        }
        return text.append('"').toString();
    }

    private static String fingerprint(GraphClassificationContract.Catalog catalog) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            frame(digest, POLICY_PROJECTION); frame(digest, catalog.requested());
            frame(digest, catalog.skipReason()); frame(digest, catalog.queryComplete());
            frame(digest, catalog.inventoryComplete()); frame(digest, catalog.focusText());
            frame(digest, catalog.text()); frame(digest, catalog.metadata().get("targetType"));
            frame(digest, catalog.candidates().size());
            for (var candidate : catalog.candidates()) {
                frame(digest, candidate.number()); frame(digest, candidate.anchorId()); frame(digest, candidate.otherId());
                frame(digest, candidate.anchorName()); frame(digest, candidate.name()); frame(digest, candidate.entityType());
                frame(digest, candidate.relationType()); frame(digest, candidate.direction());
                frame(digest, candidate.relationIds().size());
                for (Long relation : candidate.relationIds()) frame(digest, relation);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private static void frame(MessageDigest digest, Object value) {
        if (value == null) { digest.update((byte) 0); return; }
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 1);
        digest.update(new byte[] { (byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16),
            (byte) (bytes.length >>> 8), (byte) bytes.length });
        digest.update(bytes);
    }
}
