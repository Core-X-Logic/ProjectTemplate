package com.mycompanyname.zero.saas.billing;

import com.mycompanyname.zero.saas.subscription.SubscriptionService;
import com.mycompanyname.zero.saas.subscription.web.dto.AssignEditionRequest;
import com.mycompanyname.zero.shared.domain.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * Suffix of the {@code subscription_events.actor} entry, so the trail shows WHO activated: the
     * provider's webhook ("stripe-webhook", "paytr-webhook").
     */
    private static final String WEBHOOK_ACTOR_SUFFIX = "-webhook";

    private final BillingProviderRegistry providerRegistry;
    private final WebhookEventRepository webhookEventRepository;
    private final PaymentRepository paymentRepository;
    private final SubscriptionService subscriptionService;
    private final BillingConfirmationService confirmationService;
    private final Clock clock;

    /**
     * Verifies and processes one delivery for the provider the route named. Verification happens
     * INSIDE the transaction but BEFORE any write, so a rejected signature (→ 400 via
     * {@code DomainException.validation}) rolls back a transaction that touched nothing — "invalid
     * signature stores nothing" holds by construction.
     *
     * @return the provider's success acknowledgement body ({@link BillingProvider#successAckBody()}),
     *         identical for processed, duplicate and ignored outcomes ON PURPOSE: for PayTR all
     *         three must answer the literal body {@code OK}, because anything else reads as
     *         "delivery failed" and the money is not settled — including on a duplicate, which the
     *         provider cannot distinguish from a first delivery. {@code null} means a bodyless 200
     *         (Stripe).
     */
    public String handle(String providerId, String payload, String signatureHeader) {
        BillingProvider provider = requireProvider(providerId);

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
            // Duplicate delivery. Success ack and nothing else — a 4xx here is the measured source
            // bug that put Stripe into an infinite retry loop, and for PayTR a non-"OK" body on a
            // redelivery would read as failure on the provider side.
            log.info("Duplicate {} webhook event {} acknowledged without reprocessing",
                    provider.id(), event.eventId());
            return provider.successAckBody();
        }

        WebhookEvent stored = webhookEventRepository
                .findByProviderAndEventId(provider.id(), event.eventId())
                .orElseThrow(() -> new IllegalStateException(
                        "The webhook event row this transaction just inserted is not visible"));
        stored.setStatus(dispatch(provider, event));
        stored.setProcessedAt(clock.instant());
        webhookEventRepository.save(stored);
        return provider.successAckBody();
    }

    private WebhookEventStatus dispatch(BillingProvider provider, BillingEvent event) {
        return switch (event.type()) {
            case CHECKOUT_COMPLETED -> onCheckoutCompleted(provider, event);
            case PAYMENT_FAILED -> onPaymentFailed(event);
            // Renewal-driven period extension is a later slice. The event is stored (payload and
            // all) so it can be backfilled; acknowledging with 200 keeps the provider from retrying
            // an event this version has no handler for.
            case RECURRING_PAYMENT_SUCCEEDED -> WebhookEventStatus.IGNORED;
            case UNKNOWN -> WebhookEventStatus.IGNORED;
        };
    }

    /**
     * The server-authoritative activation path. Payment found and collectable: mark it PAID and
     * move the subscription onto the paid package via the EXISTING SubscriptionService API —
     * {@code assignEdition} (snapshot + {@code PENDING_PAYMENT}, state-table row S3) followed by
     * {@code activate} ({@code PENDING_PAYMENT -> ACTIVE}, row S4), so the guard and the event trail
     * are exactly the ones every other transition uses.
     *
     * <p><b>Which prior states activate — the asymmetry is deliberate and load-bearing.</b>
     * {@code NOT_PAID} AND {@code FAILED} activate; {@code CANCELLED} does not. The rule:
     * <em>webhook-written states are reversible by the webhook; operator-written states are not.</em>
     * {@code FAILED} is written by {@link #onPaymentFailed} off a provider notification, and
     * failed → success for the SAME session is a LEGITIMATE sequence, not an anomaly — PayTR's
     * iframe lets the buyer retry the card inside the session, so a first attempt's {@code failed}
     * can be followed by a hash-valid {@code success} for the same {@code merchant_oid}. That hash
     * PROVES the money moved; refusing to activate on it left money settled with no activation, in
     * a row shape (FAILED) that the NOT_PAID-focused reconciliation also missed. {@code CANCELLED}
     * is an operator's decision about this installation's intent, which no amount of provider
     * traffic may override — money arriving for it is recorded loudly for reconciliation instead.
     */
    private WebhookEventStatus onCheckoutCompleted(BillingProvider provider, BillingEvent event) {
        if (event.externalSessionId() == null || event.externalSessionId().isBlank()) {
            // Signed but malformed beyond use. Retrying cannot fix it, so a 5xx would only schedule
            // three days of identical failures; store it, say so, move on.
            log.warn("checkout.session.completed event {} carries no session id; stored as IGNORED",
                    event.eventId());
            return WebhookEventStatus.IGNORED;
        }
        if (provider.supportsQueryConfirmation()) {
            // Retrieve-authoritative funnel (P2'-B). For a provider that CAN be asked directly
            // (iyzico), the delivered payload's claim of success is treated as a TRIGGER only: the
            // provider's own query decides, through the same BillingConfirmationService call the
            // browser callback and the reconciliation job use. A non-confirming answer is still a
            // PROCESSED event — the authoritative check ran and said "not collected"; the payment
            // row is untouched and the reconciliation job re-asks (mutation-proved: activating from
            // the payload here turns IyzicoWebhookIT's retrieve-authoritative test red).
            BillingConfirmationService.Outcome outcome = confirmationService.confirmBySessionQuery(
                    provider, event.externalSessionId(), provider.id() + WEBHOOK_ACTOR_SUFFIX);
            if (outcome == BillingConfirmationService.Outcome.NO_PAYMENT_ROW) {
                // Same loud 500 as the classic path below, same reasoning: a completed checkout
                // with no payment row is transient ordering (retry succeeds once the row exists)
                // or a genuinely lost payment (must not be swallowed). Rollback includes the dedup
                // row, so the provider's bounded retry reprocesses cleanly.
                throw new IllegalStateException(
                        "checkout-completed event for a session with no payment row");
            }
            log.info("Event {} handled through the {} query-confirmation path: {}",
                    event.eventId(), provider.id(), outcome);
            return WebhookEventStatus.PROCESSED;
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
        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            // Operator-written state: money arrived for a payment this side deliberately wrote off.
            // Do not activate off a state the operator decided against; record it loudly for
            // reconciliation (see the method javadoc for why FAILED is treated differently).
            log.warn("Payment {} is CANCELLED but received completion event {}; no activation "
                    + "performed", payment.getId(), event.eventId());
            return WebhookEventStatus.PROCESSED;
        }
        if (payment.getStatus() == PaymentStatus.FAILED) {
            // Webhook-written state, reversed by the webhook: the buyer retried inside the provider
            // session and THIS verified event proves the retry collected. See the method javadoc.
            log.info("Payment {} was FAILED but completion event {} proves collection (buyer retry); "
                    + "activating", payment.getId(), event.eventId());
        }

        String actor = provider.id() + WEBHOOK_ACTOR_SUFFIX;
        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(clock.instant());
        payment.setExternalPaymentId(event.externalPaymentId());
        paymentRepository.save(payment);

        if (payment.getTargetEditionId() != null) {
            subscriptionService.assignEdition(payment.getTenantId(),
                    new AssignEditionRequest(payment.getTargetEditionId(), payment.getPeriod(), false),
                    actor);
        }
        subscriptionService.activate(payment.getTenantId(), actor);

        log.info("Payment {} confirmed by event {}; tenant {} activated on edition {}",
                payment.getId(), event.eventId(), payment.getTenantId(), payment.getTargetEditionId());
        return WebhookEventStatus.PROCESSED;
    }

    /**
     * The failure path (P2'-A, PayTR {@code status=failed}). One transition and one only:
     * {@code NOT_PAID -> FAILED}. Everything else is recorded without being acted on:
     *
     * <ul>
     *   <li><b>{@code PAID} stays {@code PAID}.</b> A late "failed" arriving AFTER a settled success
     *       (contradictory notifications for one {@code merchant_oid} — each carries its own event
     *       id, so dedup admits both) must never undo an activation the money already bought. The
     *       for-update lookup makes the guard hold under concurrency, same as the completion path.</li>
     *   <li><b>No payment row → {@code IGNORED}, loudly.</b> Deliberately NOT the completion path's
     *       500: there, money moved and a retry can settle it once the row appears; here nothing was
     *       collected, so a 5xx would only schedule retries of a report about nothing. The payload
     *       is stored for reconciliation either way.</li>
     * </ul>
     */
    private WebhookEventStatus onPaymentFailed(BillingEvent event) {
        if (event.externalSessionId() == null || event.externalSessionId().isBlank()) {
            log.warn("Payment-failed event {} carries no session id; stored as IGNORED",
                    event.eventId());
            return WebhookEventStatus.IGNORED;
        }
        Payment payment = paymentRepository.findByExternalSessionIdForUpdate(event.externalSessionId())
                .orElse(null);
        if (payment == null) {
            log.warn("Payment-failed event {} names a session with no payment row; stored as IGNORED "
                    + "for reconciliation", event.eventId());
            return WebhookEventStatus.IGNORED;
        }
        if (payment.getStatus() == PaymentStatus.PAID) {
            log.warn("Payment {} is PAID but received failure event {}; the activation stands — a "
                    + "late 'failed' must not undo a settled success", payment.getId(), event.eventId());
            return WebhookEventStatus.PROCESSED;
        }
        if (payment.getStatus() != PaymentStatus.NOT_PAID) {
            log.info("Payment {} is already {}; failure event {} recorded without a transition",
                    payment.getId(), payment.getStatus(), event.eventId());
            return WebhookEventStatus.PROCESSED;
        }
        payment.setStatus(PaymentStatus.FAILED);
        paymentRepository.save(payment);
        log.info("Payment {} marked FAILED by event {}; no activation performed",
                payment.getId(), event.eventId());
        return WebhookEventStatus.PROCESSED;
    }

    /**
     * 404 when the named provider is not enabled, chosen over 503. 503 tells the sender "temporarily
     * down, retry" — but a webhook arriving at an installation with that provider OFF is a
     * configuration mistake that no amount of retrying fixes, so inviting three days of retries only
     * manufactures log noise. 404 states the truth: with the flag off, this surface does not exist
     * (the provider bean is not registered at all), and it also discloses nothing about whether
     * billing COULD be enabled here.
     */
    private BillingProvider requireProvider(String providerId) {
        return providerRegistry.find(providerId)
                .orElseThrow(() -> DomainException.notFound(
                        "Billing is not enabled on this installation"));
    }
}
