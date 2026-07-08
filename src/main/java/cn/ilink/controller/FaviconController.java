package cn.ilink.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Favicon 控制器。
 * 优先从上传目录读取（云服务器可直接替换文件、无需重新打包部署 jar），
 * 找不到再回退到 classpath 内打包的资源。
 */
@RestController
public class FaviconController {

    private static final MediaType SVG_TYPE = MediaType.valueOf("image/svg+xml");
    private static final MediaType PNG_TYPE = MediaType.valueOf("image/png");

    private static final CacheControl CACHE_1Y = CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic();

    /** favicon 在上传目录下的相对路径，完整路径 = {upload-dir}/images/favicon.{svg,png} */
    private static final String UPLOAD_SUBPATH = "images";

    /** 候选上传根目录：配置项 + 多个常见部署位置，按顺序依次尝试。 */
    private final Set<String> uploadRoots = new LinkedHashSet<>();

    public FaviconController(
            @Value("${file.upload-dir:${upload.path:/data/uploads/}}") String configuredDir) {
        if (StringUtils.hasText(configuredDir)) {
            uploadRoots.add(normalize(configuredDir));
        }
        // 兜底：宝塔面板默认部署目录、容器默认目录等
        uploadRoots.add("/www/wwwroot/ilink/data/uploads");
        uploadRoots.add("./data/uploads");
        uploadRoots.add("/data/uploads");
    }

    /**
     * /favicon.ico —— 浏览器默认请求的路径。
     * 优先返回上传目录的 SVG/PNG，再回退 classpath。
     */
    @GetMapping("/favicon.ico")
    public ResponseEntity<Resource> faviconIco() {
        Resource svg = resolve("favicon.svg");
        if (svg != null) {
            return ResponseEntity.ok().contentType(SVG_TYPE).cacheControl(CACHE_1Y).body(svg);
        }
        Resource png = resolve("favicon.png");
        if (png != null) {
            return ResponseEntity.ok().contentType(PNG_TYPE).cacheControl(CACHE_1Y).body(png);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * /favicon.svg —— &lt;link&gt; 标签直接引用的 SVG 图标。
     */
    @GetMapping("/favicon.svg")
    public ResponseEntity<Resource> faviconSvg() {
        Resource svg = resolve("favicon.svg");
        if (svg != null) {
            return ResponseEntity.ok().contentType(SVG_TYPE).cacheControl(CACHE_1Y).body(svg);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 按优先级解析 favicon 资源：上传目录(文件系统) → classpath。
     * @param filename favicon 文件名，如 favicon.svg
     * @return 可读的资源，或 null
     */
    private Resource resolve(String filename) {
        // 1. 上传目录（云服务器可直接替换文件）
        for (String root : uploadRoots) {
            Path file = Paths.get(root, UPLOAD_SUBPATH, filename).toAbsolutePath().normalize();
            Resource r = new FileSystemResource(file);
            if (r.exists() && r.isReadable()) {
                return r;
            }
        }
        // 2. 回退：打包进 jar 的 classpath 资源
        Resource cp;
        if ("favicon.png".equals(filename)) {
            cp = loadFromClasspath("static/images/favicon.png");
        } else {
            cp = loadFromClasspath("static/favicon.svg");
        }
        if (cp.exists() && cp.isReadable()) {
            return cp;
        }
        return null;
    }

    private Resource loadFromClasspath(String path) {
        return new ClassPathResource(path);
    }

    private String normalize(String dir) {
        String trimmed = dir.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
