package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 请求主体解析：旬、文档尾部、括号同位语与通用中心词。 */
class QueryIntentSubjectTest {
    @Test
    void xunIsATimeRangeNotPartOfTheSubject() {
        assertEquals(List.of("iLink 交流社区"),
            QueryIntent.subjectRequests("2026 年 3 月上旬，iLink 交流社区的记录关联了什么竞赛？").subjects());
        assertEquals(List.of("Link 团队空间模块"),
            QueryIntent.subjectRequests("2026 年 4 月中下旬，Link 团队空间模块开发日志关联了什么竞赛？").subjects());
        assertEquals(List.of("iLink 平台（Agentic GraphRAG 项目）"),
            QueryIntent.subjectRequests("2026 年 3 月中旬，iLink 平台（Agentic GraphRAG 项目）的开发记录关联了哪个竞赛？").subjects());
    }

    @Test
    void documentTailIsStrippedButDemonstrativesKeepTheirHead() {
        assertEquals(List.of("Agent 记忆机制"),
            QueryIntent.subjectRequests("2026 年 2 月，Agent 记忆机制相关的记录关联了哪个程序设计类竞赛？").subjects());
        assertEquals(List.of("Link 团队空间模块"),
            QueryIntent.subjectRequests("2026 年 5 月，Link 团队空间模块的记录还提到哪些竞赛？").subjects());
        // “那份开发日志”本身是所指对象，剥掉只剩指示代词。
        assertEquals(List.of("那份开发日志完成了"),
            QueryIntent.subjectRequests("6 月 22 日那份开发日志完成了哪个功能，覆盖哪几类业务？").subjects());
        // “平台记录”是主体的一部分，不是请求尾部。
        assertEquals(List.of("平台记录"),
            QueryIntent.subjectRequests("2026 年 2 月，平台记录与哪些竞赛有关联？").subjects());
        assertEquals(List.of("团队空间", "视觉重构相关记录"),
            QueryIntent.subjectRequests("2026 年 3 月 25 日以后，团队空间与视觉重构相关记录还关联着哪些竞赛？")
                .subjects());
    }

    @Test
    void parentheticalAliasAlignsRequestedNameWithAuthorizedEntity() {
        assertTrue(QueryIntent.sameSubjectName("iLink 平台（Agentic GraphRAG 项目）", "iLink Agentic GraphRAG"));
        assertFalse(QueryIntent.sameSubjectName("iLink 平台（Agentic GraphRAG 项目）", "iLink交流社区"));
        // 原始请求名保持不变，便于审计。
        assertEquals(List.of("iLink 平台（Agentic GraphRAG 项目）"),
            QueryIntent.subjectRequests("2026 年 3 月中旬，iLink 平台（Agentic GraphRAG 项目）的开发记录关联了哪个竞赛？").subjects());
    }

    @Test
    void explicitDateRangeInParenthesesIsNotASubject() {
        assertTrue(QueryIntent.subjectRequests("2026 年 3 月中旬（10 日至 20 日）有哪些竞赛关联记录？").subjects().isEmpty());
    }

    @Test
    void trailingAdverbAndSelfReferenceDoNotBlockTheEntityMatch() {
        // “直接” 是情态副词，不是名称的一部分；主体里保留它会让 #1713 ilink 永远匹配不上。
        assertEquals(List.of("iLink 平台本身（ilink 实体）"),
            QueryIntent.subjectRequests("知识图谱里 iLink 平台本身（ilink 实体）直接关联了哪些竞赛？").subjects());
        assertTrue(QueryIntent.sameSubjectName("iLink 平台本身（ilink 实体）", "ilink"));
        // 括号内外是同一个对象的两种写法，必须都能对齐；拼接形式只为兼容 “平台（项目）” 命名。
        assertTrue(QueryIntent.sameSubjectName("iLink 平台（Agentic GraphRAG 项目）", "iLink Agentic GraphRAG"));
        assertTrue(QueryIntent.sameSubjectName("iLink 平台（Agentic GraphRAG 项目）", "Agentic GraphRAG"));
        assertFalse(QueryIntent.sameSubjectName("iLink 平台本身（ilink 实体）", "iLink交流社区"));
    }

    @Test
    void coreNameFormIsOnlyKeptWhenItStillCarriesTheName() {
        // “学习平台” 去掉中心词后只剩两个字，不作为候选，避免过短形式误配。
        assertFalse(QueryIntent.aliasForms("学习平台").contains("学习"));
        assertTrue(QueryIntent.aliasForms("iLink 平台本身").contains("ilink"));
    }

    @Test
    void strongestAliasTierResolvesT3WithoutLettingBareShortNameCompete() {
        List<EvidenceLedger.EntityFact> entities = List.of(
            new EvidenceLedger.EntityFact(1713L, "ilink", "", ""),
            new EvidenceLedger.EntityFact(1968L, "iLink Agentic GraphRAG", "", ""));

        List<QueryIntent.ExactSubjectMatch> matches = QueryIntent.bestExactMatches(
            "iLink 平台（Agentic GraphRAG 项目）", entities);

        assertEquals(1, matches.size());
        assertEquals(1968L, matches.get(0).entity().id());
        assertEquals(QueryIntent.AliasTier.COMPOSITE, matches.get(0).tier());
        assertEquals("ilinkagenticgraphrag", matches.get(0).matchedAlias());
    }

    @Test
    void sameStrongestAliasStillRemainsAmbiguous() {
        List<EvidenceLedger.EntityFact> entities = List.of(
            new EvidenceLedger.EntityFact(1L, "ilink", "", ""),
            new EvidenceLedger.EntityFact(2L, "iLink", "", ""));

        List<QueryIntent.ExactSubjectMatch> matches = QueryIntent.bestExactMatches(
            "iLink 平台本身（ilink 实体）", entities);

        assertEquals(List.of(1L, 2L), matches.stream().map(match -> match.entity().id()).toList());
        assertTrue(matches.stream().allMatch(match -> match.tier() == QueryIntent.AliasTier.PARENTHETICAL));
    }

    @Test
    void explicitSelfReferenceStillResolvesToBareEntity() {
        List<EvidenceLedger.EntityFact> entities = List.of(
            new EvidenceLedger.EntityFact(1713L, "ilink", "", ""),
            new EvidenceLedger.EntityFact(1968L, "iLink Agentic GraphRAG", "", ""));

        List<QueryIntent.ExactSubjectMatch> matches = QueryIntent.bestExactMatches(
            "iLink 平台本身（ilink 实体）", entities);

        assertEquals(1, matches.size());
        assertEquals(1713L, matches.get(0).entity().id());
    }

    @Test
    void requestedEntityTypeComesFromTheQuestionFieldNotTheSubjectName() {
        QueryIntent competition = QueryIntent.fromQuestion(
            "知识图谱里 iLink 平台本身（ilink 实体）直接关联了哪些竞赛？");
        assertEquals("COMPETITION", competition.requestedEntityType());
        assertFalse(competition.requestedEntityType().equals("SKILL"));

        QueryIntent source = QueryIntent.fromQuestion("《应用设计》关联的帖子有哪些？");
        assertEquals("POST", source.requestedEntityType());
        QueryIntent skills = QueryIntent.fromQuestion("《应用设计》关联的技术栈有哪些？");
        assertEquals("SKILL", skills.requestedEntityType());
    }
}
