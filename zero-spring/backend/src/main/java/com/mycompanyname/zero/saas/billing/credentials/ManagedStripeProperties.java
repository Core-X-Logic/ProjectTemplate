package com.mycompanyname.zero.saas.billing.credentials;

import com.mycompanyname.zero.saas.billing.BillingStripeProperties;
import com.mycompanyname.zero.saas.billing.StripeBillingProvider;

/**
 * The {@link BillingStripeProperties} view the always-registered Stripe bean is built on — the
 * {@link ManagedPayTRProperties} pattern applied to the dormant provider (ADR-0017): Stripe is not
 * surfaced by the portal's default screen, but it is deliberately NOT excluded from managed
 * credentials either, so an installation that sells outside Türkiye can enable it from the same
 * screen without a code change.
 */
final class ManagedStripeProperties extends BillingStripeProperties {

    private static final String PROVIDER_ID = StripeBillingProvider.PROVIDER_ID;

    private final BillingStripeProperties environment;
    private final BillingCredentialsResolver resolver;

    ManagedStripeProperties(BillingStripeProperties environment, BillingCredentialsResolver resolver) {
        this.environment = environment;
        this.resolver = resolver;
    }

    @Override
    public boolean isEnabled() {
        return environment.isEnabled();
    }

    @Override
    public String getSecretKey() {
        return resolver.effectiveValue(PROVIDER_ID, "secretKey", environment.getSecretKey());
    }

    @Override
    public String getWebhookSecret() {
        return resolver.effectiveValue(PROVIDER_ID, "webhookSecret", environment.getWebhookSecret());
    }

    @Override
    public String getPublishableKey() {
        return resolver.effectiveValue(PROVIDER_ID, "publishableKey", environment.getPublishableKey());
    }
}
