package cn.ilink.service;

import cn.ilink.dto.UploadedFileInfo;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void uploadAssetPdfValidatesSignatureAndCanBeDeleted() throws Exception {
        FileService service = newService();
        MockMultipartFile file = new MockMultipartFile(
            "file", "report.pdf", "text/plain", "%PDF-1.7\n".getBytes());

        String url = service.upload(file, "assets");
        Path stored = service.resolveStoredPath(url);

        assertTrue(Files.exists(stored));
        assertTrue(url.contains("/assets/"));
        assertTrue(service.delete(url));
        assertFalse(Files.exists(stored));
    }

    @Test
    void uploadAssetRejectsActiveHtmlEvenWhenClientMimeLooksSafe() {
        FileService service = newService();
        MockMultipartFile file = new MockMultipartFile(
            "file", "payload.html", "application/octet-stream", "<script>alert(1)</script>".getBytes());

        assertThrows(IllegalArgumentException.class, () -> service.upload(file, "assets"));
    }

    @Test
    void uploadMetadataKeepsSafeOriginalNameAndProperties() throws Exception {
        FileService service = newService();
        MockMultipartFile file = new MockMultipartFile(
            "file", "C:\\fakepath\\竞赛报告.pdf", "application/pdf", "%PDF-1.7\n".getBytes());

        UploadedFileInfo info = service.uploadWithMetadata(file, "community");

        assertEquals("竞赛报告.pdf", info.getOriginalName());
        assertEquals(file.getSize(), info.getSize());
        assertEquals("application/pdf", info.getContentType());
        assertTrue(info.getUrl().contains("/community/"));
    }

    @Test
    void uploadMetadataRemovesControlCharactersAndLimitsDisplayName() throws Exception {
        FileService service = newService();
        String longName = "\r\n" + "成".repeat(300) + ".pdf";
        MockMultipartFile file = new MockMultipartFile(
            "file", longName, null, "%PDF-1.7\n".getBytes());

        UploadedFileInfo info = service.uploadWithMetadata(file, "assets");

        assertEquals(255, info.getOriginalName().length());
        assertTrue(info.getOriginalName().endsWith(".pdf"));
        assertFalse(info.getOriginalName().contains("\r"));
        assertFalse(info.getOriginalName().contains("\n"));
        assertEquals("application/octet-stream", info.getContentType());
    }

    @Test
    void uploadCommunityTextFileKeepsOriginalName() throws Exception {
        FileService service = newService();
        MockMultipartFile file = new MockMultipartFile(
            "file", "codex-upload-regression.txt", "text/plain",
            "iLink attachment upload regression fixture.\n".getBytes());

        String declared = ReflectionTestUtils.invokeMethod(service, "extractExtension", file.getOriginalFilename());
        String detected = ReflectionTestUtils.invokeMethod(service, "detectFileExtension", file, declared);
        assertEquals(".txt", declared);
        assertEquals(".text", detected);

        UploadedFileInfo info = service.uploadWithMetadata(file, "community");

        assertEquals("codex-upload-regression.txt", info.getOriginalName());
        assertTrue(info.getUrl().endsWith(".txt"));
    }

    @Test
    void uploadLegacyOfficeFileAcceptsOleContainerAfterTypeNormalization() throws Exception {
        FileService service = newService();
        byte[] oleHeader = new byte[] {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
        };
        MockMultipartFile file = new MockMultipartFile(
            "file", "竞赛材料.doc", "application/msword", oleHeader);

        UploadedFileInfo info = service.uploadWithMetadata(file, "community");

        assertEquals("竞赛材料.doc", info.getOriginalName());
        assertTrue(info.getUrl().endsWith(".doc"));
    }

    @Test
    void resolveStoredPathRejectsTraversalUrl() {
        FileService service = newService();

        assertThrows(IllegalArgumentException.class,
            () -> service.resolveStoredPath("http://example.com/uploads/assets/../../outside.txt"));
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
