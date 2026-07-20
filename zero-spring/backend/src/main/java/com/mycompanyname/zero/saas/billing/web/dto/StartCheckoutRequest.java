package com.mycompanyname.zero.saas.billing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Checkout initiation payload. Host-only ({@code subscriptions.manage}), so the redirect URLs are an
 * operator's input, not an anonymous caller's — they go to Stripe verbatim and Stripe validates
 * them against its own rules.
 *
 * @param tenantId      the paying tenant; must already hold a subscription row
 * @param editionId     the priced edition being bought
 * @param billingPeriod MONTHLY or ANNUAL
 * @param successUrl    where Stripe sends the buyer after payment (display only — activation NEVER
 *                      hangs on this redirect, see ADR-0014)
 * @param cancelUrl     where Stripe sends the buyer on abandon
 */
public record StartCheckoutRequest(
        @NotNull Long tenantId,
        @NotNull Long editionId,
        @NotBlank String billingPeriod,
        @NotBlank String successUrl,
        @NotBlank String cancelUrl) {
}
