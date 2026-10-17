package cn.ilink.service;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 邮件外发通道。三级降级，按可用性依次：
 * 1. Resend HTTPS API（配了 API Key 时优先，走 443 端口，不受云服务器出站 25 端口封锁影响）
 * 2. SMTP（配了 spring.mail.host 时才存在 JavaMailSender）
 * 3. 都不可用返回 false —— 由调用方决定如何降级（如开发环境把内容打进日志，或直接告知用户稍后再试）
 */
@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String fromAddress;
    private final Resend resend;

    public MailService(ObjectProvider<JavaMailSender> mailSenderProvider,
                       @Value("${app.mail.from:no-reply@ilink.local}") String fromAddress,
                       @Value("${app.mail.resend-api-key:}") String resendApiKey) {
        this.mailSenderProvider = mailSenderProvider;
        this.fromAddress = fromAddress;
        this.resend = StringUtils.hasText(resendApiKey) ? new Resend(resendApiKey.trim()) : null;
        log.info("邮件通道: {}（发件人 {}）",
            this.resend != null ? "Resend API" : "SMTP / 交由调用方降级", fromAddress);
    }

    /** 外发一封邮件；发出返回 true，两个通道都不可用返回 false */
    public boolean send(String to, String subject, String textBody, String htmlBody) {
        if (!StringUtils.hasText(to)) {
            return false;
        }
        String recipient = to.trim();

        if (resend != null) {
            try {
                CreateEmailOptions.Builder builder = CreateEmailOptions.builder()
                    .from(fromAddress)
                    .to(recipient)
                    .subject(subject)
                    .text(textBody);
                if (StringUtils.hasText(htmlBody)) {
                    builder.html(htmlBody);
                }
                resend.emails().send(builder.build());
                return true;
            } catch (ResendException e) {
                log.error("Resend 发送失败（收件人 {}，主题 {}）: status={} {}",
                    recipient, subject, e.getStatusCode(), e.getMessage());
            }
        }

        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender != null) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(fromAddress);
                message.setTo(recipient);
                message.setSubject(subject);
                message.setText(textBody);
                sender.send(message);
                return true;
            } catch (Exception e) {
                log.error("SMTP 发送失败（收件人 {}，主题 {}）: {}", recipient, subject, e.getMessage());
            }
        }
        return false;
    }
}
