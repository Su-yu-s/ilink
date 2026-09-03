package cn.ilink.controller;

import cn.ilink.common.Result;
import cn.ilink.dto.PasswordResetConfirmRequest;
import cn.ilink.dto.PasswordResetRequest;
import cn.ilink.security.LoginAttemptService;
import cn.ilink.service.PasswordResetService;
import cn.ilink.util.ClientIpResolver;
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
    private static final String GENERIC_MESSAGE =
        "如果该账号已绑定邮箱，我们会发送一封密码重置邮件，请注意查收。";

    private final PasswordResetService passwordResetService;
    private final LoginAttemptService loginAttemptService;

    public PasswordResetController(PasswordResetService passwordResetService,
                                   LoginAttemptService loginAttemptService) {
        this.passwordResetService = passwordResetService;
        this.loginAttemptService = loginAttemptService;
    }

    @PostMapping("/request")
    public ResponseEntity<Result<?>> request(@RequestBody PasswordResetRequest body,
                                             HttpServletRequest request) {
        String ip = ClientIpResolver.resolve(request);
        if (loginAttemptService.tryPasswordReset(ip)) {
            try {
                passwordResetService.requestReset(body == null ? null : body.getAccount(), ip);
            } catch (Exception e) {
                log.warn("密码重置邮件处理失败", e);
            }
        }
        return Result.ok(GENERIC_MESSAGE, null).toResponseEntity();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Result<?>> confirm(@RequestBody PasswordResetConfirmRequest body) {
        try {
            passwordResetService.resetPassword(
                body == null ? null : body.getToken(),
                body == null ? null : body.getNewPassword());
            return Result.ok("密码重置成功，请使用新密码登录", null).toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        } catch (Exception e) {
            log.error("密码重置失败", e);
            return Result.fail(500, "密码重置失败，请稍后重试").toResponseEntity();
        }
    }
}