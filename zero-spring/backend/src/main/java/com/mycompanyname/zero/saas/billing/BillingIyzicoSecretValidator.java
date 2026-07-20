package com.mycompanyname.zero.saas.billing;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Startup guard for the iyzico credentials, in the {@link BillingPayTRSecretValidator} /
 * {@code JwtSecretValidator} pattern.
 *
 * <p>Why boot-time and not first-request: an unresolved {@code ${IYZICO_SECRET_KEY}} placeholder
 * binds as a LITERAL STRING without any error (measured project-wide trap — see CLAUDE.md). A
 * mis-deployed installation would come up green, verify every {@code X-IYZ-SIGNATURE-V3} against
 * the placeholder text and answer 400 — and iyzico's retry budget is only three redeliveries
 * (10-minute cadence), after which the webhook is gone and only the reconciliation job / runbook
 * net remains. Enabled + unusable credentials is therefore a refused boot naming the property and
 * the environment variable.
 *
 * <p>{@code enabled=false} skips everything: a fresh clone must boot with no iyzico account.
 * {@link #validate} is static so the rules are unit-testable without a Spring context
 * ({@code IyzicoPropertiesValidationTest}).
 */
@Component
public class BillingIyzicoSecretValidator implements InitializingBean {

    private final BillingIyzicoProperties properties;

    public BillingIyzicoSecretValidator(BillingIyzicoProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        validate(properties);
    }

    /**
     * @throws IllegalStateException when iyzico is enabled but a required credential is missing,
     *                               blank, or a still-unresolved {@code ${...}} placeholder
     */
    public static void validate(BillingIyzicoProperties properties) {
        if (!properties.isEnabled()) {
            return;
        }
        requireUsable(properties.getApiKey(),
                "zero.billing.iyzico.api-key", "IYZICO_API_KEY");
        requireUsable(properties.getSecretKey(),
                "zero.billing.iyzico.secret-key", "IYZICO_SECRET_KEY");
        requireUsable(properties.getBaseUrl(),
                "zero.billing.iyzico.base-url", "IYZICO_BASE_URL");
    }

    private static void requireUsable(String value, String property, String envVar) {
        if (isUnusable(value)) {
            throw new IllegalStateException(property + " is not configured but zero.billing.iyzico."
                    + "enabled is true. Set the " + envVar + " environment variable to the value from "
                    + "the iyzico merchant panel, or set zero.billing.iyzico.enabled=false.");
        }
    }

    private static boolean isUnusable(String value) {
        // contains, not startsWith: a half-resolved "key-${IYZICO_SUFFIX}" is just as unusable.
        return value == null || value.isBlank() || value.contains("${");
    }
}
