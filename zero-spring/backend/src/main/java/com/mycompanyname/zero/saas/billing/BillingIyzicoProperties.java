package com.mycompanyname.zero.saas.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * iyzico wiring, all off by default — the {@link BillingPayTRProperties} pattern exactly. When
 * {@code enabled} is {@code false} (the base default) no provider bean is registered
 * ({@link BillingIyzicoConfig}) and both {@code /api/billing/webhook/iyzico} and
 * {@code /api/billing/callback/iyzico} answer 404. When it is {@code true},
 * {@link BillingIyzicoSecretValidator} refuses to boot without usable credentials: a deployment
 * that says "iyzico on" and then supplies no secret key would verify every
 * {@code X-IYZ-SIGNATURE-V3} against garbage and answer 400 to all webhooks — deliveries iyzico
 * retries only three times (every 10 minutes, per docs.iyzico.com "Webhook") before giving up.
 */
@Component
@ConfigurationProperties(prefix = "zero.billing.iyzico")
@Getter
@Setter
public class BillingIyzicoProperties {

    /** Master switch. Off = the provider bean does not exist and the iyzico surface answers 404. */
    private boolean enabled;

    /** iyzico API key, from the merchant panel (sandbox keys start {@code sandbox-}). */
    private String apiKey;

    /**
     * iyzico secret key. Double duty: the SDK derives the {@code IYZWSv2} request auth header from
     * it, and it is BOTH the HMAC key and the first element of the HMAC message of the webhook's
     * {@code X-IYZ-SIGNATURE-V3} proof (see {@link IyzicoBillingProvider#webhookSignature}) — the
     * webhook's entire authentication.
     */
    private String secretKey;

    /**
     * API base URL. The YAML default is the PRODUCTION host {@code https://api.iyzipay.com} ON
     * PURPOSE: sandbox-by-default would mean a deployment that forgot the override quietly
     * "collects" test money against real activations. Dev and test profiles override to
     * {@code https://sandbox-api.iyzipay.com} explicitly, where being loud costs nothing.
     */
    private String baseUrl;

    /**
     * Absolute public URL of {@code POST /api/billing/callback/iyzico} — where iyzico sends the
     * BUYER'S BROWSER after payment, carrying the single parameter {@code token}. Optional: when
     * blank, the checkout request's {@code successUrl} is sent instead and the SPA is expected to
     * trigger confirmation itself (P2'-C). Either way the callback is a TRIGGER only — activation
     * happens exclusively through the retrieve query ({@code BillingConfirmationService}), so a
     * mis-set callback URL costs latency (webhook/reconciliation still confirm), never money.
     */
    private String callbackUrl;
}
