package com.mycompanyname.zero.saas.billing;

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
 * Dedup ledger of provider webhooks. The row is inserted with
 * {@code insert ... on conflict (provider, event_id) do nothing} — the UNIQUE constraint IS the
 * idempotency mechanism, so this entity is never {@code save()}d into existence, only loaded and
 * updated. See {@link BillingWebhookService} for the transaction-boundary reasoning.
 */
@Entity
@Table(name = "webhook_events")
@Getter
@Setter
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "event_id", nullable = false, length = 255)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** The signed body, verbatim — replay/backfill material for anything the mapping missed. */
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WebhookEventStatus status;
}
