package com.mycompanyname.zero.saas.api;

import java.time.Instant;

/**
 * Application event published on every subscription event-trail entry (the {@code recordEvent}
 * funnel in {@code SubscriptionService} — transitions, provisioning, edition changes and the
 * expiry notice). Lives in the {@code saas :: api} named interface so consumers (today: the
 * identity module's notification bridge) can subscribe without reaching into saas internals.
 *
 * <p>The event flows {@code saas -> listener} only; saas gains no new dependency, which keeps the
 * {@code identity -> saas :: api} edge acyclic (ARCHITECTURE-RULES.md — "Modül bağımlılıkları
 * döngü kurmaz").
 *
 * @param tenantId           the tenant whose subscription changed (never null)
 * @param subscriptionId     the subscription row
 * @param editionName        machine name of the edition at the time of the event
 * @param editionDisplayName human-facing edition name (notification text uses this)
 * @param fromStatus         previous status name, or {@code null} on first provisioning
 * @param toStatus           resulting status name (equal to {@code fromStatus} for non-transition
 *                           entries such as {@code EDITION_CHANGED} or {@code EXPIRY_NOTICE})
 * @param reason             the event-trail reason constant (e.g. {@code ACTIVATED},
 *                           {@code PERIOD_ENDED}, {@code EXPIRY_NOTICE})
 * @param occurredAt         event time (injected clock)
 * @param periodEndAt        current billed period end, when one exists
 * @param trialEndAt         trial end, when the subscription is (still) trialing
 */
public record SubscriptionChanged(
        Long tenantId,
        Long subscriptionId,
        String editionName,
        String editionDisplayName,
        String fromStatus,
        String toStatus,
        String reason,
        Instant occurredAt,
        Instant periodEndAt,
        Instant trialEndAt) {

    /** Reason constant for the pre-expiry notice (no status change). */
    public static final String REASON_EXPIRY_NOTICE = "EXPIRY_NOTICE";
}
