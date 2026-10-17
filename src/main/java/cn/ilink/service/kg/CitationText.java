package cn.ilink.service.kg;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Disambiguate CSS literals from bare numeric evidence references, preserving offsets. */
public final class CitationText {
    private static final Pattern HEX = Pattern.compile(
        "(?i)(?<![\\p{L}\\p{N}#])#(?:[0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{3})(?![0-9a-z])");
    // 裸“背景 / 底 / 文字 / 前景 / 主色”在视觉描述里就是颜色语境；Markdown 的 ** 与引号先剥掉再匹配。
    private static final Pattern COLOR_PREFIX = Pattern.compile(
        "(?i)(?:颜色|色值|底色|文字色|背景色|背景|前景|主色|强调色|配色|底|文字|字体色|color|background)"
            + "(?:为|是|：|:|=|\\s|`|\"|“|\\*|_|、|，|,|（|\\(|【){0,10}$");
    private static final Pattern COLOR_SUFFIX = Pattern.compile(
        "(?i)^(?:\\*|`|\"|”|\\s|、|，|,|）|\\)|】)*(?:底色|背景色|背景|文字色|文字|前景|主色|色值|颜色|底|、|，|,)");
    // Markdown table cells have “|” before the attribute and value:
    // “| 页面底色 | #fafafa |” and “| 文字色 | #171717 |”.
    private static final Pattern TABLE_COLOR_PREFIX = Pattern.compile(
        "(?i)\\|[^|\\r\\n]{0,30}(?:颜色|色值|底色|文字色|背景色|背景|前景|主色|强调色|配色)[^|\\r\\n]{0,20}\\|[^|\\r\\n]{0,12}$");
    // 引用块 c#262 原文里的 “背景 #fafafa”“#171717 文字” 也属于颜色语境。
    private static final Pattern QUOTED_EVIDENCE = Pattern.compile("[“\"'][^”\"']{0,40}$");

    private CitationText() { }

    public static String forReferences(String answer) {
        if (answer == null) {
            return "";
        }
        StringBuilder masked = new StringBuilder(answer);
        Matcher hex = HEX.matcher(answer);
        while (hex.find()) {
            String before = answer.substring(Math.max(0, hex.start() - 40), hex.start());
            String after = answer.substring(hex.end(), Math.min(answer.length(), hex.end() + 16));
            boolean quotedEvidence = QUOTED_EVIDENCE.matcher(before).find()
                && COLOR_SUFFIX.matcher(after).find();
            if (COLOR_PREFIX.matcher(before).find() || TABLE_COLOR_PREFIX.matcher(before).find()
                || COLOR_SUFFIX.matcher(after).find() || quotedEvidence) {
                masked.replace(hex.start(), hex.end(), " ".repeat(hex.end() - hex.start()));
            }
        }
        return masked.toString();
    }
}
