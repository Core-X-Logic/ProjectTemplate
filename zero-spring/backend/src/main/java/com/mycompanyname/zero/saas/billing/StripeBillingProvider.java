package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Stripe implementation of the {@link BillingProvider} SPI (stripe-java, version pinned in the pom).
 *
 * <p><b>Verification is offline.</b> {@link Webhook#constructEvent} recomputes the HMAC over the raw
 * body with the endpoint's {@code whsec_...} secret and never touches the network, which is what
 * lets the integration tests exercise the REAL verification path with a dummy secret.
 *
 * <p><b>Field extraction reads the raw JSON, not the Stripe model graph.</b>
 * {@code event.getDataObjectDeserializer().getObject()} returns empty whenever the payload's
 * {@code api_version} differs from the SDK's pinned version — which is the NORMAL state for a
 * webhook endpoint created before an SDK upgrade. The three fields this mapping needs (session id,
 * payment intent, billing reason) are stable top-level members of {@code data.object}, so they are
 * read with Jackson and the mapping cannot silently start returning empties after a dependency bump.
 *
 * <p><b>{@link #createCheckoutSession} is the one live API call</b> and is deliberately thin: build
 * params, call, return two strings. Its real HTTP behaviour is not covered by an automated test
 * (recorded risk, per the slice contract).
 */
@Slf4j
public class StripeBillingProvider implements BillingProvider {

    public static final String PROVIDER_ID = "stripe";

    private static final String CHECKOUT_SESSION_COMPLETED = "checkout.session.completed";
    private static final String INVOICE_PAID = "invoice.paid";
    private static final String BILLING_REASON_SUBSCRIPTION_CYCLE = "subscription_cycle";

    private final BillingStripeProperties properties;
    private final ObjectMapper objectMapper;
    private final StripeClient stripeClient;

    public StripeBillingProvider(BillingStripeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // Instance-scoped client, not the static Stripe.apiKey mutable global: two providers (or a
        // test and the app) can then never fight over process-wide state.
        this.stripeClient = new StripeClient(properties.getSecretKey());
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public BillingEvent verifyAndParse(String payload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            // constructEvent would NPE its way through header parsing; refuse explicitly instead.
            throw new BillingSignatureException("Stripe-Signature header is missing");
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, properties.getWebhookSecret());
        } catch (SignatureVerificationException ex) {
            throw new BillingSignatureException("Stripe webhook signature verification failed", ex);
        } catch (RuntimeException ex) {
            // GSON's JsonSyntaxException and friends: a body that verifies but cannot be read as an
            // event is rejected the same way — it did not come out of Stripe's event pipeline.
            throw new BillingSignatureException("Stripe webhook payload could not be parsed", ex);
        }
        if (event.getId() == null || event.getId().isBlank()) {
            // No id means no dedup key; without one the idempotency contract cannot hold.
            throw new BillingSignatureException("Stripe webhook event carries no event id");
        }

        JsonNode dataObject = readDataObject(payload);
        String type = event.getType();
        if (CHECKOUT_SESSION_COMPLETED.equals(type)) {
            return new BillingEvent(event.getId(), BillingEvent.Type.CHECKOUT_COMPLETED,
                    text(dataObject, "id"), text(dataObject, "payment_intent"), payload);
        }
        if (INVOICE_PAID.equals(type)
                && BILLING_REASON_SUBSCRIPTION_CYCLE.equals(text(dataObject, "billing_reason"))) {
            return new BillingEvent(event.getId(), BillingEvent.Type.RECURRING_PAYMENT_SUCCEEDED,
                    null, text(dataObject, "payment_intent"), payload);
        }
        return new BillingEvent(event.getId(), BillingEvent.Type.UNKNOWN, null, null, payload);
    }

    @Override
    public CheckoutSession createCheckoutSession(CheckoutRequest request) {
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                // The payment row's id travels with the session so a human reading the Stripe
                // dashboard can find the matching payments row without guessing.
                .setClientReferenceId(String.valueOf(request.paymentId()))
                .setSuccessUrl(request.successUrl())
                .setCancelUrl(request.cancelUrl())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(request.currency().toLowerCase(Locale.ROOT))
                                .setUnitAmount(minorUnits(request.amount()))
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData
                                        .builder()
                                        .setName(request.editionDisplayName()
                                                + " (" + request.period() + ")")
                                        .build())
                                .build())
                        .build())
                .build();
        try {
            // v1() is the non-deprecated namespace accessor since stripe-java v33; the bare
            // stripeClient.checkout() shortcut is the deprecated spelling of the same service.
            var session = stripeClient.v1().checkout().sessions().create(params);
            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (StripeException ex) {
            // A refused session creation is this installation's problem (key, account, network),
            // never the caller's input — so it surfaces as a 500, loud, with the cause in the log.
            throw new IllegalStateException("Stripe checkout session creation failed", ex);
        }
    }

    /**
     * Stripe prices in minor units. {@code longValueExact} makes a sub-cent snapshot a loud failure
     * instead of a silently rounded charge. KNOWN LIMIT: zero-decimal currencies (JPY, KRW, ...)
     * would be charged 100x here; the catalogue currently sells USD/EUR-style currencies only, and
     * widening it means widening this method first.
     */
    private static long minorUnits(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    private JsonNode readDataObject(String payload) {
        try {
            return objectMapper.readTree(payload).path("data").path("object");
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // constructEvent already parsed this body, so reaching here means the two parsers
            // disagree about well-formedness — treat as unreadable, same as any other bad payload.
            throw new BillingSignatureException("Stripe webhook payload could not be parsed", ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull() ? value.asText() : null;
    }
}
