package com.mycompanyname.zero.saas.billing;

/**
 * A webhook payload that failed authentication: missing/invalid signature, or a body that cannot be
 * read as an event at all. The caller answers 400 and stores NOTHING — an unauthenticated payload
 * must not be able to occupy a dedup slot ({@code webhook_events} rows are reserved for payloads the
 * provider provably sent).
 *
 * <p>Deliberately unchecked and deliberately message-poor towards the caller: the response never
 * echoes the payload or the submitted signature (house rule — rejected input goes to the log, not
 * back to the sender).
 */
public class BillingSignatureException extends RuntimeException {

    public BillingSignatureException(String message) {
        super(message);
    }

    public BillingSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
