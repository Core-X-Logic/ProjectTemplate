package com.mycompanyname.zero.saas.billing.credentials;

import com.mycompanyname.zero.saas.billing.BillingIyzicoProperties;
import com.mycompanyname.zero.saas.billing.IyzicoBillingProvider;

/**
 * The {@link BillingIyzicoProperties} view the always-registered iyzico bean is built on — the
 * {@link ManagedPayTRProperties} pattern for the third provider: call-time resolution through
 * {@link BillingCredentialsResolver}, environment fallback per field, {@code isEnabled()} left to
 * the environment (see that class for the reasoning).
 *
 * <p>{@code baseUrl} keeps its production-host default through the fallback chain: a stored set
 * that omits it resolves to the environment value, whose YAML default is the production host ON
 * PURPOSE ({@link BillingIyzicoProperties#getBaseUrl}).
 */
final class ManagedIyzicoProperties extends BillingIyzicoProperties {

    private static final String PROVIDER_ID = IyzicoBillingProvider.PROVIDER_ID;

    private final BillingIyzicoProperties environment;
    private final BillingCredentialsResolver resolver;

    ManagedIyzicoProperties(BillingIyzicoProperties environment, BillingCredentialsResolver resolver) {
        this.environment = environment;
        this.resolver = resolver;
    }

    @Override
    public boolean isEnabled() {
        return environment.isEnabled();
    }

    @Override
    public String getApiKey() {
        return resolver.effectiveValue(PROVIDER_ID, "apiKey", environment.getApiKey());
    }

    @Override
    public String getSecretKey() {
        return resolver.effectiveValue(PROVIDER_ID, "secretKey", environment.getSecretKey());
    }

    @Override
    public String getBaseUrl() {
        return resolver.effectiveValue(PROVIDER_ID, "baseUrl", environment.getBaseUrl());
    }

    @Override
    public String getCallbackUrl() {
        return resolver.effectiveValue(PROVIDER_ID, "callbackUrl", environment.getCallbackUrl());
    }
}
