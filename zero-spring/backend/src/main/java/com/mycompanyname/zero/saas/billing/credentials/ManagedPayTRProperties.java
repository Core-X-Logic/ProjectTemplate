package com.mycompanyname.zero.saas.billing.credentials;

import com.mycompanyname.zero.saas.billing.BillingPayTRProperties;
import com.mycompanyname.zero.saas.billing.PayTRBillingProvider;

/**
 * The {@link BillingPayTRProperties} view the ALWAYS-registered provider bean is built on
 * (ADR-0020): every getter resolves at CALL time through {@link BillingCredentialsResolver} — the
 * stored, encrypted value when the operator saved one, the environment-bound value otherwise. The
 * provider's own logic is untouched; it already read its getters per call
 * ({@code PayTRBillingProvider#verifyAndParse}, {@code #createCheckoutSession}), which is exactly
 * what makes a portal credential change live without a restart.
 *
 * <p>{@code isEnabled()} deliberately stays the ENVIRONMENT flag: "may this provider serve" is
 * {@code BillingProviderAvailability}'s question, and boot validation
 * ({@code BillingPayTRSecretValidator}) must keep judging the environment configuration alone.
 */
final class ManagedPayTRProperties extends BillingPayTRProperties {

    private static final String PROVIDER_ID = PayTRBillingProvider.PROVIDER_ID;

    private final BillingPayTRProperties environment;
    private final BillingCredentialsResolver resolver;

    ManagedPayTRProperties(BillingPayTRProperties environment, BillingCredentialsResolver resolver) {
        this.environment = environment;
        this.resolver = resolver;
    }

    @Override
    public boolean isEnabled() {
        return environment.isEnabled();
    }

    @Override
    public String getMerchantId() {
        return resolver.effectiveValue(PROVIDER_ID, "merchantId", environment.getMerchantId());
    }

    @Override
    public String getMerchantKey() {
        return resolver.effectiveValue(PROVIDER_ID, "merchantKey", environment.getMerchantKey());
    }

    @Override
    public String getMerchantSalt() {
        return resolver.effectiveValue(PROVIDER_ID, "merchantSalt", environment.getMerchantSalt());
    }

    @Override
    public boolean isTestMode() {
        String stored = resolver.effectiveValue(PROVIDER_ID, "testMode", null);
        return stored == null || stored.isBlank()
                ? environment.isTestMode()
                : Boolean.parseBoolean(stored);
    }
}
