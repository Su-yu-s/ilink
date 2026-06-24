package cn.ilink.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RestController
public class FaviconController {

    @Value("${file.upload-dir:${upload.path:/data/uploads/}}")
    private String uploadDir;

    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create("/favicon.svg?v=5"))
            .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
            .build();
    }

    @GetMapping(value = "/favicon.svg", produces = "image/svg+xml")
    public ResponseEntity<Resource> faviconSvg() {
        Path favicon = resolveFavicon("favicon.svg");
        if (favicon == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
            .contentType(MediaType.valueOf("image/svg+xml"))
            .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
            .body(new FileSystemResource(favicon));
    }

    private Path resolveFavicon(String filename) {
        for (Path root : uploadRoots()) {
            Path file = root.resolve("images").resolve(filename).normalize();
            if (file.startsWith(root) && Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    private Set<Path> uploadRoots() {
        String path = StringUtils.hasText(uploadDir) ? uploadDir.trim() : "/data/uploads/";
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(toAbsolutePath(path));
        roots.add(toAbsolutePath("./data/uploads"));
        roots.add(toAbsolutePath("/www/wwwroot/ilink/data/uploads"));
        roots.add(toAbsolutePath("/data/uploads"));
        roots.add(toAbsolutePath("./uploads"));
        return roots;
    }

    private Path toAbsolutePath(String path) {
        return Paths.get(path).toAbsolutePath().normalize();
    }
}
