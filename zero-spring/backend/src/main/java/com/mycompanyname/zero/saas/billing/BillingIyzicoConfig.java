package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.saas.billing.credentials.ManagedBillingProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the iyzico provider UNCONDITIONALLY, on the managed-properties view — the
 * {@link BillingPayTRConfig} reasoning exactly (ADR-0020): availability moved from "the bean
 * exists" to {@code BillingProviderAvailability}, so both iyzico routes
 * ({@code /api/billing/webhook/iyzico}, {@code /api/billing/callback/iyzico}) keep answering 404
 * when neither the environment nor a stored credential set configures iyzico
 * ({@code IyzicoDisabledSurfaceIT} still pins that), while a credential set saved through the
 * portal brings the surface up WITHOUT a restart. {@link BillingIyzicoSecretValidator} keeps
 * guarding the environment path at boot; the DB path is validated at write time.
 */
@Configuration
public class BillingIyzicoConfig {

    @Bean
    public IyzicoBillingProvider iyzicoBillingProvider(BillingIyzicoProperties properties,
                                                       ManagedBillingProperties managedProperties,
                                                       ObjectMapper objectMapper) {
        return new IyzicoBillingProvider(managedProperties.iyzico(properties), objectMapper);
    }
}
