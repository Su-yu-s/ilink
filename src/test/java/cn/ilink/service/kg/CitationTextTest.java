package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CitationTextTest {
    @Test
    void numericColorIsNotAnEvidenceIdButRealCitationRemainsRequired() {
        String answer = "页面底色为 #fafafa，文字色为 #171717。c#99";
        String refs = CitationText.forReferences(answer);
        assertEquals(answer.length(), refs.length());
        assertFalse(refs.contains("#171717"));
        assertTrue(refs.contains("c#99"));
        assertTrue(PlainRagService.hasOnlyKnownCitations(answer, Set.of(99L)));
        assertFalse(PlainRagService.hasOnlyKnownCitations(answer, Set.of(88L)));
        assertFalse(PlainRagService.hasOnlyKnownCitations("文字色为 #171717", Set.of(99L)));
    }

    @Test
    void sixDigitIdsWithoutColorContextAreStillValidated() {
        assertTrue(CitationText.forReferences("来源 #123456").contains("#123456"));
        assertFalse(PlainRagService.hasOnlyKnownCitations("来源 #123456", Set.of(99L)));
    }
    @Test void nearbyColorMustNotHideUnknownNumericCitation() {
        String answer = "文字色为 #171717，来源 #999。c#99";
        assertTrue(CitationText.forReferences(answer).contains("#999"));
        assertFalse(PlainRagService.hasOnlyKnownCitations(answer, Set.of(99L)));
    }

    /** E20 的真实形态：Markdown 加粗 + 裸“背景”“文字”。 */
    @Test void markdownBoldAndBareColorWordsAreMasked() {
        String answer = "根据证据 c#262 和 c#341，团队空间简白风格的页面底色为 **#fafafa**，文字色为 **#171717**。\n"
            + "*   **页面底色**：#fafafa\n    *   依据 c#262：“背景 #fafafa”\n"
            + "*   **文字色**：#171717\n    *   依据 c#341：“#171717 文字”";
        String refs = CitationText.forReferences(answer);
        assertEquals(answer.length(), refs.length());
        assertFalse(refs.contains("#fafafa"));
        assertFalse(refs.contains("#171717"));
        assertTrue(refs.contains("c#262"));
        assertTrue(refs.contains("c#341"));
    }

    /** 10-17 的 E20 真实草稿：首句之后还有一张 Markdown 颜色表。 */
    @Test void markdownTableColorRowsAreMasked() {
        String answer = "根据证据 c#262 和 c#341，团队空间简白风格的页面底色为 **#fafafa**，文字色为 **#171717**。\n\n"
            + "| 属性 | 颜色值 | 证据来源 |\n"
            + "| :--- | :--- | :--- |\n"
            + "| 页面底色 | #fafafa | c#262, c#341 |\n"
            + "| 文字色 | #171717 | c#341 |";
        String refs = CitationText.forReferences(answer);
        assertEquals(answer.length(), refs.length());
        assertFalse(refs.contains("#fafafa"));
        assertFalse(refs.contains("#171717"));
        assertTrue(refs.contains("c#262"));
        assertTrue(refs.contains("c#341"));
    }

    /** 颜色语境不得吞掉真实引用。 */
    @Test void colorContextNeverMasksRealNumericCitations() {
        for (String answer : List.of("文字色为 #171717，来源 #999。", "背景 #fafafa，另见 #888。",
            "来源 #123456", "依据 #1234 与 #5678")) {
            String refs = CitationText.forReferences(answer);
            for (String real : List.of("#999", "#888", "#123456", "#1234", "#5678")) {
                if (answer.contains(real)) assertTrue(refs.contains(real), answer + " 误吞 " + real);
            }
        }
    }
}
