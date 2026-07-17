package com.mycompanyname.zero.localization.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * Localization infrastructure: the {@link MessageSource} backing {@code i18n/messages_*.properties}
 * and the {@link LocaleResolver} that picks the request locale from the {@code Accept-Language}
 * header, falling back to a configured default when the header is absent or unsupported.
 */
@Configuration
public class MessageSourceConfig {

    static final Locale ENGLISH = Locale.of("en");
    static final Locale TURKISH = Locale.of("tr");

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("i18n/messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setUseCodeAsDefaultMessage(true);
        return messageSource;
    }

    @Bean
    public LocaleResolver localeResolver(@Value("${zero.localization.default-language:en}") String defaultLanguage) {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(ENGLISH, TURKISH));
        Locale fallback = Locale.forLanguageTag(defaultLanguage);
        resolver.setDefaultLocale(fallback.getLanguage().isEmpty() ? ENGLISH : fallback);
        return resolver;
    }
}
