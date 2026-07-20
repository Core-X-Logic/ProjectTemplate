package com.mycompanyname.zero.saas.billing;

import com.mycompanyname.zero.saas.subscription.SubscriptionService;
import com.mycompanyname.zero.saas.subscription.web.dto.AssignEditionRequest;
import com.mycompanyname.zero.shared.domain.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Webhook intake: verify, dedup, act, in one transaction. This closes the two measured
 * source-system bugs (G14):
 *
 * <ol>
 *   <li><b>Duplicate delivery answered 4xx</b>, which providers read as "not delivered" and retry
 *       for ever. Here a duplicate resolves to 0 rows on the dedup insert and answers 200 without
 *       reprocessing.</li>
 *   <li><b>Activation waited for a browser redirect.</b> Here the edition assignment AND the
 *       activation run inside the webhook itself; the buyer's browser is not part of the payment
 *       truth at all (ADR-0014).</li>
 * </ol>
 *
 * <p><b>Transaction boundary — one transaction, chosen deliberately.</b> The dedup insert, the
 * payment/subscription mutations and the event's final status are committed or rolled back as a
 * unit:
 * <ul>
 *   <li><b>Failure mid-processing rolls back the dedup row too</b>, so the provider's retry does not
 *       hit the dedup and reprocesses from scratch — the retry is then CORRECT, not a duplicate. The
 *       retry can only be stopped by a row that COMMITTED, and a committed row means processing
 *       committed with it.</li>
 *   <li><b>The alternative (commit the row first, mark FAILED after)</b> was considered and
 *       rejected: a crash between the two commits leaves a RECEIVED row that swallows every retry
 *       with a 200 while nothing was processed — a lost payment with all gates green. Making FAILED
 *       rows re-claimable would reintroduce the concurrent-retry race the unique constraint exists
 *       to end. {@code WebhookEventStatus.FAILED} therefore stays reserved (see its javadoc).</li>
 *   <li><b>Concurrency serializes at two points, one per identity.</b> Deliveries of the SAME
 *       event id serialize on the unique index: the second insert blocks until the first
 *       transaction ends, then sees 0 rows (committed) or wins the slot (rolled back). Deliveries
 *       with DIFFERENT event ids naming the same session pass dedup by design, and serialize on
 *       the payment row instead: the webhook path reads it with {@code PESSIMISTIC_WRITE}
 *       ({@link PaymentRepository#findByExternalSessionIdForUpdate}), so the loser blocks, then
 *       reads {@code PAID} and the payment-status guard fires. Together these make "no
 *       interleaving double-processes" true for both duplicate shapes.</li>
 * </ul>
 *
 * <p>A processing failure therefore surfaces as a 500 ProblemDetail (generic body, stack trace in
 * the log only) and the provider's bounded retry schedule does the rest.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BillingWebhookService {

    /** Written into {@code subscription_events.actor} so the trail shows WHO activated: the webhook. */
    private static final String WEBHOOK_ACTOR = "stripe-webhook";

    private final ObjectProvider<BillingProvider> billingProviders;
    private final WebhookEventRepository webhookEventRepository;
    private final PaymentRepository paymentRepository;
    private final SubscriptionService subscriptionService;
    private final Clock clock;

    /**
     * Verifies and processes one delivery. Verification happens INSIDE the transaction but BEFORE
     * any write, so a rejected signature (→ 400 via {@code DomainException.validation}) rolls back
     * a transaction that touched nothing — "invalid signature stores nothing" holds by construction.
     */
    public void handle(String payload, String signatureHeader) {
        BillingProvider provider = requireProvider();

        BillingEvent event;
        try {
            event = provider.verifyAndParse(payload, signatureHeader);
        } catch (BillingSignatureException ex) {
            // WARN with the cause in the log; the response body names neither the payload nor the
            // submitted signature (house rule: rejected input is not echoed to the caller).
            log.warn("Rejected a billing webhook: {}", ex.getMessage());
            throw DomainException.validation("Webhook signature verification failed");
        }

        int inserted = webhookEventRepository.insertIfAbsent(provider.id(), event.eventId(),
                event.type().name(), event.rawPayload(), clock.instant(),
                WebhookEventStatus.RECEIVED.name());
        if (inserted == 0) {
            // Duplicate delivery. 200 and nothing else — a 4xx here is the measured source bug that
            // put Stripe into an infinite retry loop.
            log.info("Duplicate {} webhook event {} acknowledged without reprocessing",
                    provider.id(), event.eventId());
            return;
        }

        WebhookEvent stored = webhookEventRepository
                .findByProviderAndEventId(provider.id(), event.eventId())
                .orElseThrow(() -> new IllegalStateException(
                        "The webhook event row this transaction just inserted is not visible"));
        stored.setStatus(dispatch(event));
        stored.setProcessedAt(clock.instant());
        webhookEventRepository.save(stored);
    }

    private WebhookEventStatus dispatch(BillingEvent event) {
        return switch (event.type()) {
            case CHECKOUT_COMPLETED -> onCheckoutCompleted(event);
            // Renewal-driven period extension is a later slice. The event is stored (payload and
            // all) so it can be backfilled; acknowledging with 200 keeps Stripe from retrying an
            // event this version has no handler for.
            case RECURRING_PAYMENT_SUCCEEDED -> WebhookEventStatus.IGNORED;
            case UNKNOWN -> WebhookEventStatus.IGNORED;
        };
    }

    /**
     * The server-authoritative activation path. Payment found and {@code NOT_PAID}: mark it PAID and
     * move the subscription onto the paid package via the EXISTING SubscriptionService API —
     * {@code assignEdition} (snapshot + {@code PENDING_PAYMENT}, state-table row S3) followed by
     * {@code activate} ({@code PENDING_PAYMENT -> ACTIVE}, row S4), so the guard and the event trail
     * are exactly the ones every other transition uses.
     */
    private WebhookEventStatus onCheckoutCompleted(BillingEvent event) {
        if (event.externalSessionId() == null || event.externalSessionId().isBlank()) {
            // Signed but malformed beyond use. Retrying cannot fix it, so a 5xx would only schedule
            // three days of identical failures; store it, say so, move on.
            log.warn("checkout.session.completed event {} carries no session id; stored as IGNORED",
                    event.eventId());
            return WebhookEventStatus.IGNORED;
        }
        // For-update lookup, deliberately: a concurrent completion event with a DIFFERENT id for
        // the same session must queue behind this row lock and then hit the PAID guard below.
        Payment payment = paymentRepository.findByExternalSessionIdForUpdate(event.externalSessionId())
                // Loud 500 on purpose: a completed checkout this installation has no payment row for
                // is either a transient ordering problem (retry will succeed) or a genuinely lost
                // payment (must not be silently swallowed). The transaction — including the dedup
                // row — rolls back, so the provider's bounded retry reprocesses cleanly.
                .orElseThrow(() -> new IllegalStateException(
                        "checkout.session.completed for a session with no payment row"));

        if (payment.getStatus() == PaymentStatus.PAID) {
            // Replay protection layer 2 (layer 1 is the event dedup): a DIFFERENT event id naming an
            // already-settled session must not double-activate. The for-update lookup above is what
            // makes this guard hold under concurrency — a racing transaction waits on the row and
            // reads PAID here instead of a stale NOT_PAID. Acknowledge and record.
            log.info("Payment {} is already PAID; event {} acknowledged without a second activation",
                    payment.getId(), event.eventId());
            return WebhookEventStatus.PROCESSED;
        }
        if (payment.getStatus() != PaymentStatus.NOT_PAID) {
            // Money arrived for a payment this side had already written off. Do not activate off a
            // state the operator decided against; record it loudly for reconciliation.
            log.warn("Payment {} is {} but received completion event {}; no activation performed",
                    payment.getId(), payment.getStatus(), event.eventId());
            return WebhookEventStatus.PROCESSED;
        }

        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(clock.instant());
        payment.setExternalPaymentId(event.externalPaymentId());
        paymentRepository.save(payment);

        if (payment.getTargetEditionId() != null) {
            subscriptionService.assignEdition(payment.getTenantId(),
                    new AssignEditionRequest(payment.getTargetEditionId(), payment.getPeriod(), false),
                    WEBHOOK_ACTOR);
        }
        subscriptionService.activate(payment.getTenantId(), WEBHOOK_ACTOR);

        log.info("Payment {} confirmed by event {}; tenant {} activated on edition {}",
                payment.getId(), event.eventId(), payment.getTenantId(), payment.getTargetEditionId());
        return WebhookEventStatus.PROCESSED;
    }

    /**
     * 404 when billing is disabled, chosen over 503. 503 tells the sender "temporarily down, retry"
     * — but a webhook arriving at an installation with billing OFF is a configuration mistake that
     * no amount of retrying fixes, so inviting three days of retries only manufactures log noise.
     * 404 states the truth: with the flag off, this surface does not exist (the provider bean is not
     * registered at all), and it also discloses nothing about whether billing COULD be enabled here.
     */
    private BillingProvider requireProvider() {
        BillingProvider provider = billingProviders.getIfAvailable();
        if (provider == null) {
            throw DomainException.notFound("Billing is not enabled on this installation");
        }
        return provider;
    }
}
