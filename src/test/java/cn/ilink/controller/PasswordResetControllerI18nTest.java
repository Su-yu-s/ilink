package cn.ilink.controller;

import cn.ilink.common.Result;
import cn.ilink.dto.PasswordResetRequest;
import cn.ilink.security.LoginAttemptService;
import cn.ilink.service.PasswordResetService;
import cn.ilink.util.I18nMessageResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetControllerI18nTest {

    @AfterEach
    void clearLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void returnsGenericEnglishMessageWithoutRevealingWhetherAccountExists() {
        PasswordResetService resetService = mock(PasswordResetService.class);
        LoginAttemptService attemptService = mock(LoginAttemptService.class);
        when(attemptService.tryPasswordReset(anyString())).thenReturn(true);
        PasswordResetController controller = controller(resetService, attemptService);
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        Result<?> result = controller.request(new PasswordResetRequest(), request()).getBody();

        assertEquals(200, result.getCode());
        assertEquals("If this account has a linked email address, a password reset email will be sent. Please check your inbox.",
            result.getMessage());
        verify(resetService).requestReset(null, "127.0.0.1");
    }

    @Test
    void returnsLocalizedRateLimitMessage() {
        PasswordResetService resetService = mock(PasswordResetService.class);
        LoginAttemptService attemptService = mock(LoginAttemptService.class);
        when(attemptService.tryPasswordReset(anyString())).thenReturn(false);
        PasswordResetController controller = controller(resetService, attemptService);
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        Result<?> result = controller.request(new PasswordResetRequest(), request()).getBody();

        assertEquals(429, result.getCode());
        assertEquals("Too many password reset requests. Try again in 15 minutes.", result.getMessage());
        verify(resetService, never()).requestReset(anyString(), anyString());
    }

    private PasswordResetController controller(PasswordResetService resetService,
                                               LoginAttemptService attemptService) {
        PasswordResetController controller = new PasswordResetController(resetService, attemptService);
        ReflectionTestUtils.setField(controller, "i18nMessages", new I18nMessageResolver(messageSource()));
        return controller;
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
