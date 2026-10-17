package cn.ilink.service.kg;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** D2 only: the model projection and exact source unions are shared with generation. */
final class ClassificationModelPacket {
    static final String POLICY = "classification-model-claims-v1";

    private ClassificationModelPacket() { }

    static VerificationEvidenceContext.Packet pack(EvidenceLedger ledger, QueryEvidencePlan plan,
            AnswerEvidenceVerifier.VerificationResult checked, GraphClassificationContract.Catalog catalog,
            ClassificationModelContext.Projection projection, String dispositionText,
            ClassificationProofCatalog.ProofCatalog proofs, Collection<String> usedProofIds) {
        if (plan != null && plan.requiresTemporal())
            throw new IllegalArgumentException("CLASSIFICATION_SKIPPED:TEMPORAL_QUERY");
        if (ledger == null || checked == null || catalog == null || projection == null || proofs == null)
            throw new IllegalArgumentException("CLASSIFICATION_MODEL_PACKET_INPUT_REQUIRED");
        List<String> invalidProjection = projection.validate(catalog);
        if (!invalidProjection.isEmpty()) throw new IllegalArgumentException(String.join(";", invalidProjection));
        List<String> invalidProofs = proofs.validate(ledger, usedProofIds);
        if (!invalidProofs.isEmpty()) throw new IllegalArgumentException(String.join(";", invalidProofs));
        Set<Long> typedRelations = new LinkedHashSet<>();
        // Numeric labels may be omitted from the model projection only after typed bindings are checked.
        // Do not use a regex against the projected text or assume an unseen edge is represented.
        for (var candidate : catalog.candidates()) {
            var anchor = ledger.entity(candidate.anchorId()).orElseThrow(() ->
                new IllegalArgumentException("CLASSIFICATION_MODEL_ANCHOR_MISSING:" + candidate.number()));
            var other = ledger.entity(candidate.otherId()).orElseThrow(() ->
                new IllegalArgumentException("CLASSIFICATION_MODEL_MEMBER_MISSING:" + candidate.number()));
            if (!Objects.equals(anchor.name(), candidate.anchorName()) || !Objects.equals(other.name(), candidate.name())
                || !Objects.equals(other.type(), candidate.entityType()))
                throw new IllegalArgumentException("CLASSIFICATION_MODEL_IDENTITY_MISMATCH:" + candidate.number());
            for (Long id : candidate.relationIds()) {
                var edge = ledger.relation(id).orElseThrow(() ->
                    new IllegalArgumentException("UNKNOWN_RELATION:r#" + id));
                Long source = "OUT".equals(candidate.direction()) ? candidate.anchorId() : candidate.otherId();
                Long target = "OUT".equals(candidate.direction()) ? candidate.otherId() : candidate.anchorId();
                if (!Objects.equals(edge.sourceId(), source) || !Objects.equals(edge.targetId(), target)
                    || !Objects.equals(edge.type(), candidate.relationType()))
                    throw new IllegalArgumentException("CLASSIFICATION_BINDING_INVALID:" + candidate.number());
                typedRelations.add(id);
            }
        }
        if (!typedRelations.containsAll(checked.usedRelationIds()))
            throw new IllegalArgumentException("CLASSIFICATION_CHECKED_RELATION_OUTSIDE_CATALOG");
        String disposition = Objects.toString(dispositionText, "");
        if (disposition.isBlank()) throw new IllegalArgumentException("CLASSIFICATION_DISPOSITION_REQUIRED");
        var sources = ClassificationModelContext.sources(proofs, usedProofIds);
        String prefix = projection.text() + "\n【全部候选处置；原文证明仅限当前提议引用的p编号】\n" + disposition + '\n';
        String text = prefix + sources.text();
        // A historical expanded representation is a size comparison, never the current packet budget.
        String expanded = catalog.text() + "\n【全部候选处置及逐断言证明请求】\n" + disposition + '\n'
            + proofs.render(usedProofIds);
        return new VerificationEvidenceContext.Packet(text, EvidenceContext.tokens(text),
            EvidenceContext.tokens(expanded), checked.usedRelationIds().size(), sources.sourceSpanCount(), POLICY);
    }
}
