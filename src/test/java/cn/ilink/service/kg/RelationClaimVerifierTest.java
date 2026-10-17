package cn.ilink.service.kg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelationClaimVerifierTest {
    private EvidenceLedger ledger;

    @BeforeEach
    void setUp() {
        Map<Long, EvidenceLedger.EntityFact> entities = new LinkedHashMap<>();
        add(entities, 42, "LitePT 论文", "POST");
        add(entities, 43, "点云", "SKILL");
        add(entities, 44, "另一篇论文", "POST");
        add(entities, 60, "校园创新赛", "COMPETITION");
        add(entities, 61, "高校创新协会", "ORGANIZATION");
        add(entities, 62, "数据分析", "SKILL");
        add(entities, 63, "陈老师", "TEACHER");
        add(entities, 100, "示例系统", "POST");
        add(entities, 101, "Controller", "SKILL");
        add(entities, 102, "ECharts 6", "SKILL");
        add(entities, 103, "Swagger UI", "SKILL");
        add(entities, 104, "Java", "SKILL");
        add(entities, 105, "JavaScript", "SKILL");
        add(entities, 106, "图表组件", "SKILL");
        add(entities, 107, "图表组件", "SKILL");
        add(entities, 108, "R", "SKILL");
        Map<Long, EvidenceLedger.RelationFact> relations = new LinkedHashMap<>();
        edge(relations, 7, 42, 43, "MENTIONS");
        edge(relations, 8, 60, 61, "HOSTED_BY");
        edge(relations, 9, 60, 62, "MENTIONS");
        edge(relations, 10, 60, 62, "HOSTED_BY");
        edge(relations, 11, 60, 62, "REQUIRES");
        edge(relations, 12, 63, 62, "TEACHES");
        edge(relations, 13, 60, 63, "RELATED_TO");
        edge(relations, 20, 100, 101, "MENTIONS");
        edge(relations, 21, 100, 102, "MENTIONS");
        edge(relations, 22, 100, 103, "MENTIONS");
        edge(relations, 23, 100, 104, "MENTIONS");
        edge(relations, 24, 100, 105, "MENTIONS");
        edge(relations, 25, 100, 107, "MENTIONS");
        ledger = mock(EvidenceLedger.class);
        when(ledger.entities()).thenReturn(entities.values());
        entities.forEach((id, entity) -> when(ledger.entity(id)).thenReturn(Optional.of(entity)));
        relations.forEach((id, relation) -> when(ledger.relation(id)).thenReturn(Optional.of(relation)));
        when(ledger.entity(999L)).thenReturn(Optional.empty());
        when(ledger.relation(999L)).thenReturn(Optional.empty());
        when(ledger.chunk(90L)).thenReturn(Optional.of(new EvidenceLedger.ChunkFact(
            90L, "POST", 1L, "示例系统", "接口文档使用 Swagger UI。", 0, 18)));
    }

    @Test
    void citationPrefixIsNotMistakenForAnEntityNameOrUsedToSkipMemberCoverage() {
        assertTrue(RelationClaimVerifier.verify("- Controller（r#20）", ledger).isEmpty());
        var reasons = RelationClaimVerifier.verify("- Controller与ECharts 6（r#20）", ledger);
        assertTrue(reasons.contains("RELATION_CLAIM_MEMBER_UNSUPPORTED:#102"), reasons.toString());
    }

    @Test
    void existingFactFixtureAndGenericRoleLabelsPass() {
        assertTrue(RelationClaimVerifier.verify(
            "LitePT 论文 #42 提到了点云 #43，原文使用 PointROPE 和 300px。c#99 r#7", ledger).isEmpty());
        assertTrue(RelationClaimVerifier.verify(
            "在主体 #42 的已核对关系中，对象 #43 通过 r#7 关联。", ledger).isEmpty());
    }

    @Test
    void realRelationCannotSupportDifferentNamedObjectInTheSameRow() {
        var reasons = RelationClaimVerifier.verify(
            "* **r#21 ECharts 6 / r#22 Swagger UI**：数据可视化库及 API 文档界面组件【c#99】【r#20】。", ledger);
        assertTrue(reasons.contains("RELATION_ENDPOINT_MISMATCH:r#20"), reasons.toString());
        assertFalse(reasons.contains("RELATION_ENDPOINT_MISMATCH:r#21"), reasons.toString());
        assertFalse(reasons.contains("RELATION_ENDPOINT_MISMATCH:r#22"), reasons.toString());
    }

    @Test
    void groupedReferencesNeedCollectiveCorrespondenceNotOneRelationForEveryObject() {
        assertTrue(RelationClaimVerifier.verify(
            "* ECharts 6 / Swagger UI：界面相关组件【r#21】【r#22】。", ledger).isEmpty());
        assertTrue(RelationClaimVerifier.verify(
            "| 界面组件 | ECharts 6、Swagger UI | r#21、r#22 |", ledger).isEmpty());
    }

    @Test
    void explicitListCannotHideAnUnsupportedMemberBehindAnotherMembersCitation() {
        var reasons = RelationClaimVerifier.verify("* Controller 与 ECharts 6：关联组件【r#20】。", ledger);
        assertTrue(reasons.contains("RELATION_CLAIM_MEMBER_UNSUPPORTED:#102"), reasons.toString());
        assertFalse(reasons.contains("RELATION_CLAIM_MEMBER_UNSUPPORTED:#101"), reasons.toString());
        var terse = RelationClaimVerifier.verify("* Controller 与 ECharts 6，有 r#20。", ledger);
        assertTrue(terse.contains("RELATION_CLAIM_MEMBER_UNSUPPORTED:#102"), terse.toString());
    }

    @Test
    void orderedListPreservesItsMarkerWhileSplittingOrdinaryEnglishSentences() {
        var reasons = RelationClaimVerifier.verify("1. Controller 与 ECharts 6：关联组件【r#20】。", ledger);
        assertTrue(reasons.contains("RELATION_CLAIM_MEMBER_UNSUPPORTED:#102"), reasons.toString());
        assertTrue(RelationClaimVerifier.verify("1. ECharts 6 / Swagger UI：界面组件【r#21】【r#22】。", ledger).isEmpty());
        assertTrue(RelationClaimVerifier.verify("Controller r#20. ECharts 6: r#20.", ledger)
            .contains("RELATION_ENDPOINT_MISMATCH:r#20"));
    }

    @Test
    void explicitTableMemberCoverageUsesTheMemberCell() {
        var reasons = RelationClaimVerifier.verify("| 界面组件 | ECharts 6、Swagger UI | r#21 |", ledger);
        assertTrue(reasons.contains("RELATION_CLAIM_MEMBER_UNSUPPORTED:#103"), reasons.toString());
        assertTrue(RelationClaimVerifier.verify("| 界面组件 | r#21、r#22 | ECharts 6、Swagger UI |", ledger).isEmpty());
    }

    @Test
    void authorizedSourceForAnAdditionalMemberRequiresSemanticReviewInsteadOfAutomaticRejection() {
        assertTrue(RelationClaimVerifier.verify(
            "* ECharts 6 / Swagger UI：界面组件【r#21】【c#90】。", ledger).isEmpty());
        // A source citation does not excuse a relation which corresponds to neither listed member.
        assertTrue(RelationClaimVerifier.verify(
            "* ECharts 6 / Swagger UI：界面组件【r#20】【c#90】。", ledger)
            .contains("RELATION_ENDPOINT_MISMATCH:r#20"));
    }

    @Test
    void sourceRequirementIsNotAutomaticallyAssignedToAnEarlierMentionCitation() {
        var reasons = RelationClaimVerifier.verify(
            "LitePT 论文 #42 提到了点云 #43（r#7），原文要求掌握 Java #104（c#90）。", ledger);
        assertTrue(reasons.isEmpty(), reasons.toString());
        // The helper has not confirmed the source assertion; the semantic verifier must still do so.
        assertTrue(RelationClaimVerifier.verify(
            "校园创新赛 #60 在图谱关联陈老师 #63（r#13），另一项赛事的主办方为高校创新协会 #61（c#90）。", ledger).isEmpty());
    }

    @Test
    void sourceCitationDoesNotExcuseATypeMismatchWhenBothExplicitRolesBindTheEdge() {
        var reasons = RelationClaimVerifier.verify(
            "校园创新赛 #60 需要掌握数据分析 #62（r#9，c#90）。", ledger);
        assertTrue(reasons.contains("RELATION_TYPE_MISMATCH:r#9:REQUIRES"), reasons.toString());
        assertTrue(RelationClaimVerifier.verify(
            "高校创新协会的主办方为校园创新赛【r#8】【c#90】。", ledger)
            .contains("RELATION_ENDPOINT_MISMATCH:r#8"));
    }

    @Test
    void explanatoryTechnologyNamesAreNotAdditionalListMembers() {
        assertTrue(RelationClaimVerifier.verify(
            "* Controller：由 Java 后端使用，图谱关系为 r#20。", ledger).isEmpty());
        assertTrue(RelationClaimVerifier.verify(
            "Controller 与 ECharts 6 可作技术对比，Controller 的图谱关系为 r#20。", ledger).isEmpty());
    }

    @Test
    void referencesDoNotBindNamesFromAnotherSentenceOrRow() {
        assertTrue(RelationClaimVerifier.verify(
            "Controller 由 r#20 关联。\nECharts 6 由 r#21 关联。\nSwagger UI 由 r#22 关联。", ledger).isEmpty());
        var reasons = RelationClaimVerifier.verify(
            "Controller 由 r#20 关联。\nECharts 6 使用 r#20。", ledger);
        assertTrue(reasons.contains("RELATION_ENDPOINT_MISMATCH:r#20"), reasons.toString());
        assertTrue(RelationClaimVerifier.verify("Controller. ECharts 6: r#20.", ledger)
            .contains("RELATION_ENDPOINT_MISMATCH:r#20"));
    }

    @Test
    void synonymousParaphraseWithExplicitEntityIdsPasses() {
        assertTrue(RelationClaimVerifier.verify(
            "该文献 #42 讨论三维点集合 #43，可核查关系 r#7。", ledger).isEmpty());
        assertTrue(RelationClaimVerifier.verify(
            "前端可视化组件 #102 和文档界面 #103，分别见 r#21、r#22。", ledger).isEmpty());
    }

    @Test
    void unresolvableAliasIsLeftForSemanticVerification() {
        assertTrue(RelationClaimVerifier.verify(
            "文献讨论了三维点集合，可核查 r#7。", ledger).isEmpty());
    }

    @Test
    void explicitSubjectCannotBorrowAnotherSubjectsRelation() {
        var reasons = RelationClaimVerifier.verify(
            "另一篇论文 #44 提到了点云 #43，依据 r#7。", ledger);
        assertTrue(reasons.contains("RELATION_SUBJECT_MISMATCH:r#7"), reasons.toString());
    }

    @Test
    void longestCanonicalNameDoesNotTreatJavaScriptAsJava() {
        assertTrue(RelationClaimVerifier.verify("JavaScript 见 r#24。", ledger).isEmpty());
        var reasons = RelationClaimVerifier.verify("JavaScript 见 r#23。", ledger);
        assertTrue(reasons.contains("RELATION_ENDPOINT_MISMATCH:r#23"), reasons.toString());
    }

    @Test
    void ambiguousCanonicalNamesAreNotResolvedByInsertionOrder() {
        assertTrue(RelationClaimVerifier.verify("图表组件见 r#25。", ledger).isEmpty());
        assertTrue(RelationClaimVerifier.verify("* 图表组件：关联组件，见 r#25。", ledger).isEmpty());
        assertTrue(RelationClaimVerifier.verify("* 图表组件 #106：关联组件，见 r#25。", ledger)
            .contains("RELATION_CLAIM_MEMBER_UNSUPPORTED:#106"));
        var reasons = RelationClaimVerifier.verifyClaims(List.of(
            claim("wrong-same-name", RelationClaimVerifier.ClaimType.CLASSIFICATION, 100, 106, 25)), ledger);
        assertTrue(reasons.contains("RELATION_ENDPOINT_MISMATCH:r#25"), reasons.toString());
    }

    @Test
    void mentionDoesNotProveTheStrongerHostedByPredicate() {
        var reasons = RelationClaimVerifier.verify(
            "校园创新赛的主办方为数据分析【r#9】。", ledger);
        assertTrue(reasons.contains("RELATION_TYPE_MISMATCH:r#9:HOSTED_BY"), reasons.toString());
        assertTrue(reasons.contains("RELATION_ENTITY_TYPE_MISMATCH:r#9:TARGET_ORGANIZATION"), reasons.toString());
    }

    @Test
    void hostedByDirectionIsBoundToTheClaimedRoles() {
        assertTrue(RelationClaimVerifier.verify(
            "校园创新赛的主办方为高校创新协会【r#8】。", ledger).isEmpty());
        assertTrue(RelationClaimVerifier.verify(
            "校园创新赛由高校创新协会主办【r#8】。", ledger).isEmpty());
        var reverse = RelationClaimVerifier.verify(
            "高校创新协会的主办方为校园创新赛【r#8】。", ledger);
        assertTrue(reverse.contains("RELATION_ENDPOINT_MISMATCH:r#8"), reverse.toString());
    }

    @Test
    void compatibleHostedByRequiresAndTeachesClaimsPass() {
        assertTrue(RelationClaimVerifier.verifyClaims(List.of(
            claim("host", RelationClaimVerifier.ClaimType.HOSTED_BY, 60, 61, 8),
            claim("requires", RelationClaimVerifier.ClaimType.REQUIRES, 60, 62, 11),
            claim("teaches", RelationClaimVerifier.ClaimType.TEACHES, 63, 62, 12)), ledger).isEmpty());
    }

    @Test
    void storedPredicateWithWrongEndpointTypeCannotValidateAClaim() {
        var reasons = RelationClaimVerifier.verifyClaims(List.of(
            claim("host", RelationClaimVerifier.ClaimType.HOSTED_BY, 60, 62, 10)), ledger);
        assertTrue(reasons.contains("RELATION_ENTITY_TYPE_MISMATCH:r#10:TARGET_ORGANIZATION"), reasons.toString());
    }

    @Test
    void directedPredicateCannotBeReversed() {
        var reasons = RelationClaimVerifier.verifyClaims(List.of(
            claim("host", RelationClaimVerifier.ClaimType.HOSTED_BY, 61, 60, 8)), ledger);
        assertTrue(reasons.contains("RELATION_ENDPOINT_MISMATCH:r#8"), reasons.toString());
    }

    @Test
    void classificationAcceptsIncomingEdgeButCannotBorrowAnotherCandidate() {
        assertTrue(RelationClaimVerifier.verifyClaims(List.of(
            claim("incoming", RelationClaimVerifier.ClaimType.CLASSIFICATION, 102, 100, 21)), ledger).isEmpty());
        var reasons = RelationClaimVerifier.verifyClaims(List.of(
            claim("wrong", RelationClaimVerifier.ClaimType.CLASSIFICATION, 100, 102, 20)), ledger);
        assertTrue(reasons.contains("RELATION_ENDPOINT_MISMATCH:r#20"), reasons.toString());
    }

    @Test
    void unknownRelationAndMissingBindingFailInStructuredProtocol() {
        assertTrue(RelationClaimVerifier.verifyClaims(List.of(
            claim("unknown", RelationClaimVerifier.ClaimType.CLASSIFICATION, 100, 102, 999)), ledger)
            .contains("CLAIM_UNKNOWN_RELATION:r#999"));
        assertTrue(RelationClaimVerifier.verifyClaims(List.of(new RelationClaimVerifier.Claim(
            "missing", RelationClaimVerifier.ClaimType.CLASSIFICATION, 100L, null, Set.of(21L), "")), ledger)
            .contains("CLAIM_BINDING_MISSING"));
    }

    @Test
    void correctMemberBindingDoesNotCertifyCategorySemantics() {
        // These labels intentionally disagree. Only correspondence is certified by this helper.
        var first = new RelationClaimVerifier.Claim("a", RelationClaimVerifier.ClaimType.CLASSIFICATION,
            100L, 101L, Set.of(20L), "该实体属于前端生态");
        var second = new RelationClaimVerifier.Claim("b", RelationClaimVerifier.ClaimType.CLASSIFICATION,
            100L, 101L, Set.of(20L), "该实体属于后端生态");
        assertTrue(RelationClaimVerifier.verifyClaims(List.of(first, second), ledger).isEmpty());
    }

    private static RelationClaimVerifier.Claim claim(String id, RelationClaimVerifier.ClaimType type,
                                                     long source, long target, long relation) {
        return new RelationClaimVerifier.Claim(id, type, source, target, Set.of(relation), "");
    }

    private static void add(Map<Long, EvidenceLedger.EntityFact> entities, long id, String name, String type) {
        entities.put(id, new EvidenceLedger.EntityFact(id, name, type, ""));
    }

    private static void edge(Map<Long, EvidenceLedger.RelationFact> relations, long id,
                             long source, long target, String type) {
        relations.put(id, new EvidenceLedger.RelationFact(id, source, target, type,
            null, null, null, null, ""));
    }
}
