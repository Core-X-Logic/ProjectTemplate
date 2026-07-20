package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checkout initiation: authorization first (house rule — every endpoint ships with its negative
 * authorization test, and SaaS doubly so per ADR-0015: the permission IS the isolation), then the
 * NOT_PAID payment-row contract.
 */
@Import(BillingTestProviderConfig.class)
class CheckoutEndpointIT extends AbstractBillingIT {

    @Test
    @DisplayName("a tenant admin without subscriptions.manage gets 403 and no payment row is created")
    void tenantAdminWithoutThePermissionIsForbidden() {
        long editionId = createPaidEdition("billing-co-authz", "15.00", null, 0, 7);
        long ownTenantId = tenantId(DEFAULT_TENANT);
        Integer paymentsBefore = jdbc.queryForObject(
                "select count(*) from payments where target_edition_id = ?", Integer.class, editionId);

        // The body is VALID on purpose: binding and @Valid run before @PreAuthorize, so a broken
        // body would 400 and prove nothing about authorization (SaasAuthorizationIT pattern).
        ResponseEntity<JsonNode> response = postCheckout(ownTenantId, editionId, tenantAdmin());

        assertThat(response.getStatusCode())
                .as("checkout is host-only, even for the caller's own tenant — a tenant must not "
                        + "start a purchase that names its own target edition (got %s: %s)",
                        response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject(
                "select count(*) from payments where target_edition_id = ?", Integer.class, editionId))
                .as("a refused checkout must leave no payment row behind")
                .isEqualTo(paymentsBefore);
    }

    @Test
    @DisplayName("an anonymous caller gets 401 — the checkout route is closed on the chain")
    void anonymousCallerIsRejectedByTheFilterChain() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/billing/checkout",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("tenantId", 1, "editionId", 1, "billingPeriod", "MONTHLY",
                        "successUrl", "https://x.example/s", "cancelUrl", "https://x.example/c"),
                        headers),
                JsonNode.class);
        assertThat(response.getStatusCode())
                .as("only the webhook is anonymous under /api/billing; checkout must not inherit it")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the host admin starts a checkout and gets a NOT_PAID payment row with the price snapshot")
    void hostAdminStartsCheckoutAndGetsANotPaidPaymentRow() {
        long editionId = createPaidEdition("billing-co-ok", "20.00", null, 0, 7);
        long tenantId = ensureTenant("billing-co-tenant");

        JsonNode body = startCheckoutOk(tenantId, editionId);
        assertThat(body.path("sessionId").asText()).startsWith("cs_test_");
        assertThat(body.path("url").asText()).isNotBlank();
        assertThat(body.path("paymentId").isIntegralNumber()).isTrue();

        Map<String, Object> payment = paymentRowBySession(body.path("sessionId").asText());
        assertThat(payment.get("status")).isEqualTo("NOT_PAID");
        assertThat(payment.get("paid_at")).isNull();
        assertThat(payment.get("tenant_id")).isEqualTo(tenantId);
        assertThat((BigDecimal) payment.get("amount"))
                .usingComparator(BigDecimal::compareTo)
                .as("the payment snapshots the edition price at checkout time (ADR-0012)")
                .isEqualTo(new BigDecimal("20.00"));
        assertThat(payment.get("currency")).isEqualTo("USD");
        assertThat(payment.get("period")).isEqualTo("MONTHLY");
        assertThat(payment.get("subscription_id")).isNotNull();

        var handedToProvider = BillingTestProviderConfig.LAST_CHECKOUT_REQUEST.get();
        assertThat(handedToProvider).isNotNull();
        assertThat(handedToProvider.paymentId()).isEqualTo(body.path("paymentId").asLong());
        assertThat(handedToProvider.amount())
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("20.00"));
    }

    @Test
    @DisplayName("a free edition is refused — there is nothing to collect")
    void freeEditionCheckoutIsRejected() {
        long freeEditionId = createFreeEdition("billing-co-free");
        long tenantId = ensureTenant("billing-co-tenant-free");

        ResponseEntity<JsonNode> response = postCheckout(tenantId, freeEditionId, host());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }
}
