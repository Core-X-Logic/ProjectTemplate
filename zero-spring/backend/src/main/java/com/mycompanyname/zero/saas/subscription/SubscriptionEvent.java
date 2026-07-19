package com.mycompanyname.zero.saas.subscription;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Append-only lifecycle trail of a subscription. Written on every successful status change
 * (including the initial provisioning, where {@code fromStatus} is {@code null}).
 *
 * <p>Kept as a domain record independent of the {@code audit} module so it stays queryable as
 * business data rather than as generic entity history.
 */
@Entity
@Table(name = "subscription_events")
@Getter
@Setter
public class SubscriptionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 24)
    private SubscriptionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 24)
    private SubscriptionStatus toStatus;

    @Column(name = "reason", length = 64)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "actor", length = 64)
    private String actor;
}
