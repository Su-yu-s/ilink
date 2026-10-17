package cn.ilink.controller;

import cn.ilink.common.Result;
import cn.ilink.dto.PasswordResetConfirmRequest;
import cn.ilink.dto.PasswordResetRequest;
import cn.ilink.security.LoginAttemptService;
import cn.ilink.service.PasswordResetService;
import cn.ilink.util.ClientIpResolver;
import cn.ilink.util.I18nMessageResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/password-reset")
public class PasswordResetController {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetController.class);
    private final PasswordResetService passwordResetService;
    private final LoginAttemptService loginAttemptService;

    @Autowired(required = false)
    private I18nMessageResolver i18nMessages;

    public PasswordResetController(PasswordResetService passwordResetService,
                                   LoginAttemptService loginAttemptService) {
        this.passwordResetService = passwordResetService;
        this.loginAttemptService = loginAttemptService;
    }

    @PostMapping("/request")
    public ResponseEntity<Result<?>> request(@RequestBody PasswordResetRequest body,
                                             HttpServletRequest request) {
        String ip = ClientIpResolver.resolve(request);
        boolean allowed = loginAttemptService.tryPasswordReset(ip);
        if (!allowed) {
            // 超限以前是"静默丢弃但照样回 200 统一文案"，用户以为邮件已发出去却永远收不到（核实报告 P3-1）。
            // 这里如实告知：保持防枚举（不区分账号是否存在），只说明限流事实与恢复时间。
            return Result.fail(429, msg("password.reset.request.rateLimit",
                "找回密码请求过于频繁，请 15 分钟后再试")).toResponseEntity();
        }
        try {
            passwordResetService.requestReset(body == null ? null : body.getAccount(), ip);
        } catch (Exception e) {
            log.warn("密码重置邮件处理失败", e);
        }
        return Result.ok(msg("password.reset.request.generic",
            "如果该账号已绑定邮箱，我们会发送一封密码重置邮件，请注意查收。"), null).toResponseEntity();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Result<?>> confirm(@RequestBody PasswordResetConfirmRequest body) {
        try {
            passwordResetService.resetPassword(
                body == null ? null : body.getToken(),
                body == null ? null : body.getNewPassword());
            return Result.ok(msg("password.reset.confirm.success", "密码重置成功，请使用新密码登录"), null)
                .toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        } catch (Exception e) {
            log.error("密码重置失败", e);
            return Result.fail(500, msg("password.reset.confirm.failed", "密码重置失败，请稍后重试"))
                .toResponseEntity();
        }
    }

    private String msg(String key, String fallback, Object... arguments) {
        return i18nMessages == null ? fallback : i18nMessages.get(key, fallback, arguments);
    }
}
