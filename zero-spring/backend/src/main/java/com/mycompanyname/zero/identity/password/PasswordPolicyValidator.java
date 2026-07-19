package com.mycompanyname.zero.identity.password;

import com.mycompanyname.zero.identity.auth.CurrentUser;
import com.mycompanyname.zero.settings.SettingManager;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.passay.CharacterRule;
import org.passay.EnglishCharacterData;
import org.passay.LengthRule;
import org.passay.PasswordData;
import org.passay.PasswordValidator;
import org.passay.Rule;
import org.passay.RuleResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates raw passwords against a {@link PasswordPolicy} using Passay. Throws
 * {@link DomainException} with {@link ErrorCode#VALIDATION} on the first non-compliant password.
 *
 * <p>The effective policy is resolved from the hierarchical settings module
 * ({@code App.Password.*}) via {@link SettingManager}: USER &rarr; TENANT &rarr; APPLICATION &rarr; definition
 * default. Setting names are referenced as string literals so the identity module only touches the
 * {@code settings} module's public {@link SettingManager} API (never its internal registry types).
 */
@Component
@RequiredArgsConstructor
public class PasswordPolicyValidator {

    static final String SETTING_REQUIRED_LENGTH = "App.Password.RequiredLength";
    static final String SETTING_REQUIRE_DIGIT = "App.Password.RequireDigit";
    static final String SETTING_REQUIRE_UPPERCASE = "App.Password.RequireUppercase";
    static final String SETTING_REQUIRE_LOWERCASE = "App.Password.RequireLowercase";
    static final String SETTING_REQUIRE_NON_ALPHANUMERIC = "App.Password.RequireNonAlphanumeric";
    static final String SETTING_HISTORY_COUNT = "App.Password.HistoryCount";

    private final SettingManager settingManager;

    /** Validates against the policy resolved for the current user/tenant context. */
    public void validate(String rawPassword) {
        validate(currentPolicy(), rawPassword);
    }

    /** Validates against the policy resolved for an explicit tenant/user (e.g. anonymous reset flows). */
    public void validate(Long tenantId, Long userId, String rawPassword) {
        validate(resolvePolicy(tenantId, userId), rawPassword);
    }

    /** Validates against a caller-resolved policy. */
    public void validate(PasswordPolicy policy, String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION, "Password must not be empty");
        }
        List<Rule> rules = new ArrayList<>();
        rules.add(new LengthRule(policy.requiredLength(), Integer.MAX_VALUE));
        if (policy.requireDigit()) {
            rules.add(new CharacterRule(EnglishCharacterData.Digit, 1));
        }
        if (policy.requireUppercase()) {
            rules.add(new CharacterRule(EnglishCharacterData.UpperCase, 1));
        }
        if (policy.requireLowercase()) {
            rules.add(new CharacterRule(EnglishCharacterData.LowerCase, 1));
        }
        if (policy.requireNonAlphanumeric()) {
            rules.add(new CharacterRule(EnglishCharacterData.Special, 1));
        }
        PasswordValidator validator = new PasswordValidator(rules);
        RuleResult result = validator.validate(new PasswordData(rawPassword));
        if (!result.isValid()) {
            String detail = String.join("; ", validator.getMessages(result));
            throw new DomainException(ErrorCode.VALIDATION, "Password does not meet policy: " + detail);
        }
    }

    /** Resolves the policy for the currently authenticated user/tenant (or application scope if none). */
    public PasswordPolicy currentPolicy() {
        return resolvePolicy(CurrentUser.tenantId(), CurrentUser.userId());
    }

    /** Builds a {@link PasswordPolicy} from the settings hierarchy for the given tenant/user. */
    public PasswordPolicy resolvePolicy(Long tenantId, Long userId) {
        return new PasswordPolicy(
                intSetting(SETTING_REQUIRED_LENGTH, tenantId, userId, PasswordPolicy.DEFAULT_REQUIRED_LENGTH),
                boolSetting(SETTING_REQUIRE_DIGIT, tenantId, userId, true),
                boolSetting(SETTING_REQUIRE_UPPERCASE, tenantId, userId, true),
                boolSetting(SETTING_REQUIRE_LOWERCASE, tenantId, userId, true),
                boolSetting(SETTING_REQUIRE_NON_ALPHANUMERIC, tenantId, userId, false),
                intSetting(SETTING_HISTORY_COUNT, tenantId, userId, PasswordPolicy.DEFAULT_HISTORY_COUNT));
    }

    private int intSetting(String name, Long tenantId, Long userId, int fallback) {
        String value = settingManager.getOrDefault(name, tenantId, userId);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean boolSetting(String name, Long tenantId, Long userId, boolean fallback) {
        String value = settingManager.getOrDefault(name, tenantId, userId);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }
}
