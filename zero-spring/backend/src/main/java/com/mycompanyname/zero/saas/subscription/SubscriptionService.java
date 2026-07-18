package com.mycompanyname.zero.saas.subscription;

import com.mycompanyname.zero.saas.edition.Edition;
import com.mycompanyname.zero.saas.edition.EditionRepository;
import com.mycompanyname.zero.saas.subscription.web.dto.AssignEditionRequest;
import com.mycompanyname.zero.saas.subscription.web.dto.SubscriptionDetailDto;
import com.mycompanyname.zero.saas.subscription.web.dto.SubscriptionDto;
import com.mycompanyname.zero.saas.subscription.web.dto.SubscriptionEventDto;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.tenancy.Tenant;
import com.mycompanyname.zero.tenancy.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Subscription lifecycle. Two kinds of operation are deliberately distinguished:
 *
 * <ul>
 *   <li><b>Provisioning</b> ({@link #assignEdition}, {@link #provisionDefaultSubscription}) — rows
 *       S1-S3 of the state table, which have no source state. Selling a package computes the
 *       resulting status from the edition itself, so no transition guard applies.</li>
 *   <li><b>Transitions</b> ({@link #transition}, {@link #activate}, {@link #cancel}) — rows S4-S12,
 *       guarded by {@link SubscriptionStatus#canTransitionTo}. An illegal transition raises
 *       {@code DomainException(VALIDATION)} instead of being silently ignored (K11).</li>
 * </ul>
 *
 * <p>Every successful status change appends a {@link SubscriptionEvent}.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class SubscriptionService {

    private static final String REASON_PROVISIONED = "PROVISIONED";
    private static final String REASON_EDITION_ASSIGNED = "EDITION_ASSIGNED";
    private static final String REASON_ACTIVATED = "ACTIVATED";
    private static final String REASON_CANCELLED = "CANCELLED";
    private static final String SYSTEM_ACTOR = "system";

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final EditionRepository editionRepository;
    private final TenantRepository tenantRepository;

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
    public SubscriptionDetailDto activate(Long tenantId, String actor) {
        transition(tenantId, SubscriptionStatus.ACTIVE, REASON_ACTIVATED, actor);
        return getByTenantId(tenantId);
    }

    /** S12: access is retained until {@code currentPeriodEndAt}, which is therefore preserved. */
    public SubscriptionDetailDto cancel(Long tenantId, String actor) {
        transition(tenantId, SubscriptionStatus.CANCELLED, REASON_CANCELLED, actor);
        return getByTenantId(tenantId);
    }

    /**
     * Guarded state change. Rejects any transition the state table does not allow — for example
     * {@code EXPIRED -> TRIALING} (a trial can never be re-entered) or anything out of the terminal
     * {@code CANCELLED} state.
     */
    public Subscription transition(Long tenantId, SubscriptionStatus target, String reason, String actor) {
        Subscription subscription = requireSubscription(tenantId);
        SubscriptionStatus from = subscription.getStatus();
        if (!from.canTransitionTo(target)) {
            throw DomainException.validation(
                    "Illegal subscription transition " + from + " -> " + target + " for tenant " + tenantId);
        }
        Edition edition = editionRepository.findById(subscription.getEditionId())
                .orElseThrow(() -> DomainException.notFound("Edition not found: " + subscription.getEditionId()));

        Instant now = Instant.now();
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

    // --- internals ---

    /** Computes status + snapshot for the edition being sold (state-table rows S1-S3). */
    private void applyPackage(Subscription subscription, Edition edition, BillingPeriod period, boolean trial) {
        Instant now = Instant.now();
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

    private void recordEvent(Subscription subscription, SubscriptionStatus from, SubscriptionStatus to,
                             String reason, String actor) {
        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(subscription.getId());
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setReason(reason);
        event.setOccurredAt(Instant.now());
        event.setActor(actor == null || actor.isBlank() ? SYSTEM_ACTOR : actor);
        subscriptionEventRepository.save(event);
    }

    private Subscription requireSubscription(Long tenantId) {
        return subscriptionRepository.findByTenantId(tenantId)
                .orElseThrow(() -> DomainException.notFound("No subscription for tenant: " + tenantId));
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
