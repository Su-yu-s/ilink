package cn.ilink.collab;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OOXML 容器的 zip bomb 防护：单个条目的解压体积超过收紧后的 32 MiB 上限时，
 * 必须归类成稳定的 FILE_ZIP_BOMB，而不是被当成"文件损坏"或直接把 Worker 打满。
 */
class TeamFileZipBombTest {
    @TempDir
    Path tempDir;

    private final TeamFileTextExtractor extractor = new TeamFileTextExtractor();

    private byte[] validDocx() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("正文");
            document.write(out);
            return out.toByteArray();
        }
    }

    /** 保留原有条目，只把 document.xml 换成高度可压缩的巨块，外层体积仍然很小。 */
    private Path rebuildWithHugeDocumentXml(byte[] base, int uncompressedChars, String name)
        throws Exception {
        Path seed = tempDir.resolve("seed.docx");
        Files.write(seed, base);
        Path target = tempDir.resolve(name);

        List<String> names = new ArrayList<>();
        List<byte[]> payloads = new ArrayList<>();
        try (ZipFile source = new ZipFile(seed.toFile())) {
            Collections.list(source.entries()).forEach(entry -> {
                String entryName = entry.getName();
                try {
                    if ("word/document.xml".equals(entryName)) {
                        return; // 稍后放到最后写入
                    }
                    names.add(entryName);
                    payloads.add(source.getInputStream(entry).readAllBytes());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            String body = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>"
                + new String(new char[uncompressedChars]).replace('\0', 'a')
                + "</w:t></w:r></w:p></w:body></w:document>";
            names.add("word/document.xml");
            payloads.add(body.getBytes(StandardCharsets.UTF_8));
        }

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            zip.setLevel(9);
            for (int i = 0; i < names.size(); i++) {
                zip.putNextEntry(new ZipEntry(names.get(i)));
                zip.write(payloads.get(i));
                zip.closeEntry();
            }
        }
        Files.deleteIfExists(seed);
        return target;
    }

    @Test
    void singleEntryExpandingPastTheLimitIsReportedAsZipBomb() throws Exception {
        Path bomb = rebuildWithHugeDocumentXml(validDocx(), 40 * 1024 * 1024, "bomb.docx");

        // 外层体积必须仍在 20 MiB 源文件上限之内，否则命中的是 FILE_TOO_LARGE。
        long outer = Files.size(bomb);
        assertTrue(outer < TeamFileTextExtractor.MAX_SOURCE_BYTES,
            "样本外层体积应小于 20 MiB，实际 " + outer);

        TeamFileTextExtractor.ExtractResult result = extractor.extract(bomb, "bomb.docx");

        assertEquals("SKIPPED", result.status(), String.valueOf(result.safeMessage()));
        assertEquals("FILE_ZIP_BOMB", result.errorCode());
    }

    @Test
    void ordinaryDocxStillExtractsAfterTighteningTheEntryLimit() throws Exception {
        Path file = tempDir.resolve("normal.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("竞赛方案正文");
            document.write(Files.newOutputStream(file));
        }

        TeamFileTextExtractor.ExtractResult result = extractor.extract(file, "normal.docx");

        assertEquals("READY", result.status(), String.valueOf(result.safeMessage()));
        assertTrue(result.content().contains("竞赛方案正文"), result.content());
    }
}
