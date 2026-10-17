package cn.ilink.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * 全站文案字典（客户端运行时使用）。
 * <p>
 * 早期版本把两份目录（当前语言 + 中文原文）直接内联进每个页面，单页 HTML 因此多出约 250KB。
 * 改为同源、可缓存的 JS 文件后：HTML 只剩一个 script 标签，浏览器二次访问走 304，
 * 也让「英文模式下必须携带全部中文原文」这件事不再拖累页面体积。
 * <p>
 * 中文目录同时充当「中文原文 → 英文」的运行时对照表（见 common.js 的 buildUiCopyMap），
 * 用于翻译脚本动态渲染、无法逐处改写的界面文案。
 */
@RestController
public class I18nCatalogController {

    private static final MediaType JAVASCRIPT = MediaType.parseMediaType("application/javascript;charset=UTF-8");

    private final ObjectMapper objectMapper;

    public I18nCatalogController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/i18n/catalog.js", produces = "application/javascript;charset=UTF-8")
    public ResponseEntity<String> catalog(@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        String body = buildBody(LocaleContextHolder.getLocale());
        String etag = "\"" + sha256Hex(body) + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.noCache().cachePrivate())
                    .header(HttpHeaders.VARY, HttpHeaders.COOKIE)
                    .build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                // no-cache = 每次都带 ETag 回源校验，命中即 304；开发期改词条无需清浏览器缓存
                .cacheControl(CacheControl.noCache().cachePrivate())
                .header(HttpHeaders.VARY, HttpHeaders.COOKIE)
                .contentType(JAVASCRIPT)
                .body(body);
    }

    private String buildBody(Locale locale) {
        Map<String, String> localized = readCatalog(locale);
        Map<String, String> source = readCatalog(Locale.SIMPLIFIED_CHINESE);
        return "window.__I18N__ = " + toJson(localized) + ";\n"
                + "window.__I18N_SOURCE__ = " + toJson(source) + ";\n"
                + "document.documentElement.lang = window.__I18N__['language.current'] === 'English' ? 'en' : 'zh-CN';\n";
    }

    private Map<String, String> readCatalog(Locale locale) {
        ResourceBundle bundle = ResourceBundle.getBundle("messages", locale);
        Map<String, String> catalog = new LinkedHashMap<>();
        Enumeration<String> keys = bundle.getKeys();
        while (keys.hasMoreElements()) {
            String key = keys.nextElement();
            catalog.put(key, bundle.getString(key));
        }
        return catalog;
    }

    private String toJson(Map<String, String> catalog) {
        try {
            // U+2028/U+2029 在 JS 里是换行符，JSON 默认不转义，放在外链脚本里同样需要手工兜底
            return objectMapper.writeValueAsString(catalog)
                    .replace("<", "\\u003C")
                    .replace("\u2028", "\\u2028")
                    .replace("\u2029", "\\u2029");
        } catch (Exception e) {
            throw new IllegalStateException("文案字典序列化失败", e);
        }
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256 实现", e);
        }
    }
}
