package cn.ilink.util;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * 富文本入库前清洗，降低 XSS 风险。
 */
public final class HtmlSanitizer {

    private static final Safelist COMMUNITY_POST_SAFELIST = Safelist.relaxed()
        .addTags("span")
        .addAttributes("span", "class", "data-ilink-markdown", "hidden", "aria-hidden");

    private HtmlSanitizer() {
    }

    public static String communityPost(String html) {
        if (html == null) {
            return "";
        }
        String s = html.trim();
        if (s.isEmpty()) {
            return "";
        }
        return Jsoup.clean(s, COMMUNITY_POST_SAFELIST);
    }

    /** 列表摘要：从 HTML 提取纯文本 */
    public static String plainTextExcerpt(String html, int maxLen) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String t = Jsoup.parse(html).text();
        if (t.length() > maxLen) {
            return t.substring(0, maxLen) + "…";
        }
        return t;
    }
}
