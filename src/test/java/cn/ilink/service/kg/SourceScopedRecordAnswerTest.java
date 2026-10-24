package cn.ilink.service.kg;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgEntity;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.mapper.KgEntityMapper;
import cn.ilink.mapper.KgRelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The named-record source exit releases an already verified source answer as what it is, instead of
 * prefixing it with a partial-answer label. Every other combination must keep the limited exit.
 */
class SourceScopedRecordAnswerTest {

    private static final String QUESTION = "《java面试的一些要点》在图谱里关联的技能中，与「代理」相关的有哪些？";
    private static final String DRAFT = "《java面试的一些要点》中与「代理」相关的技能：动态代理、JDK 动态代理、"
        + "CGLIB 动态代理 (c#345)。";

    /**
     * Mirrored copy of the completeness rule the public scorer applies to every algorithm. It is
     * copied on purpose: production code must not depend on the scorer, but a released answer must
     * not look like a self-declared partial result either.
     */
    private static final Pattern SCORER_INCOMPLETE = Pattern.compile(
        "供核对部分内容|结果仅覆盖本次可核对部分|以下列出已核对的部分结果"
            + "|(?:尚不能|无法|不能)(?:完整)?(?:确认|证实)(?:所问|上述)?的?图谱(?:关联|关系|集合)"
            + "|无法确认图谱中是否存在关联边"
            + "|(?:无法|不能|尚未)确认(?:上述|这些|所问)?(?:技能|实体|节点)[^。！？；]{0,100}"
            + "图谱中[^。！？；]{0,50}(?:关联|关系)");

    private EvidenceLedger ledger;
    private GraphAnswerScope.Scope scope;

    @BeforeEach
    void setUp() {
        KgEntityMapper entities = mock(KgEntityMapper.class);
        KgRelationMapper relations = mock(KgRelationMapper.class);
        KgDocumentChunkMapper chunks = mock(KgDocumentChunkMapper.class);
        KgScopeService scopes = mock(KgScopeService.class);
        when(scopes.canSee(any(KgEntity.class), any())).thenReturn(true);
        when(scopes.canSee(any(), any(), any(), any())).thenReturn(true);
        when(scopes.canSeeChunk(any(KgDocumentChunk.class), any())).thenReturn(true);
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setId(345L);
        chunk.setDocType("POST");
        chunk.setDocId(5L);
        chunk.setTitle("java面试的一些要点");
        chunk.setContent("动态代理有几种实现方式？JDK 动态代理和 CGLIB 动态代理有什么区别？");
        chunk.setScope(KgEntity.SCOPE_GLOBAL);
        when(chunks.selectById(345L)).thenReturn(chunk);
        ledger = new EvidenceLedger(entities, relations, chunks, scopes);
        ledger.addToolResult("search_documents", Map.of("chunks", List.of(Map.of(
            "chunkId", 345L, "docType", "POST", "docId", 5L, "title", "java面试的一些要点"))), 8L);
        scope = new GraphAnswerScope.Scope(true, true, false, true, "", Set.of(), 0, 0, false);
    }

    private static AnswerEvidenceVerifier.VerificationResult check(boolean supported,
                                                                   Set<Long> relations, Set<Long> chunks) {
        return new AnswerEvidenceVerifier.VerificationResult(supported, List.of(), Set.of(), relations, chunks);
    }

    private static AnswerEvidenceVerifier.VerificationResult check(boolean supported, List<String> reasons,
                                                                   Set<Long> relations, Set<Long> chunks) {
        return new AnswerEvidenceVerifier.VerificationResult(supported, reasons, Set.of(), relations, chunks);
    }

    @Test
    void releasesVerifiedSourceAnswerAboutANamedRecordWithItsBasis() {
        List<Map<String, Object>> trace = new ArrayList<>();
        AgenticGraphRagService.AgenticAnswer answer = AgenticGraphRagService.sourceScopedRecordAnswer(
            QUESTION, DRAFT, check(true, Set.of(), Set.of(345L)), scope, ledger, trace, 1, 3);

        assertNotNull(answer, "A verified answer about the named record must not be relabelled as partial.");
        assertTrue(answer.answer.startsWith("依据《java面试的一些要点》（POST:5）的本次授权原文："),
            answer.answer);
        assertTrue(answer.answer.contains(DRAFT), answer.answer);
        assertEquals(1, answer.citations.size());
        assertEquals(345L, answer.citations.get(0).get("chunkId"));
        assertEquals("POST", answer.citations.get(0).get("docType"));
        assertEquals(5L, answer.citations.get(0).get("docId"));
        assertEquals("SOURCE_SCOPED_RECORD_ANSWER", trace.get(0).get("answerMode"));
        assertEquals("SOURCE_SCOPED", trace.get(0).get("answerCoverage"));
        assertEquals("PARTIAL", trace.get(0).get("graphCoverage"));
    }

    @Test
    void releasedAnswerDoesNotReadAsASelfDeclaredPartialResult() {
        AgenticGraphRagService.AgenticAnswer answer = AgenticGraphRagService.sourceScopedRecordAnswer(
            QUESTION, DRAFT, check(true, Set.of(), Set.of(345L)), scope, ledger, new ArrayList<>(), 1, 3);

        assertNotNull(answer);
        String prose = answer.answer.replaceAll("(?s)```.*?```|“[^”]*”|\"[^\"]*\"", "")
            .replaceAll("[*`_\\s]", "");
        assertTrue(!SCORER_INCOMPLETE.matcher(prose).find(),
            "The released wording must describe its basis without declaring itself incomplete: " + prose);
    }

    @Test
    void answerThatAlreadyDeclaresItselfPartialKeepsTheLimitedExit() {
        String partial = "以下列出已核对的部分结果：动态代理、JDK 动态代理 (c#345)。";
        assertNull(AgenticGraphRagService.sourceScopedRecordAnswer(
            QUESTION, partial, check(true, Set.of(), Set.of(345L)), scope, ledger, new ArrayList<>(), 1, 3));
    }

    @Test
    void answerThatClaimsAnExhaustiveListKeepsTheLimitedExit() {
        String exhaustive = "该文档关联的完整列表为：动态代理、JDK 动态代理 (c#345)。";
        assertNull(AgenticGraphRagService.sourceScopedRecordAnswer(
            QUESTION, exhaustive, check(true, Set.of(), Set.of(345L)), scope, ledger, new ArrayList<>(), 1, 3));
    }

    @Test
    void answerThatUsesRelationEvidenceKeepsTheLimitedExit() {
        assertNull(AgenticGraphRagService.sourceScopedRecordAnswer(QUESTION, DRAFT,
            check(true, Set.of(7L), Set.of(345L)), scope, ledger, new ArrayList<>(), 1, 3));
    }

    @Test
    void unsupportedFactsKeepTheLimitedExit() {
        assertNull(AgenticGraphRagService.sourceScopedRecordAnswer(QUESTION, DRAFT,
            check(false, Set.of(), Set.of(345L)), scope, ledger, new ArrayList<>(), 1, 3));
    }

    /**
     * The 2026-10-17 M4 run reached the partial-answer label through the coverage-only branch, which
     * precedes the supported path. That branch must consult the exit itself, otherwise the fix never
     * applies to the case it was written for.
     */
    @Test
    void coverageOnlyFailureReleasesTheNamedRecordAnswer() {
        List<Map<String, Object>> trace = new ArrayList<>();
        AgenticGraphRagService.AgenticAnswer answer = AgenticGraphRagService.sourceScopedRecordAnswer(
            QUESTION, DRAFT,
            check(false, List.of("SEMANTIC_UNSUPPORTED:COVERAGE:只有原文支持，图谱关系尚未完整确认"),
                Set.of(), Set.of(345L)),
            scope, ledger, trace, 1, 3);

        assertNotNull(answer, "A coverage-only verdict must not relabel the named record's source answer.");
        assertTrue(answer.answer.startsWith("依据《java面试的一些要点》（POST:5）的本次授权原文："),
            answer.answer);
        assertEquals("SOURCE_SCOPED_RECORD_ANSWER", trace.get(0).get("answerMode"));
    }

    @Test
    void unsupportedFactReasonKeepsTheLimitedExit() {
        assertNull(AgenticGraphRagService.sourceScopedRecordAnswer(QUESTION, DRAFT,
            check(false, List.of("SEMANTIC_UNSUPPORTED:FACTS:与原文矛盾"), Set.of(), Set.of(345L)),
            scope, ledger, new ArrayList<>(), 1, 3));
    }

    @Test
    void uncertainFactReasonKeepsTheLimitedExit() {
        assertNull(AgenticGraphRagService.sourceScopedRecordAnswer(QUESTION, DRAFT,
            check(false, List.of("SEMANTIC_UNCERTAIN:FACTS:无法确认"), Set.of(), Set.of(345L)),
            scope, ledger, new ArrayList<>(), 1, 3));
    }

    @Test
    void answerWithoutANamedRecordTitleKeepsTheLimitedExit() {
        String other = "动态代理、JDK 动态代理、CGLIB 动态代理 (c#345)。";
        assertNull(AgenticGraphRagService.sourceScopedRecordAnswer(
            "iLink 平台本身（ilink 实体）直接关联了哪些竞赛？", other,
            check(true, Set.of(), Set.of(345L)), scope, ledger, new ArrayList<>(), 1, 3));
    }
}
