package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The managed-credential admin surface (ADR-0020), proven in the DEFAULT context — no billing
 * environment flag is on, so everything a test observes here is the DB path: authorization first
 * (house rule), then the write-only contract (values in, never out; ciphertext at rest), then the
 * live consequences WITHOUT a restart — the PayTR webhook surface coming up because credentials
 * were SAVED, verifying against the SAVED key (DB beats environment at call time), and going back
 * down when the row is deleted.
 *
 * <p>Every test cleans {@code billing_provider_credentials} afterwards: the database outlives this
 * class, and a leftover row would flip {@code PayTRDisabledSurfaceIT}'s 404 into a 200 — the very
 * runtime-enablement this slice adds, aimed at the wrong test.
 */
class BillingProviderCredentialsAdminIT extends AbstractSaasIT {

    private static final String PROVIDERS_PATH = "/api/billing/providers";
    private static final String PAYTR_CREDENTIALS_PATH = PROVIDERS_PATH + "/paytr/credentials";
    private static final String PAYTR_WEBHOOK_PATH = "/api/billing/webhook/paytr";

    private static final String DB_MERCHANT_ID = "654321";
    private static final String DB_MERCHANT_KEY = "db-managed-merchant-key-never-in-env";
    private static final String DB_MERCHANT_SALT = "db-managed-merchant-salt";

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanManagedCredentials() {
        jdbc.update("delete from billing_provider_credentials");
    }

    // --- authorization: the negative tests come first (house rule) ---

    @Test
    @DisplayName("a tenant admin gets 403 on every managed-credential endpoint — host boundary, not privilege level")
    void tenantAdminIsForbiddenEverywhere() {
        HttpHeaders tenant = tenantAdmin();
        assertThat(exchange(HttpMethod.GET, PROVIDERS_PATH, null, tenant).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.PUT, PAYTR_CREDENTIALS_PATH,
                Map.of("enabled", false, "credentials", Map.of("merchantId", "x")), tenant)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.DELETE, PAYTR_CREDENTIALS_PATH, null, tenant).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.PUT, PROVIDERS_PATH + "/order",
                Map.of("order", List.of("paytr")), tenant).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("select count(*) from billing_provider_credentials",
                Integer.class)).as("a refused write must store nothing").isZero();
    }

    @Test
    @DisplayName("an anonymous caller gets 401 — the admin surface is closed on the chain")
    void anonymousCallerIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = restTemplate.exchange(PROVIDERS_PATH, HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- the write-only contract ---

    @Test
    @DisplayName("saved credentials are ciphertext at rest and appear in no response — only masked status comes back")
    void credentialsAreEncryptedAtRestAndNeverEchoed() {
        ResponseEntity<JsonNode> put = putPaytrCredentials(true);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody()).isNotNull();
        String putBody = put.getBody().toString();
        assertThat(putBody)
                .as("the PUT response must not round-trip any submitted value")
                .doesNotContain(DB_MERCHANT_KEY)
                .doesNotContain(DB_MERCHANT_SALT)
                .doesNotContain(DB_MERCHANT_ID);

        String atRest = jdbc.queryForObject(
                "select credentials_secret from billing_provider_credentials where provider = 'paytr'",
                String.class);
        assertThat(atRest).as("the row must exist with a stored secret").isNotBlank();
        assertThat(atRest)
                .as("at rest the credential set is ONE ciphertext, no plaintext fragment")
                .doesNotContain(DB_MERCHANT_KEY)
                .doesNotContain(DB_MERCHANT_SALT)
                .doesNotContain("merchantKey");
        // Well-formed FieldEncryptionService output: base64 of at least IV (12) + tag (16) bytes.
        assertThat(Base64.getDecoder().decode(atRest).length).isGreaterThan(28);

        ResponseEntity<JsonNode> get = exchange(HttpMethod.GET, PROVIDERS_PATH, null, host());
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody()).isNotNull();
        assertThat(get.getBody().toString())
                .as("the status read is masked — never a stored value")
                .doesNotContain(DB_MERCHANT_KEY)
                .doesNotContain(DB_MERCHANT_SALT);
        JsonNode paytr = statusOf(get.getBody(), "paytr");
        assertThat(paytr.path("enabled").asBoolean()).isTrue();
        assertThat(paytr.path("configured").asBoolean()).isTrue();
        assertThat(paytr.path("source").asText()).isEqualTo("db");
        assertThat(paytr.path("maskedHint").asText())
                .as("hint = **** + last four of the least-secret identifier (merchant number)")
                .isEqualTo("****" + DB_MERCHANT_ID.substring(DB_MERCHANT_ID.length() - 4));
        assertThat(paytr.path("configuredFields")).isNotEmpty();
    }

    @Test
    @DisplayName("an unknown credential field is a 400 and enabling without the required fields is a 400")
    void writeValidationIsLoud() {
        ResponseEntity<JsonNode> unknownField = exchange(HttpMethod.PUT, PAYTR_CREDENTIALS_PATH,
                Map.of("enabled", false, "credentials", Map.of("apiKey", "wrong-vocabulary")),
                host());
        assertThat(unknownField.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> incompleteEnable = exchange(HttpMethod.PUT, PAYTR_CREDENTIALS_PATH,
                Map.of("enabled", true, "credentials", Map.of("merchantId", DB_MERCHANT_ID)),
                host());
        assertThat(incompleteEnable.getStatusCode())
                .as("enabling must restate the boot validator's completeness rule at write time")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> unknownProvider = exchange(HttpMethod.PUT,
                PROVIDERS_PATH + "/nonexistent/credentials",
                Map.of("enabled", false, "credentials", Map.of()), host());
        assertThat(unknownProvider.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- DB beats environment, at call time, without a restart ---

    @Test
    @DisplayName("the PayTR webhook comes up on SAVED credentials, verifies against the SAVED key, and goes back down on DELETE")
    void savedCredentialsDriveTheWebhookSurfaceWithoutARestart() {
        // Before any row: the fresh-clone state this context proves everywhere else — 404.
        assertThat(postPaytrNotification(validNotification(DB_MERCHANT_KEY, DB_MERCHANT_SALT))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(putPaytrCredentials(true).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verified against the SAVED key — resolved per call, so no restart happened between the
        // 404 above and this 200/OK (the PayTR settlement contract: the literal body "OK").
        ResponseEntity<String> verified =
                postPaytrNotification(validNotification(DB_MERCHANT_KEY, DB_MERCHANT_SALT));
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody()).isEqualTo("OK");

        // A notification hashed with any OTHER key must fail verification: the stored key is the
        // one in force, not the (blank) environment one and not the attacker's.
        assertThat(postPaytrNotification(validNotification("some-other-key", DB_MERCHANT_SALT))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Disabling closes NEW checkouts only — the webhook surface must stay open for in-flight
        // payments of this provider.
        assertThat(putPaytrEnabled(false).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postPaytrNotification(validNotification(DB_MERCHANT_KEY, DB_MERCHANT_SALT))
                .getStatusCode())
                .as("disabled = closed to new checkouts, NOT to webhooks (ADR-0020)")
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> checkout =
                postCheckout(tenantId(DEFAULT_TENANT), 999_999L, host());
        assertThat(checkout.getStatusCode())
                .as("with the only credentialed provider disabled, checkout has no provider: 404 "
                        + "before any edition lookup")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // DELETE drops the row: environment behaviour (nothing configured -> 404) returns.
        ResponseEntity<JsonNode> delete =
                exchange(HttpMethod.DELETE, PAYTR_CREDENTIALS_PATH, null, host());
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(postPaytrNotification(validNotification(DB_MERCHANT_KEY, DB_MERCHANT_SALT))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the failover order round-trips, creating order-only rows without enabling anything")
    void orderRoundTripsWithoutEnabling() {
        ResponseEntity<JsonNode> put = exchange(HttpMethod.PUT, PROVIDERS_PATH + "/order",
                Map.of("order", List.of("iyzico", "paytr")), host());
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> get = exchange(HttpMethod.GET, PROVIDERS_PATH, null, host());
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode iyzico = statusOf(get.getBody(), "iyzico");
        JsonNode paytr = statusOf(get.getBody(), "paytr");
        assertThat(iyzico.path("displayOrder").asInt()).isZero();
        assertThat(paytr.path("displayOrder").asInt()).isEqualTo(1);
        assertThat(iyzico.path("enabled").asBoolean())
                .as("an order-only row must not enable a provider").isFalse();
        assertThat(iyzico.path("configured").asBoolean()).isFalse();

        ResponseEntity<JsonNode> duplicate = exchange(HttpMethod.PUT, PROVIDERS_PATH + "/order",
                Map.of("order", List.of("paytr", "paytr")), host());
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- plumbing ---

    /** {@code AbstractBillingIT}'s checkout POST, local: this IT lives in the default context. */
    private ResponseEntity<JsonNode> postCheckout(long tenantId, long editionId, HttpHeaders headers) {
        Map<String, Object> body = Map.of(
                "tenantId", tenantId,
                "editionId", editionId,
                "billingPeriod", "MONTHLY",
                "successUrl", "https://app.example.com/billing/success",
                "cancelUrl", "https://app.example.com/billing/cancel");
        return restTemplate.exchange("/api/billing/checkout", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> putPaytrCredentials(boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        body.put("credentials", Map.of(
                "merchantId", DB_MERCHANT_ID,
                "merchantKey", DB_MERCHANT_KEY,
                "merchantSalt", DB_MERCHANT_SALT,
                "testMode", "true"));
        return exchange(HttpMethod.PUT, PAYTR_CREDENTIALS_PATH, body, host());
    }

    /** Flips the enabled flag alone — blank credentials mean "keep" (the write-only merge rule). */
    private ResponseEntity<JsonNode> putPaytrEnabled(boolean enabled) {
        return exchange(HttpMethod.PUT, PAYTR_CREDENTIALS_PATH,
                Map.of("enabled", enabled, "credentials", Map.of()), host());
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, Object body,
                                              HttpHeaders headers) {
        return restTemplate.exchange(path, method,
                body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    private JsonNode statusOf(JsonNode listBody, String provider) {
        assertThat(listBody).isNotNull();
        for (JsonNode node : listBody) {
            if (provider.equals(node.path("provider").asText())) {
                return node;
            }
        }
        throw new AssertionError("provider missing from the status list: " + provider);
    }

    /**
     * A PayTR {@code status=failed} notification (independent HMAC implementation, the
     * {@code AbstractBillingIT} strategy). {@code failed} on purpose: it exercises the full
     * verification path but needs no payment row — a completed event without a row is a loud 500
     * by design, which is not what these tests are about.
     */
    private String validNotification(String merchantKey, String merchantSalt) {
        String merchantOid = "ZPADMINIT" + System.nanoTime();
        String status = "failed";
        String totalAmount = "999";
        String hash = base64Hmac(merchantOid + merchantSalt + status + totalAmount, merchantKey);
        return "merchant_oid=" + merchantOid + "&status=" + status
                + "&total_amount=" + totalAmount
                + "&failed_reason_code=1&failed_reason_msg=test"
                + "&hash=" + URLEncoder.encode(hash, StandardCharsets.UTF_8);
    }

    private ResponseEntity<String> postPaytrNotification(String formBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return restTemplate.exchange(PAYTR_WEBHOOK_PATH, HttpMethod.POST,
                new HttpEntity<>(formBody, headers), String.class);
    }

    private static String base64Hmac(String message, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot compute the test notification hash", ex);
        }
    }
}
