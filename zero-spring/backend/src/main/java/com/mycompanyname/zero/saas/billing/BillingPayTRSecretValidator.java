package com.mycompanyname.zero.saas.billing;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Startup guard for the PayTR credentials, in the {@link BillingStripeSecretValidator} /
 * {@code JwtSecretValidator} pattern.
 *
 * <p>Why this cannot be left to the first request: an unresolved {@code ${PAYTR_MERCHANT_KEY}}
 * placeholder binds as a LITERAL STRING without any error (measured project-wide trap — see
 * CLAUDE.md), so a mis-deployed installation would come up green and then verify every notification
 * hash against the placeholder text — answering 400 to all of them, which PayTR reads as failed
 * deliveries while buyers HAVE been charged. Enabled + unusable credentials is therefore a refused
 * boot with a message that names the property and the environment variable.
 *
 * <p>{@code enabled=false} skips everything: a fresh clone must boot with no PayTR account.
 * {@link #validate} is static so the rules are unit-testable without a Spring context
 * ({@code PayTRPropertiesValidationTest}).
 */
@Component
public class BillingPayTRSecretValidator implements InitializingBean {

    private final BillingPayTRProperties properties;

    public BillingPayTRSecretValidator(BillingPayTRProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        validate(properties);
    }

    /**
     * @throws IllegalStateException when PayTR is enabled but a required credential is missing,
     *                               blank, or a still-unresolved {@code ${...}} placeholder
     */
    public static void validate(BillingPayTRProperties properties) {
        if (!properties.isEnabled()) {
            return;
        }
        requireUsable(properties.getMerchantId(),
                "zero.billing.paytr.merchant-id", "PAYTR_MERCHANT_ID");
        requireUsable(properties.getMerchantKey(),
                "zero.billing.paytr.merchant-key", "PAYTR_MERCHANT_KEY");
        requireUsable(properties.getMerchantSalt(),
                "zero.billing.paytr.merchant-salt", "PAYTR_MERCHANT_SALT");
    }

    private static void requireUsable(String value, String property, String envVar) {
        if (isUnusable(value)) {
            throw new IllegalStateException(property + " is not configured but zero.billing.paytr."
                    + "enabled is true. Set the " + envVar + " environment variable to the value from "
                    + "the PayTR merchant panel, or set zero.billing.paytr.enabled=false.");
        }
    }

    private static boolean isUnusable(String value) {
        // contains, not startsWith: a half-resolved "key-${PAYTR_SUFFIX}" is just as unusable.
        return value == null || value.isBlank() || value.contains("${");
    }
}
