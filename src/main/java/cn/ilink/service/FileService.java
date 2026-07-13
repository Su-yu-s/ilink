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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FileService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long MB = 1024L * 1024L;

    private static final Map<String, UploadRule> RULES = Map.of(
        "avatars", new UploadRule(Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp"), 1 * MB,
            "\u5934\u50cf", "jpg\u3001png\u3001gif\u3001webp", "1MB"),
        "certificates", new UploadRule(Set.of(".jpg", ".jpeg", ".png", ".pdf"), 2 * MB,
            "\u8bc1\u4e66", "jpg\u3001png\u3001pdf", "2MB"),
        "images", new UploadRule(Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp"), 512 * 1024,
            "\u56fe\u7247", "jpg\u3001png\u3001gif\u3001webp", "500KB")
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
        String actualExtension = detectFileExtension(file);
        if (actualExtension == null || !rule.allowedExtensions.contains(actualExtension)) {
            throw new IllegalArgumentException("\u6587\u4ef6\u5185\u5bb9\u4e0e\u6269\u5c55\u540d\u4e0d\u5339\u914d");
        }
        extension = normalizeStoredExtension(actualExtension);

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
        }

        return normalizeAccessPrefix(accessUrlPrefix)
            + bizType + "/" + year + "/" + month + "/" + day + "/" + filename;
    }

    private UploadRule validateBizType(String bizType) {
        if (!StringUtils.hasText(bizType)) {
            throw new IllegalArgumentException("\u4e1a\u52a1\u7c7b\u578b\u4e0d\u80fd\u4e3a\u7a7a");
        }
        UploadRule rule = RULES.get(bizType.trim());
        if (rule == null) {
            throw new IllegalArgumentException("\u4e1a\u52a1\u7c7b\u578b\u4ec5\u652f\u6301 avatars\u3001certificates\u3001images");
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

    private String detectFileExtension(MultipartFile file) throws IOException {
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
        return null;
    }

    private String normalizeStoredExtension(String extension) {
        return ".jpeg".equals(extension) ? ".jpg" : extension;
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
