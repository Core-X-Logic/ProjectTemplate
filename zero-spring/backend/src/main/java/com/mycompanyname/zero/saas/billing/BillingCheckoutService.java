package com.mycompanyname.zero.saas.billing;

import com.mycompanyname.zero.saas.billing.credentials.BillingCheckoutCircuitBreaker;
import com.mycompanyname.zero.saas.billing.credentials.BillingProviderAvailability;
import com.mycompanyname.zero.saas.billing.web.dto.CheckoutSessionDto;
import com.mycompanyname.zero.saas.billing.web.dto.StartCheckoutRequest;
import com.mycompanyname.zero.saas.edition.Edition;
import com.mycompanyname.zero.saas.edition.EditionRepository;
import com.mycompanyname.zero.saas.subscription.BillingPeriod;
import com.mycompanyname.zero.saas.subscription.Subscription;
import com.mycompanyname.zero.saas.subscription.SubscriptionRepository;
import com.mycompanyname.zero.shared.domain.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Starts a hosted checkout: snapshot the price onto a {@code NOT_PAID} payment row, hand the
 * provider the session request, remember the session id. Nothing here changes the subscription —
 * the subscription moves ONLY when the provider's webhook confirms the money (ADR-0014); a buyer
 * who abandons checkout leaves nothing behind but a {@code NOT_PAID} row.
 *
 * <p><b>Failover (ADR-0020).</b> With no provider named, the checkout-enabled providers are tried
 * in the operator's stored order ({@code display_order}); a TRANSPORT-CLASS initiation failure —
 * connect/timeout, an {@code IOException} anywhere in the cause chain, or an HTTP 5xx — moves to
 * the next candidate, because those say "could not reach a working provider". A 4xx or any other
 * refusal PROPAGATES: the provider answered and rejected, and retrying the SAME defective request
 * against a different provider would only spread it (mutation-proved:
 * {@code BillingCheckoutFailoverIT} turns red if 4xx starts failing over). Two consecutive
 * transport failures put a provider on a cool-down ({@link BillingCheckoutCircuitBreaker}) so it
 * is skipped rather than paid for with a timeout per checkout — but skipped only while an
 * alternative exists, and attempted anyway when every candidate is cooling down: the breaker
 * optimises latency, it never refuses a checkout by itself. A checkout that names its provider
 * gets exactly that provider and NO failover — the caller chose deliberately.
 *
 * <p><b>{@code payments.provider} names the provider that ACTUALLY issued the session.</b> The id
 * is (re)written before each attempt, so a checkout that failed over commits with the succeeding
 * provider's id — the webhook and the reconciliation job depend on that row to route the finish of
 * this payment ({@code payment.setProvider} ordering note below).
 *
 * <p><b>Transaction note.</b> The provider API call runs inside the transaction, which holds a DB
 * connection across one HTTPS round-trip — and under failover across up to N of them, one per
 * candidate (bounded by the enabled-provider count and each adapter's own transport ceiling;
 * recorded in ADR-0020 as an accepted cost). Accepted because the failure modes come out right: a
 * refused session creation rolls the payment row back (no orphan), and the rare inverse — session
 * created, then commit fails — leaves a session no webhook can match, which the webhook path
 * reports loudly (500 → bounded retry) instead of silently.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BillingCheckoutService {

    private final BillingProviderRegistry providerRegistry;
    private final BillingProviderAvailability availability;
    private final BillingCheckoutCircuitBreaker circuitBreaker;
    private final PaymentRepository paymentRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final EditionRepository editionRepository;

    // No Clock here on purpose: this service stamps nothing itself. created_at/updated_at come from
    // JPA auditing, paid_at belongs exclusively to the webhook path, and the cool-down clock lives
    // inside BillingCheckoutCircuitBreaker.

    public CheckoutSessionDto startCheckout(StartCheckoutRequest request, String actor) {
        List<BillingProvider> candidates = resolveCandidates(request.provider());

        Subscription subscription = subscriptionRepository.findByTenantId(request.tenantId())
                .orElseThrow(() -> DomainException.notFound(
                        "No subscription for tenant: " + request.tenantId()));
        Edition edition = editionRepository.findById(request.editionId())
                .orElseThrow(() -> DomainException.notFound(
                        "Edition not found: " + request.editionId()));
        if (edition.isFree()) {
            throw DomainException.validation("Edition '" + edition.getName()
                    + "' is free and needs no checkout; assign it directly");
        }
        BillingPeriod period = BillingPeriod.parseOrNull(request.billingPeriod());
        if (period == null) {
            throw DomainException.validation("Edition '" + edition.getName()
                    + "' is priced and requires a billing period");
        }
        BigDecimal amount = edition.priceFor(period.name());
        if (amount == null) {
            throw DomainException.validation("Edition '" + edition.getName()
                    + "' has no " + period + " price");
        }

        Payment payment = new Payment();
        payment.setTenantId(request.tenantId());
        payment.setSubscriptionId(subscription.getId());
        payment.setTargetEditionId(edition.getId());
        payment.setAmount(amount);
        payment.setCurrency(edition.getCurrency());
        payment.setPeriod(period.name());
        payment.setStatus(PaymentStatus.NOT_PAID);

        // Pass 1 honours the cool-down; pass 2 exists so an all-open breaker can DELAY a provider
        // but never refuse the checkout outright (see the class contract).
        boolean failover = candidates.size() > 1;
        List<BillingProvider> skippedCoolingDown = new ArrayList<>();
        RuntimeException lastTransportFailure = null;
        for (BillingProvider candidate : candidates) {
            if (failover && circuitBreaker.isOpen(candidate.id())) {
                log.info("Billing provider {} is cooling down after consecutive initiation "
                        + "failures; skipped in this checkout's failover order", candidate.id());
                skippedCoolingDown.add(candidate);
                continue;
            }
            Attempt attempt = attempt(candidate, payment, edition, request, actor, failover);
            if (attempt.session() != null) {
                return attempt.session();
            }
            lastTransportFailure = attempt.transportFailure();
        }
        for (BillingProvider candidate : skippedCoolingDown) {
            Attempt attempt = attempt(candidate, payment, edition, request, actor, failover);
            if (attempt.session() != null) {
                return attempt.session();
            }
            lastTransportFailure = attempt.transportFailure();
        }
        // Every candidate failed transport-wise; surface the last cause loudly (500, rollback —
        // no orphan payment row survives this).
        throw lastTransportFailure != null ? lastTransportFailure
                : new IllegalStateException("No billing provider could be attempted for checkout");
    }

    /** Exactly one of the two is non-null: the started session, or the recorded transport failure. */
    private record Attempt(CheckoutSessionDto session, RuntimeException transportFailure) {
    }

    /**
     * One initiation attempt. Returns the session on success and the failure on a transport-class
     * error (already recorded on the breaker); every other failure propagates and rolls the
     * transaction back.
     *
     * <p>P2'-B ordering, kept under failover: the provider id goes onto the row BEFORE the provider
     * call — a session created while the commit failed leaves no row at all, and a row without a
     * session is skipped by the reconciliation scan. Under failover the id is REWRITTEN before each
     * attempt, so the committed row always names the provider whose session it stores.
     */
    private Attempt attempt(BillingProvider provider, Payment payment, Edition edition,
                            StartCheckoutRequest request, String actor, boolean failover) {
        payment.setProvider(provider.id());
        Payment saved = paymentRepository.save(payment);
        try {
            CheckoutSession session = provider.createCheckoutSession(new CheckoutRequest(
                    saved.getId(), saved.getTenantId(), edition.getDisplayName(),
                    saved.getAmount(), edition.getCurrency(), saved.getPeriod(),
                    request.successUrl(), request.cancelUrl()));
            circuitBreaker.recordSuccess(provider.id());
            saved.setExternalSessionId(session.sessionId());
            paymentRepository.save(saved);
            log.info("Checkout started by {} via {}: payment {} for tenant {} -> edition '{}' "
                            + "({} {} {})", actor, provider.id(), saved.getId(), saved.getTenantId(),
                    edition.getName(), saved.getAmount(), edition.getCurrency(), saved.getPeriod());
            return new Attempt(new CheckoutSessionDto(saved.getId(), session.sessionId(),
                    session.url()), null);
        } catch (RuntimeException ex) {
            if (!failover || !isTransportFailure(ex)) {
                // A named-provider checkout, a 4xx, or any non-transport refusal: the provider
                // answered (or was chosen deliberately) — propagate, roll back, tell the operator.
                throw ex;
            }
            circuitBreaker.recordFailure(provider.id());
            // Operational diagnosis line (contract requirement): WHICH provider was tried and WHY
            // the failover moved on — with the cause chain in the log, never in the response.
            log.warn("Checkout initiation via {} failed with a transport-class error; failing over "
                    + "to the next enabled provider", provider.id(), ex);
            return new Attempt(null, ex);
        }
    }

    /**
     * The failover condition (ADR-0020): connect/read failures ({@link ResourceAccessException}),
     * an {@link IOException} or {@link TimeoutException} anywhere in the cause chain (the Stripe
     * SDK wraps transport problems that way), or an HTTP 5xx from the provider. A 4xx is
     * deliberately ABSENT: the provider answered and refused — that is a fact about our request,
     * and no other provider fixes it.
     */
    static boolean isTransportFailure(RuntimeException ex) {
        for (Throwable current = ex; current != null;
                current = current.getCause() == current ? null : current.getCause()) {
            if (current instanceof ResourceAccessException
                    || current instanceof IOException
                    || current instanceof TimeoutException) {
                return true;
            }
            if (current instanceof RestClientResponseException response
                    && response.getStatusCode().is5xxServerError()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the ordered candidate list this checkout may run through (P2'-A, reshaped by
     * ADR-0020).
     *
     * <ul>
     *   <li>No provider available for checkout → 404, same decision and reasoning as
     *       {@code BillingWebhookService#requireProvider}: the surface does not exist. This is the
     *       old "empty registry" branch — the registry is never empty now that provider beans are
     *       unconditional, so the question moved to {@link BillingProviderAvailability}.</li>
     *   <li>Provider omitted → every checkout-enabled provider in the operator's stored failover
     *       order. One enabled provider = a single-element list, exactly the old
     *       {@code single()} behaviour; several = the failover chain replaces the old "must
     *       choose" 400.</li>
     *   <li>Provider named but not checkout-enabled → 400 naming the valid ids (configuration
     *       facts, not echoed caller input). Named and enabled → that provider alone, no
     *       failover.</li>
     * </ul>
     */
    private List<BillingProvider> resolveCandidates(String requested) {
        List<BillingProvider> enabled = availability.checkoutCandidates(providerRegistry);
        if (enabled.isEmpty()) {
            throw DomainException.notFound("Billing is not enabled on this installation");
        }
        if (requested == null || requested.isBlank()) {
            return enabled;
        }
        List<String> enabledIds = enabled.stream().map(BillingProvider::id).toList();
        return enabled.stream()
                .filter(provider -> provider.id().equals(requested))
                .findFirst()
                .map(List::of)
                .orElseThrow(() -> DomainException.validation(
                        "Unknown billing provider; enabled providers: " + enabledIds));
    }
}
