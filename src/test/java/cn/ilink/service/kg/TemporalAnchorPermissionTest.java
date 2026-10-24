package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T11 根因：时序查询的主体必须来自权限已过滤的 rankedSeeds。
 * 修复只做名称对齐，不扩大可见性边界。
 */
class TemporalAnchorPermissionTest {
    private static final String QUESTION = "2026 年 4 月中下旬，Link 团队空间模块开发日志关联了什么竞赛？";

    @Test
    void parsedSubjectAlignsWithVisibleAnchorAfterTailRemoval() {
        assertEquals(List.of("Link 团队空间模块"), QueryIntent.subjectRequests(QUESTION).subjects());
        EvidenceLedger ledger = ledger(List.of(new EvidenceLedger.EntityFact(1965L, "Link 团队空间模块", "POST", "")));
        assertTrue(QueryIntent.fromQuestion(QUESTION).matchesSubject("Link 团队空间模块"));
        assertFalse(rankedSeeds(QUESTION, ledger).isEmpty(), "可见锚点应被接受");
    }

    @Test
    void invisibleEntityIsStillRejectedEvenWhenNameMatches() {
        // 权限过滤后的账本里没有该实体：名称再匹配也不能授权。
        EvidenceLedger ledger = ledger(List.of());
        assertTrue(QueryIntent.fromQuestion(QUESTION).matchesSubject("Link 团队空间模块"));
        assertTrue(rankedSeeds(QUESTION, ledger).isEmpty(), "不可见实体不得成为时序锚点");
    }

    @Test
    void sameNameEntitiesStayAmbiguousAndGrantNoAnchor() {
        EvidenceLedger ledger = ledger(List.of(
            new EvidenceLedger.EntityFact(1L, "Link 团队空间模块", "POST", ""),
            new EvidenceLedger.EntityFact(2L, "Link 团队空间模块", "POST", "")));
        assertTrue(rankedSeeds(QUESTION, ledger).isEmpty(), "同名实体不构成唯一锚点");
    }

    @Test
    void shorterUnrelatedEntityCannotBePromotedBySubsequence() {
        // 50/51 边界：请求名归一化后 17 字符、实体 10 字符时，旧阈值恰好拒绝；
        // 修复后靠尾部剥离对齐，而不是放宽阈值去接受无关短名。
        EvidenceLedger ledger = ledger(List.of(new EvidenceLedger.EntityFact(3L, "团队空间", "POST", "")));
        assertTrue(rankedSeeds(QUESTION, ledger).isEmpty(), "无关短名不得被提升为锚点");
    }

    /** 复刻 AgenticGraphRagService.rankedSeeds 的授权规则。 */
    private static List<EvidenceLedger.EntityFact> rankedSeeds(String question, EvidenceLedger ledger) {
        QueryIntent intent = QueryIntent.fromQuestion(question);
        java.util.LinkedHashMap<Long, EvidenceLedger.EntityFact> selected = new java.util.LinkedHashMap<>();
        for (String subject : intent.subjects()) {
            List<EvidenceLedger.EntityFact> exact = QueryIntent.bestExactMatches(subject, ledger.entities())
                .stream().map(QueryIntent.ExactSubjectMatch::entity).toList();
            if (exact.size() == 1) {
                selected.put(exact.get(0).id(), exact.get(0));
                continue;
            }
            if (exact.size() > 1) continue;
            QueryIntent single = new QueryIntent(List.of(subject), "", false);
            List<EvidenceLedger.EntityFact> candidates = ledger.entities().stream()
                .filter(entity -> single.matchesSubject(entity.name())).toList();
            if (candidates.size() == 1) selected.put(candidates.get(0).id(), candidates.get(0));
        }
        return List.copyOf(selected.values());
    }

    private static EvidenceLedger ledger(List<EvidenceLedger.EntityFact> entities) {
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.entities()).thenReturn(entities);
        for (EvidenceLedger.EntityFact entity : entities)
            when(ledger.entity(entity.id())).thenReturn(Optional.of(entity));
        return ledger;
    }
}
