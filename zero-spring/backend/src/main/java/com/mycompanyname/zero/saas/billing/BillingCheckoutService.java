package com.mycompanyname.zero.saas.billing;

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

import java.math.BigDecimal;

/**
 * Starts a hosted checkout: snapshot the price onto a {@code NOT_PAID} payment row, hand the
 * provider the session request, remember the session id. Nothing here changes the subscription —
 * the subscription moves ONLY when the provider's webhook confirms the money (ADR-0014); a buyer
 * who abandons checkout leaves nothing behind but a {@code NOT_PAID} row.
 *
 * <p><b>Transaction note.</b> The provider API call runs inside the transaction, which holds a DB
 * connection across one HTTPS round-trip. Accepted for this slice because the failure modes come
 * out right: a refused session creation rolls the payment row back (no orphan), and the rare
 * inverse — session created, then commit fails — leaves a session no webhook can match, which the
 * webhook path reports loudly (500 → bounded retry) instead of silently.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BillingCheckoutService {

    private final BillingProviderRegistry providerRegistry;
    private final PaymentRepository paymentRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final EditionRepository editionRepository;

    // No Clock here on purpose: this service stamps nothing itself. created_at/updated_at come from
    // JPA auditing, and paid_at belongs exclusively to the webhook path.

    public CheckoutSessionDto startCheckout(StartCheckoutRequest request, String actor) {
        BillingProvider provider = resolveProvider(request.provider());

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
        payment = paymentRepository.save(payment);

        CheckoutSession session = provider.createCheckoutSession(new CheckoutRequest(
                payment.getId(), payment.getTenantId(), edition.getDisplayName(),
                amount, edition.getCurrency(), period.name(),
                request.successUrl(), request.cancelUrl()));
        payment.setExternalSessionId(session.sessionId());
        paymentRepository.save(payment);

        log.info("Checkout started by {} via {}: payment {} for tenant {} -> edition '{}' ({} {} {})",
                actor, provider.id(), payment.getId(), payment.getTenantId(), edition.getName(),
                amount, edition.getCurrency(), period);
        return new CheckoutSessionDto(payment.getId(), session.sessionId(), session.url());
    }

    /**
     * Resolves the provider the checkout should run through (P2'-A).
     *
     * <ul>
     *   <li>Billing off entirely → 404, same decision and reasoning as
     *       {@code BillingWebhookService#requireProvider}: the surface does not exist.</li>
     *   <li>Provider omitted → the single enabled provider, so the common one-provider installation
     *       needs no new request field; with several enabled the request must choose, and the 400
     *       names the valid ids (configuration facts, not echoed caller input).</li>
     *   <li>Provider named but not enabled → 400 naming the valid ids. The submitted value itself is
     *       deliberately not echoed back (house rule).</li>
     * </ul>
     */
    private BillingProvider resolveProvider(String requested) {
        if (providerRegistry.isEmpty()) {
            throw DomainException.notFound("Billing is not enabled on this installation");
        }
        if (requested == null || requested.isBlank()) {
            return providerRegistry.single().orElseThrow(() -> DomainException.validation(
                    "More than one billing provider is enabled; 'provider' must be one of "
                            + providerRegistry.ids()));
        }
        return providerRegistry.find(requested).orElseThrow(() -> DomainException.validation(
                "Unknown billing provider; enabled providers: " + providerRegistry.ids()));
    }
}
