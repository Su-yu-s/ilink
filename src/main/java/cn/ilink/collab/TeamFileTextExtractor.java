package cn.ilink.collab;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Safe plain-text extraction for team library files. Unsupported types are
 * explicitly skipped instead of failing the upload or download path.
 */
@Service
public class TeamFileTextExtractor {
    public static final long MAX_SOURCE_BYTES = 20L * 1024 * 1024;
    public static final int MAX_TEXT_BYTES = 5 * 1024 * 1024;
    public static final int MAX_PDF_PAGES = 300;
    public static final int MAX_SLIDES = 300;
    public static final int MAX_CELLS = 20_000;
    private static final int MAX_OUTPUT_CHARS = 2_000_000;

    /**
     * OOXML 容器是 zip，解压后的单条目体积才是真正的攻击面。POI 5.3.0 默认
     * MAX_ENTRY_SIZE = 4 GiB，远大于本服务的源文件上限，因此显式收紧到
     * 32 MiB；条目数量（默认 1000）与解压比例（默认 0.01）保持库默认值，
     * 把它们改成更大的数只会放松防护。触发阈值时在 {@link #extract} 里
     * 归类成稳定错误码，而不是笼统的"文件损坏"。
     */
    public static final long MAX_ZIP_ENTRY_BYTES = 32L * 1024 * 1024;

    static {
        org.apache.poi.openxml4j.util.ZipSecureFile.setMaxEntrySize(MAX_ZIP_ENTRY_BYTES);
    }

    public ExtractResult extract(Path file, String displayName) {
        if (file == null || !Files.isRegularFile(file)) {
            return ExtractResult.failed("FILE_MISSING", "文件已不存在");
        }
        try {
            long size = Files.size(file);
            if (size > MAX_SOURCE_BYTES) {
                return ExtractResult.skipped("FILE_TOO_LARGE", "文件超过 20 MiB，未建立正文索引");
            }
            String extension = extension(displayName);
            return switch (extension) {
                case "pdf" -> pdf(file);
                case "docx" -> docx(file);
                case "xlsx" -> xlsx(file);
                case "pptx" -> pptx(file);
                case "md", "markdown", "txt", "csv" -> text(file);
                default -> ExtractResult.skipped("UNSUPPORTED_TYPE",
                    "该文件类型不建立正文索引");
            };
        } catch (OutOfMemoryError e) {
            return ExtractResult.failed("FILE_TOO_COMPLEX", "文件结构过于复杂，无法解析");
        } catch (Exception e) {
            if (isZipBomb(e)) {
                return ExtractResult.skipped("FILE_ZIP_BOMB",
                    "文件解压后体积异常，未建立正文索引");
            }
            return ExtractResult.failed("FILE_UNREADABLE", "文件无法解析或已损坏");
        }
    }

    private static boolean isZipBomb(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            String message = cursor.getMessage();
            if (message == null) {
                if (cursor.getCause() == cursor) break;
                continue;
            }
            // POI 的三条阈值消息文案固定：条目解压体积、解压比例、条目数量。
            if (message.contains("Zip bomb detected")
                || message.contains("more internal file entries than expected")) {
                return true;
            }
            if (cursor.getCause() == cursor) break;
        }
        return false;
    }

    private ExtractResult pdf(Path file) throws Exception {
        try (InputStream input = Files.newInputStream(file);
             PDDocument document = PDDocument.load(input)) {
            if (document.isEncrypted()) {
                return ExtractResult.skipped("FILE_ENCRYPTED", "加密 PDF 不建立正文索引");
            }
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                return ExtractResult.skipped("FILE_TOO_MANY_PAGES",
                    "PDF 页数超过 300，未建立正文索引");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return clean(stripper.getText(document));
        }
    }

    private ExtractResult docx(Path file) throws Exception {
        try (InputStream input = Files.newInputStream(file);
             XWPFDocument document = new XWPFDocument(input);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return clean(extractor.getText());
        }
    }

    private ExtractResult xlsx(Path file) throws Exception {
        try (InputStream input = Files.newInputStream(file);
             org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                 new org.apache.poi.xssf.usermodel.XSSFWorkbook(input)) {
            StringBuilder out = new StringBuilder();
            DataFormatter formatter = new DataFormatter(Locale.getDefault());
            int cells = 0;
            for (Sheet sheet : workbook) {
                out.append("\n## ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell);
                        if (value == null || value.isBlank()) continue;
                        cells++;
                        if (cells > MAX_CELLS) {
                            return ExtractResult.skipped("FILE_TOO_MANY_CELLS",
                                "表格非空单元格超过 20000，未建立正文索引");
                        }
                        if (line.length() > 0) line.append(" | ");
                        line.append(value);
                        if (line.length() > 4000) break;
                    }
                    if (line.length() > 0) out.append(line).append('\n');
                    if (out.length() > MAX_OUTPUT_CHARS) break;
                }
                if (out.length() > MAX_OUTPUT_CHARS) break;
            }
            return clean(out.toString());
        }
    }

    private ExtractResult pptx(Path file) throws Exception {
        try (InputStream input = Files.newInputStream(file);
             XMLSlideShow slides = new XMLSlideShow(input)) {
            if (slides.getSlides().size() > MAX_SLIDES) {
                return ExtractResult.skipped("FILE_TOO_MANY_PAGES",
                    "演示文稿页数超过 300，未建立正文索引");
            }
            StringBuilder out = new StringBuilder();
            for (XSLFSlide slide : slides.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String value = textShape.getText();
                        if (value != null && !value.isBlank()) out.append(value).append('\n');
                    }
                }
                if (out.length() > MAX_OUTPUT_CHARS) break;
            }
            return clean(out.toString());
        }
    }

    private ExtractResult text(Path file) throws Exception {
        if (Files.size(file) > MAX_TEXT_BYTES) {
            return ExtractResult.skipped("FILE_TOO_LARGE", "文本超过 5 MiB，未建立正文索引");
        }
        String value = Files.readString(file, StandardCharsets.UTF_8);
        return clean(value);
    }

    private ExtractResult clean(String raw) {
        String value = raw == null ? "" : raw
            .replace("\u0000", "")
            .replaceAll("[\\x01-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "")
            .replace("\r\n", "\n").replace('\r', '\n').trim();
        if (value.length() > MAX_OUTPUT_CHARS) {
            value = value.substring(0, MAX_OUTPUT_CHARS);
        }
        return value.isEmpty() ? ExtractResult.skipped("NO_TEXT",
            "文件没有可提取的文本层") : ExtractResult.ready(value);
    }

    private String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record ExtractResult(String status, String content, String errorCode,
                                String safeMessage) {
        static ExtractResult ready(String content) {
            return new ExtractResult("READY", content, null, null);
        }

        static ExtractResult skipped(String errorCode, String message) {
            return new ExtractResult("SKIPPED", null, errorCode, message);
        }

        static ExtractResult failed(String errorCode, String message) {
            return new ExtractResult("FAILED", null, errorCode, message);
        }
    }
}
