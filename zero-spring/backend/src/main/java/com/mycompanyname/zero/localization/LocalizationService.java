package com.mycompanyname.zero.localization;

import com.mycompanyname.zero.localization.web.dto.LanguageDto;
import com.mycompanyname.zero.shared.domain.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Read-only localization service. Supported languages are fixed to English and Turkish; there is
 * no DB-backed language table and no per-tenant override — adding either is an extension point for
 * the application built on this template. Full dictionaries are read straight
 * from the UTF-8 {@code i18n/messages_*.properties} bundles so the frontend can bootstrap its i18n.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LocalizationService {

    private static final List<LanguageDto> LANGUAGES = List.of(
            new LanguageDto("en", "English"),
            new LanguageDto("tr", "Türkçe"));

    private final MessageSource messageSource;

    /**
     * Resolves a single message for the given locale, returning the key itself when it is unknown.
     */
    public String getString(String key, Locale locale) {
        return messageSource.getMessage(key, null, key, locale);
    }

    /**
     * Returns every key/value pair for the requested culture. Unknown cultures are rejected with a
     * NOT_FOUND error.
     */
    public Map<String, String> getDictionary(String culture) {
        String language = normalize(culture);
        Properties properties = new Properties();
        ClassPathResource resource = new ClassPathResource("i18n/messages_" + language + ".properties");
        try (InputStream in = resource.getInputStream();
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            log.error("Failed to load localization bundle for {}", language, e);
            throw new DomainException(
                    com.mycompanyname.zero.shared.domain.ErrorCode.INTERNAL,
                    "Failed to load localization resources");
        }
        Map<String, String> dictionary = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            dictionary.put(name, properties.getProperty(name));
        }
        return dictionary;
    }

    public List<LanguageDto> getLanguages() {
        return LANGUAGES;
    }

    private String normalize(String culture) {
        if (culture == null || culture.isBlank()) {
            throw DomainException.notFound("Unsupported culture: " + culture);
        }
        String tag = Locale.forLanguageTag(culture.replace('_', '-')).getLanguage();
        String language = tag.isEmpty() ? culture.toLowerCase(Locale.ROOT).split("[-_]")[0] : tag;
        boolean supported = LANGUAGES.stream().anyMatch(lang -> lang.name().equals(language));
        if (!supported) {
            throw DomainException.notFound("Unsupported culture: " + culture);
        }
        return language;
    }
}
