package cn.ilink;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防止前端再次使用错误的 /api/teams 复数路径。
 */
class ApiPathContractTest {

    private static final Path JS_ROOT = Path.of("src/main/resources/static/js");

    @Test
    void staticJsMustNotUseLegacyTeamsApiPath() throws Exception {
        try (Stream<Path> paths = Files.walk(JS_ROOT)) {
            List<String> offenders = paths
                .filter(p -> p.toString().endsWith(".js"))
                .flatMap(p -> {
                    try {
                        String content = Files.readString(p, StandardCharsets.UTF_8);
                        if (content.contains("/api/teams")) {
                            return Stream.of(p.toString().replace('\\', '/'));
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return Stream.empty();
                })
                .toList();

            assertTrue(offenders.isEmpty(),
                "Found legacy /api/teams paths in: " + offenders);
        }
    }

    @Test
    void chatJsUsesCorrectTeamApiPrefix() throws Exception {
        Path chatJs = JS_ROOT.resolve("team-chat.js");
        assertTrue(Files.exists(chatJs), "team-chat.js should exist");
        String content = Files.readString(chatJs, StandardCharsets.UTF_8);
        assertTrue(content.contains("/team/"), "team-chat should call /api/team via request()");
        assertFalse(content.contains("/api/teams"), "team-chat must not use /api/teams");
        assertFalse(content.contains("renderMockMembers"), "mock members fallback removed");
        assertFalse(content.contains("loadMockHistory"), "mock history fallback removed");
    }

    @Test
    void galleryTemplateReadsPaginationFromExtra() throws Exception {
        Path galleryJs = Path.of("src/main/resources/static/js/gallery.js");
        assertTrue(Files.exists(galleryJs), "gallery.js should exist");
        String content = Files.readString(galleryJs, StandardCharsets.UTF_8);
        assertTrue(content.contains("d.extra && d.extra.pagination"),
            "gallery must resolve pagination from Result.extra");
        assertFalse(content.contains("render(d.data, d.pagination)"),
            "gallery must not read pagination only from top-level field");
    }

    @Test
    void primaryPagesUseUnifiedSearchFieldFragment() throws Exception {
        List<String> pages = List.of(
            "gallery.html",
            "team-market.html",
            "teacher-wall.html",
            "community.html",
            "competitions.html",
            "admin.html"
        );
        for (String name : pages) {
            Path file = Path.of("src/main/resources/templates", name);
            assertTrue(Files.exists(file), name + " should exist");
            String content = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(
                content.contains("fragments/search-field"),
                name + " must use fragments/search-field"
            );
        }
        Path fragment = Path.of("src/main/resources/templates/fragments/search-field.html");
        String frag = Files.readString(fragment, StandardCharsets.UTF_8);
        assertTrue(frag.contains("il-search-field"), "search-field fragment must define il-search-field");
    }

    @Test
    void staticJsPostMutationsShouldUseApiFetchOrRequest() throws Exception {
        try (Stream<Path> paths = Files.walk(JS_ROOT)) {
            List<String> offenders = paths
                .filter(p -> p.toString().endsWith(".js"))
                .filter(p -> !p.getFileName().toString().equals("common.js"))
                .flatMap(p -> {
                    try {
                        String content = Files.readString(p, StandardCharsets.UTF_8);
                        if (containsUnsafePostFetch(content)) {
                            return Stream.of(p.toString().replace('\\', '/'));
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return Stream.empty();
                })
                .toList();

            assertTrue(offenders.isEmpty(),
                "POST/PUT/DELETE must use apiFetch() or request(), not raw fetch(): " + offenders);
        }
    }

    private static boolean containsUnsafePostFetch(String content) {
        String[] methods = {"POST", "PUT", "DELETE", "PATCH"};
        int idx = 0;
        while ((idx = content.indexOf("fetch(", idx)) >= 0) {
            int start = Math.max(0, idx - 120);
            String snippet = content.substring(start, Math.min(content.length(), idx + 80));
            for (String method : methods) {
                if (snippet.contains("method:'" + method) || snippet.contains("method:\"" + method)
                    || snippet.contains("method: '" + method) || snippet.contains("method: \"" + method)) {
                    return true;
                }
            }
            idx += 6;
        }
        return false;
    }

    @Test
    void commonJsExportsApiFetch() throws Exception {
        Path common = JS_ROOT.resolve("common.js");
        String content = Files.readString(common, StandardCharsets.UTF_8);
        assertTrue(content.contains("function apiFetch"), "common.js must define apiFetch");
        assertTrue(content.contains("apiFetch: apiFetch"), "ILink namespace must export apiFetch");
        assertTrue(content.contains("showFieldHint: showFieldHint"), "ILink namespace must export showFieldHint");
    }

    @Test
    void listPagesResolvePaginationFromExtra() throws Exception {
        List<String> listJs = List.of("team-market.js", "teacher-wall.js", "gallery.js");
        for (String name : listJs) {
            Path file = JS_ROOT.resolve(name);
            assertTrue(Files.exists(file), name + " should exist");
            String content = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(
                content.contains("extra.pagination") || content.contains("extra && result.extra.pagination"),
                name + " should read pagination from extra"
            );
        }
    }
}
