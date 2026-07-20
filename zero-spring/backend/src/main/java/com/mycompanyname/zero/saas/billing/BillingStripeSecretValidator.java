package com.mycompanyname.zero.saas.billing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Startup guard for the Stripe secrets, in the {@code JwtSecretValidator} pattern.
 *
 * <p>Why this cannot be left to the first request: an unresolved {@code ${STRIPE_SECRET_KEY}}
 * placeholder binds as a LITERAL STRING without any error (measured project-wide trap — see
 * CLAUDE.md), so a mis-deployed installation would come up green, accept checkout requests, and fail
 * only when Stripe rejects the garbage key — or worse, verify every webhook signature against the
 * literal placeholder and answer 400 to all of them, which reads as "Stripe is broken" instead of
 * "this deployment is". Enabled + unusable secrets is therefore a refused boot with a message that
 * names the property and the environment variable.
 *
 * <p>{@code enabled=false} skips everything: a fresh clone must boot with no Stripe account.
 * {@link #validate} is static so the rules are unit-testable without a Spring context
 * ({@code BillingPropertiesValidationTest}).
 */
@Component
@Slf4j
public class BillingStripeSecretValidator implements InitializingBean {

    private final BillingStripeProperties properties;

    public BillingStripeSecretValidator(BillingStripeProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        validate(properties);
    }

    /**
     * @throws IllegalStateException when billing is enabled but a required secret is missing, blank,
     *                               or a still-unresolved {@code ${...}} placeholder
     */
    public static void validate(BillingStripeProperties properties) {
        if (!properties.isEnabled()) {
            return;
        }
        requireUsable(properties.getSecretKey(),
                "zero.billing.stripe.secret-key", "STRIPE_SECRET_KEY");
        requireUsable(properties.getWebhookSecret(),
                "zero.billing.stripe.webhook-secret", "STRIPE_WEBHOOK_SECRET");
        if (isUnusable(properties.getPublishableKey())) {
            // Not fatal: nothing server-side reads it. But a deployment that enabled billing and
            // forgot it will find out at frontend-integration time; say so now, once, at boot.
            log.warn("zero.billing.stripe.publishable-key is not set. Server-side billing works "
                    + "without it, but the frontend checkout integration will need it.");
        }
    }

    private static void requireUsable(String value, String property, String envVar) {
        if (isUnusable(value)) {
            throw new IllegalStateException(property + " is not configured but zero.billing.stripe."
                    + "enabled is true. Set the " + envVar + " environment variable to the value from "
                    + "the Stripe dashboard, or set zero.billing.stripe.enabled=false.");
        }
    }

    private static boolean isUnusable(String value) {
        // contains, not startsWith: a half-resolved "sk_${STRIPE_KEY_SUFFIX}" is just as unusable.
        return value == null || value.isBlank() || value.contains("${");
    }
}
