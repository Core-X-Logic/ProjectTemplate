package com.mycompanyname.zero.settings.domain;

import com.mycompanyname.zero.shared.domain.DomainException;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry of every setting the platform understands. New settings MUST be added
 * here to be readable/writable through {@code SettingManager}. Values are stored as strings and
 * interpreted by their consumers.
 */
public final class SettingDefinitions {

    private static final EnumSet<Scope> APP_TENANT = EnumSet.of(Scope.APPLICATION, Scope.TENANT);
    private static final EnumSet<Scope> APP_TENANT_USER = EnumSet.of(Scope.APPLICATION, Scope.TENANT, Scope.USER);

    // --- Password policy (visible to client) ---
    public static final SettingDefinition PASSWORD_REQUIRED_LENGTH =
            def("App.Password.RequiredLength", "6", APP_TENANT, true);
    public static final SettingDefinition PASSWORD_REQUIRE_DIGIT =
            def("App.Password.RequireDigit", "true", APP_TENANT, true);
    public static final SettingDefinition PASSWORD_REQUIRE_UPPERCASE =
            def("App.Password.RequireUppercase", "true", APP_TENANT, true);
    public static final SettingDefinition PASSWORD_REQUIRE_LOWERCASE =
            def("App.Password.RequireLowercase", "true", APP_TENANT, true);
    public static final SettingDefinition PASSWORD_REQUIRE_NON_ALPHANUMERIC =
            def("App.Password.RequireNonAlphanumeric", "false", APP_TENANT, true);
    public static final SettingDefinition PASSWORD_HISTORY_COUNT =
            def("App.Password.HistoryCount", "3", APP_TENANT, true);

    // --- User management ---
    public static final SettingDefinition USERMANAGEMENT_EMAIL_CONFIRMATION_REQUIRED =
            def("App.UserManagement.IsEmailConfirmationRequired", "false", APP_TENANT, false);
    public static final SettingDefinition USERMANAGEMENT_SESSION_TIMEOUT_SECONDS =
            def("App.UserManagement.SessionTimeOutSeconds", "0", APP_TENANT_USER, true);

    // --- Auth / lockout ---
    public static final SettingDefinition AUTH_LOCKOUT_MAX_FAILED_ATTEMPTS =
            def("App.Auth.LockoutMaxFailedAttempts", "5", APP_TENANT, false);
    public static final SettingDefinition AUTH_LOCKOUT_DURATION_SECONDS =
            def("App.Auth.LockoutDurationSeconds", "300", APP_TENANT, false);

    // --- Localization ---
    public static final SettingDefinition LOCALIZATION_DEFAULT_LANGUAGE =
            def("App.Localization.DefaultLanguage", "en", APP_TENANT, true);

    // --- Email ---
    public static final SettingDefinition EMAIL_DEFAULT_FROM_ADDRESS =
            def("App.Email.DefaultFromAddress", "noreply@zero.local", APP_TENANT, false);
    public static final SettingDefinition EMAIL_DEFAULT_FROM_DISPLAY_NAME =
            def("App.Email.DefaultFromDisplayName", "Zero", APP_TENANT, false);

    public static final List<SettingDefinition> ALL = List.of(
            PASSWORD_REQUIRED_LENGTH,
            PASSWORD_REQUIRE_DIGIT,
            PASSWORD_REQUIRE_UPPERCASE,
            PASSWORD_REQUIRE_LOWERCASE,
            PASSWORD_REQUIRE_NON_ALPHANUMERIC,
            PASSWORD_HISTORY_COUNT,
            USERMANAGEMENT_EMAIL_CONFIRMATION_REQUIRED,
            USERMANAGEMENT_SESSION_TIMEOUT_SECONDS,
            AUTH_LOCKOUT_MAX_FAILED_ATTEMPTS,
            AUTH_LOCKOUT_DURATION_SECONDS,
            LOCALIZATION_DEFAULT_LANGUAGE,
            EMAIL_DEFAULT_FROM_ADDRESS,
            EMAIL_DEFAULT_FROM_DISPLAY_NAME);

    private static final Map<String, SettingDefinition> BY_NAME = index();

    private SettingDefinitions() {
    }

    private static SettingDefinition def(String name, String defaultValue, EnumSet<Scope> scopes, boolean visibleToClient) {
        return new SettingDefinition(name, defaultValue, scopes, false, visibleToClient);
    }

    private static Map<String, SettingDefinition> index() {
        Map<String, SettingDefinition> map = new LinkedHashMap<>();
        for (SettingDefinition definition : ALL) {
            map.put(definition.name(), definition);
        }
        return Map.copyOf(map);
    }

    /**
     * Returns the definition for {@code name} or throws a VALIDATION error if it is not a known setting.
     */
    public static SettingDefinition require(String name) {
        SettingDefinition definition = BY_NAME.get(name);
        if (definition == null) {
            throw DomainException.validation("Unknown setting: " + name);
        }
        return definition;
    }

    public static List<SettingDefinition> forScope(Scope scope) {
        return ALL.stream().filter(definition -> definition.scopes().contains(scope)).toList();
    }

    public static List<SettingDefinition> clientVisible() {
        return ALL.stream().filter(SettingDefinition::visibleToClient).toList();
    }
}
