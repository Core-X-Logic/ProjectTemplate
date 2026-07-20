package com.mycompanyname.zero.saas.billing;

/**
 * Payment lifecycle. Mirrored by {@code ck_payments_status} in {@code V8__billing.sql} — adding a
 * value here means a new migration widening the CHECK constraint.
 */
public enum PaymentStatus {
    /** Created at checkout start; nothing collected yet. */
    NOT_PAID,
    /** Confirmed by the provider webhook. Terminal for this slice. */
    PAID,
    /** The provider reported the charge failed. Written by a later slice. */
    FAILED,
    /** Abandoned/voided before collection. Written by a later slice. */
    CANCELLED
}
