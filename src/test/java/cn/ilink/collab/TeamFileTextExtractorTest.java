package cn.ilink.collab;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TeamFileTextExtractorTest {
    @TempDir
    Path tempDir;

    private final TeamFileTextExtractor extractor = new TeamFileTextExtractor();

    @ParameterizedTest
    @ValueSource(strings = {"notes.txt", "README.md", "guide.markdown", "data.csv"})
    void extractsUtf8TextAndMarkdown(String fileName) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, "  第一行\r\n第二行\u0000  \r\n", StandardCharsets.UTF_8);

        TeamFileTextExtractor.ExtractResult result = extractor.extract(file, fileName);

        assertEquals("READY", result.status());
        assertEquals("第一行\n第二行", result.content());
        assertNull(result.errorCode());
        assertNull(result.safeMessage());
    }

    @Test
    void skipsUnsupportedTypesWithoutReadingThemAsText() throws Exception {
        Path file = tempDir.resolve("archive.zip");
        Files.write(file, new byte[]{1, 2, 3});

        TeamFileTextExtractor.ExtractResult result = extractor.extract(file, "archive.ZIP");

        assertEquals("SKIPPED", result.status());
        assertEquals("UNSUPPORTED_TYPE", result.errorCode());
        assertNull(result.content());
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken.pdf", "broken.docx", "broken.xlsx", "broken.pptx"})
    void corruptOfficeAndPdfFilesFailSafely(String fileName) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.write(file, "not a real office document".getBytes(StandardCharsets.UTF_8));

        TeamFileTextExtractor.ExtractResult result = extractor.extract(file, fileName);

        assertEquals("FAILED", result.status());
        assertEquals("FILE_UNREADABLE", result.errorCode());
        assertEquals("文件无法解析或已损坏", result.safeMessage());
    }

    @Test
    void encryptedPdfIsSkippedWhenTheEmptyUserPasswordCanOpenIt() throws Exception {
        Path file = tempDir.resolve("protected.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                "owner-password", "", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(file.toFile());
        }

        TeamFileTextExtractor.ExtractResult result = extractor.extract(file, "protected.pdf");

        assertEquals("SKIPPED", result.status());
        assertEquals("FILE_ENCRYPTED", result.errorCode());
        assertNull(result.content());
    }

    @Test
    void sourceFileOverTwentyMebibytesIsSkippedBeforeParsing() throws Exception {
        Path file = tempDir.resolve("large.pdf");
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file.toFile(), "rw")) {
            randomAccessFile.setLength(TeamFileTextExtractor.MAX_SOURCE_BYTES + 1);
        }

        TeamFileTextExtractor.ExtractResult result = extractor.extract(file, "large.pdf");

        assertEquals("SKIPPED", result.status());
        assertEquals("FILE_TOO_LARGE", result.errorCode());
        assertEquals("文件超过 20 MiB，未建立正文索引", result.safeMessage());
    }

    @Test
    void plainTextOverFiveMebibytesIsSkipped() throws Exception {
        Path file = tempDir.resolve("large.txt");
        Files.write(file, new byte[TeamFileTextExtractor.MAX_TEXT_BYTES + 1]);

        TeamFileTextExtractor.ExtractResult result = extractor.extract(file, "large.txt");

        assertEquals("SKIPPED", result.status());
        assertEquals("FILE_TOO_LARGE", result.errorCode());
        assertEquals("文本超过 5 MiB，未建立正文索引", result.safeMessage());
    }

    @Test
    void missingFileReturnsAnActionableFailure() {
        TeamFileTextExtractor.ExtractResult result =
            extractor.extract(tempDir.resolve("missing.txt"), "missing.txt");

        assertEquals("FAILED", result.status());
        assertEquals("FILE_MISSING", result.errorCode());
        assertEquals("文件已不存在", result.safeMessage());
    }
}
