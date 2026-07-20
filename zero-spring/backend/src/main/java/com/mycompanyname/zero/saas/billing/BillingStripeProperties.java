package com.mycompanyname.zero.saas.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Stripe wiring, all off by default. When {@code enabled} is {@code false} — the base, dev and test
 * default — no provider bean is registered ({@link BillingStripeConfig}) and the billing surface
 * answers 404. When it is {@code true}, {@link BillingStripeSecretValidator} refuses to boot without
 * real secrets, in the {@code JwtSecretValidator} pattern: a deployment that says "billing on" and
 * then supplies no key must fail loudly at startup, not 500 on the first webhook.
 */
@Component
@ConfigurationProperties(prefix = "zero.billing.stripe")
@Getter
@Setter
public class BillingStripeProperties {

    /** Master switch. Off = the provider bean does not exist and the billing surface answers 404. */
    private boolean enabled;

    /** Stripe API secret key ({@code sk_...}); used for the checkout-session API call. */
    private String secretKey;

    /**
     * The endpoint signing secret ({@code whsec_...}) Stripe shows when the webhook endpoint is
     * registered. Verification runs fully offline — this is the webhook's entire authentication.
     */
    private String webhookSecret;

    /**
     * Publishable key ({@code pk_...}). Not used server-side today; carried so the frontend can be
     * handed it later without a schema change. Blank is a warning, never a boot failure.
     */
    private String publishableKey;
}
