package com.mycompanyname.zero.saas.billing;

import com.mycompanyname.zero.saas.subscription.SubscriptionService;
import com.mycompanyname.zero.saas.subscription.web.dto.AssignEditionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * The retrieve-authoritative confirmation path (P2'-B): given a session id, ask the PROVIDER'S OWN
 * query API whether the money was collected, and activate only on its answer. One method, three
 * triggers — the webhook ({@code BillingWebhookService} funnels query-capable providers here
 * instead of trusting the delivered payload), the buyer's browser callback
 * ({@code BillingCallbackController}, a trigger that is never believed), and the scheduled
 * reconciliation job ({@code BillingReconciliationService}). Keeping them on literally the same
 * call is the contract's design requirement: there is no second activation code path to drift.
 *
 * <p><b>Order of operations, chosen deliberately.</b>
 * <ol>
 *   <li>Unlocked PROJECTION peek first ({@code PaymentRepository.PaymentPeek}) — id, status,
 *       provider, never the entity: an unknown session answers without any provider call — which
 *       is what keeps the anonymous callback from being a lever that drives outbound API traffic —
 *       and an already-{@code PAID} or operator-{@code CANCELLED} row answers without one either.
 *       The projection (not merely a lighter read) is what makes step 3's guard TRUE: an entity
 *       peek would sit in the persistence context, and Hibernate resolves the later locked query
 *       BY IDENTITY — the loser of a race would take the SQL lock and still re-read its own stale
 *       {@code NOT_PAID}, pass the guard, and activate a second time. Measured red before the fix
 *       ({@code BillingConfirmationConcurrencyIT}).</li>
 *   <li>The provider query runs BEFORE the row lock is taken, so a row lock is never held across a
 *       network round-trip (the transaction still holds its DB connection through the call — the
 *       same accepted trade-off as {@code BillingCheckoutService}, same reasoning).</li>
 *   <li>Only a {@code collected} answer takes the {@code PESSIMISTIC_WRITE} lookup — the FIRST
 *       entity load of this transaction, thanks to step 1 — and RE-CHECKS the guards under the
 *       lock: a webhook, a callback and a reconciliation run racing on the same payment serialize
 *       on the row, the losers re-read the winner's committed {@code PAID} and return
 *       {@link Outcome#ALREADY_PAID} — no interleaving double-activates, the
 *       {@code BillingWebhookService} concurrency argument restated.</li>
 * </ol>
 *
 * <p><b>Which prior states activate.</b> {@code NOT_PAID} and {@code FAILED} do — {@code FAILED} is
 * webhook-written and the provider's own server now says the money moved, the same
 * reversible-by-provider rule the webhook path holds. {@code CANCELLED} does not: an operator's
 * write-off is not overridden by any amount of provider traffic; it is logged loudly for the
 * runbook instead.
 *
 * <p>A provider-query transport failure propagates: the webhook wrapper turns it into a 500 whose
 * rollback lets the provider's bounded retry re-ask; the reconciliation job catches it per payment
 * and re-asks next run.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BillingConfirmationService {

    /** What one confirmation attempt concluded — every value is safe to log, none carries input. */
    public enum Outcome {
        /** No payment row carries this session id; nothing was asked of the provider. */
        NO_PAYMENT_ROW,
        /** The payment is already settled; nothing to do (replay/race — both normal). */
        ALREADY_PAID,
        /** Operator-written {@code CANCELLED}; deliberately never overridden. Logged loudly. */
        OPERATOR_CANCELLED,
        /** The provider's query did NOT confirm collection; the payment row was not touched. */
        NOT_CONFIRMED,
        /** The provider confirmed collection: payment {@code PAID} + subscription activated. */
        CONFIRMED_ACTIVATED
    }

    private final PaymentRepository paymentRepository;
    private final SubscriptionService subscriptionService;
    private final Clock clock;

    public Outcome confirmBySessionQuery(BillingProvider provider, String sessionId, String actor) {
        if (sessionId == null || sessionId.isBlank()) {
            return Outcome.NO_PAYMENT_ROW;
        }
        // Projection, NOT the entity — see the class contract and PaymentRepository.PaymentPeek:
        // an entity here would poison the locked re-read below with this transaction's stale copy.
        PaymentRepository.PaymentPeek peek =
                paymentRepository.peekByExternalSessionId(sessionId).orElse(null);
        if (peek == null) {
            return Outcome.NO_PAYMENT_ROW;
        }
        if (peek.getStatus() == PaymentStatus.PAID) {
            return Outcome.ALREADY_PAID;
        }
        if (peek.getStatus() == PaymentStatus.CANCELLED) {
            log.warn("Payment {} is CANCELLED but a {} confirmation was triggered by {}; the "
                    + "operator's write-off stands, no provider query performed", peek.getId(),
                    provider.id(), actor);
            return Outcome.OPERATOR_CANCELLED;
        }
        if (peek.getProvider() != null && !peek.getProvider().equals(provider.id())) {
            // Cross-provider routing (stack-review Finding 3): this row belongs to another
            // provider, so asking THIS one about its session id proves nothing about this payment
            // — whatever the answer, acting on it would settle money on the wrong authority's
            // word. Refused before any network call. The null case is deliberately allowed
            // through: pre-V9 rows carry no attribution, and the confirming provider backfills it
            // below on a collected answer.
            log.warn("Payment {} belongs to provider {} but a {} confirmation was triggered by {}; "
                    + "refusing the cross-provider query", peek.getId(), peek.getProvider(),
                    provider.id(), actor);
            return Outcome.NOT_CONFIRMED;
        }

        ProviderPaymentConfirmation confirmation = provider.confirmBySessionQuery(sessionId);
        if (!confirmation.collected()) {
            log.warn("Payment {}: the {} query does not confirm collection ({}); NOT activated — "
                    + "triggered by {}, the reconciliation job re-asks while the payment stays {}",
                    peek.getId(), provider.id(), confirmation.detail(), actor, peek.getStatus());
            return Outcome.NOT_CONFIRMED;
        }

        Payment payment = paymentRepository.findByExternalSessionIdForUpdate(sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "The payment row read moments ago disappeared mid-confirmation"));
        if (payment.getStatus() == PaymentStatus.PAID) {
            // Raced by a concurrent confirmation of the same session; the winner already did
            // everything below. Normal, and the reason the guards are re-checked under the lock.
            return Outcome.ALREADY_PAID;
        }
        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            log.warn("Payment {} became CANCELLED while the {} query confirmed collection "
                    + "(triggered by {}); money is settled for a written-off payment — reconcile "
                    + "manually per runbook §3.9", payment.getId(), provider.id(), actor);
            return Outcome.OPERATOR_CANCELLED;
        }
        if (payment.getStatus() == PaymentStatus.FAILED) {
            log.info("Payment {} was FAILED but the {} query confirms collection (buyer retry); "
                    + "activating", payment.getId(), provider.id());
        }

        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(clock.instant());
        if (confirmation.externalPaymentId() != null && !confirmation.externalPaymentId().isBlank()) {
            // The query's payment id outranks anything a webhook payload claimed (see
            // ProviderPaymentConfirmation): it is the id the provider's server just vouched for.
            payment.setExternalPaymentId(confirmation.externalPaymentId());
        }
        if (payment.getProvider() == null) {
            // Pre-V9 rows carry no provider; the confirming provider is by definition the right one.
            payment.setProvider(provider.id());
        }
        paymentRepository.save(payment);

        if (payment.getTargetEditionId() != null) {
            subscriptionService.assignEdition(payment.getTenantId(),
                    new AssignEditionRequest(payment.getTargetEditionId(), payment.getPeriod(), false),
                    actor);
        }
        subscriptionService.activate(payment.getTenantId(), actor);

        log.info("Payment {} confirmed by the {} query (triggered by {}); tenant {} activated on "
                + "edition {}", payment.getId(), provider.id(), actor, payment.getTenantId(),
                payment.getTargetEditionId());
        return Outcome.CONFIRMED_ACTIVATED;
    }
}
