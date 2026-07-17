package com.mycompanyname.zero.identity.password;

/**
 * Resolved password-policy parameters.
 *
 * <p>The concrete values originate from the hierarchical settings module
 * ({@code App.Password.*}, see CONTRACT-phase2 §6). Because {@code identity} must not depend on the
 * {@code settings} module (dependencies flow settings &rarr; identity, never the reverse), callers
 * that can read settings build a {@link PasswordPolicy} and pass it to
 * {@link PasswordPolicyValidator#validate(PasswordPolicy, String)}. When no resolved policy is
 * available the {@link #defaults()} match the setting defaults from the contract.
 */
public record PasswordPolicy(
        int requiredLength,
        boolean requireDigit,
        boolean requireUppercase,
        boolean requireLowercase,
        boolean requireNonAlphanumeric,
        int historyCount) {

    public static final int DEFAULT_REQUIRED_LENGTH = 6;
    public static final int DEFAULT_HISTORY_COUNT = 3;

    /** Defaults mirroring {@code App.Password.*} defaults in CONTRACT-phase2 §6. */
    public static PasswordPolicy defaults() {
        return new PasswordPolicy(
                DEFAULT_REQUIRED_LENGTH, true, true, true, false, DEFAULT_HISTORY_COUNT);
    }
}
