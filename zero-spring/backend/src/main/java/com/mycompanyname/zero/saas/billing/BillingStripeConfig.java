package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Stripe provider only when the deployment asked for it.
 *
 * <p>The conditional lives HERE and not on the controllers on purpose. The webhook route must exist
 * on every profile — {@code SecurityPathBindingIT} requires every {@code permitAll} matcher and
 * every {@code zero.ratelimit.paths} entry to resolve to a live route, and both name the webhook
 * unconditionally. So the web surface is always mapped and answers 404 when no provider bean exists,
 * while the bean that holds secrets and talks to Stripe is registered only behind the flag (and
 * behind {@link BillingStripeSecretValidator}, which has already refused to boot with unusable
 * secrets by the time this bean is constructed).
 */
@Configuration
public class BillingStripeConfig {

    @Bean
    @ConditionalOnProperty(prefix = "zero.billing.stripe", name = "enabled", havingValue = "true")
    public StripeBillingProvider stripeBillingProvider(BillingStripeProperties properties,
                                                       ObjectMapper objectMapper) {
        return new StripeBillingProvider(properties, objectMapper);
    }
}
