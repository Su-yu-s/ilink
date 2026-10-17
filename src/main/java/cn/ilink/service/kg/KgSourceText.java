package cn.ilink.service.kg;

import org.jsoup.Jsoup;

/** Normalizes stored rich text before extraction and evidence indexing. */
final class KgSourceText {
    private KgSourceText() { }

    static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String withoutDataUris = raw.replaceAll(
            "(?i)data:[a-z0-9.+/-]+;base64,[a-z0-9+/=\\s]+", "");
        if (withoutDataUris.matches("(?s).*<[/!]?[A-Za-z][^>]*>.*")) {
            return Jsoup.parse(withoutDataUris).text().replace('\u00a0', ' ').trim();
        }
        return withoutDataUris.trim();
    }
}
