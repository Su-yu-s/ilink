package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KgTimeRangeTest {
    @Test
    void parsesMonthAndSubMonthWindows() {
        assertRange("2026 年 2 月，平台记录", "2026-02-01", "2026-03-01");
        assertRange("2026 年 3 月上旬有哪些竞赛", "2026-03-01", "2026-03-11");
        assertRange("2026 年 3 月中旬关联了什么", "2026-03-11", "2026-03-21");
        assertRange("2026 年 4 月中下旬有哪些", "2026-04-11", "2026-05-01");
    }

    @Test
    void parsesExplicitDayAndCrossMonthWindows() {
        assertRange("2026 年 3 月 1 日至 3 月 5 日", "2026-03-01", "2026-03-06");
        assertRange("2026 年 3 月中旬（10 日至 20 日）", "2026-03-10", "2026-03-21");
        assertRange("2026 年 3 月 25 日至 4 月 15 日", "2026-03-25", "2026-04-16");
        assertRange("2026 年 3 月 25 日以后", "2026-03-26", "9999-12-31");
        assertRange("2026 年 5 月之后", "2026-06-01", "9999-12-31");
    }

    @Test
    void undatedQuestionHasNoInventedWindow() {
        assertTrue(KgTimeRange.fromQuestion("iLink 有哪些竞赛关联？").isEmpty());
    }

    @Test
    void enumeratedMonthsRemainSeparateWindows() {
        List<KgTimeRange> windows = KgTimeRange.monthWindowsFromQuestion(
            "2026 年 2 月和 4 月都有关联竞赛的记录有哪些？");
        assertEquals(2, windows.size());
        assertEquals(LocalDate.parse("2026-02-01"), windows.get(0).startInclusive());
        assertEquals(LocalDate.parse("2026-03-01"), windows.get(0).endExclusive());
        assertEquals(LocalDate.parse("2026-04-01"), windows.get(1).startInclusive());
        assertEquals(LocalDate.parse("2026-05-01"), windows.get(1).endExclusive());

        List<KgTimeRange> crossYear = KgTimeRange.monthWindowsFromQuestion(
            "2025 年 12 月与 2026 年 1 月都出现了什么？");
        assertEquals(LocalDate.parse("2025-12-01"), crossYear.get(0).startInclusive());
        assertEquals(LocalDate.parse("2026-01-01"), crossYear.get(1).startInclusive());
    }

    @Test
    void rangeBetweenTwoDatesIsNotMisreadAsEnumeratedMonths() {
        assertTrue(KgTimeRange.monthWindowsFromQuestion("2026 年 3 月 25 日至 4 月 15 日").isEmpty());
        assertTrue(KgTimeRange.monthWindowsFromQuestion("2026 年 3 月").isEmpty());
    }

    private static void assertRange(String question, String start, String end) {
        KgTimeRange range = KgTimeRange.fromQuestion(question).orElseThrow();
        assertEquals(LocalDate.parse(start), range.startInclusive());
        assertEquals(LocalDate.parse(end), range.endExclusive());
    }
    @org.junit.jupiter.api.Test
    void evaluationDateAlsoControlsRelationInstant() {
        String oldFlag = System.getProperty("kgEval"), oldDate = System.getProperty("kgEvalDate");
        try {
            System.setProperty("kgEval", "true");
            System.setProperty("kgEvalDate", "2026-09-26");
            org.junit.jupiter.api.Assertions.assertEquals(java.time.LocalDate.of(2026, 9, 26), KgTimeRange.today());
            org.junit.jupiter.api.Assertions.assertEquals(
                java.time.Instant.parse("2026-09-25T16:00:00Z"), KgTimeRange.now().toInstant());
        } finally {
            if (oldFlag == null) System.clearProperty("kgEval"); else System.setProperty("kgEval", oldFlag);
            if (oldDate == null) System.clearProperty("kgEvalDate"); else System.setProperty("kgEvalDate", oldDate);
        }
    }

}
