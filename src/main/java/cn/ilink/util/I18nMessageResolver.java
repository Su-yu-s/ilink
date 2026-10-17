package cn.ilink.util;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/** Resolves server messages in the locale selected by the current request. */
@Component
public class I18nMessageResolver {
    private final MessageSource messageSource;

    public I18nMessageResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String get(String key, String fallback, Object... arguments) {
        return messageSource.getMessage(key, arguments, fallback, LocaleContextHolder.getLocale());
    }
}
