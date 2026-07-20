package com.mycompanyname.zero.saas.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PayTR wiring, all off by default — the {@link BillingStripeProperties} pattern exactly. When
 * {@code enabled} is {@code false} (the base, dev and test default) no provider bean is registered
 * ({@link BillingPayTRConfig}) and {@code /api/billing/webhook/paytr} answers 404. When it is
 * {@code true}, {@link BillingPayTRSecretValidator} refuses to boot without real credentials: a
 * deployment that says "PayTR on" and then supplies no key must fail loudly at startup, not answer
 * 400 to every notification — which PayTR reads as "delivery failed" while the buyer HAS been
 * charged.
 */
@Component
@ConfigurationProperties(prefix = "zero.billing.paytr")
@Getter
@Setter
public class BillingPayTRProperties {

    /** Master switch. Off = the provider bean does not exist and the PayTR surface answers 404. */
    private boolean enabled;

    /** PayTR merchant number ({@code merchant_id}), from the PayTR merchant panel. */
    private String merchantId;

    /**
     * {@code merchant_key} — the HMAC key of BOTH formulas: the checkout token and the notification
     * hash. This is the webhook's entire authentication.
     */
    private String merchantKey;

    /**
     * {@code merchant_salt} — concatenated INTO the HMAC message (not the key). NOTE its position
     * differs between the two formulas: appended to the END of the token message, but placed right
     * after {@code merchant_oid} in the notification message. See {@link PayTRBillingProvider}.
     */
    private String merchantSalt;

    /**
     * {@code test_mode} flag sent on the checkout token request ({@code 1}/{@code 0} on the wire).
     * Test transactions settle no money; the flag participates in the token HMAC, so flipping it
     * without redeploying cannot go unnoticed.
     */
    private boolean testMode;
}
