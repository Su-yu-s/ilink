package cn.ilink.collab;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析预算限制：超大页数、超量非空单元格、超量幻灯片都必须提前止损，
 * 否则一次索引会把 Worker 的内存和 CPU 打满。
 */
class TeamFileTextExtractorLimitsTest {
    @TempDir
    Path tempDir;

    private final TeamFileTextExtractor extractor = new TeamFileTextExtractor();

    @Test
    void pdfOverThePageLimitIsSkippedBeforeExtractingText() throws Exception {
        Path file = tempDir.resolve("long.pdf");
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page <= TeamFileTextExtractor.MAX_PDF_PAGES; page++) {
                PDPage pdPage = new PDPage();
                document.addPage(pdPage);
                try (PDPageContentStream content =
                         new PDPageContentStream(document, pdPage)) {
                    content.beginText();
                    content.setFont(PDType1Font.HELVETICA, 10);
                    content.newLineAtOffset(40, 700);
                    content.showText("page " + page);
                    content.endText();
                }
            }
            document.save(file.toFile());
        }
        assertTrue(Files.size(file) < TeamFileTextExtractor.MAX_SOURCE_BYTES);

        TeamFileTextExtractor.ExtractResult result = extractor.extract(file, "long.pdf");

        assertEquals("SKIPPED", result.status());
        assertEquals("FILE_TOO_MANY_PAGES", result.errorCode());
        assertEquals("PDF 页数超过 300，未建立正文索引", result.safeMessage());
        assertNull(result.content());
    }

    @Test
    void pdfWithinThePageLimitIsStillExtracted() throws Exception {
        Path file = tempDir.resolve("short.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage pdPage = new PDPage();
            document.addPage(pdPage);
            try (PDPageContentStream content = new PDPageContentStream(document, pdPage)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(60, 700);
                content.showText("team collaboration text");
                content.endText();
            }
            document.save(file.toFile());
        }

        TeamFileTextExtractor.ExtractResult result = extractor.extract(file, "short.pdf");

        assertEquals("READY", result.status(), String.valueOf(result.safeMessage()));
        assertTrue(result.content().contains("team collaboration text"), result.content());
    }

    @Test
    void xlsxOverTheCellBudgetIsSkippedPartWayThrough() throws Exception {
        Path file = tempDir.resolve("wide.xlsx");
        int columns = 100;
        int rows = TeamFileTextExtractor.MAX_CELLS / columns + 2;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("材料");
            for (int r = 0; r < rows; r++) {
                XSSFRow row = sheet.createRow(r);
                for (int c = 0; c < columns; c++) {
                    row.createCell(c).setCellValue("v" + r + "_" + c);
                }
            }
            workbook.write(Files.newOutputStream(file));
        }
        assertTrue(Files.size(file) < TeamFileTextExtractor.MAX_SOURCE_BYTES);

        TeamFileTextExtractor.ExtractResult result = extractor.extract(file, "wide.xlsx");

        assertEquals("SKIPPED", result.status());
        assertEquals("FILE_TOO_MANY_CELLS", result.errorCode());
        assertEquals("表格非空单元格超过 20000，未建立正文索引", result.safeMessage());
    }

    @Test
    void pptxOverTheSlideLimitIsSkipped() throws Exception {
        Path file = tempDir.resolve("long.pptx");
        try (XMLSlideShow slideShow = new XMLSlideShow()) {
            for (int i = 0; i <= TeamFileTextExtractor.MAX_SLIDES; i++) {
                slideShow.createSlide();
            }
            slideShow.write(Files.newOutputStream(file));
        }
        assertTrue(Files.size(file) < TeamFileTextExtractor.MAX_SOURCE_BYTES);

        TeamFileTextExtractor.ExtractResult result = extractor.extract(file, "long.pptx");

        assertEquals("SKIPPED", result.status());
        assertEquals("FILE_TOO_MANY_PAGES", result.errorCode());
        assertTrue(result.safeMessage().contains("300"), result.safeMessage());
    }
}
