package com.mycompanyname.zero.saas.billing;

/**
 * The seam between the billing domain and a payment provider (P2-A, multi-provider since P2'-A).
 *
 * <p>Everything the application knows about a provider goes through this interface, so the domain
 * logic — payment rows, webhook dedup, server-side activation — is testable without any network and
 * a second provider is a new implementation rather than a rewrite. Implementations register as
 * beans behind their own {@code zero.billing.<provider>.enabled} flag ({@link BillingStripeConfig},
 * {@link BillingPayTRConfig}) and are collected into the {@link BillingProviderRegistry}, keyed by
 * {@link #id()}; "no provider bean" is the normal state of a fresh clone and the web layer must
 * treat it as "this surface does not exist" rather than crash.
 */
public interface BillingProvider {

    /**
     * Stable provider identifier, stored in {@code webhook_events} rows and used as the
     * {@link BillingProviderRegistry} key ("stripe", "paytr"). Two beans sharing an id refuse boot.
     */
    String id();

    /**
     * Verifies the webhook payload's authenticity and maps it onto a {@link BillingEvent}.
     *
     * <p>This is the webhook's ONLY authentication: the endpoint is anonymous by necessity, and the
     * verification is what proves the payload left the provider. Implementations must work fully
     * offline — no verification step may call out over the network.
     *
     * <p>{@code signatureHeader} is the provider-specific out-of-band proof, when the provider uses
     * one: Stripe sends {@code Stripe-Signature} and the controller passes it here. A provider whose
     * proof travels INSIDE the body (PayTR's {@code hash} form field) receives {@code null} and must
     * ignore the parameter — the raw {@code payload} carries everything it needs.
     *
     * @throws BillingSignatureException when the proof is absent, invalid, or the payload cannot be
     *                                   read as an event at all; the caller answers 400 and stores
     *                                   nothing
     */
    BillingEvent verifyAndParse(String payload, String signatureHeader);

    /**
     * The exact response body this provider's webhook requires on a SUCCESSFUL acknowledgement
     * (processed, duplicate and ignored deliveries alike), or {@code null} for a bodyless 200.
     *
     * <p>Exists because PayTR reads the response BODY, not just the status line: its notification
     * is settled only by the literal plain-text body {@code OK} — anything else (JSON, whitespace,
     * casing) is treated as a failed notification and <b>the money is not settled</b>. Stripe keeps
     * the default: it reads the status code and nothing else. The webhook controller returns this
     * verbatim as {@code text/plain}; no error handler may wrap it ({@code PayTRWebhookIT} pins the
     * exact bytes).
     */
    default String successAckBody() {
        return null;
    }

    /**
     * Creates a hosted checkout session for the given payment. This is the one method that performs
     * a real provider API call; it is deliberately thin and its live behaviour is NOT covered by an
     * automated test (recorded risk — integration tests replace it with a canned session).
     */
    CheckoutSession createCheckoutSession(CheckoutRequest request);
}
