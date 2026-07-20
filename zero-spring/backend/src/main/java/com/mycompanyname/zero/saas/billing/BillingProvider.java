package com.mycompanyname.zero.saas.billing;

/**
 * The seam between the billing domain and a payment provider (P2-A).
 *
 * <p>Everything the application knows about a provider goes through this interface, so the domain
 * logic — payment rows, webhook dedup, server-side activation — is testable without any network and
 * a second provider is a new implementation rather than a rewrite. The Stripe implementation is
 * {@link StripeBillingProvider}; its bean is registered only when {@code zero.billing.stripe.enabled}
 * is {@code true} (see {@link BillingStripeConfig}), so "no provider bean" is the normal state of a
 * fresh clone and the web layer must treat it as "this surface does not exist" rather than crash.
 */
public interface BillingProvider {

    /** Stable provider identifier, stored in {@code payments}/{@code webhook_events} rows ("stripe"). */
    String id();

    /**
     * Verifies the webhook signature and maps the payload onto a {@link BillingEvent}.
     *
     * <p>This is the webhook's ONLY authentication: the endpoint is anonymous by necessity, and the
     * signature is what proves the payload left the provider. Implementations must work fully
     * offline — no verification step may call out over the network.
     *
     * @throws BillingSignatureException when the signature is absent, invalid, or the payload cannot
     *                                   be read as an event at all; the caller answers 400 and stores
     *                                   nothing
     */
    BillingEvent verifyAndParse(String payload, String signatureHeader);

    /**
     * Creates a hosted checkout session for the given payment. This is the one method that performs
     * a real provider API call; it is deliberately thin and its live behaviour is NOT covered by an
     * automated test (recorded risk — integration tests replace it with a canned session).
     */
    CheckoutSession createCheckoutSession(CheckoutRequest request);
}
