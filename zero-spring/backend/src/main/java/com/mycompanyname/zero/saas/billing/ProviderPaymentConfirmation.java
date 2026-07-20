package com.mycompanyname.zero.saas.billing;

/**
 * The answer of a provider's OWN query API about one checkout session (P2'-B) — the authoritative
 * fact behind {@link BillingProvider#confirmBySessionQuery}.
 *
 * <p>{@code collected} means the provider's server, asked directly, states the money was collected
 * AND released for use (for iyzico: outer {@code status=success}, {@code paymentStatus=SUCCESS} and
 * {@code fraudStatus=1} — a payment under fraud review, {@code fraudStatus=0}, is deliberately NOT
 * collected yet: activating it and then having the review decline it would be an activation the
 * money never bought; the reconciliation job re-asks later).
 *
 * @param collected         {@code true} only when the provider confirms a settled, usable payment
 * @param externalPaymentId the provider's payment id from the QUERY response — preferred over
 *                          whatever a webhook payload claimed, because the query answer is the one
 *                          the provider's server just vouched for; may be {@code null}
 * @param detail            why a non-collected answer was not collected, for the LOG only — never
 *                          echoed to any caller (house rule)
 */
public record ProviderPaymentConfirmation(
        boolean collected,
        String externalPaymentId,
        String detail) {

    public static ProviderPaymentConfirmation collected(String externalPaymentId) {
        return new ProviderPaymentConfirmation(true, externalPaymentId, null);
    }

    public static ProviderPaymentConfirmation notCollected(String detail) {
        return new ProviderPaymentConfirmation(false, null, detail);
    }
}
