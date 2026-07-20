package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.saas.billing.BillingEvent;
import com.mycompanyname.zero.saas.billing.BillingPayTRProperties;
import com.mycompanyname.zero.saas.billing.BillingProvider;
import com.mycompanyname.zero.saas.billing.CheckoutRequest;
import com.mycompanyname.zero.saas.billing.CheckoutSession;
import com.mycompanyname.zero.saas.billing.PayTRBillingProvider;
import com.mycompanyname.zero.saas.billing.PayTRBillingProviderTestHook;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The PayTR provider the PayTR ITs run against — the {@code BillingTestProviderConfig} strategy
 * applied to the second provider: REAL notification-hash verification, canned checkout sessions.
 *
 * <p>{@code verifyAndParse} delegates to a genuine {@link PayTRBillingProvider} built from the test
 * properties (dummy merchant key/salt), so the offline HMAC verification — the webhook's entire
 * authentication — is what the tests actually exercise, not a stub of it. {@code
 * createCheckoutSession} is the one SPI method whose real implementation performs a live PayTR
 * get-token call; it is replaced with a recording fake (that call is deliberately untested-live,
 * recorded risk), but the {@code merchant_oid} it hands out comes from the REAL generator, so the
 * oid the webhook tests round-trip obeys the real alphanumeric/length rules.
 *
 * <p>{@code @Primary} REPLACES the real bean the enabled flag registers, under the same provider id
 * — {@code BillingProviderRegistryConfig} resolves that primariness before the registry's
 * duplicate-id refusal, which is exactly the semantics the Stripe test double relies on too.
 */
@TestConfiguration
public class PayTRTestProviderConfig {

    /** What the last checkout handed the provider — asserted instead of a live PayTR round-trip. */
    public static final AtomicReference<CheckoutRequest> LAST_CHECKOUT_REQUEST = new AtomicReference<>();

    @Bean
    @Primary
    BillingProvider recordingPayTRProvider(BillingPayTRProperties properties,
                                           ObjectMapper objectMapper) {
        PayTRBillingProvider real = new PayTRBillingProvider(properties, objectMapper);
        return new BillingProvider() {
            @Override
            public String id() {
                return real.id();
            }

            @Override
            public BillingEvent verifyAndParse(String payload, String signatureHeader) {
                return real.verifyAndParse(payload, signatureHeader);
            }

            @Override
            public String successAckBody() {
                return real.successAckBody();
            }

            @Override
            public CheckoutSession createCheckoutSession(CheckoutRequest request) {
                LAST_CHECKOUT_REQUEST.set(request);
                // The REAL merchant_oid generator: unique per call, alphanumeric, ≤64 — so the oid
                // stored on the payment row and replayed by the webhook tests is a faithful one.
                String merchantOid = PayTRBillingProviderTestHook.newMerchantOid(request.paymentId());
                return new CheckoutSession(merchantOid,
                        "https://www.paytr.com/odeme/guvenli/test-token-" + merchantOid);
            }
        };
    }
}
