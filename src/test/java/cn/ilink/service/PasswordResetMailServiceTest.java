package cn.ilink.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.ilink.util.I18nMessageResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * 未配置 Resend API Key（且无 SMTP）时的降级行为：开发环境把重置链接写进日志，不抛异常。
 * 真实发信无法在单测里覆盖（需密钥与外网），靠降级路径锁住最常见的本地开发场景。
 */
class PasswordResetMailServiceTest {

    @Test
    void sendsEnglishResetMailWhenEnglishLocaleIsSelected() {
        MailService mail = mock(MailService.class);
        when(mail.send(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        PasswordResetMailService service = new PasswordResetMailService(
            mail, new MockEnvironment(), "https://ilink.work");
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        ReflectionTestUtils.setField(service, "i18nMessages", new I18nMessageResolver(source));

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        try {
            service.sendResetLink("student@example.com", "rawtoken123");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("student@example.com"), subject.capture(), text.capture(), html.capture());
        assertEquals("Reset your iLink password", subject.getValue());
        assertTrue(text.getValue().contains("Use this link to reset your iLink password"));
        assertTrue(html.getValue().contains("Reset password"));
        assertTrue(html.getValue().contains("https://ilink.work/forgot-password.html?token=rawtoken123"));
        assertFalse(text.getValue().contains("重置"));
    }

    @Test
    void withoutApiKeyFallsBackToDevLogWithResetLink() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(PasswordResetMailService.class);
        logger.addAppender(appender);
        try {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("dev");
            PasswordResetMailService service = new PasswordResetMailService(
                mailService(),
                environment, "https://ilink.work/");

            service.sendResetLink("student@example.com", "rawtoken123");

            List<String> links = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("forgot-password.html"))
                .collect(Collectors.toList());
            assertEquals(1, links.size(), "应只输出一条重置链接日志，实际: " + appender.list);
            // 末尾斜杠被去掉，链接可直接点击
            assertTrue(links.get(0).contains("https://ilink.work/forgot-password.html?token=rawtoken123"),
                "链接拼装错误: " + links.get(0));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void blankRecipientSilentlySkips() {
        MockEnvironment environment = new MockEnvironment();
        PasswordResetMailService service = new PasswordResetMailService(
            mailService(),
            environment, "https://ilink.work");

        // 收件地址为空时静默跳过，不抛异常
        service.sendResetLink("   ", "rawtoken123");
        service.sendResetLink(null, "rawtoken123");
    }

    /** 不带 Resend Key 的邮件通道：两个在线通道都不可用，走调用方的日志降级 */
    private static MailService mailService() {
        return new MailService(
            new StaticListableBeanFactory().getBeanProvider(JavaMailSender.class),
            "iLink <noreply@ilink.work>", "");
    }
}
