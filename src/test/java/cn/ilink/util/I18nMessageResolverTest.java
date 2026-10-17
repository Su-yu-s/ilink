package cn.ilink.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class I18nMessageResolverTest {

    private final I18nMessageResolver resolver = new I18nMessageResolver(messageSource());

    @AfterEach
    void clearLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void resolvesMessagesForChineseAndEnglishLocales() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertEquals("账号已被暂停使用，如有疑问请联系管理员",
            resolver.get("account.disabled", "fallback"));

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertEquals("This account has been suspended. Contact an administrator if you have questions.",
            resolver.get("account.disabled", "fallback"));
    }

    @Test
    void interpolatesArgumentsInBothLocales() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertEquals("你仍是以下团队的创建者：星火。请先转让或解散团队后再注销账号。",
            resolver.get("account.delete.blockedDetailed", "fallback", "星火"));

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertEquals("You still created these teams: Spark. Transfer or dissolve them before closing your account.",
            resolver.get("account.delete.blockedDetailed", "fallback", "Spark"));
    }

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
