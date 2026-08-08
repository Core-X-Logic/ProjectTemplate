package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.saas.billing.credentials.ManagedBillingProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Stripe provider UNCONDITIONALLY, on the managed-properties view — the
 * {@link BillingPayTRConfig} reasoning (ADR-0020). The webhook route still answers 404 when
 * neither the environment nor a stored credential set configures Stripe
 * ({@code BillingProviderAvailability}), and {@link BillingStripeSecretValidator} keeps refusing
 * boot on an enabled-but-secretless ENVIRONMENT configuration.
 *
 * <p><b>Documented limit (ADR-0020):</b> {@code StripeBillingProvider} binds its API client to
 * {@code getSecretKey()} AT CONSTRUCTION (instance-scoped {@code StripeClient}, a deliberate
 * choice against the SDK's mutable global), so a checkout secret key saved through the portal for
 * Stripe takes effect on the next restart — unlike PayTR and iyzico, whose adapters read every
 * credential per call. The webhook secret and publishable key resolve per call for Stripe too.
 * Stripe is dormant (ADR-0017) and not surfaced by the portal's default screen; lifting the limit
 * means making the client per-call inside the adapter, recorded as follow-up work, not smuggled
 * into this slice.
 */
@Configuration
public class BillingStripeConfig {

    @Bean
    public StripeBillingProvider stripeBillingProvider(BillingStripeProperties properties,
                                                       ManagedBillingProperties managedProperties,
                                                       ObjectMapper objectMapper) {
        return new StripeBillingProvider(managedProperties.stripe(properties), objectMapper);
    }
}
