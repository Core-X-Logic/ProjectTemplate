package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the PayTR provider only when the deployment asked for it — the {@link BillingStripeConfig}
 * pattern exactly, and for the same reason: the {@code /api/billing/webhook/paytr} route must exist
 * on every profile ({@code SecurityPathBindingIT} requires every {@code permitAll} matcher and every
 * {@code zero.ratelimit.paths} entry to resolve to a live route, and both name the webhook
 * unconditionally), so the web surface is always mapped and answers 404 when no provider bean
 * exists, while the bean that holds the merchant credentials is registered only behind the flag
 * (and behind {@link BillingPayTRSecretValidator}, which has already refused to boot with unusable
 * credentials by the time this bean is constructed).
 */
@Configuration
public class BillingPayTRConfig {

    @Bean
    @ConditionalOnProperty(prefix = "zero.billing.paytr", name = "enabled", havingValue = "true")
    public PayTRBillingProvider paytrBillingProvider(BillingPayTRProperties properties,
                                                     ObjectMapper objectMapper) {
        return new PayTRBillingProvider(properties, objectMapper);
    }
}
