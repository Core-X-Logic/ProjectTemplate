package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iyzipay.Options;
import com.iyzipay.model.Address;
import com.iyzipay.model.BasketItem;
import com.iyzipay.model.BasketItemType;
import com.iyzipay.model.Buyer;
import com.iyzipay.model.CheckoutForm;
import com.iyzipay.model.CheckoutFormInitialize;
import com.iyzipay.model.Currency;
import com.iyzipay.model.PaymentGroup;
import com.iyzipay.request.CreateCheckoutFormInitializeRequest;
import com.iyzipay.request.RetrieveCheckoutFormRequest;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * iyzico Checkout Form (CF) implementation of the {@link BillingProvider} SPI (P2'-B; provider
 * strategy in ADR-0017). Spec sources, quoted where load-bearing: docs.iyzico.com ("Checkout Form"
 * and "Webhook" pages) and github.com/iyzico/iyzipay-java (SDK 2.0.142, pinned in the pom).
 *
 * <p><b>The flow, and which step is authoritative.</b>
 * <ol>
 *   <li>{@link #createCheckoutSession}: CF initialize
 *       ({@code POST /payment/iyzipos/checkoutform/initialize/auth/ecom}) → {@code token} +
 *       {@code paymentPageUrl}. The token is the session id stored on the payment row.</li>
 *   <li>The buyer pays on the hosted page.</li>
 *   <li>The buyer's BROWSER is sent to {@code callbackUrl} carrying the single parameter
 *       {@code token} (HTTP method undocumented — {@code BillingCallbackController} accepts POST
 *       form-encoded AND tolerates GET). The callback is a TRIGGER only, never trusted.</li>
 *   <li>{@link #confirmBySessionQuery}: the RETRIEVE call
 *       ({@code POST /payment/iyzipos/checkoutform/auth/ecom/detail}, body {@code {locale, token,
 *       conversationId}}) — the AUTHORITATIVE answer: outer {@code status}, {@code paymentStatus}
 *       ({@code SUCCESS}), {@code paymentId}, {@code paidPrice}, {@code fraudStatus}. Activation
 *       requires {@code fraudStatus == 1}; {@code 0} is "under review" and the reconciliation job
 *       re-asks later.</li>
 *   <li>Server-to-server webhook, verified by {@link #verifyAndParse} — which STILL funnels through
 *       the retrieve ({@code BillingWebhookService} query-confirmation path), so no delivered
 *       payload ever activates on its own.</li>
 * </ol>
 *
 * <p><b>Webhook verification is offline and self-implemented</b> (quoted formula, docs.iyzico.com
 * "Webhook", signature v3): header {@code X-IYZ-SIGNATURE-V3} = lowercase hex
 * {@code HMAC-SHA256(key = secretKey, message = secretKey + iyziEventType + iyziPaymentId + token +
 * paymentConversationId + status)} for HPP/Checkout-Form events. Absent fields contribute the empty
 * string, matching a sender that concatenates what it sent. Compared CONSTANT-TIME via
 * {@link MessageDigest#isEqual}; the submitted hex is lowercased first so a case difference is not
 * treated as forgery.
 *
 * <p><b>Event-type tolerance is deliberate.</b> iyzico's own documentation spells the CF event type
 * BOTH {@code CHECKOUT_FORM_AUTH} and {@code CHECKOUTFORM_AUTH} on different pages, so
 * {@link #mapEventType} strips underscores and compares case-insensitively instead of trusting
 * either spelling ({@code IyzicoSignatureTest} pins both).
 *
 * <p><b>Dedup key — documented uncertainty.</b> {@code iyziReferenceCode} is documented as unique
 * per delivery, but "unique per request" may mean a RETRY carries a NEW reference code — the docs do
 * not say (recorded in the risk register). If it does, the {@code webhook_events} dedup admits the
 * retry; that is harmless BY DESIGN here, because this provider never activates from the delivery:
 * every admitted event runs the same idempotent retrieve-confirm path, and the payment-status guard
 * ({@code PAID} stays {@code PAID}) is the second layer. A payload with NO reference code falls back
 * to {@code token + ":" + STATUS} (the PayTR shape) so at least exact replays dedup; a payload with
 * neither a reference code nor a token offers no dedup identity at all and is refused as
 * unverifiable-in-practice ({@link BillingSignatureException} → 400, nothing stored).
 *
 * <p><b>The two live API calls</b> ({@link #createCheckoutSession}, {@link #confirmBySessionQuery})
 * are deliberately thin and their real HTTP behaviour is NOT covered by an automated test — the
 * recorded PROD-R37 risk pattern, extended to iyzico in the register. Integration tests replace them
 * through the SPI while {@link #verifyAndParse} runs REAL code against a dummy secret. Buyer
 * identity is sent as documented placeholders (the PROD-R44 pattern): iyzico's docs do not pin which
 * buyer sub-fields are mandatory, so the historically-safe set (id, name, surname, identityNumber,
 * email, registrationAddress, city, country, ip) plus a billing address is sent, from tenant data
 * where it exists and placeholders where it does not. Basket items are {@code VIRTUAL} (SaaS
 * edition), so no shipping address is required.
 */
@Slf4j
public class IyzicoBillingProvider implements BillingProvider {

    public static final String PROVIDER_ID = "iyzico";

    /** The webhook's out-of-band proof; verified in {@link #verifyAndParse}, offline. */
    public static final String SIGNATURE_HEADER = "X-IYZ-SIGNATURE-V3";

    /** The CF event type with underscores stripped — both documented spellings normalize to this. */
    static final String NORMALIZED_CF_EVENT_TYPE = "CHECKOUTFORMAUTH";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String OUTER_STATUS_SUCCESS = "success";
    private static final String PAYMENT_STATUS_SUCCESS = "SUCCESS";
    /** docs show FAILURE; FAILED is accepted too — the same tolerance rule as the event type. */
    private static final List<String> PAYMENT_STATUS_FAILED = List.of("FAILURE", "FAILED");
    private static final int FRAUD_STATUS_APPROVED = 1;

    private final BillingIyzicoProperties properties;
    private final ObjectMapper objectMapper;

    public IyzicoBillingProvider(BillingIyzicoProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    // successAckBody stays the default null: iyzico settles delivery on HTTP 200 alone
    // (docs.iyzico.com "Webhook": first delivery ~15s after payment, then up to 3 retries every
    // 10 minutes until it sees a 200) — no body contract like PayTR's "OK".

    @Override
    public BillingEvent verifyAndParse(String payload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new BillingSignatureException(SIGNATURE_HEADER + " header is missing");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(payload == null ? "" : payload);
        } catch (IOException ex) {
            throw new BillingSignatureException("iyzico webhook payload is not readable JSON", ex);
        }
        if (root == null || !root.isObject()) {
            throw new BillingSignatureException("iyzico webhook payload is not a JSON object");
        }

        String eventType = root.path("iyziEventType").asText("");
        String status = root.path("status").asText("");
        String token = root.path("token").asText("");
        String iyziPaymentId = root.path("iyziPaymentId").asText("");
        String conversationId = root.path("paymentConversationId").asText("");
        String referenceCode = root.path("iyziReferenceCode").asText("");

        String expected = webhookSignature(properties.getSecretKey(), eventType, iyziPaymentId,
                token, conversationId, status);
        String submitted = signatureHeader.trim().toLowerCase(Locale.ROOT);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                submitted.getBytes(StandardCharsets.UTF_8))) {
            // The 400 this becomes is the correct financial answer: acknowledging an unverified
            // delivery would spend one of iyzico's three retries on a payload nobody proved.
            throw new BillingSignatureException("iyzico webhook " + SIGNATURE_HEADER
                    + " verification failed");
        }

        if (referenceCode.isBlank() && token.isBlank()) {
            // Verified but unusable: no reference code and no token means no dedup identity and no
            // session to act on. CF events always carry a token per the docs, so this shape is not
            // a legitimate delivery this version can honour; refusing (400, nothing stored) beats
            // storing rows that can never be deduped or matched.
            throw new BillingSignatureException(
                    "iyzico webhook carries neither iyziReferenceCode nor token");
        }
        String eventId = referenceCode.isBlank()
                ? token + ":" + status.toUpperCase(Locale.ROOT)
                : referenceCode;

        return new BillingEvent(eventId, mapEventType(eventType, status),
                token.isBlank() ? null : token,
                iyziPaymentId.isBlank() ? null : iyziPaymentId,
                payload);
    }

    @Override
    public CheckoutSession createCheckoutSession(CheckoutRequest request) {
        CreateCheckoutFormInitializeRequest init = new CreateCheckoutFormInitializeRequest();
        init.setLocale(com.iyzipay.model.Locale.TR.getValue());
        // The payment row's id, echoed back by initialize AND sent on retrieve — the handle a
        // human uses to correlate the iyzico panel with the payments table.
        init.setConversationId(String.valueOf(request.paymentId()));
        // price must equal the sum of basket item prices; paidPrice is what is charged. One
        // VIRTUAL line item at the snapshot amount keeps the three trivially equal.
        init.setPrice(request.amount());
        init.setPaidPrice(request.amount());
        init.setCurrency(iyzicoCurrency(request.currency()));
        init.setBasketId("ZPB" + request.paymentId());
        init.setPaymentGroup(PaymentGroup.PRODUCT.name());
        init.setCallbackUrl(callbackUrl(request));
        init.setBuyer(placeholderBuyer(request));
        init.setBillingAddress(placeholderAddress(request));
        init.setBasketItems(List.of(basketItem(request)));

        CheckoutFormInitialize result = CheckoutFormInitialize.create(init, apiOptions());
        if (result == null || !OUTER_STATUS_SUCCESS.equals(result.getStatus())
                || result.getToken() == null || result.getToken().isBlank()) {
            // A refused initialize is this installation's problem (credentials, account,
            // parameters), never the caller's input — 500, loud, iyzico's reason in the log only.
            throw new IllegalStateException("iyzico checkout-form initialize refused the session: "
                    + (result == null ? "empty response"
                            : result.getErrorCode() + " " + result.getErrorMessage()));
        }
        return new CheckoutSession(result.getToken(), result.getPaymentPageUrl());
    }

    @Override
    public boolean supportsQueryConfirmation() {
        return true;
    }

    /**
     * The authoritative step (see the class contract): retrieve by the stored token, answer only
     * what iyzico's server vouches for. Reused verbatim by the webhook funnel, the browser-callback
     * trigger and the reconciliation job — one call, three triggers, by design.
     */
    @Override
    public ProviderPaymentConfirmation confirmBySessionQuery(String sessionId) {
        RetrieveCheckoutFormRequest retrieve = new RetrieveCheckoutFormRequest();
        retrieve.setLocale(com.iyzipay.model.Locale.TR.getValue());
        retrieve.setToken(sessionId);
        CheckoutForm form = CheckoutForm.retrieve(retrieve, apiOptions());
        if (form == null) {
            throw new IllegalStateException(
                    "iyzico checkout-form retrieve returned no response for a stored token");
        }
        return mapRetrieve(form.getStatus(), form.getPaymentStatus(), form.getFraudStatus(),
                form.getPaymentId(), form.getErrorMessage());
    }

    // ------------------------------------------------------------------------------------------
    // Pure functions, package-visible so IyzicoSignatureTest (via IyzicoBillingProviderTestHook)
    // can pin them against offline vectors — the PayTR "real crypto, dummy secrets" strategy.
    // ------------------------------------------------------------------------------------------

    /**
     * The v3 webhook signature (quoted, docs.iyzico.com "Webhook"): lowercase hex
     * {@code HMAC-SHA256(key = secretKey, message = secretKey + iyziEventType + iyziPaymentId +
     * token + paymentConversationId + status)}. Note the secret key appears TWICE — as the HMAC key
     * and as the first element of the message; the two roles must never be merged or dropped.
     */
    static String webhookSignature(String secretKey, String eventType, String iyziPaymentId,
                                   String token, String conversationId, String status) {
        String message = secretKey + eventType + iyziPaymentId + token + conversationId + status;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot compute the iyzico webhook signature", ex);
        }
    }

    /**
     * Tolerant on purpose (see the class contract): the event type is compared with underscores
     * stripped, case-insensitively — the docs themselves use two spellings — and only the CF-auth
     * type maps to a domain event. Everything else is {@link BillingEvent.Type#UNKNOWN}: stored
     * with its payload, acknowledged 200, never an error (a signed event that verified provably
     * left iyzico, whatever this version makes of it).
     */
    static BillingEvent.Type mapEventType(String eventType, String status) {
        String normalizedType = eventType == null ? ""
                : eventType.replace("_", "").trim().toUpperCase(Locale.ROOT);
        if (!NORMALIZED_CF_EVENT_TYPE.equals(normalizedType)) {
            return BillingEvent.Type.UNKNOWN;
        }
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (PAYMENT_STATUS_SUCCESS.equals(normalizedStatus)) {
            // Completion CLAIM only: BillingWebhookService funnels this provider's completions
            // through confirmBySessionQuery, so this mapping never activates anything by itself.
            return BillingEvent.Type.CHECKOUT_COMPLETED;
        }
        if (PAYMENT_STATUS_FAILED.contains(normalizedStatus)) {
            return BillingEvent.Type.PAYMENT_FAILED;
        }
        return BillingEvent.Type.UNKNOWN;
    }

    /**
     * The activation predicate, in one pure place: collected only when the OUTER status says the
     * retrieve itself succeeded, {@code paymentStatus} is {@code SUCCESS}, and {@code fraudStatus}
     * is exactly {@code 1} (approved). {@code fraudStatus 0} is "under review": not collected YET —
     * the payment stays where it is and the reconciliation job re-asks; {@code -1} is declined.
     */
    static ProviderPaymentConfirmation mapRetrieve(String status, String paymentStatus,
                                                   Integer fraudStatus, String paymentId,
                                                   String errorMessage) {
        boolean retrieveSucceeded = OUTER_STATUS_SUCCESS.equalsIgnoreCase(status == null ? "" : status);
        boolean paid = PAYMENT_STATUS_SUCCESS.equalsIgnoreCase(paymentStatus == null ? "" : paymentStatus);
        boolean fraudApproved = fraudStatus != null && fraudStatus == FRAUD_STATUS_APPROVED;
        if (retrieveSucceeded && paid && fraudApproved) {
            return ProviderPaymentConfirmation.collected(paymentId);
        }
        // Detail goes to the LOG only (house rule: never echoed to a caller).
        return ProviderPaymentConfirmation.notCollected("status=" + status
                + ", paymentStatus=" + paymentStatus
                + ", fraudStatus=" + fraudStatus
                + (errorMessage == null || errorMessage.isBlank() ? "" : ", error=" + errorMessage));
    }

    // ------------------------------------------------------------------------------------------

    private Options apiOptions() {
        Options options = new Options();
        options.setApiKey(properties.getApiKey());
        options.setSecretKey(properties.getSecretKey());
        options.setBaseUrl(properties.getBaseUrl());
        return options;
    }

    private String callbackUrl(CheckoutRequest request) {
        String configured = properties.getCallbackUrl();
        return configured == null || configured.isBlank() ? request.successUrl() : configured;
    }

    private BasketItem basketItem(CheckoutRequest request) {
        BasketItem item = new BasketItem();
        item.setId("payment-" + request.paymentId());
        item.setName(request.editionDisplayName() + " (" + request.period() + ")");
        item.setCategory1("SaaS");
        // VIRTUAL: a SaaS edition ships nothing, so no shipping address is required.
        item.setItemType(BasketItemType.VIRTUAL.name());
        item.setPrice(request.amount());
        return item;
    }

    /**
     * Documented placeholders, the PROD-R44 pattern restated for iyzico: {@code CheckoutRequest}
     * models no buyer identity in the host-operated flow, and iyzico's docs do not pin which buyer
     * sub-fields are mandatory — so the historically-safe set is sent, from tenant data where it
     * exists and placeholders where it does not. Recorded in the risk register; the first live
     * sandbox smoke (P2'-C) measures whether initialize accepts them.
     */
    private Buyer placeholderBuyer(CheckoutRequest request) {
        Buyer buyer = new Buyer();
        buyer.setId("tenant-" + request.tenantId());
        buyer.setName("Tenant");
        buyer.setSurname(String.valueOf(request.tenantId()));
        // 11 digits, syntactically valid, semantically a documented placeholder (no real TCKN).
        buyer.setIdentityNumber("11111111111");
        buyer.setEmail("billing+tenant" + request.tenantId() + "@host-operated.invalid");
        buyer.setRegistrationAddress("n/a");
        buyer.setCity("Istanbul");
        buyer.setCountry("Turkey");
        buyer.setIp("127.0.0.1");
        return buyer;
    }

    private Address placeholderAddress(CheckoutRequest request) {
        Address address = new Address();
        address.setContactName("Tenant " + request.tenantId());
        address.setCity("Istanbul");
        address.setCountry("Turkey");
        address.setAddress("n/a");
        return address;
    }

    /**
     * TRY only for now (ADR-0017, the {@code PayTRBillingProvider.paytrCurrency} rule): anything
     * else is a loud configuration failure rather than a charge in a currency nobody chose.
     */
    private static String iyzicoCurrency(String currency) {
        String normalized = currency == null ? "" : currency.toUpperCase(Locale.ROOT);
        if (Currency.TRY.name().equals(normalized) || "TL".equals(normalized)) {
            return Currency.TRY.name();
        }
        throw new IllegalStateException("iyzico checkout supports TRY only; the edition is priced in "
                + currency + ". Widen IyzicoBillingProvider.iyzicoCurrency before selling this currency.");
    }
}
