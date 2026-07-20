package com.mycompanyname.zero.saas.billing.web.dto;

/**
 * A started checkout: the payment row that will settle it and where to send the buyer.
 *
 * @param paymentId the {@code NOT_PAID} payment row created for this checkout
 * @param sessionId provider session id ({@code cs_...})
 * @param url       the hosted checkout page to redirect the buyer to
 */
public record CheckoutSessionDto(Long paymentId, String sessionId, String url) {
}
