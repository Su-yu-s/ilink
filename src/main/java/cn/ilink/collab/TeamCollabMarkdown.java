package cn.ilink.collab;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Deterministic Markdown normalization and URL policy. */
public final class TeamCollabMarkdown {
    public static final int MAX_CHARS = 200 * 1024;

    private TeamCollabMarkdown() {
    }

    public static String normalize(String raw) {
        String value = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        value = value.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        if (value.length() > MAX_CHARS) {
            throw new TeamCollabException(TeamCollabException.Code.CONTENT_TOO_LARGE,
                "文档正文不能超过 200 KiB");
        }
        return value;
    }

    /**
     * Server-side defense-in-depth for any HTML fragment that is later rendered
     * as HTML. Markdown source itself is stored unchanged; the browser uses
     * DOMPurify for its final rendering pass.
     */
    public static String sanitizeHtmlFragment(String html) {
        Safelist safelist = Safelist.relaxed()
            .addTags("details", "summary", "del", "s", "input")
            .addAttributes("a", "href", "title", "rel")
            .addAttributes("img", "src", "alt", "title")
            .addAttributes("input", "type", "checked", "disabled")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https")
            .removeTags("iframe", "object", "embed", "form", "script", "style", "link", "meta")
            .removeAttributes(":all", "style", "onerror", "onload", "onclick");
        return Jsoup.clean(html == null ? "" : html, safelist);
    }

    public static String hash(String markdown) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(("iLink/team-collab/document/v1\u0000" + markdown)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String exportFilename(String title, String extension) {
        String stem = title == null ? "团队文档" : title.trim();
        stem = stem.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]", "_").trim();
        if (stem.isEmpty()) stem = "团队文档";
        String[] reserved = {"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4",
            "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4",
            "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"};
        String upper = stem.toUpperCase(Locale.ROOT);
        for (String name : reserved) {
            if (upper.equals(name) || upper.startsWith(name + ".")) {
                stem = "_" + stem;
                break;
            }
        }
        int maxStem = Math.max(1, 120 - extension.length());
        if (stem.length() > maxStem) stem = stem.substring(0, maxStem).trim();
        return stem + extension;
    }
}
