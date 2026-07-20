package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * PayTR iFrame API implementation of the {@link BillingProvider} SPI (P2'-A; provider strategy in
 * ADR-0017). Spec: dev.paytr.com, "iFrame API" — the two HMAC formulas below are quoted from it.
 *
 * <p><b>Notification ("Bildirim URL").</b> Server-to-server POST,
 * {@code application/x-www-form-urlencoded}. Guaranteed fields: {@code merchant_oid},
 * {@code status} ({@code success}|{@code failed}), {@code total_amount} (kuruş integer, ×100 — and
 * it may be HIGHER than the requested amount when the buyer picked an instalment plan with a
 * surcharge, which is why this mapping never cross-checks it against the payment row), {@code hash};
 * on failure additionally {@code failed_reason_code}/{@code failed_reason_msg}. Other fields exist
 * but are NOT guaranteed and nothing here depends on them.
 *
 * <p><b>Hash verification is offline and is the webhook's entire authentication</b> (quoted
 * formula): {@code hash = base64(HMAC-SHA256(merchant_oid + merchant_salt + status + total_amount,
 * key = merchant_key))}, all strings UTF-8, HMAC over the raw bytes, then Base64. Compared
 * CONSTANT-TIME via {@link MessageDigest#isEqual} — a byte-by-byte {@code equals} would leak match
 * length to the one caller this endpoint must assume is hostile.
 *
 * <p><b>Dedup event id — a documented choice, because PayTR has NO event id.</b> {@code
 * merchant_oid} is unique per transaction and the docs state only the FIRST notification counts, so
 * the dedup key is {@code merchant_oid + ":" + status}. A redelivered {@code success} therefore
 * dedupes exactly; a SECOND status for the same oid gets its own {@code webhook_events} row on
 * purpose. The two orderings are NOT symmetric and {@code BillingWebhookService} holds both rules
 * (both mutation-proved): failed → success is the buyer RETRYING inside the iframe session — the
 * success hash proves collection, so {@code FAILED -> PAID} activates; success → failed is
 * contradictory and inert — {@code PAID} stays {@code PAID}.
 *
 * <p><b>Checkout token (get-token request)</b> — note the SALT POSITION DIFFERS from the
 * notification formula (end of message here, after {@code merchant_oid} there): {@code paytr_token =
 * base64(HMAC-SHA256(merchant_id + user_ip + merchant_oid + email + payment_amount + user_basket +
 * no_installment + max_installment + currency + test_mode + merchant_salt, key = merchant_key))}.
 * {@code payment_amount} is INTEGER KURUŞ ("9.99" → 999) — one of the three amount formats that are
 * the reason money stays in minor-unit-safe {@code BigDecimal} in the core and converts only at this
 * adapter edge (ADR-0017). {@code user_basket} is {@code base64(JSON [[name, unitPrice, qty]])}.
 * The response is JSON {@code {"status":"success","token":"..."}} and the buyer's iframe URL is
 * {@code https://www.paytr.com/odeme/guvenli/{token}}.
 *
 * <p><b>{@link #createCheckoutSession} is the one live API call</b> and is deliberately thin: build
 * the form, POST, read two fields. Its real HTTP behaviour is not covered by an automated test
 * (recorded risk, same as Stripe — PROD-R37); integration tests replace it with a recording fake
 * while {@link #verifyAndParse} runs REAL code against a dummy salt/key. The buyer-identity fields
 * PayTR requires ({@code email}, {@code user_ip}, name/address/phone) are NOT modelled by the
 * host-operated {@code CheckoutRequest} yet and are sent as documented placeholders — recorded in
 * the risk register; wiring real buyer data is part of making this call live.
 */
@Slf4j
public class PayTRBillingProvider implements BillingProvider {

    public static final String PROVIDER_ID = "paytr";

    /** The literal settlement contract: anything but this exact body = failed notification. */
    static final String SUCCESS_ACK_BODY = "OK";

    static final String TOKEN_ENDPOINT = "https://www.paytr.com/odeme/api/get-token";
    static final String IFRAME_URL_PREFIX = "https://www.paytr.com/odeme/guvenli/";

    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILED = "failed";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Alphanumeric only — PayTR rejects a {@code merchant_oid} with dashes (no hyphened UUIDs). */
    private static final char[] OID_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final int OID_RANDOM_SUFFIX_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BillingPayTRProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public PayTRBillingProvider(BillingPayTRProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    /** See the class contract: for PayTR the money settles ONLY on the literal body {@code OK}. */
    @Override
    public String successAckBody() {
        return SUCCESS_ACK_BODY;
    }

    /**
     * {@code signatureHeader} is ignored by design: PayTR's proof is the {@code hash} FIELD inside
     * the form body, not a header (see {@link BillingProvider#verifyAndParse}).
     */
    @Override
    public BillingEvent verifyAndParse(String payload, String signatureHeader) {
        Map<String, String> fields = parseFormBody(payload);
        String merchantOid = requireField(fields, "merchant_oid");
        String status = requireField(fields, "status");
        String totalAmount = requireField(fields, "total_amount");
        String submittedHash = requireField(fields, "hash");

        String expectedHash = notificationHash(merchantOid, status, totalAmount,
                properties.getMerchantKey(), properties.getMerchantSalt());
        if (!MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.UTF_8),
                submittedHash.getBytes(StandardCharsets.UTF_8))) {
            // The 400 this becomes is the CORRECT financial answer, not just the secure one:
            // answering OK to an unverified notification would confirm money nobody proved was paid.
            throw new BillingSignatureException("PayTR notification hash verification failed");
        }

        String eventId = merchantOid + ":" + status;
        if (STATUS_SUCCESS.equals(status)) {
            // PayTR carries no separate payment id; merchant_oid is the only transaction handle.
            return new BillingEvent(eventId, BillingEvent.Type.CHECKOUT_COMPLETED,
                    merchantOid, null, payload);
        }
        if (STATUS_FAILED.equals(status)) {
            return new BillingEvent(eventId, BillingEvent.Type.PAYMENT_FAILED,
                    merchantOid, null, payload);
        }
        // A status this version does not know, but the hash DID verify (status is inside the HMAC
        // message, so it provably left PayTR). Stored as UNKNOWN/IGNORED with the payload intact.
        return new BillingEvent(eventId, BillingEvent.Type.UNKNOWN, merchantOid, null, payload);
    }

    @Override
    public CheckoutSession createCheckoutSession(CheckoutRequest request) {
        String merchantOid = newMerchantOid(request.paymentId());
        String paymentAmount = String.valueOf(toKurus(request.amount()));
        String basket = userBasket(request.editionDisplayName() + " (" + request.period() + ")",
                request.amount(), objectMapper);
        String currency = paytrCurrency(request.currency());
        String testMode = properties.isTestMode() ? "1" : "0";
        String noInstallment = "0";
        String maxInstallment = "0";
        // Placeholders, recorded (risk register): CheckoutRequest models no buyer identity yet.
        String email = "billing+tenant" + request.tenantId() + "@host-operated.invalid";
        String userIp = "127.0.0.1";

        String token = checkoutToken(properties.getMerchantId(), userIp, merchantOid, email,
                paymentAmount, basket, noInstallment, maxInstallment, currency, testMode,
                properties.getMerchantKey(), properties.getMerchantSalt());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("merchant_id", properties.getMerchantId());
        form.add("user_ip", userIp);
        form.add("merchant_oid", merchantOid);
        form.add("email", email);
        form.add("payment_amount", paymentAmount);
        form.add("paytr_token", token);
        form.add("user_basket", basket);
        form.add("debug_on", properties.isTestMode() ? "1" : "0");
        form.add("no_installment", noInstallment);
        form.add("max_installment", maxInstallment);
        form.add("user_name", "Tenant " + request.tenantId());
        form.add("user_address", "n/a");
        form.add("user_phone", "n/a");
        form.add("merchant_ok_url", request.successUrl());
        form.add("merchant_fail_url", request.cancelUrl());
        form.add("timeout_limit", "30");
        form.add("currency", currency);
        form.add("test_mode", testMode);

        JsonNode response = restClient.post()
                .uri(TOKEN_ENDPOINT)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || !"success".equals(response.path("status").asText())) {
            // A refused token is this installation's problem (credentials, account, parameters),
            // never the caller's input — 500, loud, with PayTR's reason in the log only.
            throw new IllegalStateException("PayTR get-token refused the checkout session request: "
                    + (response == null ? "empty response" : response.path("reason").asText("no reason")));
        }
        return new CheckoutSession(merchantOid, IFRAME_URL_PREFIX + response.path("token").asText());
    }

    // ------------------------------------------------------------------------------------------
    // Pure functions, package-visible so PayTRTokenRequestTest can pin them against offline
    // vectors — the same "real crypto, dummy secrets" strategy the Stripe ITs use.
    // ------------------------------------------------------------------------------------------

    /** Notification formula: salt sits AFTER {@code merchant_oid} (not at the end — that is the token). */
    static String notificationHash(String merchantOid, String status, String totalAmount,
                                   String merchantKey, String merchantSalt) {
        return base64Hmac(merchantOid + merchantSalt + status + totalAmount, merchantKey);
    }

    /** Token formula: salt is APPENDED to the message end. The two formulas must never be merged. */
    static String checkoutToken(String merchantId, String userIp, String merchantOid, String email,
                                String paymentAmount, String userBasket, String noInstallment,
                                String maxInstallment, String currency, String testMode,
                                String merchantKey, String merchantSalt) {
        return base64Hmac(merchantId + userIp + merchantOid + email + paymentAmount + userBasket
                + noInstallment + maxInstallment + currency + testMode + merchantSalt, merchantKey);
    }

    /**
     * Kuruş conversion at the adapter edge: "9.99" → 999, "150" → 15000. {@code longValueExact}
     * makes a sub-kuruş snapshot a loud failure instead of a silently rounded charge (the
     * {@code StripeBillingProvider.minorUnits} rule, restated for TL).
     */
    static long toKurus(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    /** {@code base64(JSON [[name, unitPriceString, 1]])} — one line item, quantity one, per checkout. */
    static String userBasket(String itemName, BigDecimal unitPrice, ObjectMapper objectMapper) {
        ArrayNode basket = objectMapper.createArrayNode();
        ArrayNode item = basket.addArray();
        item.add(itemName);
        item.add(unitPrice.toPlainString());
        item.add(1);
        try {
            return Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(basket));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize the PayTR basket", ex);
        }
    }

    /**
     * {@code merchant_oid}: alphanumeric ONLY, max 64 chars (PayTR rejects dashes, so never a
     * hyphened UUID). Derived from the payment id (a human can correlate panel and DB) plus a
     * random suffix (a re-tried checkout for the same payment must not collide — PayTR requires
     * uniqueness per transaction). "ZP" + 19-digit-max id + 10 random = ≤ 31 chars, safely inside 64.
     */
    static String newMerchantOid(long paymentId) {
        StringBuilder oid = new StringBuilder("ZP").append(paymentId);
        for (int i = 0; i < OID_RANDOM_SUFFIX_LENGTH; i++) {
            oid.append(OID_ALPHABET[RANDOM.nextInt(OID_ALPHABET.length)]);
        }
        return oid.toString();
    }

    /**
     * First-wins, UTF-8-decoded form parse. First-wins matters: the hash covers the values this
     * method returns, so letting a LATER duplicate key override would let an attacker append
     * {@code &status=success} behind a validly hashed {@code failed} notification. With first-wins
     * the appended duplicate is inert; hash verification then vouches for what was actually read.
     */
    static Map<String, String> parseFormBody(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new BillingSignatureException("PayTR notification body is empty");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        try {
            for (String pair : payload.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int split = pair.indexOf('=');
                String key = split < 0 ? pair : pair.substring(0, split);
                String value = split < 0 ? "" : pair.substring(split + 1);
                fields.putIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException ex) {
            throw new BillingSignatureException("PayTR notification body could not be parsed", ex);
        }
        return fields;
    }

    private static String requireField(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            // The missing field is named in the log via the exception message; the HTTP response
            // stays generic (BillingWebhookService never echoes rejection detail to the caller).
            throw new BillingSignatureException("PayTR notification is missing the field " + name);
        }
        return value;
    }

    /**
     * TL only for now (ADR-0017): the catalogue snapshot says {@code TRY} (ISO 4217), the PayTR wire
     * format says {@code TL}. Anything else is a loud configuration failure — silently sending an
     * unsupported currency would let PayTR default the charge to TL at face value.
     */
    private static String paytrCurrency(String currency) {
        String normalized = currency == null ? "" : currency.toUpperCase(Locale.ROOT);
        if ("TRY".equals(normalized) || "TL".equals(normalized)) {
            return "TL";
        }
        throw new IllegalStateException("PayTR checkout supports TRY only; the edition is priced in "
                + currency + ". Widen PayTRBillingProvider.paytrCurrency before selling this currency.");
    }

    private static String base64Hmac(String message, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot compute the PayTR HMAC", ex);
        }
    }
}
