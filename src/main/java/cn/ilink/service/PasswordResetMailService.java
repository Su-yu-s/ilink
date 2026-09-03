package cn.ilink.service;

import cn.ilink.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Arrays;

@Service
public class PasswordResetMailService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailService.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final Environment environment;
    private final String publicBaseUrl;
    private final String fromAddress;

    public PasswordResetMailService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            Environment environment,
            @Value("${app.public-base-url:http://localhost:8090}") String publicBaseUrl,
            @Value("${app.mail.from:no-reply@ilink.local}") String fromAddress) {
        this.mailSenderProvider = mailSenderProvider;
        this.environment = environment;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.fromAddress = fromAddress;
    }

    public void sendResetLink(User user, String rawToken) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        String link = publicBaseUrl + "/forgot-password.html?token=" + rawToken;
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender != null) {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(user.getEmail().trim());
            message.setSubject("iLink 密码重置");
            message.setText("你正在重置 iLink 账号密码。链接 30 分钟内有效且只能使用一次：\n\n" +
                link + "\n\n如非本人操作，请忽略本邮件。");
            sender.send(message);
            return;
        }
        if (Arrays.asList(environment.getActiveProfiles()).contains("dev")) {
            log.info("开发环境密码重置链接（仅本地日志可见）: {}", link);
        } else {
            log.warn("邮件服务未配置，未能发送密码重置邮件");
        }
    }
}
