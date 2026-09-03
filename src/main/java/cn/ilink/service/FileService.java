package cn.ilink.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class FileService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long MB = 1024L * 1024L;

    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
        ".pdf", ".zip", ".rar", ".7z",
        ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".txt", ".md", ".csv"
    );

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        ".jpg", ".jpeg", ".png", ".gif", ".webp"
    );

    private static final Set<String> ATTACHMENT_EXTENSIONS;

    static {
        Set<String> extensions = new HashSet<>(IMAGE_EXTENSIONS);
        extensions.addAll(DOCUMENT_EXTENSIONS);
        ATTACHMENT_EXTENSIONS = Set.copyOf(extensions);
    }

    private static final Map<String, UploadRule> RULES = Map.of(
        "avatars", new UploadRule(IMAGE_EXTENSIONS, 1 * MB,
            "\u5934\u50cf", "jpg\u3001png\u3001gif\u3001webp", "1MB"),
        "certificates", new UploadRule(Set.of(".jpg", ".jpeg", ".png", ".pdf"), 2 * MB,
            "\u8bc1\u4e66", "jpg\u3001png\u3001pdf", "2MB"),
        "images", new UploadRule(IMAGE_EXTENSIONS, 2 * MB,
            "\u56fe\u7247", "jpg\u3001png\u3001gif\u3001webp", "2MB"),
        "proofs", new UploadRule(Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".pdf"), 5 * MB,
            "\u8bc1\u660e\u9644\u4ef6", "jpg\u3001png\u3001gif\u3001webp\u3001pdf", "5MB"),
        "community", new UploadRule(ATTACHMENT_EXTENSIONS, 20 * MB,
            "\u793e\u533a\u9644\u4ef6", "\u56fe\u7247\u3001pdf\u3001office\u3001\u538b\u7f29\u5305\u548c\u6587\u672c", "20MB"),
        "tasks", new UploadRule(ATTACHMENT_EXTENSIONS, 20 * MB,
            "\u4efb\u52a1\u9644\u4ef6", "\u56fe\u7247\u3001pdf\u3001office\u3001\u538b\u7f29\u5305\u548c\u6587\u672c", "20MB"),
        "assets", new UploadRule(ATTACHMENT_EXTENSIONS, 20 * MB,
            "\u6210\u679c\u9644\u4ef6", "\u56fe\u7247\u3001pdf\u3001office\u3001\u538b\u7f29\u5305\u548c\u6587\u672c", "20MB")
    );

    @Value("${file.upload-dir:/data/uploads/}")
    private String uploadDir;

    @Value("${file.access-url-prefix:http://121.40.34.68:8090/uploads/}")
    private String accessUrlPrefix;

    public String upload(MultipartFile file, String bizType) throws IOException {
        UploadRule rule = validateBizType(bizType);
        validateFile(file, rule);

        String extension = extractExtension(file.getOriginalFilename());
        if (!rule.allowedExtensions.contains(extension)) {
            throw new IllegalArgumentException(rule.displayName + "\u4ec5\u652f\u6301 "
                + rule.allowedText + " \u683c\u5f0f");
        }
        String actualExtension = detectFileExtension(file, extension);
        if (actualExtension == null || !rule.allowedExtensions.contains(actualExtension)) {
            throw new IllegalArgumentException("\u6587\u4ef6\u5185\u5bb9\u4e0e\u6269\u5c55\u540d\u4e0d\u5339\u914d");
        }
        if (!isCompatibleExtension(extension, actualExtension)) {
            throw new IllegalArgumentException("\u6587\u4ef6\u5185\u5bb9\u4e0e\u6269\u5c55\u540d\u4e0d\u5339\u914d");
        }
        extension = normalizeStoredExtension(actualExtension, extension);

        LocalDate now = LocalDate.now(DEFAULT_ZONE);
        String year = String.format("%04d", now.getYear());
        String month = String.format("%02d", now.getMonthValue());
        String day = String.format("%02d", now.getDayOfMonth());

        Path root = resolveUploadRoot();
        Path targetDir = root.resolve(bizType).resolve(year).resolve(month).resolve(day).normalize();
        if (!targetDir.startsWith(root)) {
            throw new IllegalArgumentException("\u4e0a\u4f20\u8def\u5f84\u975e\u6cd5");
        }
        Files.createDirectories(targetDir);

        String filename = UUID.randomUUID().toString().replace("-", "")
            + "_" + System.currentTimeMillis() + extension;
        Path target = targetDir.resolve(filename).normalize();
        if (!target.startsWith(targetDir)) {
            throw new IllegalArgumentException("\u6587\u4ef6\u540d\u975e\u6cd5");
        }

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }

        return normalizeAccessPrefix(accessUrlPrefix)
            + bizType + "/" + year + "/" + month + "/" + day + "/" + filename;
    }

    /**
     * Resolve a previously returned public URL to a managed local file.
     * The resolved path is always constrained to the configured upload root.
     */
    public Path resolveStoredPath(String fileUrl) {
        if (!StringUtils.hasText(fileUrl)) {
            throw new IllegalArgumentException("\u6587\u4ef6\u5730\u5740\u4e0d\u80fd\u4e3a\u7a7a");
        }
        String value = fileUrl.trim().replace('\\', '/');
        String normalizedPrefix = normalizeAccessPrefix(accessUrlPrefix);
        String relative = value.startsWith(normalizedPrefix)
            ? value.substring(normalizedPrefix.length())
            : extractRelativeUploadPath(value);

        if (!StringUtils.hasText(relative) || relative.startsWith("/") || hasPathTraversalSegment(relative)) {
            throw new IllegalArgumentException("\u6587\u4ef6\u5730\u5740\u975e\u6cd5");
        }
        Path root = resolveUploadRoot();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("\u6587\u4ef6\u5730\u5740\u975e\u6cd5");
        }
        return resolved;
    }

    public boolean delete(String fileUrl) throws IOException {
        if (!StringUtils.hasText(fileUrl)) {
            return false;
        }
        return Files.deleteIfExists(resolveStoredPath(fileUrl));
    }

    private UploadRule validateBizType(String bizType) {
        if (!StringUtils.hasText(bizType)) {
            throw new IllegalArgumentException("\u4e1a\u52a1\u7c7b\u578b\u4e0d\u80fd\u4e3a\u7a7a");
        }
        UploadRule rule = RULES.get(bizType.trim());
        if (rule == null) {
            throw new IllegalArgumentException("\u4e1a\u52a1\u7c7b\u578b\u4e0d\u53d7\u652f\u6301");
        }
        return rule;
    }

    private void validateFile(MultipartFile file, UploadRule rule) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("\u8bf7\u9009\u62e9\u8981\u4e0a\u4f20\u7684\u6587\u4ef6");
        }
        if (file.getSize() > rule.maxSize) {
            throw new IllegalArgumentException(rule.displayName
                + "\u6587\u4ef6\u5927\u5c0f\u4e0d\u80fd\u8d85\u8fc7 " + rule.maxSizeText);
        }
    }

    private String extractExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            throw new IllegalArgumentException("\u6587\u4ef6\u540d\u4e0d\u80fd\u4e3a\u7a7a");
        }

        String normalizedName = originalFilename.trim().replace('\\', '/');
        if (normalizedName.indexOf('\0') >= 0 || hasPathTraversalSegment(normalizedName)) {
            throw new IllegalArgumentException("\u6587\u4ef6\u540d\u975e\u6cd5");
        }

        String filename = StringUtils.getFilename(normalizedName);
        int dotIndex = filename == null ? -1 : filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            throw new IllegalArgumentException("\u6587\u4ef6\u7f3a\u5c11\u6269\u5c55\u540d");
        }
        return filename.substring(dotIndex).toLowerCase(Locale.ROOT);
    }

    private boolean hasPathTraversalSegment(String filename) {
        for (String segment : filename.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private Path resolveUploadRoot() {
        String path = StringUtils.hasText(uploadDir) ? uploadDir.trim() : "/data/uploads/";
        return Paths.get(path).toAbsolutePath().normalize();
    }

    private String normalizeAccessPrefix(String prefix) {
        String value = StringUtils.hasText(prefix) ? prefix.trim() : "http://121.40.34.68:8090/uploads/";
        return value.endsWith("/") ? value : value + "/";
    }

    private String extractRelativeUploadPath(String fileUrl) {
        String path = fileUrl;
        try {
            URI uri = new URI(fileUrl);
            if (uri.getScheme() != null || uri.getHost() != null) {
                path = uri.getPath();
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("\u6587\u4ef6\u5730\u5740\u975e\u6cd5", e);
        }
        String marker = "/uploads/";
        int markerIndex = path.indexOf(marker);
        if (markerIndex < 0) {
            throw new IllegalArgumentException("\u6587\u4ef6\u5730\u5740\u4e0d\u5c5e\u4e8e\u53d7\u7ba1\u4e0a\u4f20\u76ee\u5f55");
        }
        return path.substring(markerIndex + marker.length());
    }

    private String detectFileExtension(MultipartFile file, String declaredExtension) throws IOException {
        byte[] header = readHeader(file, 12);
        if (header.length >= 3
            && (header[0] & 0xFF) == 0xFF
            && (header[1] & 0xFF) == 0xD8
            && (header[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        if (header.length >= 8
            && (header[0] & 0xFF) == 0x89
            && header[1] == 0x50
            && header[2] == 0x4E
            && header[3] == 0x47
            && header[4] == 0x0D
            && header[5] == 0x0A
            && header[6] == 0x1A
            && header[7] == 0x0A) {
            return ".png";
        }
        if (startsWithAscii(header, "GIF87a") || startsWithAscii(header, "GIF89a")) {
            return ".gif";
        }
        if (header.length >= 12
            && startsWithAscii(header, "RIFF")
            && header[8] == 0x57
            && header[9] == 0x45
            && header[10] == 0x42
            && header[11] == 0x50) {
            return ".webp";
        }
        if (startsWithAscii(header, "%PDF")) {
            return ".pdf";
        }
        if (header.length >= 8
            && header[0] == 0x50 && header[1] == 0x4B
            && (header[2] == 0x03 || header[2] == 0x05 || header[2] == 0x07)
            && (header[3] == 0x04 || header[3] == 0x06 || header[3] == 0x08)) {
            return detectZipContainer(file);
        }
        if (header.length >= 8
            && (header[0] & 0xFF) == 0xD0 && (header[1] & 0xFF) == 0xCF
            && header[2] == 0x11 && (header[3] & 0xFF) == 0xE0
            && (header[4] & 0xFF) == 0xA1 && (header[5] & 0xFF) == 0xB1
            && header[6] == 0x1A && (header[7] & 0xFF) == 0xE1) {
            return ".ole";
        }
        if (startsWithAscii(header, "Rar!")) {
            return ".rar";
        }
        if (header.length >= 6
            && header[0] == 0x37 && header[1] == 0x7A
            && (header[2] & 0xFF) == 0xBC && (header[3] & 0xFF) == 0xAF
            && header[4] == 0x27 && header[5] == 0x1C) {
            return ".7z";
        }
        if (Set.of(".txt", ".md", ".csv").contains(declaredExtension) && looksLikeText(file)) {
            return ".text";
        }
        return null;
    }

    private String detectZipContainer(MultipartFile file) throws IOException {
        boolean hasContentTypes = false;
        boolean hasWord = false;
        boolean hasExcel = false;
        boolean hasPowerPoint = false;
        int inspected = 0;
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && inspected++ < 256) {
                String name = entry.getName().replace('\\', '/');
                if ("[Content_Types].xml".equals(name)) hasContentTypes = true;
                if (name.startsWith("word/")) hasWord = true;
                if (name.startsWith("xl/")) hasExcel = true;
                if (name.startsWith("ppt/")) hasPowerPoint = true;
            }
        }
        if (hasContentTypes && hasWord) return ".docx";
        if (hasContentTypes && hasExcel) return ".xlsx";
        if (hasContentTypes && hasPowerPoint) return ".pptx";
        return ".zip";
    }

    private boolean looksLikeText(MultipartFile file) throws IOException {
        int inspected = 0;
        try (InputStream input = new BufferedInputStream(file.getInputStream())) {
            int value;
            while ((value = input.read()) != -1 && inspected++ < 8192) {
                if (value == 0) return false;
                if (value < 0x09 || (value > 0x0D && value < 0x20)) return false;
            }
        }
        return true;
    }

    private boolean isCompatibleExtension(String declared, String actual) {
        if (Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp").contains(actual)) {
            return IMAGE_EXTENSIONS.contains(declared);
        }
        if (".ole".equals(actual)) {
            return Set.of(".doc", ".xls", ".ppt").contains(declared);
        }
        if (".text".equals(actual)) {
            return Set.of(".txt", ".md", ".csv").contains(declared);
        }
        return declared.equals(actual);
    }

    private String normalizeStoredExtension(String actual, String declared) {
        if (".jpeg".equals(actual)) return ".jpg";
        if (".ole".equals(actual) || ".text".equals(actual)) return declared;
        return actual;
    }

    private byte[] readHeader(MultipartFile file, int size) throws IOException {
        byte[] buffer = new byte[size];
        int total = 0;
        try (InputStream inputStream = new BufferedInputStream(file.getInputStream())) {
            while (total < size) {
                int count = inputStream.read(buffer, total, size - total);
                if (count == -1) {
                    break;
                }
                total += count;
            }
        }
        byte[] header = new byte[total];
        System.arraycopy(buffer, 0, header, 0, total);
        return header;
    }

    private boolean startsWithAscii(byte[] bytes, String expected) {
        if (bytes.length < expected.length()) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if (bytes[i] != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static class UploadRule {
        private final Set<String> allowedExtensions;
        private final long maxSize;
        private final String displayName;
        private final String allowedText;
        private final String maxSizeText;

        private UploadRule(Set<String> allowedExtensions, long maxSize, String displayName,
                           String allowedText, String maxSizeText) {
            this.allowedExtensions = allowedExtensions;
            this.maxSize = maxSize;
            this.displayName = displayName;
            this.allowedText = allowedText;
            this.maxSizeText = maxSizeText;
        }
    }
}
