package com.mycompanyname.zero.saas.subscription;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Explicit subscription lifecycle state, rather than an ambiguous combination of implicit
 * date/flag fields (ADR-0009).
 *
 * <p>{@link #ALLOWED} below is the authoritative transition table; its rows are referred to as
 * S1-S13 elsewhere in this package. Rows S1-S3 have no source state: they describe
 * <em>provisioning</em> (creating or re-assigning a package), which is handled by
 * {@code SubscriptionService.assignEdition} and therefore not part of this guard. Everything else is
 * a transition and an invalid one raises {@code DomainException(VALIDATION)} rather than silently
 * doing nothing — a silent no-op leaves the caller believing the change took effect. See
 * ARCHITECTURE-RULES.md — "Durum geçişleri açık tablo, sessiz no-op yok".
 */
public enum SubscriptionStatus {

    TRIALING,
    ACTIVE,
    GRACE,
    EXPIRED,
    CANCELLED,
    PENDING_PAYMENT;

    private static final Map<SubscriptionStatus, Set<SubscriptionStatus>> ALLOWED = allowedTransitions();

    private static Map<SubscriptionStatus, Set<SubscriptionStatus>> allowedTransitions() {
        Map<SubscriptionStatus, Set<SubscriptionStatus>> map = new EnumMap<>(SubscriptionStatus.class);
        // S5 payment confirmed, S6 trial elapsed, S12 cancellation
        map.put(TRIALING, EnumSet.of(ACTIVE, EXPIRED, CANCELLED));
        // S7 grace, S8 expiry, S11 renewal (ACTIVE -> ACTIVE), S12 cancellation
        map.put(ACTIVE, EnumSet.of(ACTIVE, GRACE, EXPIRED, CANCELLED));
        // S9 grace elapsed, plus a late payment recovering the subscription
        map.put(GRACE, EnumSet.of(ACTIVE, EXPIRED, CANCELLED));
        // S4 payment confirmed (server-side only)
        map.put(PENDING_PAYMENT, EnumSet.of(ACTIVE, EXPIRED, CANCELLED));
        // S10 downgrade onto the (free) expiring edition — a trial can never be re-entered
        map.put(EXPIRED, EnumSet.of(ACTIVE, CANCELLED));
        // terminal: coming back requires assigning a package again (provisioning, not a transition)
        map.put(CANCELLED, EnumSet.noneOf(SubscriptionStatus.class));
        return map;
    }

    public boolean canTransitionTo(SubscriptionStatus target) {
        return target != null && ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    /** {@code true} while the tenant may use business endpoints. */
    public boolean grantsAccess() {
        return this != EXPIRED && this != PENDING_PAYMENT;
    }
}
