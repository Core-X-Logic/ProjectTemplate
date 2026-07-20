package com.mycompanyname.zero.saas.billing;

import java.math.BigDecimal;

/**
 * What {@link BillingProvider#createCheckoutSession} needs to sell one edition period once.
 *
 * <p>The amount/currency are the edition-price SNAPSHOT taken onto the {@code payments} row, not a
 * live catalogue read — the same rule as subscriptions (ADR-0012): a price edit between checkout
 * start and payment must not change what this buyer pays.
 *
 * @param paymentId          the {@code payments} row this session will settle; sent to the provider
 *                           as the client reference so a human can correlate the two
 * @param tenantId           the paying tenant
 * @param editionDisplayName what the buyer sees on the provider's checkout page
 * @param amount             price snapshot in major units (e.g. 10.00)
 * @param currency           ISO 4217, upper case (e.g. USD)
 * @param period             MONTHLY or ANNUAL, for the line-item label
 * @param successUrl         where the provider redirects after payment
 * @param cancelUrl          where the provider redirects on abandon
 */
public record CheckoutRequest(
        Long paymentId,
        Long tenantId,
        String editionDisplayName,
        BigDecimal amount,
        String currency,
        String period,
        String successUrl,
        String cancelUrl) {
}
