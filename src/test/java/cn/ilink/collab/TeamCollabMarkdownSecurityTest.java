package cn.ilink.collab;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security gates for collaboration documents: HTML/XSS defense in depth,
 * dangerous URL protocols, export filename traversal, and content budget.
 */
class TeamCollabMarkdownSecurityTest {

    @Test
    void scriptTagsAndEventHandlersAreRemovedFromRenderedHtml() {
        String dirty = "<p onclick=\"steal()\">标题</p>"
            + "<script>alert(1)</script>"
            + "<img src=\"x\" onerror=\"alert(2)\">"
            + "<iframe src=\"https://evil.example\"></iframe>";

        String clean = TeamCollabMarkdown.sanitizeHtmlFragment(dirty);

        assertFalse(clean.contains("<script"), clean);
        assertFalse(clean.contains("onclick"), clean);
        assertFalse(clean.contains("onerror"), clean);
        assertFalse(clean.contains("<iframe"), clean);
        assertTrue(clean.contains("标题"), clean);
    }

    @Test
    void dangerousLinkProtocolsAreStrippedButHttpAndMailtoSurvive() {
        String dirty = "<a href=\"javascript:alert(1)\">js</a>"
            + "<a href=\"data:text/html;base64,PHNjcmlwdD4=\">data</a>"
            + "<a href=\"vbscript:msgbox(1)\">vbs</a>"
            + "<a href=\"https://ilink.work/doc\">https</a>"
            + "<a href=\"mailto:team@demo.local\">mail</a>";

        String clean = TeamCollabMarkdown.sanitizeHtmlFragment(dirty);

        assertFalse(clean.contains("javascript:"), clean);
        assertFalse(clean.contains("data:"), clean);
        assertFalse(clean.contains("vbscript:"), clean);
        assertTrue(clean.contains("https://ilink.work/doc"), clean);
        assertTrue(clean.contains("mailto:team@demo.local"), clean);
    }

    @Test
    void inlineStyleAttributesAreRemoved() {
        String clean = TeamCollabMarkdown.sanitizeHtmlFragment(
            "<p style=\"position:fixed;top:0\">说明</p>");
        assertFalse(clean.contains("style="), clean);
        assertTrue(clean.contains("说明"), clean);
    }

    @Test
    void exportFilenameNeutralizesTraversalAndReservedWindowsNames() {
        String traversal = TeamCollabMarkdown.exportFilename("..\\..\\etc/passwd", ".md");
        assertFalse(traversal.contains("/"), traversal);
        assertFalse(traversal.contains("\\"), traversal);
        assertTrue(traversal.endsWith(".md"), traversal);

        String reserved = TeamCollabMarkdown.exportFilename("CON", ".md");
        assertTrue(reserved.startsWith("_"), reserved);
        assertEquals("_CON.md", reserved);

        assertEquals("a_b_c.md", TeamCollabMarkdown.exportFilename("a/b:c", ".md"));
        assertEquals("团队文档.md", TeamCollabMarkdown.exportFilename("   ", ".md"));

        String longest = TeamCollabMarkdown.exportFilename("标".repeat(200), ".md");
        assertTrue(longest.length() <= 120, "length=" + longest.length());
    }

    @Test
    void normalizeRejectsOversizedContentAndStripsControlCharacters() {
        String control = "正文\u0000\u0007尾";
        assertEquals("正文尾", TeamCollabMarkdown.normalize(control));
        assertEquals("两行\n正文", TeamCollabMarkdown.normalize("两行\r\n正文"));

        String tooLarge = "字".repeat(TeamCollabMarkdown.MAX_CHARS + 1);
        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> TeamCollabMarkdown.normalize(tooLarge));
        assertEquals(TeamCollabException.Code.CONTENT_TOO_LARGE, error.code());
    }

    @Test
    void hashIsStableForSameContentAndDifferentForDifferentContent() {
        assertEquals(TeamCollabMarkdown.hash("同一份正文"),
            TeamCollabMarkdown.hash("同一份正文"));
        assertFalse(TeamCollabMarkdown.hash("同一份正文")
            .equals(TeamCollabMarkdown.hash("同一份正文 ")));
    }
}
