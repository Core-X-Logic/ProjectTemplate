package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.saas.billing.credentials.ManagedBillingProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the PayTR provider UNCONDITIONALLY, on the managed-properties view (ADR-0020). Until
 * managed credentials this bean sat behind {@code @ConditionalOnProperty(zero.billing.paytr
 * .enabled)} and "bean exists" WAS the enabled fact; with the portal able to enable a provider at
 * runtime that fact became {@code BillingProviderAvailability}'s to answer, per request — a bean
 * cannot appear mid-flight, so it must always exist and the surfaces must filter instead
 * ({@code BillingWebhookService#requireProvider} 404s exactly as before when neither the
 * environment nor a stored credential set enables PayTR; {@code PayTRDisabledSurfaceIT} still
 * pins that).
 *
 * <p>Constructing the bean with no credentials is safe: the constructor stores references and
 * builds a {@code RestClient}, and every credential getter resolves at CALL time through
 * {@link ManagedBillingProperties} — stored value when the operator saved one, environment value
 * otherwise. {@link BillingPayTRSecretValidator} still refuses boot when the ENVIRONMENT says
 * enabled without usable credentials; DB-path completeness is validated at write time instead
 * ({@code BillingProviderAdminService}).
 */
@Configuration
public class BillingPayTRConfig {

    @Bean
    public PayTRBillingProvider paytrBillingProvider(BillingPayTRProperties properties,
                                                     ManagedBillingProperties managedProperties,
                                                     ObjectMapper objectMapper) {
        return new PayTRBillingProvider(managedProperties.paytr(properties), objectMapper);
    }
}
