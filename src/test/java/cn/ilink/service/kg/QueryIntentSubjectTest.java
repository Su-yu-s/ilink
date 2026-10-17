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
}
