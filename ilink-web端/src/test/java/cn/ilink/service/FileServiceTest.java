package cn.ilink.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadAvatarPngCreatesDatedFileAndReturnsPublicUrl() throws Exception {
        FileService service = newService();
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x00
        };
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", png);

        String url = service.upload(file, "avatars");

        LocalDate now = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String expectedPrefix = String.format("http://example.com/uploads/avatars/%04d/%02d/%02d/",
            now.getYear(), now.getMonthValue(), now.getDayOfMonth());
        assertTrue(url.startsWith(expectedPrefix));
        assertTrue(url.endsWith(".png"));
        assertTrue(Files.exists(resolveStoredPath(url.substring("http://example.com/uploads/".length()))));
    }

    @Test
    void uploadAllowsConsecutiveDotsInBaseFilename() throws Exception {
        FileService service = newService();
        byte[] jpg = new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46
        };
        MockMultipartFile file = new MockMultipartFile("file", "\u82cf..jpg", "image/jpeg", jpg);

        String url = service.upload(file, "avatars");

        assertTrue(url.contains("/avatars/"));
        assertTrue(url.endsWith(".jpg"));
    }

    @Test
    void uploadStoresWebpContentWithCorrectExtensionEvenWhenFilenameEndsWithJpg() throws Exception {
        FileService service = newService();
        byte[] webp = new byte[] {
            0x52, 0x49, 0x46, 0x46, 0x10, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20
        };
        MockMultipartFile file = new MockMultipartFile("file", "wallhaven.jpg", "image/webp", webp);

        String url = service.upload(file, "avatars");

        assertTrue(url.contains("/avatars/"));
        assertTrue(url.endsWith(".webp"));
        assertTrue(Files.exists(resolveStoredPath(url.substring("http://example.com/uploads/".length()))));
    }

    @Test
    void uploadRejectsUnknownBizType() {
        FileService service = newService();
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[] {1, 2, 3});

        assertThrows(IllegalArgumentException.class, () -> service.upload(file, "bad"));
    }

    @Test
    void uploadRejectsFileWithMismatchedSignature() {
        FileService service = newService();
        MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", new byte[] {1, 2, 3});

        assertThrows(IllegalArgumentException.class, () -> service.upload(file, "avatars"));
    }

    @Test
    void uploadRejectsPathTraversalFilename() {
        FileService service = newService();
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x00
        };
        MockMultipartFile file = new MockMultipartFile("file", "../evil.png", "image/png", png);

        assertThrows(IllegalArgumentException.class, () -> service.upload(file, "avatars"));
    }

    private FileService newService() {
        FileService service = new FileService();
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "accessUrlPrefix", "http://example.com/uploads/");
        return service;
    }

    private Path resolveStoredPath(String relativeUrl) {
        Path path = tempDir;
        for (String part : relativeUrl.split("/")) {
            path = path.resolve(part);
        }
        return path;
    }
}
