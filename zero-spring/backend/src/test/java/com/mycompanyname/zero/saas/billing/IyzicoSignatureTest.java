package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The iyzico webhook v3 signature and the adapter's pure mappings, pinned offline (P2'-B) — the
 * {@code PayTRTokenRequestTest} strategy: real production code, dummy secrets, and the expected
 * values computed INDEPENDENTLY with plain {@code javax.crypto} so the test can never share a code
 * path with the verification it certifies.
 *
 * <p><b>The vector's inputs are documented here</b> so a red assertion is debuggable: secret key
 * {@code it_dummy_iyzico_secret_never_real}, event type {@code CHECKOUT_FORM_AUTH}, iyziPaymentId
 * {@code 123456789}, token {@code tok-vector-0001}, conversation id {@code 42}, status
 * {@code SUCCESS}. The documented formula (docs.iyzico.com "Webhook", signature v3):
 * lowercase hex of {@code HMAC-SHA256(key = secretKey, message = secretKey + iyziEventType +
 * iyziPaymentId + token + paymentConversationId + status)} — note the secret key appears both as
 * the key AND as the first message element.
 *
 * <p><b>Event-type tolerance is a pinned requirement, not a convenience:</b> iyzico's own docs
 * spell the CF event type {@code CHECKOUT_FORM_AUTH} on one page and {@code CHECKOUTFORM_AUTH} on
 * another, so a strict enum on either spelling drops the other one's deliveries on the floor.
 */
class IyzicoSignatureTest {

    private static final String SECRET_KEY = "it_dummy_iyzico_secret_never_real";

    private final IyzicoBillingProvider provider = new IyzicoBillingProvider(
            properties(SECRET_KEY), new ObjectMapper());

    // ------------------------------------------------------------------ the signature itself

    @Test
    @DisplayName("a payload signed per the documented v3 formula verifies and maps to CHECKOUT_COMPLETED")
    void selfComputedVectorVerifies() {
        String payload = payload("CHECKOUT_FORM_AUTH", "SUCCESS", "tok-vector-0001", "123456789",
                "42", "ref-0001");
        String signature = independentSignature("CHECKOUT_FORM_AUTH", "123456789", "tok-vector-0001",
                "42", "SUCCESS");

        BillingEvent event = provider.verifyAndParse(payload, signature);

        assertThat(event.eventId())
                .as("iyziReferenceCode is the dedup key")
                .isEqualTo("ref-0001");
        assertThat(event.type()).isEqualTo(BillingEvent.Type.CHECKOUT_COMPLETED);
        assertThat(event.externalSessionId())
                .as("the token is the session handle the payment row was keyed with")
                .isEqualTo("tok-vector-0001");
        assertThat(event.externalPaymentId()).isEqualTo("123456789");
        assertThat(event.rawPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("an UPPERCASE hex signature of the correct MAC still verifies (case is not forgery)")
    void uppercaseHexIsAccepted() {
        String payload = payload("CHECKOUT_FORM_AUTH", "SUCCESS", "tok-case", "1", "42", "ref-case");
        String signature = independentSignature("CHECKOUT_FORM_AUTH", "1", "tok-case", "42", "SUCCESS");

        BillingEvent event = provider.verifyAndParse(payload, signature.toUpperCase(java.util.Locale.ROOT));

        assertThat(event.type()).isEqualTo(BillingEvent.Type.CHECKOUT_COMPLETED);
    }

    @Test
    @DisplayName("a tampered field (status flipped after signing) is refused")
    void tamperedStatusIsRefused() {
        // Signed as FAILURE, delivered claiming SUCCESS: the exact upgrade an attacker wants.
        String signature = independentSignature("CHECKOUT_FORM_AUTH", "1", "tok-tamper", "42", "FAILURE");
        String payload = payload("CHECKOUT_FORM_AUTH", "SUCCESS", "tok-tamper", "1", "42", "ref-tamper");

        assertThatThrownBy(() -> provider.verifyAndParse(payload, signature))
                .isInstanceOf(BillingSignatureException.class)
                .hasMessageContaining("X-IYZ-SIGNATURE-V3");
    }

    @Test
    @DisplayName("a missing signature header is refused before anything is read")
    void missingHeaderIsRefused() {
        String payload = payload("CHECKOUT_FORM_AUTH", "SUCCESS", "tok-x", "1", "42", "ref-x");

        assertThatThrownBy(() -> provider.verifyAndParse(payload, null))
                .isInstanceOf(BillingSignatureException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> provider.verifyAndParse(payload, "  "))
                .isInstanceOf(BillingSignatureException.class);
    }

    @Test
    @DisplayName("a body that is not JSON is refused as unverifiable")
    void nonJsonBodyIsRefused() {
        assertThatThrownBy(() -> provider.verifyAndParse("merchant_oid=ZP1&status=success", "aa"))
                .isInstanceOf(BillingSignatureException.class);
        assertThatThrownBy(() -> provider.verifyAndParse("[1,2,3]", "aa"))
                .isInstanceOf(BillingSignatureException.class);
    }

    @Test
    @DisplayName("a verified payload with neither iyziReferenceCode nor token is refused (no dedup identity)")
    void noDedupIdentityIsRefused() {
        String signature = independentSignature("CHECKOUT_FORM_AUTH", "1", "", "42", "SUCCESS");
        String payload = "{\"iyziEventType\":\"CHECKOUT_FORM_AUTH\",\"status\":\"SUCCESS\","
                + "\"iyziPaymentId\":\"1\",\"paymentConversationId\":\"42\"}";

        assertThatThrownBy(() -> provider.verifyAndParse(payload, signature))
                .isInstanceOf(BillingSignatureException.class)
                .hasMessageContaining("neither");
    }

    @Test
    @DisplayName("a missing iyziReferenceCode falls back to token:STATUS as the dedup key")
    void missingReferenceCodeFallsBackToTokenAndStatus() {
        String signature = independentSignature("CHECKOUT_FORM_AUTH", "1", "tok-noref", "42", "SUCCESS");
        String payload = "{\"iyziEventType\":\"CHECKOUT_FORM_AUTH\",\"status\":\"SUCCESS\","
                + "\"iyziPaymentId\":\"1\",\"paymentConversationId\":\"42\",\"token\":\"tok-noref\"}";

        BillingEvent event = provider.verifyAndParse(payload, signature);

        assertThat(event.eventId())
                .as("the PayTR-shaped fallback: exact replays still dedup")
                .isEqualTo("tok-noref:SUCCESS");
    }

    // ------------------------------------------------------------------ event-type tolerance

    @Test
    @DisplayName("both documented CF spellings — CHECKOUT_FORM_AUTH and CHECKOUTFORM_AUTH — are accepted")
    void bothDocumentedEventTypeSpellingsAreAccepted() {
        for (String spelling : new String[] {"CHECKOUT_FORM_AUTH", "CHECKOUTFORM_AUTH",
                "checkout_form_auth", "CheckoutForm_Auth"}) {
            String payload = payload(spelling, "SUCCESS", "tok-sp", "1", "42", "ref-sp-" + spelling);
            String signature = independentSignature(spelling, "1", "tok-sp", "42", "SUCCESS");
            assertThat(provider.verifyAndParse(payload, signature).type())
                    .as("spelling '%s' must map to CHECKOUT_COMPLETED — iyzico's docs use more "
                            + "than one, so a strict enum silently drops deliveries", spelling)
                    .isEqualTo(BillingEvent.Type.CHECKOUT_COMPLETED);
        }
    }

    @Test
    @DisplayName("CF FAILURE (and the FAILED spelling) maps to PAYMENT_FAILED; unknown types to UNKNOWN")
    void statusAndTypeMappings() {
        assertThat(IyzicoBillingProvider.mapEventType("CHECKOUT_FORM_AUTH", "FAILURE"))
                .isEqualTo(BillingEvent.Type.PAYMENT_FAILED);
        assertThat(IyzicoBillingProvider.mapEventType("CHECKOUTFORM_AUTH", "failed"))
                .isEqualTo(BillingEvent.Type.PAYMENT_FAILED);
        assertThat(IyzicoBillingProvider.mapEventType("CHECKOUT_FORM_AUTH", "INIT_THREEDS"))
                .as("a status this version does not act on is stored, not erred on")
                .isEqualTo(BillingEvent.Type.UNKNOWN);
        assertThat(IyzicoBillingProvider.mapEventType("API_AUTH", "SUCCESS"))
                .as("non-CF event families are stored as UNKNOWN/IGNORED")
                .isEqualTo(BillingEvent.Type.UNKNOWN);
        assertThat(IyzicoBillingProvider.mapEventType(null, null))
                .isEqualTo(BillingEvent.Type.UNKNOWN);
    }

    // ------------------------------------------------------------------ the activation predicate

    @Test
    @DisplayName("retrieve maps to collected ONLY on status=success + paymentStatus=SUCCESS + fraudStatus=1")
    void retrieveMappingIsTheActivationPredicate() {
        assertThat(IyzicoBillingProvider.mapRetrieve("success", "SUCCESS", 1, "ipay-1", null))
                .satisfies(confirmation -> {
                    assertThat(confirmation.collected()).isTrue();
                    assertThat(confirmation.externalPaymentId()).isEqualTo("ipay-1");
                });
        assertThat(IyzicoBillingProvider.mapRetrieve("success", "SUCCESS", 0, "ipay-1", null)
                .collected())
                .as("fraudStatus=0 is UNDER REVIEW: activating it and having the review decline "
                        + "would be an activation the money never bought — not collected YET")
                .isFalse();
        assertThat(IyzicoBillingProvider.mapRetrieve("success", "SUCCESS", -1, "ipay-1", null)
                .collected())
                .as("fraudStatus=-1 is declined")
                .isFalse();
        assertThat(IyzicoBillingProvider.mapRetrieve("success", "SUCCESS", null, "ipay-1", null)
                .collected())
                .as("an absent fraudStatus must fail CLOSED, never default to approved")
                .isFalse();
        assertThat(IyzicoBillingProvider.mapRetrieve("success", "FAILURE", 1, null, "declined")
                .collected())
                .isFalse();
        assertThat(IyzicoBillingProvider.mapRetrieve("failure", "SUCCESS", 1, "ipay-1", "no token")
                .collected())
                .as("the OUTER status is the retrieve call's own verdict; a failed retrieve "
                        + "confirms nothing whatever the inner fields claim")
                .isFalse();
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * The documented formula, computed with plain {@code javax.crypto} — independent of the
     * production implementation on purpose.
     */
    private static String independentSignature(String eventType, String iyziPaymentId, String token,
                                               String conversationId, String status) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((SECRET_KEY + eventType + iyziPaymentId + token
                    + conversationId + status).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot compute the test signature", ex);
        }
    }

    private static String payload(String eventType, String status, String token,
                                  String iyziPaymentId, String conversationId, String referenceCode) {
        return ("{\"iyziEventType\":\"%s\",\"status\":\"%s\",\"token\":\"%s\","
                + "\"iyziPaymentId\":\"%s\",\"paymentConversationId\":\"%s\","
                + "\"iyziReferenceCode\":\"%s\",\"merchantId\":123,\"paymentId\":\"%s\","
                + "\"iyziEventTime\":1700000000000}")
                .formatted(eventType, status, token, iyziPaymentId, conversationId, referenceCode,
                        iyziPaymentId);
    }

    private static BillingIyzicoProperties properties(String secretKey) {
        BillingIyzicoProperties properties = new BillingIyzicoProperties();
        properties.setEnabled(true);
        properties.setApiKey("sandbox-dummy-api-key");
        properties.setSecretKey(secretKey);
        properties.setBaseUrl("https://sandbox-api.iyzipay.com");
        return properties;
    }
}
