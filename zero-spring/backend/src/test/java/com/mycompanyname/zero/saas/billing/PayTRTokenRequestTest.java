package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Offline pins for the pure half of {@link PayTRBillingProvider} — the checkout-token formula, the
 * notification-hash formula, the kuruş conversion, the basket shape and the {@code merchant_oid}
 * rules. No network anywhere; the live get-token HTTP call stays deliberately untested (recorded
 * risk, PROD-R37 pattern).
 *
 * <p><b>The vectors are SELF-COMPUTED, outside the JVM.</b> Expected values were produced with an
 * independent HMAC-SHA256/Base64 implementation (Python {@code hmac}/{@code hashlib}/{@code base64})
 * over the documented inputs below, then hardcoded — so the test shares no code path with the
 * production formula and a sign error on either side goes red. Vector inputs, verbatim:
 *
 * <pre>
 *   merchant_id    = 123456
 *   user_ip        = 203.0.113.10
 *   merchant_oid   = ZP42TESTOID01
 *   email          = ops@example.com
 *   payment_amount = 999                  (kuruş of 9.99 TL)
 *   user_basket    = base64('[["Pro (MONTHLY)","9.99",1]]')
 *   no_installment = 0
 *   max_installment= 0
 *   currency       = TL
 *   test_mode      = 1
 *   merchant_key   = test-merchant-key    (HMAC key)
 *   merchant_salt  = test-merchant-salt   (in-message; position differs per formula)
 * </pre>
 */
class PayTRTokenRequestTest {

    private static final String MERCHANT_KEY = "test-merchant-key";
    private static final String MERCHANT_SALT = "test-merchant-salt";
    private static final String MERCHANT_OID = "ZP42TESTOID01";
    private static final String BASKET_B64 = "W1siUHJvIChNT05USExZKSIsIjkuOTkiLDFdXQ==";

    /**
     * The salt-at-END formula. A token computed with the NOTIFICATION formula's salt position over
     * these same inputs would produce a different string — the two formulas differ only there, and
     * merging them is the most likely future defect this vector exists to catch.
     */
    @Test
    @DisplayName("paytr_token matches the self-computed vector (salt appended to the message end)")
    void checkoutTokenMatchesTheOfflineVector() {
        String token = PayTRBillingProvider.checkoutToken(
                "123456", "203.0.113.10", MERCHANT_OID, "ops@example.com",
                "999", BASKET_B64, "0", "0", "TL", "1",
                MERCHANT_KEY, MERCHANT_SALT);

        assertThat(token).isEqualTo("G0IZ3V/qo38nReuI/yukiPXL0LAjzu/1WtEbFX7h7nQ=");
    }

    /** The salt-after-merchant_oid formula, pinned for both statuses over the same inputs. */
    @Test
    @DisplayName("notification hash matches the self-computed vectors for success and failed")
    void notificationHashMatchesTheOfflineVectors() {
        assertThat(PayTRBillingProvider.notificationHash(
                MERCHANT_OID, "success", "999", MERCHANT_KEY, MERCHANT_SALT))
                .isEqualTo("kZ2/cJXjj9sGGCX4B3sRgt1reEwStjPcwyNwb2ttzsE=");
        assertThat(PayTRBillingProvider.notificationHash(
                MERCHANT_OID, "failed", "999", MERCHANT_KEY, MERCHANT_SALT))
                .isEqualTo("4J/ZIbyNbNFZqzT02y68/1S8n56NRholbaVZTIIKcZ8=");
    }

    @Test
    @DisplayName("kuruş conversion: 9.99 -> 999, 150 -> 15000; a sub-kuruş amount fails loudly")
    void kurusConversionIsExactTimesOneHundred() {
        assertThat(PayTRBillingProvider.toKurus(new BigDecimal("9.99"))).isEqualTo(999L);
        assertThat(PayTRBillingProvider.toKurus(new BigDecimal("150"))).isEqualTo(15000L);
        assertThat(PayTRBillingProvider.toKurus(new BigDecimal("150.00"))).isEqualTo(15000L);
        assertThatThrownBy(() -> PayTRBillingProvider.toKurus(new BigDecimal("9.999")))
                .as("a snapshot finer than one kuruş must be a loud failure, never a rounded charge")
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("user_basket is base64 of a JSON array of [name, unitPriceString, qty]")
    void userBasketIsBase64JsonOfTheDocumentedShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String basket = PayTRBillingProvider.userBasket("Pro (MONTHLY)", new BigDecimal("9.99"), mapper);

        assertThat(basket)
                .as("the exact bytes participate in the token HMAC, so the encoding is pinned too")
                .isEqualTo(BASKET_B64);

        JsonNode decoded = mapper.readTree(Base64.getDecoder().decode(basket));
        assertThat(decoded.isArray()).isTrue();
        assertThat(decoded.get(0).get(0).asText()).isEqualTo("Pro (MONTHLY)");
        assertThat(decoded.get(0).get(1).asText()).isEqualTo("9.99");
        assertThat(decoded.get(0).get(2).asInt()).isEqualTo(1);
    }

    /**
     * The property PayTR enforces on its side: alphanumeric ONLY (no dashes — a hyphened UUID is
     * rejected), at most 64 chars. Checked across many samples including the extreme payment id.
     */
    @Test
    @DisplayName("merchant_oid is alphanumeric, ≤64 chars, carries the payment id, and does not repeat")
    void merchantOidIsAlphanumericBoundedAndUnique() {
        Pattern alphanumeric = Pattern.compile("^[a-zA-Z0-9]+$");
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (int i = 0; i < 500; i++) {
            String oid = PayTRBillingProvider.newMerchantOid(Long.MAX_VALUE);
            assertThat(oid).matches(alphanumeric);
            assertThat(oid.length()).isLessThanOrEqualTo(64);
            assertThat(oid).contains(String.valueOf(Long.MAX_VALUE));
            assertThat(seen.add(oid))
                    .as("a re-tried checkout for the same payment must get a fresh merchant_oid")
                    .isTrue();
        }
    }

    /**
     * First-wins form parsing, and why it is load-bearing: the hash covers the values the parser
     * RETURNS, so a later duplicate key must be inert — otherwise appending
     * {@code &status=success} behind a validly hashed {@code failed} notification would flip the
     * event while the hash still verified.
     */
    @Test
    @DisplayName("form parsing decodes UTF-8 percent-escapes and keeps the FIRST duplicate key")
    void formParsingIsFirstWinsAndDecodes() {
        var fields = PayTRBillingProvider.parseFormBody(
                "merchant_oid=ZP1A&status=failed&total_amount=999&hash=aGFzaA%3D%3D&status=success");

        assertThat(fields.get("merchant_oid")).isEqualTo("ZP1A");
        assertThat(fields.get("status"))
                .as("the appended contradictory duplicate must be inert")
                .isEqualTo("failed");
        assertThat(fields.get("hash")).isEqualTo("aGFzaA==");
    }
}
