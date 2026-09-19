package cn.ilink.controller;

import cn.ilink.entity.Feedback;
import cn.ilink.security.LoginAttemptService;
import cn.ilink.service.FeedbackPushService;
import cn.ilink.service.FeedbackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * FeedbackController 的请求编排与异常映射。
 *
 * <p><b>覆盖范围说明</b>：{@code addFilters = false} 关掉了安全过滤器链，
 * 因此本测试<b>不</b>验证 SecurityConfig 里 <code>/api/feedback</code> 与 <code>/feedback/view/**</code> 的
 * permitAll 规则，也不验证 CSRF。那两条规则由 SecurityConfig 自身保证，
 * 改动时需人工复核（全仓零方法级权限注解，权限矩阵只在该文件里）。
 */
@WebMvcTest(controllers = FeedbackController.class)
@AutoConfigureMockMvc(addFilters = false)
class FeedbackControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FeedbackService feedbackService;

    @MockBean
    private FeedbackPushService feedbackPushService;

    @MockBean
    private LoginAttemptService loginAttemptService;

    private Feedback feedbackWithId(long id) {
        Feedback feedback = new Feedback();
        feedback.setId(id);
        feedback.setCreatedAt(new Date());
        feedback.setType("BUG");
        feedback.setDescription("编辑器分栏滚动不同步");
        feedback.setContact("su@example.com");
        feedback.setImageUrls("[\"/uploads/feedback/a.png\"]");
        feedback.setHandled(false);
        return feedback;
    }

    @Test
    void submit_returnsDetailUrlAndDisplayNumber() throws Exception {
        when(loginAttemptService.tryFeedback(any())).thenReturn(true);
        Feedback feedback = feedbackWithId(1L);
        when(feedbackService.submit(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new FeedbackService.Submission(feedback, "raw-token"));
        when(feedbackService.buildDetailUrl("raw-token"))
            .thenReturn("http://121.40.34.68:8090/feedback/view/raw-token");
        when(feedbackPushService.send(feedback, "http://121.40.34.68:8090/feedback/view/raw-token"))
            .thenReturn(true);

        mockMvc.perform(multipart("/api/feedback")
                .param("type", "BUG")
                .param("desc", "编辑器分栏滚动不同步")
                .param("contact", "su@example.com"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.detailUrl").value("http://121.40.34.68:8090/feedback/view/raw-token"))
            .andExpect(jsonPath("$.data.displayNumber").value(
                FeedbackService.displayNumber(feedback)));
    }

    @Test
    void submit_acceptsImageParts() throws Exception {
        when(loginAttemptService.tryFeedback(any())).thenReturn(true);
        when(feedbackService.submit(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new FeedbackService.Submission(feedbackWithId(1L), "raw-token"));
        when(feedbackService.buildDetailUrl(any())).thenReturn("http://localhost:8090/feedback/view/raw-token");

        MockMultipartFile image = new MockMultipartFile("images", "shot.png", "image/png",
            "bytes".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/feedback")
                .file(image)
                .param("type", "UX")
                .param("desc", "上传截图后预览区不显示"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(feedbackService).submit(eq("UX"), eq("上传截图后预览区不显示"), any(), any(), any(), any(), any());
    }

    @Test
    void submit_returnsTooManyRequestsWhenRateLimited() throws Exception {
        when(loginAttemptService.tryFeedback(any())).thenReturn(false);

        // Result.toHttpStatus() 不映射 429，限流沿用项目既有约定：HTTP 200 + body.code=429
        // （与 AiAssistantController 的配额超限一致，前端 request() 按 body.code 报错）
        mockMvc.perform(multipart("/api/feedback").param("desc", "刷屏"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(429))
            .andExpect(jsonPath("$.message").value("提交过于频繁，请稍后再试"));

        verify(feedbackService, never()).submit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void submit_mapsValidationFailureToBadRequest() throws Exception {
        when(loginAttemptService.tryFeedback(any())).thenReturn(true);
        when(feedbackService.submit(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("请填写详细描述"));

        mockMvc.perform(multipart("/api/feedback").param("desc", ""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("请填写详细描述"));
    }

    @Test
    void submit_stillSucceedsWhenPushFails() throws Exception {
        when(loginAttemptService.tryFeedback(any())).thenReturn(true);
        when(feedbackService.submit(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new FeedbackService.Submission(feedbackWithId(1L), "raw-token"));
        when(feedbackService.buildDetailUrl(any())).thenReturn("http://localhost:8090/feedback/view/raw-token");
        when(feedbackPushService.send(any(), any())).thenReturn(false);

        mockMvc.perform(multipart("/api/feedback").param("desc", "推送挂了也要提交成功"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void detail_rendersFeedbackForValidToken() throws Exception {
        Feedback feedback = feedbackWithId(1L);
        when(feedbackService.findByToken("valid-token")).thenReturn(feedback);

        mockMvc.perform(get("/feedback/view/valid-token"))
            .andExpect(status().isOk())
            .andExpect(view().name("feedback-detail"))
            .andExpect(model().attribute("displayNumber", FeedbackService.displayNumber(feedback)))
            .andExpect(model().attribute("typeLabel", "Bug 反馈"))
            .andExpect(model().attribute("imageUrls", List.of("/uploads/feedback/a.png")));
    }

    @Test
    void detail_returnsNotFoundForUnknownToken() throws Exception {
        when(feedbackService.findByToken("forged-token")).thenReturn(null);

        mockMvc.perform(get("/feedback/view/forged-token"))
            .andExpect(status().isNotFound());
    }

    @Test
    void detail_hidesImagesWhenStoredJsonIsCorrupted() throws Exception {
        Feedback feedback = feedbackWithId(1L);
        feedback.setImageUrls("corrupted");
        when(feedbackService.findByToken("valid-token")).thenReturn(feedback);

        mockMvc.perform(get("/feedback/view/valid-token"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("imageUrls", List.of()));
    }
}
