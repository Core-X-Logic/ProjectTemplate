package com.mycompanyname.zero.saas.billing;

/**
 * A provider webhook payload, verified and reduced to the facts the domain acts on.
 *
 * <p>{@code rawPayload} is the exact signed body and is what gets stored in
 * {@code webhook_events.payload}: whatever this mapping misses today can be replayed or backfilled
 * from it tomorrow, which is why {@link Type#UNKNOWN} events are stored too rather than dropped.
 *
 * @param eventId           provider-unique event id; with the provider id it forms the dedup key
 * @param type              mapped event type; anything the domain does not act on is {@code UNKNOWN}
 * @param externalSessionId checkout session id ({@code cs_...}) when the event carries one
 * @param externalPaymentId provider payment id ({@code pi_...}) when the event carries one
 * @param rawPayload        the signed body, verbatim
 */
public record BillingEvent(
        String eventId,
        Type type,
        String externalSessionId,
        String externalPaymentId,
        String rawPayload) {

    /** Event vocabulary of the SPI — provider-neutral on purpose. */
    public enum Type {
        /** A hosted checkout completed; the payment behind {@code externalSessionId} was collected. */
        CHECKOUT_COMPLETED,
        /** A recurring charge for an existing subscription succeeded (renewal, not first purchase). */
        RECURRING_PAYMENT_SUCCEEDED,
        /** Everything else. Stored as {@code IGNORED}, answered 200, never an error. */
        UNKNOWN
    }
}
