package com.mycompanyname.zero.saas.billing;

/**
 * Webhook-event lifecycle. Mirrored by {@code ck_webhook_events_status} in {@code V8__billing.sql}.
 */
public enum WebhookEventStatus {
    /** Inserted, not yet dispatched. Transient inside the processing transaction. */
    RECEIVED,
    /** Dispatched and acted on (including the "already paid, nothing to do" replay case). */
    PROCESSED,
    /** Stored but deliberately not acted on: UNKNOWN types, malformed-but-signed payloads. */
    IGNORED,
    /**
     * Reserved. Under the single-transaction design in {@code BillingWebhookService} a failed
     * attempt ROLLS BACK its own row so the provider's retry reprocesses cleanly — a durable FAILED
     * marker would occupy the dedup slot and turn every retry into a silent 200. The value stays in
     * the vocabulary (and in the CHECK constraint) for an operator or a later slice that records
     * failures out-of-band.
     */
    FAILED
}
