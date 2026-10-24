package cn.ilink.service.kg;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.KgRelation;
import cn.ilink.mapper.KgAgentTraceMapper;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.kg.tool.GraphTool;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Temporal coverage stays independent of non-temporal candidate classification and neighbor coverage. */
class AgenticTemporalScopeIntegrationTest {
    private static final String SHORT_TITLE = "前端视觉重构报告";
    private static final String TITLE = "前端页面视觉重构升级报告";

    @Test
    void completeWindowWithShortDocumentTitleIsConditionalAndMakesNoChatRequest() {
        AiClient ai = mock(AiClient.class);
        var answer = service(ledger(TITLE, true, true), ai).query(question(SHORT_TITLE), 8L);
        assertTrue(answer.answer.contains("若你指的是《" + TITLE + "》"),
            "answer=" + answer.answer + " trace=" + answer.trace);
        assertFalse(answer.answer.contains("仅覆盖本次可核对部分"));
        assertTrue(answer.answer.contains("r#10"));
        assertEquals("CONDITIONAL", last(answer, "answerCoverage"));
        assertEquals("DOCUMENT_TITLE_MATCH_UNCONFIRMED", last(answer, "coverageReason"));
        Map<?, ?> scope = (Map<?, ?>) last(answer, "temporalAnswerScope");
        assertEquals(false, scope.get("requestedSubjectsConfirmed"));
        assertEquals(true, scope.get("resolvedWindowComplete"));
        assertEquals(false, scope.get("requestedScopeComplete"));
        assertEquals("TEMPORAL_QUERY", last(answer, "classificationSkipReason"));
        verifyNoInteractions(ai);
    }

    @Test
    void truncatedWindowKeepsPartialWarningEvenWithDocumentTitleEvidence() {
        AiClient ai = mock(AiClient.class);
        var answer = service(ledger(TITLE, false, true), ai).query(question(SHORT_TITLE), 8L);
        assertTrue(answer.answer.contains("仅覆盖本次可核对部分"),
            "answer=" + answer.answer + " trace=" + answer.trace);
        assertEquals("PARTIAL", last(answer, "answerCoverage"));
        assertEquals(false, ((Map<?, ?>) last(answer, "temporalAnswerScope")).get("resolvedWindowComplete"));
        verifyNoInteractions(ai);
    }

    @Test
    void exactSubjectAndCompleteWindowDoNotRequireNeighborExpansionOrClassification() {
        AiClient ai = mock(AiClient.class);
        var answer = service(ledger(TITLE, true, true), ai).query(question(TITLE), 8L);
        assertFalse(answer.answer.contains("仅覆盖本次可核对部分"));
        assertEquals("COMPLETE", last(answer, "answerCoverage"));
        assertEquals("TEMPORAL_QUERY", last(answer, "classificationSkipReason"));
        assertTrue(answer.trace.stream().noneMatch(step -> step.containsKey("graphCandidateInventory")));
        verifyNoInteractions(ai);
    }

    @Test
    void exactCompleteEmptyWindowCanReturnNoMatchWithoutChat() {
        AiClient ai = mock(AiClient.class);
        var answer = service(ledger(TITLE, true, false), ai).query(question(TITLE), 8L);
        assertTrue(answer.answer.contains("没有符合所问条件的竞赛关联"));
        assertEquals("COMPLETE", last(answer, "answerCoverage"));
        assertEquals(true, ((Map<?, ?>) last(answer, "temporalAnswerScope")).get("requestedScopeComplete"));
        verifyNoInteractions(ai);
    }

    @Test
    void recordGroupsWithIncompleteMemberWindowsReturnVerifiedPartialAnswerWithCitations() {
        String query = "2026 年 3 月 25 日以后，团队空间与视觉重构相关记录还关联着哪些竞赛？";
        EvidenceLedger ledger = temporalGroupLedger();
        AiClient ai = mock(AiClient.class);

        var answer = new AgenticGraphRagService(mock(GraphRagService.class),
            mock(KgAgentTraceMapper.class), ai, List.of(
                searchTool(List.of(
                    new EvidenceLedger.EntityFact(1725L, "iLink 团队空间模块开发成果", "POST", ""),
                    new EvidenceLedger.EntityFact(1965L, "Link 团队空间模块", "POST", ""),
                    new EvidenceLedger.EntityFact(1735L, "前端页面视觉重构升级报告", "POST", ""),
                    new EvidenceLedger.EntityFact(1973L, "挑战杯全国大学生课外学术科技作品竞赛",
                        "COMPETITION", ""))),
                temporalTool(true)),
            factory(ledger)).query(query, 8L);

        assertTrue(answer.answer.contains("挑战杯全国大学生课外学术科技作品竞赛"),
            "answer=" + answer.answer + " trace=" + answer.trace + " citations=" + answer.citations);
        assertTrue(answer.answer.contains("尚未完成所问时间窗查询"), answer.answer);
        assertTrue(answer.answer.contains("Link 团队空间模块（#1965）"), answer.answer);
        assertFalse(answer.answer.contains("iLink 团队空间模块开发成果（#1725）"),
            answer.answer);
        assertFalse(answer.answer.contains("前端页面视觉重构升级报告（#1735）"),
            answer.answer);
        for (long relationId : List.of(10L, 12L)) {
            assertTrue(answer.citations.stream().anyMatch(citation ->
                Long.valueOf(relationId).equals(citation.get("relationId"))),
                "missing r#" + relationId + " citations=" + answer.citations);
        }
        assertEquals("PARTIAL", last(answer, "answerCoverage"));
        assertEquals("TEMPORAL_RECORD_MEMBERS_INCOMPLETE", last(answer, "coverageReason"));
        assertFalse("COMPLETE".equals(last(answer, "answerCoverage")));
        verifyNoInteractions(ai);
    }

    @Test
    void recordGroupsWithEveryMemberWindowCompleteCanReturnCompleteAnswer() {
        String query = "2026 年 3 月 25 日以后，团队空间与视觉重构相关记录还关联着哪些竞赛？";
        EvidenceLedger ledger = temporalGroupLedger();
        AiClient ai = mock(AiClient.class);

        var answer = new AgenticGraphRagService(mock(GraphRagService.class),
            mock(KgAgentTraceMapper.class), ai, List.of(
                searchTool(List.of(
                    new EvidenceLedger.EntityFact(1725L, "iLink 团队空间模块开发成果", "POST", ""),
                    new EvidenceLedger.EntityFact(1965L, "Link 团队空间模块", "POST", ""),
                    new EvidenceLedger.EntityFact(1735L, "前端页面视觉重构升级报告", "POST", ""),
                    new EvidenceLedger.EntityFact(1973L, "挑战杯全国大学生课外学术科技作品竞赛",
                        "COMPETITION", ""))),
                temporalTool(false)),
            factory(ledger)).query(query, 8L);

        assertTrue(answer.answer.contains("挑战杯全国大学生课外学术科技作品竞赛"),
            "answer=" + answer.answer + " trace=" + answer.trace + " citations=" + answer.citations);
        assertEquals("COMPLETE", last(answer, "answerCoverage"));
        assertEquals("AUTHORIZED_RECORD_GROUPS", last(answer, "coverageReason"));
        assertFalse(answer.answer.contains("本次授权查询范围内没有已确认的正向匹配"),
            answer.answer);
        for (long relationId : List.of(10L, 11L, 12L)) {
            assertTrue(answer.citations.stream().anyMatch(citation ->
                Long.valueOf(relationId).equals(citation.get("relationId"))),
                "missing r#" + relationId + " citations=" + answer.citations);
        }
        verifyNoInteractions(ai);
    }

    @Test
    void unresolvedTitleCannotUseEmptyTemporalToolResultToSkipSemanticVerification() {
        AiClient ai = mock(AiClient.class);
        String query = question(SHORT_TITLE);
        var result = new AnswerEvidenceVerifier().verifySemantic(query,
            "在指定时间段内，没有符合所问条件的竞赛关联。",
            ledger(TITLE, true, false), QueryEvidencePlan.fromQuestion(query), ai);
        assertFalse(result.supported());
        assertTrue(result.reasons().stream().anyMatch(reason ->
            reason.startsWith("TEMPORAL_ABSENCE_SCOPE_UNCONFIRMED")));
        verifyNoInteractions(ai);
    }

    @Test
    void partialWindowCannotSupportNoMatchInModelFallback() {
        AiClient ai = mock(AiClient.class);
        String query = question(TITLE);
        var result = new AnswerEvidenceVerifier().verifySemantic(query,
            "在指定时间段内，没有符合所问条件的竞赛关联。",
            ledger(TITLE, false, false), QueryEvidencePlan.fromQuestion(query), ai);
        assertFalse(result.supported());
        assertTrue(result.reasons().stream().anyMatch(reason ->
            reason.startsWith("TEMPORAL_ABSENCE_SCOPE_UNCONFIRMED")));
        verifyNoInteractions(ai);
    }

    @Test
    void sourceOnlyPropertyAnswerIsNotDiscardedWhenGraphTraversalIsIncomplete() {
        String query = "知识图谱里，中国高校计算机大赛与中国高校计算机大赛 AIGC 创新赛分别由什么组织主办？";
        var host = new EvidenceLedger.EntityFact(301L, "全国高等学校计算机教育研究会", "ORGANIZATION", "");
        var chunk = new EvidenceLedger.ChunkFact(336L, "COMPETITION", 53L,
            "中国高校计算机大赛 AIGC 创新赛",
            "竞赛名称：中国高校计算机大赛 AIGC 创新赛；主办方：全国高等学校计算机教育研究会。", 0, 50);
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.entities()).thenReturn(List.of(host));
        when(ledger.entity(301L)).thenReturn(Optional.of(host));
        when(ledger.relations()).thenReturn(List.of());
        when(ledger.chunks()).thenReturn(List.of(chunk));
        when(ledger.chunk(336L)).thenReturn(Optional.of(chunk));
        when(ledger.containsChunk(336L)).thenReturn(true);
        when(ledger.coverage()).thenReturn(List.of());

        AiClient ai = mock(AiClient.class);
        when(ai.chat(anyList(), anyInt()))
            .thenReturn(new AiClient.AiChatResult(
                "中国高校计算机大赛 AIGC 创新赛由全国高等学校计算机教育研究会主办。c#336", 1, 1))
            .thenReturn(new AiClient.AiChatResult(
                "{\"facts\":\"SUPPORTED\",\"coverage\":\"INCOMPLETE\",\"unsupported\":[],"
                    + "\"missing\":[\"授权图谱候选未完整展示\"]}", 1, 1));
        EvidenceLedgerFactory factory = mock(EvidenceLedgerFactory.class);
        when(factory.create()).thenReturn(ledger);
        var service = new AgenticGraphRagService(mock(GraphRagService.class),
            mock(KgAgentTraceMapper.class), ai, List.of(), factory);

        var answer = service.query(query, 8L);

        assertTrue(answer.answer.contains("全国高等学校计算机教育研究会"));
        assertTrue(answer.answer.contains("c#336"));
        assertFalse(answer.answer.startsWith("以下是《"));
        assertEquals("COMPLETE_SOURCE_ONLY", last(answer, "answerCoverage"));
        assertEquals("PARTIAL", last(answer, "graphCoverage"));
    }

    private static String question(String title) {
        return "2026 年 3 月，" + title + "与哪个竞赛有关联？";
    }

    private static EvidenceLedger ledger(String title, boolean complete, boolean hasMatch) {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        var source = new EvidenceLedger.EntityFact(1L, title, "POST", "");
        var target = new EvidenceLedger.EntityFact(2L, "校园创新赛", "COMPETITION", "");
        var edge = new EvidenceLedger.RelationFact(10L, 1L, 2L, "RELATED_TO",
            day("2026-03-05"), day("2026-04-01"), "POST:6", 99L, "工程记录与校园创新赛关联。");
        var chunk = new EvidenceLedger.ChunkFact(99L, "POST", 6L, title,
            "工程记录与校园创新赛关联。", 0, 14);
        when(ledger.entities()).thenReturn(List.of(source, target));
        when(ledger.entity(1L)).thenReturn(Optional.of(source));
        when(ledger.entity(2L)).thenReturn(Optional.of(target));
        when(ledger.containsEntity(1L)).thenReturn(true);
        when(ledger.containsEntity(2L)).thenReturn(true);
        when(ledger.relations()).thenReturn(hasMatch ? List.of(edge) : List.of());
        if (hasMatch) when(ledger.relation(10L)).thenReturn(Optional.of(edge));
        when(ledger.chunks()).thenReturn(List.of(chunk));
        when(ledger.chunk(99L)).thenReturn(Optional.of(chunk));
        when(ledger.containsChunk(99L)).thenReturn(true);
        when(ledger.hasToolResult("query_temporal")).thenReturn(true);
        when(ledger.completeTemporal(eq(1L), any(KgTimeRange.class))).thenReturn(complete);
        // completeRelations deliberately stays false: it is not a temporal completeness requirement.
        return ledger;
    }

    private static AgenticGraphRagService service(EvidenceLedger ledger, AiClient ai) {
        EvidenceLedgerFactory factory = mock(EvidenceLedgerFactory.class);
        when(factory.create()).thenReturn(ledger);
        GraphTool temporal = new GraphTool() {
            public String getName() { return "query_temporal"; }
            public String getDescription() { return "isolated temporal fixture"; }
            public String getParametersSchema() { return "{}"; }
            public Object execute(Map<String, Object> params, Long userId) {
                return Map.of("relations", List.of());
            }
        };
        return new AgenticGraphRagService(mock(GraphRagService.class), mock(KgAgentTraceMapper.class),
            ai, List.of(temporal), factory);
    }

    private static EvidenceLedgerFactory factory(EvidenceLedger ledger) {
        EvidenceLedgerFactory factory = mock(EvidenceLedgerFactory.class);
        when(factory.create()).thenReturn(ledger);
        return factory;
    }

    private static GraphTool searchTool(List<EvidenceLedger.EntityFact> entities) {
        return new GraphTool() {
            public String getName() { return "search_entities"; }
            public String getDescription() { return "temporal group fixture"; }
            public String getParametersSchema() { return "{}"; }
            public Object execute(Map<String, Object> params, Long userId) {
                List<Map<String, Object>> rows = entities.stream()
                    .filter(entity -> QueryIntent.normalize(entity.name())
                        .contains(QueryIntent.normalize(String.valueOf(params.get("query")))))
                    .map(entity -> Map.<String, Object>of(
                        "entityId", entity.id(), "name", entity.name(),
                        "entityType", entity.type()))
                    .toList();
                return Map.of("entities", rows);
            }
        };
    }

    private static GraphTool temporalTool(boolean incomplete) {
        return new GraphTool() {
            public String getName() { return "query_temporal"; }
            public String getDescription() { return "temporal group fixture"; }
            public String getParametersSchema() { return "{}"; }
            public Object execute(Map<String, Object> params, Long userId) {
                Long entityId = Long.valueOf(String.valueOf(params.get("entity_id")));
                boolean missing = incomplete && entityId == 1965L;
                long relationId = switch (entityId.intValue()) {
                    case 1725 -> 10L;
                    case 1965 -> 11L;
                    case 1735 -> 12L;
                    default -> throw new IllegalArgumentException("unexpected temporal entity: " + entityId);
                };
                List<Map<String, Object>> relations = missing ? List.of()
                    : List.of(Map.of("relationId", relationId, "sourceId", entityId,
                        "targetId", 1973L, "relationType", "RELATED_TO",
                        "validFrom", "2026-03-25", "validTo", "2026-04-30"));
                Map<?, ?> requested = params.get("time_range") instanceof Map<?, ?> map ? map : Map.of();
                String start = requested.get("start") == null
                    ? "2026-03-26" : String.valueOf(requested.get("start"));
                String end = requested.get("end") == null
                    ? "9999-12-31" : String.valueOf(requested.get("end"));
                return Map.of("relations", relations, "coverage", Map.of(
                    "complete", !missing, "truncated", false, "returned", relations.size(),
                    "scope", Map.of("entityId", entityId,
                        "startInclusive", start,
                        "endExclusive", "9999-12-31".equals(end) ? end
                            : LocalDate.parse(end, DateTimeFormatter.ISO_DATE).plusDays(1).toString())));
            }
        };
    }

    private static EvidenceLedger temporalGroupLedger() {
        Map<Long, KgEntity> entities = new LinkedHashMap<>();
        for (EvidenceLedger.EntityFact fact : List.of(
            new EvidenceLedger.EntityFact(1725L, "iLink 团队空间模块开发成果", "POST", ""),
            new EvidenceLedger.EntityFact(1965L, "Link 团队空间模块", "POST", ""),
            new EvidenceLedger.EntityFact(1735L, "前端页面视觉重构升级报告", "POST", ""),
            new EvidenceLedger.EntityFact(1973L, "挑战杯全国大学生课外学术科技作品竞赛",
                "COMPETITION", ""))) {
            KgEntity entity = new KgEntity();
            entity.setId(fact.id());
            entity.setDisplayName(fact.name());
            entity.setEntityType(fact.type());
            entity.setScope(KgEntity.SCOPE_GLOBAL);
            entity.setStatus(KgEntity.STATUS_ACTIVE);
            entities.put(entity.getId(), entity);
        }
        Map<Long, KgRelation> relations = new LinkedHashMap<>();
        Map<Long, Long> relationSources = Map.of(10L, 1725L, 11L, 1965L, 12L, 1735L);
        relationSources.forEach((relationId, sourceId) -> {
            KgRelation relation = new KgRelation();
            relation.setId(relationId);
            relation.setSourceId(sourceId);
            relation.setTargetId(1973L);
            relation.setRelationType(KgRelation.RELATED_TO);
            relation.setValidFrom(day("2026-03-25"));
            relation.setValidTo(day("2026-04-30"));
            relation.setScope(KgEntity.SCOPE_GLOBAL);
            relations.put(relationId, relation);
        });
        KgEntityMapper entityMapper = mock(KgEntityMapper.class);
        KgRelationMapper relationMapper = mock(KgRelationMapper.class);
        KgDocumentChunkMapper chunkMapper = mock(KgDocumentChunkMapper.class);
        when(entityMapper.selectById(anyLong())).thenAnswer(invocation ->
            entities.get(((Number) invocation.getArgument(0)).longValue()));
        when(relationMapper.selectById(anyLong())).thenAnswer(invocation ->
            relations.get(((Number) invocation.getArgument(0)).longValue()));
        when(chunkMapper.selectById(anyLong())).thenReturn(null);
        KgScopeService scopes = mock(KgScopeService.class);
        when(scopes.canSee(any(KgEntity.class), any())).thenReturn(true);
        when(scopes.canSee(any(), any(), any(), any())).thenReturn(true);
        return new EvidenceLedger(entityMapper, relationMapper, chunkMapper, scopes);
    }

    private static Object last(AgenticGraphRagService.AgenticAnswer answer, String key) {
        return answer.trace.stream().filter(step -> step.containsKey(key)).reduce((first, second) -> second)
            .orElseThrow().get(key);
    }

    private static Date day(String value) {
        return Date.from(LocalDate.parse(value).atStartOfDay(KgTimeRange.ZONE).toInstant());
    }
}
