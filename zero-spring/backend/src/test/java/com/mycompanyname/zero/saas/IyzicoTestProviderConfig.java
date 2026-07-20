package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.saas.billing.BillingEvent;
import com.mycompanyname.zero.saas.billing.BillingIyzicoProperties;
import com.mycompanyname.zero.saas.billing.BillingProvider;
import com.mycompanyname.zero.saas.billing.CheckoutRequest;
import com.mycompanyname.zero.saas.billing.CheckoutSession;
import com.mycompanyname.zero.saas.billing.IyzicoBillingProvider;
import com.mycompanyname.zero.saas.billing.ProviderPaymentConfirmation;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The iyzico provider the P2'-B ITs run against — the {@code PayTRTestProviderConfig} strategy for
 * the third provider: REAL {@code X-IYZ-SIGNATURE-V3} verification (the genuine
 * {@link IyzicoBillingProvider} built from the test properties, dummy secret), while the SPI's two
 * LIVE calls are replaced:
 *
 * <ul>
 *   <li>{@code createCheckoutSession} → a canned token/page URL, request recorded (that call is
 *       deliberately untested-live — the recorded PROD-R37 risk pattern);</li>
 *   <li>{@code confirmBySessionQuery} → the canned answer in {@link #RETRIEVE_RESULTS}, keyed by
 *       token, WITH EVERY CALL COUNTED ({@link #RETRIEVE_CALLS}). This is the contract's designed
 *       seam: the retrieve is faked exactly where the reconciliation job, the webhook funnel and
 *       the callback all call it, so all three triggers are proven against the SAME hook. Tests
 *       can the answers through {@code IyzicoBillingProviderTestHook.mapRetrieve} — the REAL
 *       activation predicate — so a fraud-review fixture is production logic, not a hand-built
 *       record. A token with no canned answer resolves to NOT collected (the fail-closed reading:
 *       an unanswerable retrieve must never activate).</li>
 * </ul>
 *
 * <p>{@code @Primary} REPLACES the real bean the enabled flag registers, under the same provider
 * id — resolved by {@code BillingProviderRegistryConfig} before the registry's duplicate-id
 * refusal, the same semantics the Stripe and PayTR doubles rely on.
 */
@TestConfiguration
public class IyzicoTestProviderConfig {

    /** Canned retrieve answers, keyed by checkout-form token. Tests reset this per scenario. */
    public static final Map<String, ProviderPaymentConfirmation> RETRIEVE_RESULTS =
            new ConcurrentHashMap<>();

    /** Every confirmBySessionQuery call — the proof dedup short-circuits BEFORE the retrieve. */
    public static final AtomicInteger RETRIEVE_CALLS = new AtomicInteger();

    /**
     * When set, every {@code confirmBySessionQuery} call runs this BEFORE answering — the seam the
     * concurrency and transport-failure tests need: a {@code CyclicBarrier} here holds two threads
     * INSIDE the provider query (after the peek, before the locked read — exactly the race window
     * of the stale-first-level-cache defect), and a throwing runnable simulates an SDK transport
     * failure. Tests MUST clear it in a finally block.
     */
    public static final AtomicReference<Runnable> RETRIEVE_INTERCEPTOR = new AtomicReference<>();

    /** What the last checkout handed the provider — asserted instead of a live iyzico round-trip. */
    public static final AtomicReference<CheckoutRequest> LAST_CHECKOUT_REQUEST = new AtomicReference<>();

    @Bean
    @Primary
    BillingProvider recordingIyzicoProvider(BillingIyzicoProperties properties,
                                            ObjectMapper objectMapper) {
        IyzicoBillingProvider real = new IyzicoBillingProvider(properties, objectMapper);
        return new BillingProvider() {
            @Override
            public String id() {
                return real.id();
            }

            @Override
            public BillingEvent verifyAndParse(String payload, String signatureHeader) {
                // REAL v3 verification — the webhook's entire authentication is what runs here.
                return real.verifyAndParse(payload, signatureHeader);
            }

            @Override
            public String successAckBody() {
                return real.successAckBody();
            }

            @Override
            public CheckoutSession createCheckoutSession(CheckoutRequest request) {
                LAST_CHECKOUT_REQUEST.set(request);
                String token = "IYZT" + UUID.randomUUID().toString().replace("-", "");
                return new CheckoutSession(token,
                        "https://sandbox-cf.iyzipay.com/?token=" + token);
            }

            @Override
            public boolean supportsQueryConfirmation() {
                return real.supportsQueryConfirmation();
            }

            @Override
            public ProviderPaymentConfirmation confirmBySessionQuery(String sessionId) {
                RETRIEVE_CALLS.incrementAndGet();
                Runnable interceptor = RETRIEVE_INTERCEPTOR.get();
                if (interceptor != null) {
                    interceptor.run();
                }
                return RETRIEVE_RESULTS.getOrDefault(sessionId,
                        ProviderPaymentConfirmation.notCollected(
                                "no canned retrieve result for this token (test default: fail closed)"));
            }
        };
    }
}
