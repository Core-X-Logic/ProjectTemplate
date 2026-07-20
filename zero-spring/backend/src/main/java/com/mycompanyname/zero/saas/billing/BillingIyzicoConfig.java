package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the iyzico provider only when the deployment asked for it — the
 * {@link BillingPayTRConfig} pattern exactly, and for the same reason: the
 * {@code /api/billing/webhook/iyzico} and {@code /api/billing/callback/iyzico} routes must exist on
 * every profile ({@code SecurityPathBindingIT} requires every {@code permitAll} matcher and every
 * {@code zero.ratelimit.paths} entry to resolve to a live route, and both name the iyzico paths
 * unconditionally), so the web surface is always mapped and answers 404 when no provider bean
 * exists, while the bean that holds the merchant credentials is registered only behind the flag
 * (and behind {@link BillingIyzicoSecretValidator}, which has already refused to boot with unusable
 * credentials by the time this bean is constructed).
 */
@Configuration
public class BillingIyzicoConfig {

    @Bean
    @ConditionalOnProperty(prefix = "zero.billing.iyzico", name = "enabled", havingValue = "true")
    public IyzicoBillingProvider iyzicoBillingProvider(BillingIyzicoProperties properties,
                                                       ObjectMapper objectMapper) {
        return new IyzicoBillingProvider(properties, objectMapper);
    }
}
