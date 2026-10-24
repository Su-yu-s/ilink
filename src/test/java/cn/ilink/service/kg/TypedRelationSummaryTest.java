package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TypedRelationSummaryTest {
    private static final String QUESTION =
        "知识图谱里 iLink 平台本身（ilink 实体）直接关联了哪些竞赛？";

    @Test
    void zeroTargetDoesNotPublishAConfidentNegativeFromAnAmbiguousAnchor() {
        EvidenceLedger ledger = ledger(false);
        SubjectScopeLedger.Analysis scopes = scopes(true);

        assertTrue(TypedRelationSummary.build(QUESTION, ledger, scopes).isEmpty(),
            "zero target rows must continue evidence exploration instead of becoming COMPLETE");
    }

    @Test
    void targetRelationsAreListedWithoutMixingInNonTargetRelations() {
        EvidenceLedger ledger = ledger(true);

        TypedRelationSummary.Result summary = TypedRelationSummary.build(
            QUESTION, ledger, scopes(true)).orElseThrow();

        assertEquals(1, summary.targetRelationCount());
        assertEquals(11, summary.nonTargetRelationCount());
        assertTrue(summary.text().contains("蓝桥杯"));
        assertTrue(summary.text().contains("r#956"));
        assertFalse(summary.text().contains("Spring Boot"));
        assertFalse(summary.text().contains("r#945"));
        assertFalse(summary.text().contains("关系记录数为 0"));
    }

    @Test
    void incompleteOrUnresolvedScopeCannotProduceACompleteSummary() {
        EvidenceLedger ledger = ledger(false);

        assertTrue(TypedRelationSummary.build(QUESTION, ledger, scopes(false)).isEmpty());
        SubjectScopeLedger.SubjectScope unresolved = new SubjectScopeLedger.SubjectScope(
            "iLink 平台本身（ilink 实体）", SubjectScopeLedger.Resolution.UNRESOLVED,
            null, null, List.of(), false, List.of(), java.util.Set.of(),
            null, null, List.of());
        SubjectScopeLedger.Analysis unresolvedScopes = new SubjectScopeLedger.Analysis(
            List.of(unresolved), true, "PARSED");
        assertTrue(TypedRelationSummary.build(QUESTION, ledger, unresolvedScopes).isEmpty());
    }

    @Test
    void incompleteLedgerPageCannotBeUpgradedByACompleteSubjectScope() {
        EvidenceLedger ledger = ledger(false);
        when(ledger.completeRelations("expand_neighbors", 1713L)).thenReturn(false);

        SubjectScopeLedger.Analysis assessed = SubjectScopeLedger.assess(QUESTION, ledger);

        assertFalse(assessed.queryComplete());
        assertTrue(TypedRelationSummary.build(QUESTION, ledger, assessed).isEmpty());
    }

    private static SubjectScopeLedger.Analysis scopes(boolean queryComplete) {
        List<Long> relationIds = new ArrayList<>();
        for (long id = 945; id <= 955; id++) relationIds.add(id);
        SubjectScopeLedger.SubjectScope scope = new SubjectScopeLedger.SubjectScope(
            "iLink 平台本身（ilink 实体）", SubjectScopeLedger.Resolution.EXACT,
            1713L, "ilink", List.of(1713L), queryComplete, relationIds,
            java.util.Set.of("MENTIONS"), "ilink", QueryIntent.AliasTier.PARENTHETICAL, List.of());
        return new SubjectScopeLedger.Analysis(List.of(scope), true, "PARSED");
    }

    private static EvidenceLedger ledger(boolean includeCompetition) {
        List<EvidenceLedger.EntityFact> entities = new ArrayList<>();
        entities.add(new EvidenceLedger.EntityFact(1713L, "ilink", "POST", ""));
        List<EvidenceLedger.RelationFact> relations = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            long relationId = 945L + i;
            long entityId = 1714L + i;
            entities.add(new EvidenceLedger.EntityFact(entityId, "Skill " + (i + 1), "SKILL", ""));
            relations.add(new EvidenceLedger.RelationFact(relationId, 1713L, entityId,
                "MENTIONS", null, null, null, null, ""));
        }
        if (includeCompetition) {
            entities.add(new EvidenceLedger.EntityFact(956L, "蓝桥杯", "COMPETITION", ""));
            relations.add(new EvidenceLedger.RelationFact(956L, 1713L, 956L,
                "MENTIONS", null, null, null, null, ""));
        }

        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.entities()).thenReturn(entities);
        when(ledger.relations()).thenReturn(relations);
        for (EvidenceLedger.EntityFact entity : entities) {
            when(ledger.entity(entity.id())).thenReturn(Optional.of(entity));
        }
        for (EvidenceLedger.RelationFact relation : relations) {
            when(ledger.relation(relation.id())).thenReturn(Optional.of(relation));
        }
        when(ledger.completeRelations("expand_neighbors", 1713L)).thenReturn(true);
        return ledger;
    }
}
