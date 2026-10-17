package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Protocol and identity regression source; no model or database access. */
class GraphClassificationContractTest {
    private static final String TITLE = "分析报告";
    private static final String QUESTION = "《" + TITLE + "》在图谱里关联的技术栈中，哪些属于所问生态？";

    @Test
    void mixedSubjectRenderingKeepsTheAnchorOfEveryCandidateVisible() {
        String question = "《报告甲》与《报告乙》在图谱里关联类别如何分类？";
        var ledger = fixture(List.of(entity(1, "报告甲", "POST"), entity(2, "报告乙", "POST")),
            List.of(relation(10, 1, 2, "RELATED_TO")));
        var catalog = GraphClassificationContract.prepare(question, QueryEvidencePlan.fromQuestion(question), ledger);
        var parsed = GraphClassificationContract.parse("{\"groups\":[{\"s\":\"U\",\"role\":\"待确认\",\"ids\":\"1-2\",\"proofs\":[],\"why\":\"分类证据不足\"}]}", catalog);
        assertTrue(parsed.complete(), parsed.failures().toString());
        var answer = GraphClassificationContract.render(catalog, parsed, ledger);
        assertTrue(answer.answer().contains("《报告甲》：报告乙"));
        assertTrue(answer.answer().contains("《报告乙》：报告甲"));
        assertEquals("PARTIAL", answer.coverage());
    }

    @Test
    void temporalSkipTakesPriorityEvenWhenClassificationAndRecommendationWordsArePresent() {
        String question = "2026年3月，《分析报告》在图谱中关联哪些技术栈，推荐如何分类？";
        GraphClassificationContract.Catalog catalog = GraphClassificationContract.prepare(question,
            QueryEvidencePlan.fromQuestion(question), fixture(List.of(entity(1, TITLE, "POST")), List.of()));
        assertFalse(catalog.requested());
        assertEquals("TEMPORAL_QUERY", catalog.skipReason());
        assertTrue(catalog.candidates().isEmpty());
    }

    @Test
    void ordinaryRagAndGraphHostLookupDoNotBecomeClassificationQueries() {
        assertEquals("NOT_GRAPH_CLASSIFICATION", GraphClassificationContract.skipReason(
            "《分析报告》的技术栈有哪些？", QueryEvidencePlan.fromQuestion("《分析报告》的技术栈有哪些？")));
        String question = "图谱中，校园创新赛由什么组织主办？";
        assertEquals("NOT_GRAPH_CLASSIFICATION", GraphClassificationContract.skipReason(question,
            QueryEvidencePlan.fromQuestion(question)));
    }

    @Test
    void recommendationSkipsClassificationWithoutWeakeningTemporalPriority() {
        String question = "请推荐适合我的竞赛，图谱里按技能分类";
        assertEquals("RECOMMENDATION_QUERY", GraphClassificationContract.skipReason(question,
            QueryEvidencePlan.fromQuestion(question)));
    }

    @Test
    void candidateNumbersDoNotDependOnInputRelationOrderAndKeepDuplicateMappings() {
        List<EvidenceLedger.EntityFact> entities = List.of(entity(1, TITLE, "POST"),
            entity(2, "图表组件", "SKILL"), entity(3, "服务框架", "SKILL"));
        EvidenceLedger.RelationFact first = relation(11, 1, 2, "MENTIONS");
        EvidenceLedger.RelationFact duplicate = relation(10, 1, 2, "MENTIONS");
        EvidenceLedger.RelationFact second = relation(12, 1, 3, "MENTIONS");
        var forward = catalog(fixture(entities, List.of(first, second, duplicate)));
        var reversed = catalog(fixture(entities, List.of(second, duplicate, first)));
        assertEquals(forward.candidates(), reversed.candidates());
        assertEquals(2, forward.candidates().size());
        assertEquals(List.of(10L, 11L), forward.candidates().get(0).relationIds());
        assertTrue(forward.text().contains("1:图表组件 r#10,r#11"));
        assertTrue(forward.inventoryComplete());
        assertTrue(forward.queryComplete());
    }

    @Test
    void sameEndpointWithDifferentPredicateOrDirectionHasSeparateCandidateIdentity() {
        EvidenceLedger ledger = fixture(List.of(entity(1, TITLE, "POST"), entity(2, "图表组件", "SKILL")),
            List.of(relation(10, 1, 2, "MENTIONS"), relation(11, 2, 1, "MENTIONS"),
                relation(12, 1, 2, "RELATED_TO")));
        var result = catalog(ledger);
        assertEquals(3, result.candidates().size());
        assertEquals(List.of("IN", "OUT", "OUT"), result.candidates().stream()
            .map(GraphClassificationContract.Candidate::direction).toList());
        assertEquals(List.of("MENTIONS", "MENTIONS", "RELATED_TO"), result.candidates().stream()
            .map(GraphClassificationContract.Candidate::relationType).toList());
    }

    @Test
    void sharedEdgeHasSeparateAnchorPerspectiveForBothRequestedSubjects() {
        String question = "《报告甲》与《报告乙》在图谱里关联类别如何分类？";
        var result = GraphClassificationContract.prepare(question, QueryEvidencePlan.fromQuestion(question),
            fixture(List.of(entity(1, "报告甲", "POST"), entity(2, "报告乙", "POST")),
                List.of(relation(10, 1, 2, "RELATED_TO"))));
        assertTrue(result.requested());
        assertEquals(2, result.candidates().size());
        assertEquals(List.of(1L, 2L), result.candidates().stream()
            .map(GraphClassificationContract.Candidate::anchorId).toList());
        assertEquals(List.of("OUT", "IN"), result.candidates().stream()
            .map(GraphClassificationContract.Candidate::direction).toList());
    }

    @Test
    void targetTypeComesFromFocusRatherThanWordsInsideSubjectTitle() {
        String question = "《技能竞赛分析报告》在图谱中关联的组织如何分类？";
        var result = GraphClassificationContract.prepare(question, QueryEvidencePlan.fromQuestion(question),
            fixture(List.of(entity(1, "技能竞赛分析报告", "POST"), entity(2, "图表组件", "SKILL"),
                    entity(3, "研究协会", "ORGANIZATION")),
                List.of(relation(10, 1, 2, "MENTIONS"), relation(11, 1, 3, "RELATED_TO"))));
        assertEquals(1, result.candidates().size());
        assertEquals("研究协会", result.candidates().get(0).name());
    }

    @Test
    void expiredOrFutureEdgesAndUnauthorizedMissingEndpointsAreNotCandidates() {
        Date past = date(LocalDate.of(2000, 1, 1));
        Date future = date(LocalDate.of(2100, 1, 1));
        var result = catalog(fixture(List.of(entity(1, TITLE, "POST"), entity(2, "图表组件", "SKILL")),
            List.of(relation(10, 1, 2, "MENTIONS"), timedRelation(11, 1, 2, future, null),
                timedRelation(12, 1, 2, null, past), relation(13, 1, 99, "MENTIONS"))));
        assertEquals(1, result.candidates().size());
        assertEquals(List.of(10L), result.candidates().get(0).relationIds());
    }

    @Test
    void unresolvedOrSameNameAmbiguousSubjectCannotBorrowAnotherScope() {
        var unresolved = catalog(fixture(List.of(entity(1, TITLE + "附录", "POST"),
            entity(2, "图表组件", "SKILL")), List.of(relation(10, 1, 2, "MENTIONS"))));
        assertFalse(unresolved.queryComplete());
        assertTrue(unresolved.candidates().isEmpty());
        var ambiguous = catalog(fixture(List.of(entity(1, TITLE, "POST"), entity(3, TITLE, "POST"),
            entity(2, "图表组件", "SKILL")), List.of(relation(10, 1, 2, "MENTIONS"))));
        assertFalse(ambiguous.queryComplete());
        assertTrue(ambiguous.candidates().isEmpty());
    }

    @Test
    void incompleteQueryIsIndependentOfCompleteDisplayedInventory() {
        EvidenceLedger ledger = basicLedger(2);
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(false);
        var result = catalog(ledger);
        assertFalse(result.queryComplete());
        assertTrue(result.inventoryComplete());
        assertEquals(2, result.candidates().size());
    }

    @Test
    void oversizedInventoryKeepsAllIdentitiesInMetadataAndRejectsGenerationProtocol() {
        List<EvidenceLedger.EntityFact> entities = new ArrayList<>(List.of(entity(1, TITLE, "POST")));
        List<EvidenceLedger.RelationFact> relations = new ArrayList<>();
        for (int number = 0; number < 180; number++) {
            entities.add(entity(number + 2, "用于独立数据处理与异步服务协作的超长组件名称" + number, "SKILL"));
            relations.add(relation(number + 10, 1, number + 2, "MENTIONS"));
        }
        var result = catalog(fixture(entities, relations));
        assertTrue(result.queryComplete());
        assertFalse(result.inventoryComplete());
        assertEquals(180, result.candidates().size());
        assertEquals(180, ((List<?>) result.metadata().get("candidates")).size());
        assertTrue(EvidenceContext.tokens(result.text()) <= 1200);
        assertFalse(GraphClassificationContract.parse(json("I", "组件", "1-180", "p1", "原文支持类别"), result).complete());
    }

    @Test
    void compactRangesAndIntegerArraysProduceTheSameExhaustivePartition() {
        var catalog = catalog(basicLedger(4));
        String ranges = "{\"groups\":[" + group("I", "图表组件", "\"1-3\"", "[\"p1\"]", "原文明确类别")
            + "," + group("E", "其他类别", "[4]", "[\"p2\"]", "原文证明范围不同") + "]}";
        var result = GraphClassificationContract.parse(ranges, catalog);
        assertTrue(result.complete());
        assertEquals(List.of(1, 2, 3), result.groups().get(0).ids());
        assertEquals(0, result.uncertainCount());
    }

    @Test
    void omissionIsNeverAutomaticallyRepaired() {
        var result = GraphClassificationContract.parse(json("I", "组件", "1", "p1", "原文支持类别"),
            catalog(basicLedger(2)));
        assertFalse(result.complete());
        assertTrue(result.failures().contains("CLASSIFICATION_OMITTED_CANDIDATE:2"));
        assertEquals(List.of(1), result.groups().get(0).ids());
    }

    @Test
    void duplicateDispositionInOneRangeOrAcrossStatesIsRejected() {
        var catalog = catalog(basicLedger(2));
        assertFalse(GraphClassificationContract.parse(json("I", "组件", "1-2,2", "p1", "原文支持类别"), catalog).complete());
        String duplicate = "{\"groups\":[" + group("I", "组件", "[1,2]", "[\"p1\"]", "原文支持类别")
            + "," + group("E", "其他类别", "[2]", "[\"p2\"]", "不符合属性") + "]}";
        assertTrue(GraphClassificationContract.parse(duplicate, catalog).failures()
            .contains("CLASSIFICATION_DUPLICATE_CANDIDATE:2"));
    }

    @Test
    void unknownNegativeFractionalAndOverflowCandidateIdsAreRejected() {
        var catalog = catalog(basicLedger(1));
        for (String ids : List.of("[2]", "[-1]", "[1.0]", "[2147483648]", "\"1-999999999\"", "\"2-1\"")) {
            String raw = "{\"groups\":[" + group("I", "组件", ids, "[\"p1\"]", "原文支持类别") + "]}";
            assertFalse(GraphClassificationContract.parse(raw, catalog).complete(), ids);
        }
    }

    @Test
    void includeAndExcludeRequireProofsAndNonemptyShortReasons() {
        var catalog = catalog(basicLedger(1));
        for (String state : List.of("I", "E")) {
            assertFalse(GraphClassificationContract.parse("{\"groups\":["
                + group(state, "组件", "[1]", "[]", "原文支持类别") + "]}", catalog).complete());
            assertFalse(GraphClassificationContract.parse(json(state, "组件", "1", "p1", ""), catalog).complete());
        }
    }

    @Test
    void validUncertainDispositionRemainsPartialRatherThanFabricatingExclusion() {
        var catalog = catalog(basicLedger(1));
        var result = GraphClassificationContract.parse("{\"groups\":["
            + group("U", "待确认", "[1]", "[]", "现有证据不足，需补充原文") + "]}", catalog);
        assertTrue(result.complete());
        assertEquals(1, result.uncertainCount());
        var rendered = GraphClassificationContract.render(catalog, result, basicLedger(1));
        assertEquals("PARTIAL", rendered.coverage());
        assertEquals("CLASSIFICATION_UNCERTAIN_CANDIDATES", rendered.coverageReason());
        assertTrue(rendered.answer().contains("待确认"));
        assertTrue(GraphClassificationContract.claims(catalog, result).isEmpty());
    }

    @Test
    void uncertainStateCannotHideAnAffirmativeFactOrUseAConfidentCategoryLabel() {
        var catalog = catalog(basicLedger(1));
        assertFalse(GraphClassificationContract.parse("{\"groups\":["
            + group("U", "待确认", "[1]", "[]", "证据不足，但采用某语言实现") + "]}", catalog).complete());
        assertFalse(GraphClassificationContract.parse("{\"groups\":["
            + group("U", "属于服务框架", "[1]", "[]", "现有证据不足") + "]}", catalog).complete());
    }

    @Test
    void unknownProofExistenceIsLeftForActualSelectionCheckButMalformedProofIdIsRejected() {
        var catalog = catalog(basicLedger(1));
        assertTrue(GraphClassificationContract.parse(json("I", "组件", "1", "p999", "原文支持类别"), catalog).complete());
        for (String proof : List.of("r#10", "p0", "p-1", "https://example.test/proof"))
            assertFalse(GraphClassificationContract.parse(json("I", "组件", "1", proof, "原文支持类别"), catalog).complete());
    }

    @Test
    void invalidJsonFencesTrailingTextDuplicateFieldsAndFreeNameFieldsAreRejected() {
        var catalog = catalog(basicLedger(1));
        String valid = json("I", "组件", "1", "p1", "原文支持类别");
        for (String raw : List.of("```json\n" + valid + "\n```", valid + " trailing", valid + "{}",
            "{\"groups\":[],\"groups\":[]}",
            valid.replace("\"role\":\"组件\"", "\"role\":\"组件\",\"name\":\"伪造名称\""),
            valid.replace("\"s\":\"I\"", "\"s\":\"INCLUDE\"")))
            assertFalse(GraphClassificationContract.parse(raw, catalog).complete(), raw);
    }

    @Test
    void emptyOrTruncatedResponseDoesNotBecomeAnEmptySuccessfulClassification() {
        var catalog = catalog(basicLedger(2));
        for (String raw : List.of("", "{\"groups\":[", "{\"groups\":[]}")) {
            var result = GraphClassificationContract.parse(raw, catalog);
            assertFalse(result.complete());
            assertEquals("PARTIAL", GraphClassificationContract.render(catalog, result, basicLedger(2)).coverage());
        }
    }

    @Test
    void roleAndReasonLengthAndCitationInjectionHaveHardBoundaries() {
        var catalog = catalog(basicLedger(1));
        for (String role : List.of("组".repeat(25), "组件 r#10", "组件\n额外说明", "<script>"))
            assertFalse(GraphClassificationContract.parse(json("I", role, "1", "p1", "原文支持类别"), catalog).complete());
        assertFalse(GraphClassificationContract.parse(json("I", "组件", "1", "p1", "理".repeat(81)), catalog).complete());
    }

    @Test
    void reasonDiagnosticsLocateEveryOversizedGroupWithoutLosingCompatibilityFailure() {
        var catalog = catalog(basicLedger(2));
        String raw = "{\"groups\":[" + group("I", "组件", "[1]", "[\"p1\"]", "理".repeat(87))
            + "," + group("E", "其他类别", "[2]", "[\"p2\"]", "理".repeat(130)) + "]}";
        var parsed = GraphClassificationContract.parse(raw, catalog);
        assertFalse(parsed.complete());
        assertTrue(parsed.failures().contains("CLASSIFICATION_INVALID_REASON"));
        assertTrue(parsed.failures().contains("CLASSIFICATION_GROUP:1:WHY:CODEPOINT_LIMIT:87>80"));
        assertTrue(parsed.failures().contains("CLASSIFICATION_GROUP:2:WHY:CODEPOINT_LIMIT:130>80"));
        assertEquals(List.of("CLASSIFICATION_GROUP:1:WHY:CODEPOINT_LIMIT:87>80",
            "CLASSIFICATION_GROUP:2:WHY:CODEPOINT_LIMIT:130>80"),
            parsed.diagnosticsMetadata().get("groupDiagnostics"));
    }

    @Test
    void reasonAndRoleDiagnosticsDistinguishTypesBlankValuesAndForbiddenText() {
        var catalog = catalog(basicLedger(1));
        var typed = GraphClassificationContract.parse(json("I", "组件", "1", "p1", "原文支持类别")
            .replace("\"role\":\"组件\"", "\"role\":123")
            .replace("\"why\":\"原文支持类别\"", "\"why\":null"), catalog);
        assertTrue(typed.failures().contains("CLASSIFICATION_GROUP:1:ROLE:TYPE"));
        assertTrue(typed.failures().contains("CLASSIFICATION_GROUP:1:WHY:TYPE"));
        var blank = GraphClassificationContract.parse(json("I", " ", "1", "p1", " "), catalog);
        assertTrue(blank.failures().contains("CLASSIFICATION_GROUP:1:ROLE:BLANK"));
        assertTrue(blank.failures().contains("CLASSIFICATION_GROUP:1:WHY:BLANK"));
        var cited = GraphClassificationContract.parse(json("I", "组件 r#10", "1", "p1", "见https://example.test"), catalog);
        assertTrue(cited.failures().contains("CLASSIFICATION_GROUP:1:ROLE:FORBIDDEN_MARKUP_LINK_OR_CITATION"));
        assertTrue(cited.failures().contains("CLASSIFICATION_GROUP:1:WHY:FORBIDDEN_MARKUP_LINK_OR_CITATION"));
    }

    @Test
    void proofIdsAdjacentToChineseCannotEscapeTheTextGuard() {
        var catalog = catalog(basicLedger(1));
        for (String reason : List.of("p19明确类别", "原文p19支持类别", "p17/p19支持类别", "p0支持类别")) {
            var parsed = GraphClassificationContract.parse(json("I", "组件", "1", "p1", reason), catalog);
            assertFalse(parsed.complete(), reason);
            assertTrue(parsed.failures().contains("CLASSIFICATION_GROUP:1:WHY:FORBIDDEN_PROOF_ID"), reason);
        }
        assertTrue(GraphClassificationContract.parse(json("I", "组件", "1", "p1",
            "原文描述api19与step19以及p19value配置"), catalog).complete());
    }

    @Test
    void textLengthCountsCodePointsRatherThanUtf16CodeUnits() {
        var catalog = catalog(basicLedger(1));
        String supplementary = new String(Character.toChars(0x1F4C4));
        assertTrue(GraphClassificationContract.parse(json("I", "组件", "1", "p1",
            supplementary.repeat(80)), catalog).complete());
        var oversized = GraphClassificationContract.parse(json("I", "组件", "1", "p1",
            supplementary.repeat(81)), catalog);
        assertTrue(oversized.failures().contains("CLASSIFICATION_GROUP:1:WHY:CODEPOINT_LIMIT:81>80"));
    }

    @Test
    void duplicateOwnershipDiagnosticsKeepBothGroupsAndNeverReassignCandidates() {
        var catalog = catalog(basicLedger(2));
        String raw = "{\"groups\":[" + group("E", "其他类别", "[2]", "[\"p1\"]", "原文支持排除")
            + "," + group("U", "待确认", "[1,2]", "[]", "分类证据不足") + "]}";
        var parsed = GraphClassificationContract.parse(raw, catalog);
        assertFalse(parsed.complete());
        assertEquals(List.of(2), parsed.groups().get(0).ids());
        assertEquals(List.of(1, 2), parsed.groups().get(1).ids());
        assertEquals(List.of(Map.of("candidateId", 2, "groupIndexes", List.of(1, 2),
            "states", List.of("E", "U"), "assignmentCount", 2)),
            parsed.diagnosticsMetadata().get("duplicateCandidateOwnership"));
        assertEquals(List.of(2), parsed.diagnosticsMetadata().get("proposedUncertainAmbiguousIds"));
        assertEquals(false, parsed.diagnosticsMetadata(true).get("uncertaintySemanticallyVerified"));
    }

    @Test
    void uncertainCountsAreProposalsAndKeepDistinctIdentitySeparateFromAssignments() {
        var catalog = catalog(basicLedger(2));
        var parsed = GraphClassificationContract.parse("{\"groups\":["
            + group("U", "待确认", "[1,1,2]", "[]", "分类证据不足") + "]}", catalog);
        assertEquals(3, parsed.uncertainCount());
        assertEquals(3, parsed.diagnosticsMetadata().get("proposedUncertainCount"));
        assertEquals(2, parsed.diagnosticsMetadata().get("proposedUncertainDistinctCount"));
        assertEquals(List.of(1, 2), parsed.diagnosticsMetadata().get("proposedUncertainCandidateIds"));
        assertEquals(false, parsed.diagnosticsMetadata().get("partitionValid"));
        assertEquals(false, parsed.diagnosticsMetadata().get("uncertaintySemanticallyVerified"));
        var valid = GraphClassificationContract.parse("{\"groups\":["
            + group("U", "待确认", "[1,2]", "[]", "分类证据不足") + "]}", catalog);
        assertEquals(true, valid.diagnosticsMetadata().get("partitionValid"));
        assertEquals(false, valid.diagnosticsMetadata().get("uncertaintySemanticallyVerified"));
        assertEquals(true, valid.diagnosticsMetadata(true).get("semanticVerdictAccepted"));
        assertEquals(false, valid.diagnosticsMetadata(true).get("uncertaintySemanticallyVerified"));
    }

    @Test
    void strictUncertaintyGuardReportsItsClauseRequirementWithoutAcceptingAssertiveReasons() {
        var catalog = catalog(basicLedger(1));
        for (String reason : List.of("证据不足，但采用某语言实现",
            "仅提及名称或作为模块/配置/文件存在，未明确其所属语言生态或独立技术属性。")) {
            var parsed = GraphClassificationContract.parse("{\"groups\":["
                + group("U", "待确认", "[1]", "[]", reason) + "]}", catalog);
            assertFalse(parsed.complete());
            assertTrue(parsed.failures().contains("CLASSIFICATION_UNCERTAIN_ASSERTION"));
            assertTrue(parsed.failures().contains("CLASSIFICATION_GROUP:1:WHY:UNCERTAINTY_MARKER_REQUIRED_PER_CLAUSE"));
        }
        assertTrue(GraphClassificationContract.parse("{\"groups\":["
            + group("U", "待确认", "[1]", "[]", "分类证据不足") + "]}", catalog).complete());
    }

    @Test
    void malformedProofElementsAreLocatedWithoutExtractingEmbeddedIds() {
        var catalog = catalog(basicLedger(1));
        var parsed = GraphClassificationContract.parse("{\"groups\":["
            + group("I", "组件", "[1]", "[\"p17,p19:解释\",17]", "原文支持类别") + "]}", catalog);
        assertFalse(parsed.complete()); assertTrue(parsed.groups().get(0).proofs().isEmpty());
        assertTrue(parsed.failures().contains("CLASSIFICATION_GROUP:1:PROOFS:1:FORMAT_EXPECTED_SINGLE_P_ID"));
        assertTrue(parsed.failures().contains("CLASSIFICATION_GROUP:1:PROOFS:2:TYPE_EXPECTED_STRING"));
        assertTrue(parsed.failures().contains("CLASSIFICATION_GROUP:1:PROOFS:NONEMPTY_VALID_IDS_REQUIRED"));
        assertFalse(parsed.failures().stream().anyMatch(failure -> failure.startsWith("UNKNOWN_PROOF:")));
        assertTrue(GraphClassificationContract.parse(json("I", "组件", "1", "p999", "原文支持类别"), catalog).complete());
    }

    @Test
    void unparseableProposalCannotReportZeroUncertaintyAsAnObservedPartition() {
        var catalog = catalog(basicLedger(4));
        var parsed = GraphClassificationContract.parse("{\"groups\":[", catalog);
        assertEquals(0, parsed.uncertainCount());
        assertEquals(false, parsed.diagnosticsMetadata().get("proposedUncertainCountAvailable"));
        assertEquals("UNAVAILABLE", parsed.diagnosticsMetadata().get("proposalParseStatus"));
        assertTrue(parsed.failures().stream().noneMatch(failure -> failure.startsWith("CLASSIFICATION_OMITTED_CANDIDATE:")));
        assertEquals(false, parsed.diagnosticsMetadata().get("semanticVerdictAccepted"));
    }

    @Test
    void dispositionsKeepOnlyCompactDecisionProtocolWithoutCanonicalNames() {
        var catalog = catalog(basicLedger(3));
        var parsed = GraphClassificationContract.parse(json("I", "组件", "1-3", "p1", "原文支持类别"), catalog);
        String compact = GraphClassificationContract.dispositions(catalog, parsed);
        assertTrue(compact.contains("\"ids\":\"1-3\""));
        assertTrue(compact.contains("\"proofs\":[\"p1\"]"));
        assertFalse(compact.contains("测试组件"));
        assertTrue(GraphClassificationContract.parse(compact, catalog).complete());
    }

    @Test
    void rendererUsesCanonicalLedgerNamesAndPreservesDuplicateRelationCitations() {
        EvidenceLedger ledger = fixture(List.of(entity(1, TITLE, "POST"), entity(2, "组件完整版 8", "SKILL")),
            List.of(relation(10, 1, 2, "MENTIONS"), relation(11, 1, 2, "MENTIONS")));
        var catalog = catalog(ledger);
        var parsed = GraphClassificationContract.parse(json("I", "组件", "1", "p1", "原文支持类别"), catalog);
        var rendered = GraphClassificationContract.render(catalog, parsed, ledger);
        assertEquals("COMPLETE", rendered.coverage());
        assertTrue(rendered.answer().contains("组件完整版 8 [r#10 r#11]"));
        assertFalse(rendered.answer().contains("原文支持类别"));
        assertTrue(RelationClaimVerifier.verifyClaims(GraphClassificationContract.claims(catalog, parsed), ledger).isEmpty());
    }

    @Test
    void completePartitionOfPartialQueryCannotProduceCompleteAnswer() {
        EvidenceLedger ledger = basicLedger(1);
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(false);
        var catalog = catalog(ledger);
        var parsed = GraphClassificationContract.parse(json("I", "组件", "1", "p1", "原文支持类别"), catalog);
        assertTrue(parsed.complete());
        assertEquals("PARTIAL", GraphClassificationContract.render(catalog, parsed, ledger).coverage());
        assertEquals("CLASSIFICATION_QUERY_INCOMPLETE", GraphClassificationContract.render(catalog, parsed, ledger).coverageReason());
    }

    @Test
    void wrongObjectRelationIsRejectedByRenderingAndProductionClaimBinding() {
        EvidenceLedger ledger = basicLedger(1);
        var catalog = catalog(ledger);
        var parsed = GraphClassificationContract.parse(json("I", "组件", "1", "p1", "原文支持类别"), catalog);
        EvidenceLedger wrong = fixture(List.of(entity(1, TITLE, "POST"), entity(2, "测试组件0", "SKILL"),
            entity(3, "控制器", "SKILL")), List.of(relation(10, 1, 3, "MENTIONS")));
        assertEquals("CLASSIFICATION_BINDING_INVALID", GraphClassificationContract.render(catalog, parsed, wrong).coverageReason());
        assertTrue(RelationClaimVerifier.verifyClaims(GraphClassificationContract.claims(catalog, parsed), wrong)
            .contains("RELATION_ENDPOINT_MISMATCH:r#10"));
    }

    @Test
    void completeEmptyCatalogStillCannotRenderAnUnqualifiedAbsenceClaim() {
        EvidenceLedger ledger = fixture(List.of(entity(1, TITLE, "POST")), List.of());
        var catalog = catalog(ledger);
        var parsed = GraphClassificationContract.parse("{\"groups\":[]}", catalog);
        assertTrue(parsed.complete());
        var answer = GraphClassificationContract.render(catalog, parsed, ledger);
        assertEquals("PARTIAL", answer.coverage());
        assertEquals("CLASSIFICATION_NO_CANDIDATES", answer.coverageReason());
        assertFalse(answer.answer().contains("图谱不存在"));
    }

    @Test
    void closedDecisionsAcceptExactlyTheFourStateReasonCombinations() {
        var catalog = catalog(basicLedger(4));
        String raw = decisions(decision("I", "MATCH", "[1]", "[\"p1\"]"),
            decision("E", "OUTSIDE_ATTRIBUTE", "[2]", "[\"p1\"]"),
            decision("E", "OUTSIDE_TYPE", "[3]", "[\"p2\"]"),
            decision("U", "INSUFFICIENT", "[4]", "[]"));
        var parsed = GraphClassificationContract.parseDecisions(raw, catalog);
        assertTrue(parsed.complete(), parsed.failures().toString());
        assertEquals(4, parsed.groups().size());
        assertEquals(1, parsed.uncertainCount());
        assertEquals("candidate-decisions-v3", parsed.diagnosticsMetadata().get("protocol"));
    }

    @Test
    void closedDecisionStateReasonMatrixRejectsEveryOtherCombination() {
        var catalog = catalog(basicLedger(1));
        for (var state : GraphClassificationContract.State.values()) {
            for (var reason : GraphClassificationContract.Reason.values()) {
                boolean allowed = state == GraphClassificationContract.State.I && reason == GraphClassificationContract.Reason.MATCH
                    || state == GraphClassificationContract.State.E && (reason == GraphClassificationContract.Reason.OUTSIDE_ATTRIBUTE
                        || reason == GraphClassificationContract.Reason.OUTSIDE_TYPE)
                    || state == GraphClassificationContract.State.U && reason == GraphClassificationContract.Reason.INSUFFICIENT;
                var parsed = GraphClassificationContract.parseDecisions(decisions(decision(state.name(), reason.name(),
                    "[1]", "[\"p1\"]")), catalog);
                assertEquals(allowed, parsed.complete(), state + ":" + reason);
                if (!allowed) assertTrue(parsed.failures().contains("CLASSIFICATION_STATE_REASON_MISMATCH"));
            }
        }
    }

    @Test
    void closedDecisionSchemaRejectsAllFreeTextAndFreeNameFields() {
        var catalog = catalog(basicLedger(1));
        String valid = decision("I", "MATCH", "[1]", "[\"p1\"]");
        for (String field : List.of("role", "why", "explanation", "name", "answer", "citation")) {
            String row = valid.substring(0, valid.length() - 1) + ",\"" + field + "\":\"自由文字\"}";
            var parsed = GraphClassificationContract.parseDecisions(decisions(row), catalog);
            assertFalse(parsed.complete(), field);
            assertTrue(parsed.failures().contains("CLASSIFICATION_INVALID_GROUP"));
        }
        assertFalse(GraphClassificationContract.parseDecisions(decisions(valid).replace("{\"groups\":",
            "{\"explanation\":\"文字\",\"groups\":"), catalog).complete());
    }

    @Test
    void historicalFreeTextProtocolCannotEnterClosedDecisionParser() {
        var catalog = catalog(basicLedger(1));
        String historical = json("I", "组件", "1", "p1", "原文支持类别");
        assertTrue(GraphClassificationContract.parse(historical, catalog).complete());
        var closed = GraphClassificationContract.parseDecisions(historical, catalog);
        assertFalse(closed.complete());
        assertEquals(List.of(1), closed.diagnosticsMetadata().get("missingCandidateIds"));
    }

    @Test
    void closedStateAndReasonRequireExactCodesRatherThanLabelsOrCoercion() {
        var catalog = catalog(basicLedger(1));
        for (String state : List.of("INCLUDE", "i", " I ", "符合")) {
            var parsed = GraphClassificationContract.parseDecisions(decisions(decision(state, "MATCH", "[1]", "[\"p1\"]")), catalog);
            assertTrue(parsed.failures().contains("CLASSIFICATION_UNKNOWN_STATE"));
        }
        for (String reason : List.of("OTHER", "match", "MATCH ", "符合条件")) {
            var parsed = GraphClassificationContract.parseDecisions(decisions(decision("I", reason, "[1]", "[\"p1\"]")), catalog);
            assertTrue(parsed.failures().contains("CLASSIFICATION_UNKNOWN_REASON"));
            assertEquals("RECOGNIZED_GROUPS_ONLY", parsed.diagnosticsMetadata().get("proposalParseStatus"));
        }
        assertFalse(GraphClassificationContract.parseDecisions(decisions(decision("I", "MATCH", "[1]", "[\"p1\"]"))
            .replace("\"reason\":\"MATCH\"", "\"reason\":1"), catalog).complete());
    }

    @Test
    void malformedClosedProposalNeverReportsAnObservedZeroUncertaintyPartition() {
        var catalog = catalog(basicLedger(1));
        for (String raw : List.of("", "{\"groups\":[", "null")) {
            var parsed = GraphClassificationContract.parseDecisions(raw, catalog);
            assertFalse(parsed.complete());
            assertEquals(false, parsed.diagnosticsMetadata(true).get("proposedUncertainCountAvailable"));
            assertEquals("UNAVAILABLE", parsed.diagnosticsMetadata().get("proposalParseStatus"));
            assertEquals(false, parsed.diagnosticsMetadata(true).get("semanticVerdictAccepted"));
        }
    }

    @Test
    void closedParserRejectsFencesTrailingResponsesAndRepeatedJsonKeys() {
        var catalog = catalog(basicLedger(1));
        String valid = decisions(decision("I", "MATCH", "[1]", "[\"p1\"]"));
        for (String raw : List.of("```json\n" + valid + "\n```", valid + "{}", valid + "说明",
            valid.replace("\"s\":\"I\"", "\"s\":\"I\",\"s\":\"E\""), "{\"groups\":[],\"groups\":[]}"))
            assertFalse(GraphClassificationContract.parseDecisions(raw, catalog).complete(), raw);
    }

    @Test
    void closedCandidateIdsAcceptCompactRangesAndIntegerArraysWithoutNames() {
        var catalog = catalog(basicLedger(4));
        for (String ids : List.of("\"1-4\"", "[1,2,3,4]", "\"1,2-4\"")) {
            var parsed = GraphClassificationContract.parseDecisions(decisions(decision("I", "MATCH", ids, "[\"p1\"]")), catalog);
            assertTrue(parsed.complete(), parsed.failures().toString());
            assertEquals(List.of(1, 2, 3, 4), parsed.groups().get(0).ids());
        }
        for (String ids : List.of("[\"1\"]", "[1.0]", "\"1:测试组件\"", "\"0-4\""))
            assertFalse(GraphClassificationContract.parseDecisions(decisions(decision("I", "MATCH", ids, "[\"p1\"]")), catalog).complete());
    }

    @Test
    void closedDecisionsRetainDuplicateOwnershipInsteadOfDeduplicatingTheProposal() {
        var catalog = catalog(basicLedger(2));
        var parsed = GraphClassificationContract.parseDecisions(decisions(
            decision("I", "MATCH", "[1,1]", "[\"p1\"]"), decision("U", "INSUFFICIENT", "[1,2]", "[]")), catalog);
        assertFalse(parsed.complete());
        assertEquals(List.of(1, 1), parsed.groups().get(0).ids());
        assertTrue(parsed.failures().contains("CLASSIFICATION_DUPLICATE_CANDIDATE:1"));
        assertEquals(List.of(Map.of("candidateId", 1, "groupIndexes", List.of(1, 1, 2),
            "states", List.of("I", "I", "U"), "assignmentCount", 3)),
            parsed.diagnosticsMetadata().get("duplicateCandidateOwnership"));
        assertEquals(List.of(1), parsed.diagnosticsMetadata().get("proposedUncertainAmbiguousIds"));
    }

    @Test
    void closedDecisionsRejectUnknownCandidatesAndDoNotSubstituteExistingNumbers() {
        var catalog = catalog(basicLedger(2));
        var parsed = GraphClassificationContract.parseDecisions(decisions(decision("I", "MATCH", "[1,3]", "[\"p1\"]")), catalog);
        assertFalse(parsed.complete());
        assertEquals(List.of(1), parsed.groups().get(0).ids());
        assertEquals(List.of(3), parsed.diagnosticsMetadata().get("unknownCandidateIds"));
        assertEquals(List.of(2), parsed.diagnosticsMetadata().get("missingCandidateIds"));
    }

    @Test
    void missingClosedCandidatesAreReportedIndividuallyAndNeverAssignedToUncertainty() {
        var catalog = catalog(basicLedger(4));
        var parsed = GraphClassificationContract.parseDecisions(decisions(decision("I", "MATCH", "[1]", "[\"p1\"]")), catalog);
        assertFalse(parsed.complete());
        assertEquals(List.of(2, 3, 4), parsed.diagnosticsMetadata().get("missingCandidateIds"));
        assertEquals(0, parsed.uncertainCount());
        assertEquals(1, parsed.groups().size());
        assertEquals("", GraphClassificationContract.decisionDispositions(catalog, parsed));
        assertTrue(GraphClassificationContract.decisionClaims(catalog, parsed).isEmpty());
    }

    @Test
    void allEightyEightCandidatesRemainRequiredEvenWhenFourAdjacentIdsAreMissing() {
        // Full inventory is supplied explicitly so this is a parser test, not an inventory-budget test.
        var original = catalog(basicLedger(88));
        var complete = new GraphClassificationContract.Catalog(true, "", original.candidates(), "",
            Map.of(), true, true, original.focusText());
        var parsed = GraphClassificationContract.parseDecisions(decisions(decision("I", "MATCH", "\"1-76,81-88\"", "[\"p1\"]")), complete);
        assertFalse(parsed.complete());
        assertEquals(List.of(77, 78, 79, 80), parsed.diagnosticsMetadata().get("missingCandidateIds"));
        assertEquals(84, parsed.groups().get(0).ids().size());
        assertEquals(0, parsed.uncertainCount());
    }

    @Test
    void closedProofMalformedElementDiagnosticsNeverExtractEmbeddedIds() {
        var catalog = catalog(basicLedger(1));
        var parsed = GraphClassificationContract.parseDecisions(decisions(decision("I", "MATCH", "[1]", "[\"p1:说明\",1]")), catalog);
        assertFalse(parsed.complete());
        assertTrue(parsed.groups().get(0).proofs().isEmpty());
        assertTrue(parsed.failures().contains("CLASSIFICATION_GROUP:1:PROOFS:1:FORMAT_EXPECTED_SINGLE_P_ID"));
        assertTrue(parsed.failures().contains("CLASSIFICATION_GROUP:1:PROOFS:2:TYPE_EXPECTED_STRING"));
        assertTrue(parsed.failures().contains("CLASSIFICATION_PROOF_REQUIRED"));
    }

    @Test
    void everyDecisiveClosedStateRequiresProofWhileUncertaintyDoesNot() {
        var catalog = catalog(basicLedger(1));
        for (String row : List.of(decision("I", "MATCH", "[1]", "[]"),
            decision("E", "OUTSIDE_ATTRIBUTE", "[1]", "[]"), decision("E", "OUTSIDE_TYPE", "[1]", "[]"))) {
            var parsed = GraphClassificationContract.parseDecisions(decisions(row), catalog);
            assertFalse(parsed.complete());
            assertTrue(parsed.failures().contains("CLASSIFICATION_PROOF_REQUIRED"));
        }
        assertTrue(GraphClassificationContract.parseDecisions(decisions(decision("U", "INSUFFICIENT", "[1]", "[]")), catalog).complete());
        assertTrue(GraphClassificationContract.parseDecisions(decisions(decision("U", "INSUFFICIENT", "[1]", "[\"p1\"]")), catalog).complete());
    }

    @Test
    void closedProofSyntaxDoesNotPretendToValidateExistenceOrSourceSupport() {
        var catalog = catalog(basicLedger(1));
        var parsed = GraphClassificationContract.parseDecisions(decisions(decision("I", "MATCH", "[1]", "[\"p999\"]")), catalog);
        assertTrue(parsed.complete());
        assertEquals(false, parsed.diagnosticsMetadata().get("semanticVerdictAccepted"));
        assertEquals(false, parsed.diagnosticsMetadata(true).get("uncertaintySemanticallyVerified"));
    }

    @Test
    void closedProofListsRejectDuplicateIdsRatherThanRepairingThem() {
        var catalog = catalog(basicLedger(1));
        var parsed = GraphClassificationContract.parseDecisions(decisions(decision("I", "MATCH", "[1]", "[\"p1\",\"p1\"]")), catalog);
        assertFalse(parsed.complete());
        assertTrue(parsed.failures().contains("CLASSIFICATION_DUPLICATE_PROOF_ID"));
        assertTrue(parsed.failures().contains("CLASSIFICATION_GROUP:1:PROOFS:2:DUPLICATE_ID"));
    }

    @Test
    void sameReasonMayCoverSeveralDisjointGroupsBecauseReasonIsNotAPartitionSlot() {
        // v10 M5 split seven candidates into four MATCH groups plus one OUTSIDE_TYPE group and was
        // rejected purely for reusing a reason code. A reason says WHY a candidate was disposed of,
        // so disjoint groups may legitimately share one. The integrity guards still apply.
        var catalog = catalog(basicLedger(2));
        var parsed = GraphClassificationContract.parseDecisions(decisions(
            decision("I", "MATCH", "[1]", "[\"p1\"]"), decision("I", "MATCH", "[2]", "[\"p2\"]")), catalog);
        assertTrue(parsed.complete(), "Two disjoint MATCH groups must be accepted, got " + parsed.failures());
        assertEquals(2, parsed.groups().size());
        assertFalse(parsed.failures().contains("CLASSIFICATION_DUPLICATE_REASON:MATCH"));
        assertFalse(parsed.failures().contains("CLASSIFICATION_GROUP:2:REASON:DUPLICATE_CODE:MATCH"));
    }

    @Test
    void groupCardinalityIsBoundedByCandidateCountRatherThanByReasonCodes() {
        // Five groups over five candidates is a legal partition even though there are only four
        // reason codes, because two groups share one reason.
        var catalog = catalog(basicLedger(5));
        var parsed = GraphClassificationContract.parseDecisions(decisions(
            decision("I", "MATCH", "[1]", "[\"p1\"]"), decision("E", "OUTSIDE_ATTRIBUTE", "[2]", "[\"p1\"]"),
            decision("E", "OUTSIDE_TYPE", "[3]", "[\"p1\"]"), decision("U", "INSUFFICIENT", "[4]", "[]"),
            decision("I", "MATCH", "[5]", "[\"p1\"]")), catalog);
        assertTrue(parsed.complete(), "Five groups over five candidates must be accepted, got " + parsed.failures());
        assertFalse(parsed.failures().contains("CLASSIFICATION_TOO_MANY_GROUPS"));
        // A legal partition reports the raw proposed U count; only a rejected protocol hides it.
        assertEquals(true, parsed.diagnosticsMetadata().get("proposedUncertainCountAvailable"));
    }

    @Test
    void moreGroupsThanCandidatesIsStillRejected() {
        // The real cardinality guard: a partition can never have more groups than members.
        var catalog = catalog(basicLedger(2));
        var parsed = GraphClassificationContract.parseDecisions(decisions(
            decision("I", "MATCH", "[1]", "[\"p1\"]"), decision("I", "MATCH", "[1]", "[\"p1\"]"),
            decision("I", "MATCH", "[2]", "[\"p2\"]"), decision("U", "INSUFFICIENT", "[2]", "[]")), catalog);
        assertFalse(parsed.complete());
        assertTrue(parsed.failures().contains("CLASSIFICATION_TOO_MANY_GROUPS"));
    }

    @Test
    void closedUncertaintyCountsRemainRawProposalsEvenAfterAcceptedSemanticVerdict() {
        var catalog = catalog(basicLedger(2));
        var duplicate = GraphClassificationContract.parseDecisions(decisions(decision("U", "INSUFFICIENT", "[1,1,2]", "[]")), catalog);
        assertEquals(3, duplicate.uncertainCount());
        assertEquals(2, duplicate.diagnosticsMetadata().get("proposedUncertainDistinctCount"));
        assertEquals(false, duplicate.diagnosticsMetadata(true).get("semanticVerdictAccepted"));
        var valid = GraphClassificationContract.parseDecisions(decisions(decision("U", "INSUFFICIENT", "[1,2]", "[]")), catalog);
        assertEquals(true, valid.diagnosticsMetadata(true).get("semanticVerdictAccepted"));
        assertEquals(false, valid.diagnosticsMetadata(true).get("uncertaintySemanticallyVerified"));
        assertEquals(false, valid.diagnosticsMetadata(true).get("queryCompletenessCertifiedByPartition"));
        assertTrue(GraphClassificationContract.decisionClaims(catalog, valid).isEmpty());
    }

    @Test
    void closedRecordsDefensivelyCopyDecisionListsFailuresAndDiagnostics() {
        List<Integer> ids = new ArrayList<>(List.of(1));
        List<String> proofs = new ArrayList<>(List.of("p1"));
        var group = new GraphClassificationContract.DecisionGroup(GraphClassificationContract.State.I,
            GraphClassificationContract.Reason.MATCH, ids, proofs);
        List<GraphClassificationContract.DecisionGroup> groups = new ArrayList<>(List.of(group));
        List<String> failures = new ArrayList<>();
        var parsed = new GraphClassificationContract.DecisionParsed(groups, failures, true, 0);
        ids.add(2); proofs.add("p2"); groups.clear(); failures.add("injected");
        assertEquals(List.of(1), group.ids()); assertEquals(List.of("p1"), group.proofs());
        assertEquals(1, parsed.groups().size()); assertTrue(parsed.failures().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> group.ids().add(3));
        assertThrows(UnsupportedOperationException.class, () -> parsed.diagnosticsMetadata().put("injected", true));
    }

    @Test
    void closedRendererOwnsNeutralLabelsAndCanonicalNamesForAllReasons() {
        var ledger = basicLedger(4);
        var catalog = catalog(ledger);
        var parsed = GraphClassificationContract.parseDecisions(decisions(
            decision("I", "MATCH", "[1]", "[\"p1\"]"), decision("E", "OUTSIDE_ATTRIBUTE", "[2]", "[\"p1\"]"),
            decision("E", "OUTSIDE_TYPE", "[3]", "[\"p1\"]"), decision("U", "INSUFFICIENT", "[4]", "[]")), catalog);
        var rendered = GraphClassificationContract.renderDecisions(catalog, parsed, ledger, null);
        assertEquals("PARTIAL", rendered.coverage());
        assertTrue(rendered.answer().contains("符合所问筛选条件"));
        assertTrue(rendered.answer().contains("不符合所问属性"));
        assertTrue(rendered.answer().contains("不属于所问对象范围"));
        assertTrue(rendered.answer().contains("待确认"));
        for (int number = 0; number < 4; number++)
            assertTrue(rendered.answer().contains("测试组件" + number + " [r#" + (number + 10) + "]"));
        assertFalse(rendered.answer().contains("Java"));
        assertEquals(3, GraphClassificationContract.decisionClaims(catalog, parsed).size());
    }

    @Test
    void closedMixedSubjectRenderingRetainsEveryCandidateAnchor() {
        String question = "《报告甲》与《报告乙》在图谱里关联类别如何分类？";
        var ledger = fixture(List.of(entity(1, "报告甲", "POST"), entity(2, "报告乙", "POST")),
            List.of(relation(10, 1, 2, "RELATED_TO")));
        var catalog = GraphClassificationContract.prepare(question, QueryEvidencePlan.fromQuestion(question), ledger);
        var parsed = GraphClassificationContract.parseDecisions(decisions(decision("U", "INSUFFICIENT", "[1,2]", "[]")), catalog);
        var rendered = GraphClassificationContract.renderDecisions(catalog, parsed, ledger, null);
        assertTrue(rendered.answer().contains("《报告甲》：报告乙 [r#10]"));
        assertTrue(rendered.answer().contains("《报告乙》：报告甲 [r#10]"));
        assertEquals("PARTIAL", rendered.coverage());
    }

    @Test
    void closedRenderingRechecksEndpointsRelationTypeDirectionAndCandidateType() {
        var catalog = catalog(basicLedger(1));
        var parsed = GraphClassificationContract.parseDecisions(decisions(decision("I", "MATCH", "[1]", "[\"p1\"]")), catalog);
        List<EvidenceLedger.EntityFact> entities = List.of(entity(1, TITLE, "POST"), entity(2, "测试组件0", "SKILL"),
            entity(3, "控制器", "SKILL"));
        for (var edge : List.of(relation(10, 1, 3, "MENTIONS"), relation(10, 2, 1, "MENTIONS"), relation(10, 1, 2, "RELATED_TO")))
            assertEquals("CLASSIFICATION_BINDING_INVALID", GraphClassificationContract.renderDecisions(catalog, parsed,
                fixture(entities, List.of(edge)), null).coverageReason());
        var changedType = fixture(List.of(entity(1, TITLE, "POST"), entity(2, "测试组件0", "ORGANIZATION")),
            List.of(relation(10, 1, 2, "MENTIONS")));
        assertEquals("CLASSIFICATION_BINDING_INVALID", GraphClassificationContract.renderDecisions(catalog, parsed, changedType, null).coverageReason());
    }

    @Test
    void closedPartitionNeverCertifiesAnIncompleteQueryOrEmptyInventory() {
        var ledger = basicLedger(1);
        when(ledger.completeRelations("expand_neighbors", 1L)).thenReturn(false);
        var catalog = catalog(ledger);
        var parsed = GraphClassificationContract.parseDecisions(decisions(decision("I", "MATCH", "[1]", "[\"p1\"]")), catalog);
        assertTrue(parsed.complete());
        var rendered = GraphClassificationContract.renderDecisions(catalog, parsed, ledger, null);
        assertEquals("PARTIAL", rendered.coverage());
        assertEquals("CLASSIFICATION_QUERY_INCOMPLETE", rendered.coverageReason());
        var emptyLedger = fixture(List.of(entity(1, TITLE, "POST")), List.of());
        var emptyCatalog = catalog(emptyLedger);
        var empty = GraphClassificationContract.parseDecisions("{\"groups\":[]}", emptyCatalog);
        assertEquals("CLASSIFICATION_NO_CANDIDATES", GraphClassificationContract.renderDecisions(emptyCatalog, empty,
            emptyLedger, null).coverageReason());
    }

    @Test
    void closedRendererResolvesProofIdsToCanonicalChunkCitationsAndRejectsMissingIds() {
        var ledger = basicLedger(1);
        String text = "测试组件0属于原文明确说明的所问属性。";
        var chunk = new EvidenceLedger.ChunkFact(701L, "POST", 1L, TITLE, text, 0, text.length());
        when(ledger.chunks()).thenReturn(List.of(chunk));
        when(ledger.chunk(701L)).thenReturn(Optional.of(chunk));
        var proofs = ClassificationProofCatalog.prepare(QUESTION, ledger, List.of(2L));
        assertFalse(proofs.spans().isEmpty());
        var catalog = catalog(ledger);
        var parsed = GraphClassificationContract.parseDecisions(decisions(decision("I", "MATCH", "[1]",
            "[\"" + proofs.spans().get(0).id() + "\"]")), catalog);
        var rendered = GraphClassificationContract.renderDecisions(catalog, parsed, ledger, proofs);
        assertEquals("COMPLETE", rendered.coverage());
        assertTrue(rendered.answer().contains("分类依据：c#701"));
        assertFalse(rendered.answer().contains("p1"));
        var missing = GraphClassificationContract.parseDecisions(decisions(decision("I", "MATCH", "[1]", "[\"p999\"]")), catalog);
        assertEquals("CLASSIFICATION_PROOF_BINDING_INVALID", GraphClassificationContract.renderDecisions(catalog, missing, ledger, proofs).coverageReason());
    }

    @Test
    void closedDispositionsRoundTripWithoutAnyFreeTextOrEntityName() {
        var catalog = catalog(basicLedger(4));
        var parsed = GraphClassificationContract.parseDecisions(decisions(
            decision("I", "MATCH", "[1,2,3]", "[\"p1\",\"p2\"]"), decision("U", "INSUFFICIENT", "[4]", "[]")), catalog);
        String compact = GraphClassificationContract.decisionDispositions(catalog, parsed);
        assertTrue(compact.contains("\"ids\":\"1-3\""));
        assertTrue(compact.contains("\"reason\":\"MATCH\""));
        assertFalse(compact.contains("role")); assertFalse(compact.contains("why"));
        assertFalse(compact.contains("测试组件"));
        var restored = GraphClassificationContract.parseDecisions(compact, catalog);
        assertTrue(restored.complete());
        assertEquals(parsed.groups(), restored.groups());
    }

    private static String decisions(String... groups) {
        return "{\"groups\":[" + String.join(",", groups) + "]}";
    }

    private static String decision(String state, String reason, String idsJson, String proofsJson) {
        return "{\"s\":\"" + state + "\",\"reason\":\"" + reason + "\",\"ids\":" + idsJson
            + ",\"proofs\":" + proofsJson + "}";
    }

    private static GraphClassificationContract.Catalog catalog(EvidenceLedger ledger) {
        return GraphClassificationContract.prepare(QUESTION, QueryEvidencePlan.fromQuestion(QUESTION), ledger);
    }

    private static String json(String state, String role, String ids, String proof, String why) {
        return "{\"groups\":[" + group(state, role, "\"" + ids + "\"", "[\"" + proof + "\"]", why) + "]}";
    }

    private static String group(String state, String role, String idsJson, String proofsJson, String why) {
        return "{\"s\":\"" + state + "\",\"role\":\"" + role + "\",\"ids\":" + idsJson
            + ",\"proofs\":" + proofsJson + ",\"why\":\"" + why + "\"}";
    }

    private static EvidenceLedger basicLedger(int count) {
        List<EvidenceLedger.EntityFact> entities = new ArrayList<>(List.of(entity(1, TITLE, "POST")));
        List<EvidenceLedger.RelationFact> relations = new ArrayList<>();
        for (int number = 0; number < count; number++) {
            entities.add(entity(number + 2, "测试组件" + number, "SKILL"));
            relations.add(relation(number + 10, 1, number + 2, "MENTIONS"));
        }
        return fixture(entities, relations);
    }

    private static EvidenceLedger fixture(List<EvidenceLedger.EntityFact> entities,
                                          List<EvidenceLedger.RelationFact> relations) {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.entities()).thenReturn(entities);
        when(ledger.relations()).thenReturn(relations);
        when(ledger.entity(anyLong())).thenAnswer(call -> entities.stream()
            .filter(entity -> entity.id().equals(call.getArgument(0))).findFirst());
        when(ledger.relation(anyLong())).thenAnswer(call -> relations.stream()
            .filter(relation -> relation.id().equals(call.getArgument(0))).findFirst());
        when(ledger.chunks()).thenReturn(List.of());
        when(ledger.completeRelations(anyString(), anyLong())).thenReturn(true);
        return ledger;
    }

    private static EvidenceLedger.EntityFact entity(long id, String name, String type) {
        return new EvidenceLedger.EntityFact(id, name, type, "");
    }

    private static EvidenceLedger.RelationFact relation(long id, long source, long target, String type) {
        return new EvidenceLedger.RelationFact(id, source, target, type, null, null, null, null, "");
    }

    private static EvidenceLedger.RelationFact timedRelation(long id, long source, long target,
                                                            Date from, Date to) {
        return new EvidenceLedger.RelationFact(id, source, target, "MENTIONS", from, to, null, null, "");
    }

    private static Date date(LocalDate date) {
        return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
