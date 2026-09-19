package cn.ilink.service;

import cn.ilink.dto.UploadedFileInfo;
import cn.ilink.entity.Feedback;
import cn.ilink.mapper.FeedbackMapper;
import cn.ilink.security.SecureTokenSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FeedbackService 的校验、令牌与展示编号逻辑。
 * 不依赖 Spring 上下文：Mapper 与 FileService 全部 mock。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceTest {

    @Mock
    private FeedbackMapper feedbackMapper;

    @Mock
    private FileService fileService;

    private FeedbackService feedbackService;

    /** 本次 insert 的实体，同时模拟 MyBatis-Plus 自增主键回写 */
    private Feedback inserted;

    @BeforeEach
    void setUp() {
        feedbackService = new FeedbackService(feedbackMapper, fileService);
        ReflectionTestUtils.setField(feedbackService, "publicBaseUrl", "http://121.40.34.68:8090");
        doAnswer(invocation -> {
            inserted = invocation.getArgument(0);
            inserted.setId(1L);
            return 1;
        }).when(feedbackMapper).insert(any(Feedback.class));
    }

    private MockMultipartFile image(String name) {
        return new MockMultipartFile("images", name, "image/png",
            "fake-image-bytes".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void submit_storesTokenHashOnly_notRawToken() {
        FeedbackService.Submission submission = feedbackService.submit(
            "BUG", "编辑器分栏滚动不同步", "su@example.com", List.of(), 7L, "1.2.3.4", "JUnit");

        String rawToken = submission.getRawToken();
        assertTrue(rawToken != null && rawToken.length() >= 32, "令牌长度不足");
        assertNotEquals(rawToken, inserted.getAccessTokenHash(), "明文令牌不得入库");
        assertTrue(SecureTokenSupport.matchesHash(rawToken, inserted.getAccessTokenHash()),
            "入库的哈希应与明文令牌匹配");
        assertEquals(7L, inserted.getSubmitterId());
        assertEquals("BUG", inserted.getType());
        assertEquals(false, inserted.getHandled());
    }

    @Test
    void submit_rejectsBlankDescription() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            feedbackService.submit("BUG", "   ", null, List.of(), null, "1.2.3.4", null));
        assertEquals("请填写详细描述", error.getMessage());
        assertNull(inserted, "校验失败时不得落库");
    }

    @Test
    void submit_rejectsOverlongDescription() {
        String tooLong = "字".repeat(FeedbackService.MAX_DESCRIPTION + 1);
        assertThrows(IllegalArgumentException.class, () ->
            feedbackService.submit("BUG", tooLong, null, List.of(), null, "1.2.3.4", null));
        assertNull(inserted);
    }

    @Test
    void submit_rejectsOverlongContact() {
        String tooLong = "x".repeat(FeedbackService.MAX_CONTACT + 1);
        assertThrows(IllegalArgumentException.class, () ->
            feedbackService.submit("BUG", "描述", tooLong, List.of(), null, "1.2.3.4", null));
        assertNull(inserted);
    }

    @Test
    void submit_rejectsMoreThanMaxImages() throws IOException {
        List<MultipartFile> images = new ArrayList<>();
        for (int i = 0; i < FeedbackService.MAX_IMAGES + 1; i += 1) {
            images.add(image("shot-" + i + ".png"));
        }

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            feedbackService.submit("BUG", "描述", null, images, null, "1.2.3.4", null));
        assertEquals("最多上传 " + FeedbackService.MAX_IMAGES + " 张截图", error.getMessage());
        // 超限应在落盘之前就被拦下
        verify(fileService, never()).uploadWithMetadata(any(), anyString());
        assertNull(inserted);
    }

    @Test
    void submit_ignoresEmptyFileParts() throws IOException {
        MockMultipartFile empty = new MockMultipartFile("images", "", "application/octet-stream", new byte[0]);
        feedbackService.submit("BUG", "描述", null, List.of(empty), null, "1.2.3.4", null);

        verify(fileService, never()).uploadWithMetadata(any(), anyString());
        assertNull(inserted.getImageUrls(), "没有有效截图时不应写入 JSON");
    }

    @Test
    void submit_uploadsImagesWithFeedbackBizType() throws IOException {
        when(fileService.uploadWithMetadata(any(), eq("feedback")))
            .thenReturn(new UploadedFileInfo("/uploads/feedback/x.png", "x.png", 10L, "image/png"));

        feedbackService.submit("BUG", "描述", null, List.of(image("x.png")), null, "1.2.3.4", null);

        verify(fileService, times(1)).uploadWithMetadata(any(), eq("feedback"));
        assertEquals(List.of("/uploads/feedback/x.png"), FeedbackService.parseImageUrls(inserted.getImageUrls()));
    }

    /**
     * 边界必须从两侧钉住：只测「超限被拒」的话，把 > 改成 >= 也不会失败，
     * 而这恰好会让 5 张截图 / 500 字描述 / 120 字联系方式被误拒（前端与规格都允许取满）。
     */
    @Test
    void submit_acceptsExactlyMaxImages() throws IOException {
        List<MultipartFile> images = new ArrayList<>();
        for (int i = 0; i < FeedbackService.MAX_IMAGES; i += 1) {
            images.add(image("shot-" + i + ".png"));
        }
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        when(fileService.uploadWithMetadata(any(), eq("feedback")))
            .thenAnswer(invocation -> new UploadedFileInfo(
                "/uploads/feedback/shot-" + seq.getAndIncrement() + ".png", "x.png", 10L, "image/png"));

        feedbackService.submit("BUG", "描述", null, images, null, "1.2.3.4", null);

        assertEquals(FeedbackService.MAX_IMAGES, FeedbackService.parseImageUrls(inserted.getImageUrls()).size(),
            "恰好 5 张截图应当被接受");
    }

    @Test
    void submit_acceptsExactlyMaxDescriptionLength() {
        String exact = "字".repeat(FeedbackService.MAX_DESCRIPTION);
        feedbackService.submit("BUG", exact, null, List.of(), null, "1.2.3.4", null);

        assertEquals(FeedbackService.MAX_DESCRIPTION, inserted.getDescription().length(),
            "恰好 500 字描述应当被接受");
    }

    @Test
    void submit_acceptsExactlyMaxContactLength() {
        String exact = "x".repeat(FeedbackService.MAX_CONTACT);
        feedbackService.submit("BUG", "描述", exact, List.of(), null, "1.2.3.4", null);

        assertEquals(FeedbackService.MAX_CONTACT, inserted.getContact().length(),
            "恰好 120 字的联系方式应当被接受");
    }

    @Test
    void submit_normalizesUnknownTypeInsteadOfRejecting() {        feedbackService.submit("not-a-real-type", "描述", null, List.of(), null, "1.2.3.4", null);
        assertEquals("OTHER", inserted.getType());
    }

    @Test
    void submit_trimsAndNullsEmptyContact() {
        feedbackService.submit("BUG", "描述", "   ", List.of(), null, "1.2.3.4", null);
        assertNull(inserted.getContact(), "空白联系方式应存 NULL");
    }

    @Test
    void submit_truncatesOversizedUserAgent() {
        String ua = "U".repeat(400);
        feedbackService.submit("BUG", "描述", null, List.of(), null, "1.2.3.4", ua);
        assertEquals(255, inserted.getUserAgent().length());
    }

    @Test
    void displayNumber_usesDateAndPaddedId() {
        Feedback feedback = new Feedback();
        feedback.setId(1L);
        feedback.setCreatedAt(dateOf(2026, Calendar.SEPTEMBER, 12));
        assertEquals("#FB20260912001", FeedbackService.displayNumber(feedback));

        feedback.setId(1234L);
        assertEquals("#FB202609121234", FeedbackService.displayNumber(feedback));
    }

    @Test
    void labelOf_mapsKnownTypes() {
        assertEquals("Bug 反馈", FeedbackService.labelOf("BUG"));
        assertEquals("功能建议", FeedbackService.labelOf("feature"));
        assertEquals("其他", FeedbackService.labelOf("garbage"));
        assertEquals("其他", FeedbackService.labelOf(null));
    }

    @Test
    void parseImageUrls_survivesCorruptedJson() {
        assertTrue(FeedbackService.parseImageUrls(null).isEmpty());
        assertTrue(FeedbackService.parseImageUrls("").isEmpty());
        assertTrue(FeedbackService.parseImageUrls("not json").isEmpty());
        assertEquals(List.of("/a.png", "/b.png"), FeedbackService.parseImageUrls("[\"/a.png\",\"/b.png\"]"));
    }

    @Test
    void buildDetailUrl_stripsTrailingSlash() {
        assertEquals("http://121.40.34.68:8090/feedback/view/tok",
            feedbackService.buildDetailUrl("tok"));
        ReflectionTestUtils.setField(feedbackService, "publicBaseUrl", "https://ilink.example.com/");
        assertEquals("https://ilink.example.com/feedback/view/tok",
            feedbackService.buildDetailUrl("tok"));
    }

    @Test
    void findByToken_rejectsBlankTokensWithoutQuerying() {
        assertNull(feedbackService.findByToken(null));
        assertNull(feedbackService.findByToken("   "));
        verify(feedbackMapper, never()).selectOne(any());
    }

    private static Date dateOf(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month, day, 12, 0, 0);
        return calendar.getTime();
    }
}
