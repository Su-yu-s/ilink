package cn.ilink.service;

import cn.ilink.util.I18nMessageResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * 密码重置邮件。发送本身交给 {@link MailService}（Resend 优先，其次 SMTP）；
 * 两个通道都不可用时，开发环境把重置链接打进日志，生产环境记警告。
 */
@Service
public class PasswordResetMailService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailService.class);
    private static final String SUBJECT = "iLink 密码重置";

    private final MailService mailService;
    private final Environment environment;
    private final String publicBaseUrl;

    @Autowired(required = false)
    private I18nMessageResolver i18nMessages;

    public PasswordResetMailService(MailService mailService,
                                    Environment environment,
                                    @Value("${app.public-base-url:http://localhost:8090}") String publicBaseUrl) {
        this.mailService = mailService;
        this.environment = environment;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public void sendResetLink(String recipientEmail, String rawToken) {
        if (recipientEmail == null || recipientEmail.isBlank()) {
            return;
        }
        String to = recipientEmail.trim();
        String link = publicBaseUrl + "/forgot-password.html?token=" + rawToken;

        if (mailService.send(to,
                msg("password.reset.email.subject", SUBJECT),
                textBody(link),
                htmlBody(link))) {
            return;
        }
        if (Arrays.asList(environment.getActiveProfiles()).contains("dev")) {
            log.info("开发环境密码重置链接（仅本地日志可见）: {}", link);
        } else {
            log.warn("邮件服务未配置，未能发送密码重置邮件");
        }
    }

    private String textBody(String link) {
        return msg("password.reset.email.text",
            "你正在重置 iLink 账号密码。链接 30 分钟内有效且只能使用一次：\n\n{0}\n\n如非本人操作，请忽略本邮件。",
            link);
    }

    private String htmlBody(String link) {
        return msg("password.reset.email.html",
            "<p>你好，</p><p>你正在请求重置 iLink 账号密码，请点击下方链接完成操作：</p>"
                + "<p><a href=\"{0}\">重置密码</a></p>"
                + "<p>该链接 30 分钟内有效，仅可使用一次。如果不是你本人的操作，请忽略此邮件。</p>",
            link);
    }

    private String msg(String key, String fallback, Object... arguments) {
        return i18nMessages == null ? fallback : i18nMessages.get(key, fallback, arguments);
    }
}
