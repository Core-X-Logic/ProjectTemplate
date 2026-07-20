package com.mycompanyname.zero.saas.billing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Checkout initiation payload. Host-only ({@code subscriptions.manage}), so the redirect URLs are an
 * operator's input, not an anonymous caller's — they go to the provider verbatim and the provider
 * validates them against its own rules.
 *
 * @param tenantId      the paying tenant; must already hold a subscription row
 * @param editionId     the priced edition being bought
 * @param billingPeriod MONTHLY or ANNUAL
 * @param successUrl    where the provider sends the buyer after payment (display only — activation
 *                      NEVER hangs on this redirect, see ADR-0014)
 * @param cancelUrl     where the provider sends the buyer on abandon
 * @param provider      billing provider id ("stripe", "paytr" — ADR-0017). OPTIONAL on purpose:
 *                      omitted, the single enabled provider is used, which is why every existing
 *                      caller keeps working; with several enabled the request must choose and an
 *                      omitted/unknown value is a 400 naming the valid ids
 *                      ({@code BillingCheckoutService#resolveProvider})
 */
public record StartCheckoutRequest(
        @NotNull Long tenantId,
        @NotNull Long editionId,
        @NotBlank String billingPeriod,
        @NotBlank String successUrl,
        @NotBlank String cancelUrl,
        String provider) {
}
