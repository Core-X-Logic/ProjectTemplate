package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.saas.billing.BillingEvent;
import com.mycompanyname.zero.saas.billing.BillingProvider;
import com.mycompanyname.zero.saas.billing.BillingStripeProperties;
import com.mycompanyname.zero.saas.billing.CheckoutRequest;
import com.mycompanyname.zero.saas.billing.CheckoutSession;
import com.mycompanyname.zero.saas.billing.StripeBillingProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The billing provider the billing ITs run against: REAL Stripe signature verification, canned
 * checkout sessions.
 *
 * <p>{@code verifyAndParse} delegates to a genuine {@link StripeBillingProvider} constructed from
 * the test properties, so the offline HMAC verification path — the webhook's entire authentication —
 * is what the tests actually exercise, not a stub of it. {@code createCheckoutSession} is the one
 * SPI method whose real implementation performs a live Stripe API call; it is replaced with a
 * recording fake because that call is deliberately untested-live (recorded risk in the slice
 * contract).
 */
@TestConfiguration
public class BillingTestProviderConfig {

    /** Monotonic so every checkout in the shared context gets a unique {@code external_session_id}. */
    public static final AtomicLong SESSION_SEQUENCE = new AtomicLong();

    /** What the last checkout handed the provider — asserted instead of a live Stripe round-trip. */
    public static final AtomicReference<CheckoutRequest> LAST_CHECKOUT_REQUEST = new AtomicReference<>();

    @Bean
    @Primary
    BillingProvider recordingBillingProvider(BillingStripeProperties properties,
                                             ObjectMapper objectMapper) {
        StripeBillingProvider real = new StripeBillingProvider(properties, objectMapper);
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
            public CheckoutSession createCheckoutSession(CheckoutRequest request) {
                LAST_CHECKOUT_REQUEST.set(request);
                String sessionId = "cs_test_" + SESSION_SEQUENCE.incrementAndGet();
                return new CheckoutSession(sessionId, "https://checkout.stripe.example/" + sessionId);
            }
        };
    }
}
