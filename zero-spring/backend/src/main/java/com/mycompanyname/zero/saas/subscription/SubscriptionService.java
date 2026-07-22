package com.mycompanyname.zero.saas.subscription;

import com.mycompanyname.zero.saas.EvictsSaasCaches;
import com.mycompanyname.zero.saas.api.SubscriptionChanged;
import com.mycompanyname.zero.saas.edition.Edition;
import com.mycompanyname.zero.saas.edition.EditionRepository;
import com.mycompanyname.zero.saas.subscription.web.dto.AssignEditionRequest;
import com.mycompanyname.zero.saas.subscription.web.dto.ChangeEditionRequest;
import com.mycompanyname.zero.saas.subscription.web.dto.EditionChangeDto;
import com.mycompanyname.zero.saas.subscription.web.dto.SubscriptionDetailDto;
import com.mycompanyname.zero.saas.subscription.web.dto.SubscriptionDto;
import com.mycompanyname.zero.saas.subscription.web.dto.SubscriptionEventDto;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.tenancy.Tenant;
import com.mycompanyname.zero.tenancy.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Subscription lifecycle. Two kinds of operation are deliberately distinguished:
 *
 * <ul>
 *   <li><b>Provisioning</b> ({@link #assignEdition}, {@link #provisionDefaultSubscription}) — rows
 *       S1-S3 of the state table, which have no source state. Selling a package computes the
 *       resulting status from the edition itself, so no transition guard applies.</li>
 *   <li><b>Transitions</b> ({@link #transition}, {@link #activate}, {@link #cancel},
 *       {@link #downgradeToExpiringEdition}) — rows S4-S12, guarded by
 *       {@link SubscriptionStatus#canTransitionTo}. An illegal transition raises
 *       {@code DomainException(VALIDATION)} instead of being silently ignored.</li>
 * </ul>
 *
 * <p>Every successful status change appends a {@link SubscriptionEvent}. Every mutating method
 * evicts the SaaS caches: a package change alters resolved feature values, and a status change
 * alters the answer {@code SubscriptionGuard} gives the tenant filter. See ARCHITECTURE-RULES.md —
 * "Feature ve abonelik cache'i yazmadan sonra bayat kalmamalı".
 *
 * <p>Time is read from the injected {@link Clock}, never from {@code Instant.now()}, so the
 * lifecycle can be exercised deterministically.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class SubscriptionService {

    private static final String REASON_PROVISIONED = "PROVISIONED";
    private static final String REASON_EDITION_ASSIGNED = "EDITION_ASSIGNED";
    private static final String REASON_EDITION_CHANGED = "EDITION_CHANGED";
    private static final String REASON_ACTIVATED = "ACTIVATED";
    private static final String REASON_CANCELLED = "CANCELLED";
    private static final String REASON_DOWNGRADED = "DOWNGRADED";
    private static final String SYSTEM_ACTOR = "system";

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final EditionRepository editionRepository;
    private final TenantRepository tenantRepository;
    private final ProrationCalculator prorationCalculator;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // --- reads ---

    @Transactional(readOnly = true)
    public Page<SubscriptionDto> list(Pageable pageable) {
        Page<Subscription> page = subscriptionRepository.findAllByOrderByTenantIdAsc(pageable);
        Map<Long, String> tenantNames = tenantNames(page.getContent());
        Map<Long, Edition> editions = editions(page.getContent());
        return page.map(subscription -> toDto(subscription, tenantNames, editions));
    }

    @Transactional(readOnly = true)
    public SubscriptionDetailDto getByTenantId(Long tenantId) {
        Subscription subscription = requireSubscription(tenantId);
        List<SubscriptionEventDto> events =
                subscriptionEventRepository.findBySubscriptionIdOrderByIdAsc(subscription.getId()).stream()
                        .map(SubscriptionService::toEventDto)
                        .toList();
        return new SubscriptionDetailDto(toDto(subscription), events);
    }

    /** Read-only view for the tenant itself ({@code GET /api/subscriptions/me}). */
    @Transactional(readOnly = true)
    public SubscriptionDto getOwnSubscription(Long tenantId) {
        if (tenantId == null) {
            throw DomainException.validation("A tenant context is required to read your own subscription");
        }
        return toDto(requireSubscription(tenantId));
    }

    // --- provisioning ---

    /**
     * Sells {@code request.editionId} to the tenant, creating the subscription when it does not exist
     * yet. The price of the chosen period is snapshotted onto the subscription so later edits to the
     * edition never change what this tenant pays (ADR-0012, rule 4).
     */
    @EvictsSaasCaches
    public SubscriptionDetailDto assignEdition(Long tenantId, AssignEditionRequest request, String actor) {
        requireTenant(tenantId);
        Edition edition = editionRepository.findById(request.editionId())
                .orElseThrow(() -> DomainException.notFound("Edition not found: " + request.editionId()));

        BillingPeriod period = BillingPeriod.parseOrNull(request.billingPeriod());
        if (edition.isFree()) {
            // Rule 3: nothing to convert into, so a trial is meaningless; a period is equally meaningless.
            if (request.trial()) {
                throw DomainException.validation(
                        "A trial cannot be started on the free edition '" + edition.getName() + "'");
            }
            period = null;
        } else {
            if (period == null) {
                throw DomainException.validation(
                        "Edition '" + edition.getName() + "' is priced and requires a billing period");
            }
            if (request.trial() && edition.getTrialDayCount() <= 0) {
                throw DomainException.validation(
                        "Edition '" + edition.getName() + "' does not offer a trial period");
            }
        }

        Subscription subscription = subscriptionRepository.findByTenantId(tenantId)
                .orElseGet(() -> {
                    Subscription created = new Subscription();
                    created.setTenantId(tenantId);
                    return created;
                });
        SubscriptionStatus from = subscription.getStatus();

        applyPackage(subscription, edition, period, request.trial());
        Subscription saved = subscriptionRepository.save(subscription);
        recordEvent(saved, from, saved.getStatus(),
                from == null ? REASON_PROVISIONED : REASON_EDITION_ASSIGNED, actor);
        return getByTenantId(tenantId);
    }

    /**
     * Creates the default subscription for a freshly created tenant (S1-S3). Idempotent: a tenant that
     * already has a subscription is left untouched, which is what makes the seeder and the
     * {@code TenantCreatedEvent} listener safe to run repeatedly.
     */
    @EvictsSaasCaches
    public void provisionDefaultSubscription(Long tenantId) {
        if (subscriptionRepository.findByTenantId(tenantId).isPresent()) {
            return;
        }
        Optional<Edition> defaultEdition = editionRepository.findFirstByActiveTrueOrderBySortOrderAscIdAsc();
        if (defaultEdition.isEmpty()) {
            log.warn("No active edition exists; tenant {} was created without a subscription", tenantId);
            return;
        }
        Edition edition = defaultEdition.get();
        Subscription subscription = new Subscription();
        subscription.setTenantId(tenantId);
        // A paid default edition starts as a trial when it offers one, otherwise it awaits payment.
        applyPackage(subscription, edition,
                edition.isFree() ? null : BillingPeriod.MONTHLY,
                !edition.isFree() && edition.getTrialDayCount() > 0);
        Subscription saved = subscriptionRepository.save(subscription);
        recordEvent(saved, null, saved.getStatus(), REASON_PROVISIONED, SYSTEM_ACTOR);
    }

    // --- transitions ---

    /** S4/S5/S10/S11: the subscription becomes usable and its period is extended. */
    @EvictsSaasCaches
    public SubscriptionDetailDto activate(Long tenantId, String actor) {
        applyTransition(requireSubscription(tenantId), SubscriptionStatus.ACTIVE, REASON_ACTIVATED, actor);
        return getByTenantId(tenantId);
    }

    /** S12: access is retained until {@code currentPeriodEndAt}, which is therefore preserved. */
    @EvictsSaasCaches
    public SubscriptionDetailDto cancel(Long tenantId, String actor) {
        applyTransition(requireSubscription(tenantId), SubscriptionStatus.CANCELLED, REASON_CANCELLED, actor);
        return getByTenantId(tenantId);
    }

    /**
     * Guarded state change. Rejects any transition the state table does not allow — for example
     * {@code EXPIRED -> TRIALING} (a trial can never be re-entered) or anything out of the terminal
     * {@code CANCELLED} state.
     *
     * <p>This is the single entry point the lifecycle job uses: the job decides <em>which</em>
     * subscriptions are due, never what their status becomes.
     */
    @EvictsSaasCaches
    public Subscription transition(Long tenantId, SubscriptionStatus target, String reason, String actor) {
        return applyTransition(requireSubscription(tenantId), target, reason, actor);
    }

    /**
     * S10: an expired subscription falls back to its edition's {@code expiringEditionId} (which the
     * catalogue guarantees is free) and becomes {@code ACTIVE} again on that package.
     *
     * @return the downgraded subscription, or empty when the edition defines no downgrade target —
     *         in which case the tenant stays {@code EXPIRED}, exactly as the source system did
     */
    @EvictsSaasCaches
    public Optional<Subscription> downgradeToExpiringEdition(Long tenantId, String actor) {
        Subscription subscription = requireSubscription(tenantId);
        if (subscription.getStatus() != SubscriptionStatus.EXPIRED) {
            throw DomainException.validation("Only an EXPIRED subscription can be downgraded, but tenant "
                    + tenantId + " is " + subscription.getStatus());
        }
        Edition current = requireEdition(subscription.getEditionId());
        Long targetId = current.getExpiringEditionId();
        if (targetId == null) {
            return Optional.empty();
        }
        Edition target = requireEdition(targetId);
        if (!target.isFree()) {
            // The catalogue enforces this on write; re-checking here keeps a hand-edited database
            // from silently moving a tenant onto something billable without a payment.
            throw DomainException.validation("The expiring edition of '" + current.getName()
                    + "' must be free, but '" + target.getName() + "' is priced");
        }

        subscription.setEditionId(target.getId());
        subscription.setBillingPeriod(null);
        subscription.setPriceAmount(null);
        subscription.setPriceCurrency(null);
        subscription.setTrialEndAt(null);
        // Going through applyTransition keeps the guard and the event trail identical to every other
        // transition; with a null billing period it also resets currentPeriodEndAt (a free package
        // never expires).
        return Optional.of(applyTransition(subscription, SubscriptionStatus.ACTIVE, REASON_DOWNGRADED, actor));
    }

    /**
     * S13: upgrade or downgrade an {@code ACTIVE} subscription.
     *
     * <p>Two source rules are preserved deliberately. The billing period end is <b>not</b> shifted —
     * the pro-rated amount already prices the unused remainder, so moving the date too would charge
     * for it twice. And when the amount stays below
     * {@code zero.saas.proration.minimum-amount}, no payment is requested at all and the edition
     * changes immediately.
     *
     * <p>No billing provider is integrated: the amount is computed, logged and returned. Turning it
     * into a checkout is left to the application built on this template.
     */
    @EvictsSaasCaches
    public EditionChangeDto changeEdition(Long tenantId, ChangeEditionRequest request, String actor) {
        Subscription subscription = requireSubscription(tenantId);
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
            throw DomainException.validation("Only an ACTIVE subscription can change edition, but tenant "
                    + tenantId + " is " + subscription.getStatus()
                    + "; assign the package instead");
        }
        Edition target = requireEdition(request.editionId());
        if (Objects.equals(target.getId(), subscription.getEditionId())) {
            throw DomainException.validation(
                    "The subscription is already on edition '" + target.getName() + "'");
        }

        BillingPeriod requested = BillingPeriod.parseOrNull(request.billingPeriod());
        BillingPeriod period = target.isFree()
                ? null
                : (requested != null ? requested : subscription.getBillingPeriod());
        if (!target.isFree() && period == null) {
            throw DomainException.validation("Edition '" + target.getName()
                    + "' is priced and requires a billing period");
        }
        BigDecimal targetPrice = period == null ? null : target.priceFor(period.name());
        if (period != null && targetPrice == null) {
            throw DomainException.validation("Edition '" + target.getName()
                    + "' has no " + period + " price");
        }

        Instant now = clock.instant();
        Instant periodEnd = subscription.getCurrentPeriodEndAt();
        // The period start is not persisted, so it is reconstructed from its end. BillingPeriod uses
        // java.time.Period, so the length is the real calendar distance (ADR-0013), not 30/365 days.
        Instant periodStart = (periodEnd == null || subscription.getBillingPeriod() == null)
                ? null
                : subscription.getBillingPeriod().rewind(periodEnd);

        ProrationResult proration = prorationCalculator.calculate(
                subscription.getPriceAmount(), targetPrice, periodStart, periodEnd, now);

        subscription.setEditionId(target.getId());
        subscription.setBillingPeriod(period);
        subscription.setPriceAmount(targetPrice);
        subscription.setPriceCurrency(period == null ? null : target.getCurrency());
        if (period == null) {
            subscription.setCurrentPeriodEndAt(null);       // moved onto a free package: never expires
        } else if (periodEnd == null) {
            subscription.setCurrentPeriodEndAt(period.advance(now)); // was unlimited: a period starts now
        }
        // else: the period end stays exactly where it was (S13).

        Subscription saved = subscriptionRepository.save(subscription);
        recordEvent(saved, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE, REASON_EDITION_CHANGED, actor);

        String currency = period == null ? null : target.getCurrency();
        log.info("Tenant {} moved to edition '{}': proration {} {} (payment required: {}, minimum {})",
                tenantId, target.getName(), proration.amount(), currency,
                proration.paymentRequired(), prorationCalculator.minimumAmount());

        return new EditionChangeDto(
                toDto(saved),
                proration.amount(),
                currency,
                proration.remainingRatio(),
                proration.paymentRequired(),
                prorationCalculator.minimumAmount());
    }

    // --- internals ---

    /**
     * The one place a status is written. Kept private and shared by every public transition method so
     * the guard, the side effects and the event trail can never drift apart.
     */
    private Subscription applyTransition(Subscription subscription, SubscriptionStatus target,
                                         String reason, String actor) {
        SubscriptionStatus from = subscription.getStatus();
        if (!from.canTransitionTo(target)) {
            throw DomainException.validation("Illegal subscription transition " + from + " -> " + target
                    + " for tenant " + subscription.getTenantId());
        }
        Edition edition = requireEdition(subscription.getEditionId());

        Instant now = clock.instant();
        subscription.setStatus(target);
        switch (target) {
            case ACTIVE -> {
                subscription.setTrialEndAt(null);
                subscription.setGraceEndAt(null);
                subscription.setCancelledAt(null);
                subscription.setCurrentPeriodEndAt(subscription.getBillingPeriod() == null
                        ? null                                                   // free: never expires
                        : subscription.getBillingPeriod().advance(now));
            }
            case GRACE -> {
                Instant base = subscription.getCurrentPeriodEndAt() == null
                        ? now
                        : subscription.getCurrentPeriodEndAt();
                subscription.setGraceEndAt(base.plus(edition.getGraceDayCount(), ChronoUnit.DAYS));
            }
            case CANCELLED -> subscription.setCancelledAt(now);
            case EXPIRED -> subscription.setGraceEndAt(null);
            default -> { /* TRIALING / PENDING_PAYMENT are provisioning-only outcomes */ }
        }
        Subscription saved = subscriptionRepository.save(subscription);
        recordEvent(saved, from, target, reason, actor);
        return saved;
    }

    /** Computes status + snapshot for the edition being sold (state-table rows S1-S3). */
    private void applyPackage(Subscription subscription, Edition edition, BillingPeriod period, boolean trial) {
        Instant now = clock.instant();
        subscription.setEditionId(edition.getId());
        subscription.setBillingPeriod(period);
        subscription.setPriceAmount(period == null ? null : edition.priceFor(period.name()));
        subscription.setPriceCurrency(period == null ? null : edition.getCurrency());
        subscription.setCancelledAt(null);
        subscription.setGraceEndAt(null);

        if (edition.isFree()) {
            // S1: free packages never expire.
            subscription.setStatus(SubscriptionStatus.ACTIVE);
            subscription.setTrialEndAt(null);
            subscription.setCurrentPeriodEndAt(null);
        } else if (trial && edition.getTrialDayCount() > 0) {
            // S2: trial runs to trialEndAt; there is no billed period yet.
            subscription.setStatus(SubscriptionStatus.TRIALING);
            subscription.setTrialEndAt(now.plus(edition.getTrialDayCount(), ChronoUnit.DAYS));
            subscription.setCurrentPeriodEndAt(null);
        } else {
            // S3: paid package awaiting a server-side confirmed payment (ADR-0014).
            subscription.setStatus(SubscriptionStatus.PENDING_PAYMENT);
            subscription.setTrialEndAt(null);
            subscription.setCurrentPeriodEndAt(null);
        }
    }

    /**
     * Writes the pre-expiry notice into the event trail (no status change) and publishes the
     * corresponding {@link SubscriptionChanged}. The event row doubles as the idempotency ledger:
     * the lifecycle job asks "has a notice been recorded since this period's window opened?"
     * before calling this — so a job that runs hourly still notifies exactly once per period.
     */
    public void recordExpiryNotice(Subscription subscription) {
        recordEvent(subscription, subscription.getStatus(), subscription.getStatus(),
                SubscriptionChanged.REASON_EXPIRY_NOTICE, SYSTEM_ACTOR);
    }

    private void recordEvent(Subscription subscription, SubscriptionStatus from, SubscriptionStatus to,
                             String reason, String actor) {
        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(subscription.getId());
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setReason(reason);
        event.setOccurredAt(clock.instant());
        event.setActor(actor == null || actor.isBlank() ? SYSTEM_ACTOR : actor);
        subscriptionEventRepository.save(event);

        // Every event-trail entry is also an application event (saas :: api). Synchronous, same
        // transaction: a listener's write (e.g. the identity notification bridge) commits or rolls
        // back WITH the transition — no "status changed but nobody was told" half-state.
        Edition edition = editionRepository.findById(subscription.getEditionId()).orElse(null);
        eventPublisher.publishEvent(new SubscriptionChanged(
                subscription.getTenantId(),
                subscription.getId(),
                edition == null ? null : edition.getName(),
                edition == null ? null : edition.getDisplayName(),
                from == null ? null : from.name(),
                to.name(),
                reason,
                event.getOccurredAt(),
                subscription.getCurrentPeriodEndAt(),
                subscription.getTrialEndAt()));
    }

    private Subscription requireSubscription(Long tenantId) {
        return subscriptionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> DomainException.notFound("No subscription for tenant: " + tenantId));
    }

    private Edition requireEdition(Long editionId) {
        return editionRepository.findById(editionId)
                .orElseThrow(() -> DomainException.notFound("Edition not found: " + editionId));
    }

    private void requireTenant(Long tenantId) {
        if (tenantId == null || !tenantRepository.existsById(tenantId)) {
            throw DomainException.notFound("Tenant not found: " + tenantId);
        }
    }

    private Map<Long, String> tenantNames(List<Subscription> subscriptions) {
        List<Long> ids = subscriptions.stream().map(Subscription::getTenantId).distinct().toList();
        Map<Long, String> names = new HashMap<>();
        for (Tenant tenant : tenantRepository.findAllById(ids)) {
            names.put(tenant.getId(), tenant.getName());
        }
        return names;
    }

    private Map<Long, Edition> editions(List<Subscription> subscriptions) {
        List<Long> ids = subscriptions.stream().map(Subscription::getEditionId).distinct().toList();
        Map<Long, Edition> editions = new HashMap<>();
        for (Edition edition : editionRepository.findAllById(ids)) {
            editions.put(edition.getId(), edition);
        }
        return editions;
    }

    private SubscriptionDto toDto(Subscription subscription) {
        Map<Long, String> tenantNames = tenantNames(List.of(subscription));
        Map<Long, Edition> editions = editions(List.of(subscription));
        return toDto(subscription, tenantNames, editions);
    }

    private static SubscriptionDto toDto(Subscription subscription,
                                         Map<Long, String> tenantNames,
                                         Map<Long, Edition> editions) {
        Edition edition = editions.get(subscription.getEditionId());
        BigDecimal price = subscription.getPriceAmount();
        return new SubscriptionDto(
                subscription.getId(),
                subscription.getTenantId(),
                tenantNames.get(subscription.getTenantId()),
                subscription.getEditionId(),
                edition == null ? null : edition.getName(),
                edition == null ? null : edition.getDisplayName(),
                subscription.getStatus().name(),
                subscription.getBillingPeriod() == null ? null : subscription.getBillingPeriod().name(),
                price,
                subscription.getPriceCurrency(),
                subscription.getTrialEndAt(),
                subscription.getCurrentPeriodEndAt(),
                subscription.getGraceEndAt(),
                subscription.getCancelledAt());
    }

    private static SubscriptionEventDto toEventDto(SubscriptionEvent event) {
        return new SubscriptionEventDto(
                event.getId(),
                event.getFromStatus() == null ? null : event.getFromStatus().name(),
                event.getToStatus().name(),
                event.getReason(),
                event.getOccurredAt(),
                event.getActor());
    }
}
