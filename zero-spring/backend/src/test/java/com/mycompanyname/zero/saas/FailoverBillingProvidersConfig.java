package com.mycompanyname.zero.saas;

import com.mycompanyname.zero.saas.billing.BillingEvent;
import com.mycompanyname.zero.saas.billing.BillingProvider;
import com.mycompanyname.zero.saas.billing.BillingSignatureException;
import com.mycompanyname.zero.saas.billing.CheckoutSession;
import com.mycompanyname.zero.saas.billing.CheckoutRequest;
import com.mycompanyname.zero.saas.billing.IyzicoBillingProvider;
import com.mycompanyname.zero.saas.billing.PayTRBillingProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TWO controllable fake providers under the REAL ids ({@code paytr}, {@code iyzico}) for the
 * failover ITs (ADR-0020). {@code @Primary} replaces the always-registered real beans per id in
 * {@code BillingProviderRegistryConfig}, the same semantics every other billing test double relies
 * on — here for both non-Stripe ids at once, so a checkout can be watched failing over from one to
 * the other WITHOUT any live provider call.
 *
 * <p>Availability is NOT faked: the ITs enable both providers by storing credentials through the
 * real admin API, so the failover order and the enablement rules exercised are the production
 * ones; only the outbound session creation (and a trivial webhook proof) are canned.
 *
 * <p>The webhook proof is deliberately trivial ({@code X-IYZ-SIGNATURE-V3: fake-proof}): these ITs
 * prove ROUTING — a payment started on a provider finishes through THAT provider's webhook — not
 * signature math, which PayTRWebhookIT/IyzicoWebhookIT own against real verification code.
 */
@TestConfiguration
public class FailoverBillingProvidersConfig {

    public static final String FAKE_PROOF_HEADER_VALUE = "fake-proof";

    /** Session-id prefixes: which provider issued a session is visible from the id itself. */
    public static final String PAYTR_SESSION_PREFIX = "PTRF";
    public static final String IYZICO_SESSION_PREFIX = "IYZF";

    /** When set, the named fake throws THIS from createCheckoutSession instead of answering. */
    public static final AtomicReference<RuntimeException> PAYTR_FAILURE = new AtomicReference<>();
    public static final AtomicReference<RuntimeException> IYZICO_FAILURE = new AtomicReference<>();

    /** Initiation attempts per fake — what the breaker/skip assertions read. */
    public static final AtomicInteger PAYTR_CALLS = new AtomicInteger();
    public static final AtomicInteger IYZICO_CALLS = new AtomicInteger();

    private static final AtomicLong SESSION_SEQUENCE = new AtomicLong();
    private static final Pattern SESSION_FIELD = Pattern.compile("\"session\"\\s*:\\s*\"([^\"]+)\"");

    public static void reset() {
        PAYTR_FAILURE.set(null);
        IYZICO_FAILURE.set(null);
        PAYTR_CALLS.set(0);
        IYZICO_CALLS.set(0);
    }

    @Bean
    @Primary
    BillingProvider fakePaytrProvider() {
        return new FakeProvider(PayTRBillingProvider.PROVIDER_ID, PAYTR_SESSION_PREFIX,
                PAYTR_FAILURE, PAYTR_CALLS, "OK");
    }

    @Bean
    @Primary
    BillingProvider fakeIyzicoProvider() {
        return new FakeProvider(IyzicoBillingProvider.PROVIDER_ID, IYZICO_SESSION_PREFIX,
                IYZICO_FAILURE, IYZICO_CALLS, null);
    }

    private record FakeProvider(String id, String sessionPrefix,
                                AtomicReference<RuntimeException> failure, AtomicInteger calls,
                                String ackBody) implements BillingProvider {

        @Override
        public String successAckBody() {
            return ackBody;
        }

        @Override
        public BillingEvent verifyAndParse(String payload, String signatureHeader) {
            if (!FAKE_PROOF_HEADER_VALUE.equals(signatureHeader)) {
                throw new BillingSignatureException("fake proof missing or wrong");
            }
            Matcher matcher = SESSION_FIELD.matcher(payload == null ? "" : payload);
            if (!matcher.find()) {
                throw new BillingSignatureException("fake payload carries no session");
            }
            String session = matcher.group(1);
            return new BillingEvent("fake-evt-" + session, BillingEvent.Type.CHECKOUT_COMPLETED,
                    session, "fake-payment-" + session, payload);
        }

        @Override
        public CheckoutSession createCheckoutSession(CheckoutRequest request) {
            calls.incrementAndGet();
            RuntimeException toThrow = failure.get();
            if (toThrow != null) {
                throw toThrow;
            }
            String sessionId = sessionPrefix + SESSION_SEQUENCE.incrementAndGet();
            return new CheckoutSession(sessionId, "https://" + id + ".example/pay/" + sessionId);
        }
    }
}
