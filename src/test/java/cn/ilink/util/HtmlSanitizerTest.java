package cn.ilink.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlSanitizerTest {

    @Test
    void communityPostPreservesMarkdownSourceMarkerAndFormatting() {
        String input = "<span class=\"ilink-markdown-source\" data-ilink-markdown=\"IyDmtYvor5U=\" "
            + "hidden aria-hidden=\"true\"></span>"
            + "<h2>标题</h2><table><thead><tr><th>列</th></tr></thead>"
            + "<tbody><tr><td><strong>值</strong></td></tr></tbody></table>"
            + "<script>alert(1)</script>";

        String cleaned = HtmlSanitizer.communityPost(input);

        assertTrue(cleaned.contains("class=\"ilink-markdown-source\""));
        assertTrue(cleaned.contains("data-ilink-markdown=\"IyDmtYvor5U=\""));
        assertTrue(cleaned.contains("<h2>标题</h2>"));
        assertTrue(cleaned.contains("<table>"));
        assertTrue(cleaned.contains("<strong>值</strong>"));
        assertFalse(cleaned.contains("<script"));
    }
}
