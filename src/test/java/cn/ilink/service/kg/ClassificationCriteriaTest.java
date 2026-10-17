package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClassificationCriteriaTest {
    @Test
    void negativeEcosystemFilterDefinesIncludeRelativeToTheQuestion() {
        var criteria = ClassificationCriteria.fromQuestion(question("不属于 Orion 生态"));
        assertTrue(criteria.explicit()); assertTrue(criteria.negative());
        assertEquals("Orion", criteria.ecosystem());
        assertTrue(criteria.instruction().contains("属于目标生态的技术应为E"));
        assertEquals(List.of("CLASSIFICATION_POLARITY_MISMATCH:GROUP:1"),
            criteria.polarityFailures(partition("I", "Orion生态组件")));
    }

    @Test
    void negativeFilterCanIncludeAnExplicitNonTargetCategory() {
        var criteria = ClassificationCriteria.fromQuestion(question("不属于 Orion 生态"));
        assertTrue(criteria.polarityFailures(partition("I", "非Orion生态组件")).isEmpty());
        assertTrue(criteria.polarityFailures(partition("E", "Orion生态组件")).isEmpty());
    }

    @Test
    void positiveFilterHasTheOppositeDirection() {
        var criteria = ClassificationCriteria.fromQuestion(question("属于 Orion 生态"));
        assertFalse(criteria.negative());
        assertTrue(criteria.polarityFailures(partition("I", "Orion生态组件")).isEmpty());
        assertFalse(criteria.polarityFailures(partition("E", "Orion生态组件")).isEmpty());
        assertFalse(criteria.polarityFailures(partition("I", "非Orion生态组件")).isEmpty());
    }

    @Test
    void ecologyWordsInsideTheRequestedTitleCannotChooseTheCriterion() {
        var criteria = ClassificationCriteria.fromQuestion(
            "《不属于 Java 生态的研究报告》在图谱里关联的技术栈中，哪些属于 Orion 生态？");
        assertTrue(criteria.explicit()); assertFalse(criteria.negative());
        assertEquals("Orion", criteria.ecosystem());
    }

    @Test
    void multipleEcosystemsAreNotReducedToTheLastOne() {
        var criteria = ClassificationCriteria.fromQuestion(question("属于 Orion 生态或属于 Vega 生态"));
        assertFalse(criteria.explicit()); assertEquals("", criteria.ecosystem());
        assertTrue(criteria.polarityFailures(partition("I", "Orion生态组件")).isEmpty());
    }

    @Test
    void compoundNegationIsNotSilentlyFlipped() {
        for (String condition : List.of("不属于非Orion生态", "属于非 Orion 生态", "不是不属于 Orion 生态"))
            assertFalse(ClassificationCriteria.fromQuestion(question(condition)).explicit(), condition);
    }

    @Test
    void syntaxGuardDoesNotGuessTheFactsOfAnIndependentComponent() {
        var criteria = ClassificationCriteria.fromQuestion(question("不属于 Orion 生态"));
        assertTrue(criteria.polarityFailures(partition("I", "独立存储服务")).isEmpty());
        assertTrue(criteria.polarityFailures(partition("E", "独立存储服务")).isEmpty());
        // Passing this local check still needs authorized source and production semantic checks.
    }

    @Test
    void uncertainGroupsNeverBecomeAnOppositeCategory() {
        var criteria = ClassificationCriteria.fromQuestion(question("不属于 Orion 生态"));
        assertTrue(criteria.polarityFailures(partition("U", "待确认")).isEmpty());
    }

    @Test
    void aNonTechnicalQuestionDoesNotInheritTechnologyExclusions() {
        for (String q : List.of("《赛事报告》在图谱里关联的竞赛如何分类？",
            "《赛事报告》在图谱里关联的竞赛中，哪些不属于 Orion 生态？")) {
            var criteria = ClassificationCriteria.fromQuestion(q);
            assertFalse(criteria.instruction().contains("独立数据库"));
            assertTrue(criteria.instruction().contains("用户所问属性"));
            assertFalse(criteria.instruction().contains("不是技术栈"));
        }
    }

    @Test
    void compactWhitespaceAndCaseDoNotAvoidAnExplicitPolarityContradiction() {
        var criteria = ClassificationCriteria.fromQuestion(question("非 Orion 生态"));
        assertFalse(criteria.polarityFailures(partition("I", "orion 生态组件")).isEmpty());
        assertEquals(ClassificationCriteria.POLICY, criteria.metadata().get("policy"));
        assertEquals(criteria.instruction(), criteria.metadata().get("instruction"));
    }

    @Test
    void punctuationInsideAnEcosystemNameIsPreservedInsteadOfChangingTheTarget() {
        for (String target : List.of("C++", "C#", ".NET")) {
            var criteria = ClassificationCriteria.fromQuestion(question("不属于 " + target + " 生态"));
            assertTrue(criteria.explicit(), target);
            assertEquals(target, criteria.ecosystem());
            assertFalse(criteria.polarityFailures(partition("I", target + "生态组件")).isEmpty());
        }
    }

    @Test
    void unrecognizedCategoryLabelReportsAPartialCheckInsteadOfConfirmedConsistency() {
        var criteria = ClassificationCriteria.fromQuestion(question("不属于 Java 生态"));
        var metadata = criteria.polarityMetadata(partition("E", "属于Java生态或无关"));
        assertEquals("PARTIAL_LABEL_CHECK", metadata.get("criterionCheckStatus"));
        assertEquals(false, metadata.get("criterionCheckComplete"));
        assertNull(metadata.get("criterionConsistent"));
        assertEquals(List.of(1), metadata.get("criterionUncheckedGroups"));
    }

    @Test
    void invalidJsonDoesNotReportACompletedDirectionCheck() {
        var criteria = ClassificationCriteria.fromQuestion(question("不属于 Java 生态"));
        var invalid = new GraphClassificationContract.Parsed(List.of(), List.of("CLASSIFICATION_INVALID_JSON"), false, 0);
        var metadata = criteria.polarityMetadata(invalid);
        assertEquals("INVALID_PARTITION", metadata.get("criterionCheckStatus"));
        assertNull(metadata.get("criterionConsistent"));
        assertEquals(false, metadata.get("criterionCheckComplete"));
    }

    @Test
    void recognizedCategoryLabelsRecordOnlyTheirExplicitConflictScope() {
        var criteria = ClassificationCriteria.fromQuestion(question("不属于 Java 生态"));
        var valid = criteria.polarityMetadata(partition("E", "Java生态组件"));
        assertEquals(true, valid.get("criterionConsistent"));
        assertEquals("EXPLICIT_ROLE_LABELS_ONLY", valid.get("criterionCheckScope"));
        assertEquals("NO_EXPLICIT_LABEL_CONFLICT", valid.get("criterionCheckStatus"));
        var invalid = criteria.polarityMetadata(partition("I", "Java生态组件"));
        assertEquals(false, invalid.get("criterionConsistent"));
        assertEquals("EXPLICIT_LABEL_CONFLICT", invalid.get("criterionCheckStatus"));
    }

    @Test
    void validDecisionCodesDoNotCertifySemanticDirectionBeforeVerification() {
        var fixture = GraphClassificationFlowTest.fixture();
        var parsed = GraphClassificationContract.parseDecisions(GraphClassificationFlowTest.PARTITION, fixture.catalog());
        assertTrue(parsed.complete());
        var metadata = ClassificationCriteria.fromQuestion(question("不属于 Orion 生态")).decisionMetadata(parsed, false);
        assertEquals("STATE_REASON_COMPATIBILITY_ONLY", metadata.get("criterionCheckScope"));
        assertEquals("DECISION_CODES_VALID", metadata.get("criterionCheckStatus"));
        assertNull(metadata.get("criterionConsistent")); assertEquals("NOT_VERIFIED", metadata.get("criterionSemanticsStatus"));
    }

    @Test
    void acceptedVerdictIsRecordedSeparatelyFromClosedCodeCompatibility() {
        var fixture = GraphClassificationFlowTest.fixture();
        var parsed = GraphClassificationContract.parseDecisions(GraphClassificationFlowTest.PARTITION, fixture.catalog());
        var metadata = ClassificationCriteria.fromQuestion(GraphClassificationFlowTest.QUESTION).decisionMetadata(parsed, true);
        assertEquals(true, metadata.get("criterionConsistent"));
        assertEquals("VERIFIER_ACCEPTED_I_E_DECISIONS", metadata.get("criterionSemanticsStatus"));
        assertEquals("STATE_REASON_COMPATIBILITY_ONLY", metadata.get("criterionCheckScope"));
    }

    @Test
    void acceptedAllUncertainDispositionCannotCertifyAnyClassificationDirection() {
        var fixture = GraphClassificationFlowTest.fixture();
        var parsed = GraphClassificationContract.parseDecisions(GraphClassificationFlowTest.UNCERTAIN, fixture.catalog());
        assertTrue(parsed.complete());
        var metadata = ClassificationCriteria.fromQuestion(GraphClassificationFlowTest.QUESTION).decisionMetadata(parsed, true);
        assertNull(metadata.get("criterionConsistent"));
        assertEquals("NO_I_E_DECISIONS", metadata.get("criterionSemanticsStatus"));
        assertEquals(false, parsed.diagnosticsMetadata(true).get("uncertaintySemanticallyVerified"));
    }

    private static String question(String condition) {
        return "《应用设计》在图谱里关联的技术栈中，哪些" + condition + "？";
    }

    private static GraphClassificationContract.Parsed partition(String state, String role) {
        var candidate = new GraphClassificationContract.Candidate(1, 1L, 2L, "应用设计", "组件甲",
            "SKILL", "MENTIONS", "OUT", List.of(10L));
        var catalog = new GraphClassificationContract.Catalog(true, "", List.of(candidate), "", Map.of(), true, true, "");
        String raw = "{\"groups\":[{\"s\":\"" + state + "\",\"role\":\"" + role
            + "\",\"ids\":\"1\",\"proofs\":[\"p1\"],\"why\":\""
            + ("U".equals(state) ? "分类证据不足" : "原文明示类别") + "\"}]}";
        var parsed = GraphClassificationContract.parse(raw, catalog);
        assertTrue(parsed.complete(), parsed.failures().toString());
        return parsed;
    }
}
