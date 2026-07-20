package com.mycompanyname.zero.saas.billing;

/**
 * A created hosted-checkout session.
 *
 * @param sessionId provider session id ({@code cs_...}); stored on the payment row as
 *                  {@code external_session_id}, which is how the completion webhook finds it back
 * @param url       the hosted checkout page the caller redirects the buyer to
 */
public record CheckoutSession(String sessionId, String url) {
}
