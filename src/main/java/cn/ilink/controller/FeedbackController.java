package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.Feedback;
import cn.ilink.entity.User;
import cn.ilink.security.LoginAttemptService;
import cn.ilink.service.FeedbackPushService;
import cn.ilink.service.FeedbackService;
import cn.ilink.util.ClientIpResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意见反馈：全站悬浮入口提交，开发者通过推送里的专属链接在手机端查看。
 *
 * <p>提交与查看均免登录：详情页通常在微信内置浏览器打开，没有会话，
 * 访问控制完全依赖 {@link FeedbackService} 生成的不可猜令牌。
 */
@Controller
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    @Autowired
    private FeedbackService feedbackService;

    @Autowired
    private FeedbackPushService feedbackPushService;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @PostMapping("/api/feedback")
    @ResponseBody
    public ResponseEntity<Result<?>> submit(@RequestParam(value = "type", required = false) String type,
                                            @RequestParam(value = "desc", required = false) String description,
                                            @RequestParam(value = "contact", required = false) String contact,
                                            @RequestParam(value = "images", required = false) List<MultipartFile> images,
                                            HttpSession session, HttpServletRequest request) {
        String clientIp = ClientIpResolver.resolve(request);
        if (!loginAttemptService.tryFeedback(clientIp)) {
            return Result.fail(429, "提交过于频繁，请稍后再试").toResponseEntity();
        }

        User user = ControllerUtils.requireUser(session);
        FeedbackService.Submission submission;
        try {
            submission = feedbackService.submit(type, description, contact, images,
                user == null ? null : user.getId(), clientIp, request.getHeader("User-Agent"));
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        }

        Feedback feedback = submission.getFeedback();
        String detailUrl = feedbackService.buildDetailUrl(submission.getRawToken());

        // 推送是外部网络调用，放在事务之外；失败不影响用户提交结果。
        if (feedbackPushService.send(feedback, detailUrl)) {
            log.info("反馈 #{} 已推送至开发者微信", feedback.getId());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", feedback.getId());
        data.put("displayNumber", FeedbackService.displayNumber(feedback));
        data.put("detailUrl", detailUrl);
        return Result.ok("反馈已提交，感谢你的反馈！", data).toResponseEntity();
    }

    @GetMapping("/feedback/view/{token}")
    public String detail(@PathVariable String token, Model model, HttpServletResponse response) {
        Feedback feedback = feedbackService.findByToken(token);
        if (feedback == null) {
            // 不区分「令牌不存在」与「令牌已失效」，避免探测
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "404";
        }
        model.addAttribute("feedback", feedback);
        model.addAttribute("displayNumber", FeedbackService.displayNumber(feedback));
        model.addAttribute("typeLabel", FeedbackService.labelOf(feedback.getType()));
        model.addAttribute("imageUrls", FeedbackService.parseImageUrls(feedback.getImageUrls()));
        return "feedback-detail";
    }
}
