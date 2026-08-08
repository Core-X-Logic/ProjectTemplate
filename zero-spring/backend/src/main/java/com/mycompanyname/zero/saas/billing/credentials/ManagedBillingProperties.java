package com.mycompanyname.zero.saas.billing.credentials;

import com.mycompanyname.zero.saas.billing.BillingIyzicoProperties;
import com.mycompanyname.zero.saas.billing.BillingPayTRProperties;
import com.mycompanyname.zero.saas.billing.BillingStripeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Factory for the managed views of the three {@code Billing*Properties} beans (ADR-0020). The
 * provider {@code @Configuration} classes ({@code BillingPayTRConfig} and siblings) build their
 * provider bean on the view this factory returns instead of the raw environment bean — the ONE
 * wiring change that turns every provider credential into "DB when stored, environment otherwise"
 * without touching a line inside the providers themselves.
 */
@Component
@RequiredArgsConstructor
public class ManagedBillingProperties {

    private final BillingCredentialsResolver resolver;

    public BillingPayTRProperties paytr(BillingPayTRProperties environment) {
        return new ManagedPayTRProperties(environment, resolver);
    }

    public BillingIyzicoProperties iyzico(BillingIyzicoProperties environment) {
        return new ManagedIyzicoProperties(environment, resolver);
    }

    public BillingStripeProperties stripe(BillingStripeProperties environment) {
        return new ManagedStripeProperties(environment, resolver);
    }
}
