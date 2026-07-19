package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.saas.subscription.SubscriptionService;
import com.mycompanyname.zero.saas.subscription.SubscriptionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the subscription validity gate.
 *
 * <p>The gate lives in {@code TenantResolverFilter}, which runs <em>before</em> authentication. Two
 * consequences are exercised here: an expired tenant is refused on business endpoints no matter who
 * is calling, and the exemption list still lets it authenticate and read its own subscription — the
 * only way it could ever recover.
 *
 * <p>Because the check precedes authentication, the unauthenticated cases below are not a shortcut:
 * they hit exactly the same filter code path that an authenticated request does, while needing no
 * user inside the tenant under test.
 */
class SubscriptionGuardIT extends AbstractSaasIT {

    private static final String STANDARD_EDITION = "Standard";
    private static final String BUSINESS_ROUTE = "/api/users";

    @Autowired
    private SubscriptionService subscriptionService;

    @AfterEach
    void restoreTheDefaultTenantSubscription() {
        // The seeded free package puts the tenant back to ACTIVE (provisioning, not a transition,
        // so it works from every state).
        assignEditionOk(tenantId(DEFAULT_TENANT), editionIdByName(STANDARD_EDITION), null, false);
    }

    @Test
    void anExpiredTenantIsRefusedOnBusinessEndpointsButKeepsReachingItsSubscription() {
        long tenantId = tenantId(DEFAULT_TENANT);
        // Obtained while the subscription is still valid — and login stays reachable afterwards,
        // which the next test asserts explicitly.
        HttpHeaders admin = tenantAdmin();
        assertThat(get(BUSINESS_ROUTE, admin).getStatusCode())
                .as("precondition: an ACTIVE subscription must not be gated")
                .isEqualTo(HttpStatus.OK);

        subscriptionService.transition(tenantId, SubscriptionStatus.EXPIRED, "TEST", "test");

        ResponseEntity<JsonNode> blocked = get(BUSINESS_ROUTE, admin);
        assertThat(blocked.getStatusCode())
                .as("an expired tenant must lose access to the application")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blocked.getBody()).isNotNull();
        assertThat(blocked.getBody().path("code").asText())
                .as("the code must distinguish 'you did not pay' from 'you lack the permission'")
                .isEqualTo("SUBSCRIPTION_INVALID");

        assertThat(get("/api/subscriptions/me", admin).getStatusCode())
                .as("the tenant must still be able to see the subscription it has to renew")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void anExpiredTenantCanStillAuthenticate() {
        long tenantId = tenantId(DEFAULT_TENANT);
        subscriptionService.transition(tenantId, SubscriptionStatus.EXPIRED, "TEST", "test");

        assertThat(login(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD).getStatusCode())
                .as("locking an expired tenant out of login would make recovery impossible")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aTenantAwaitingPaymentIsGatedTheSameWayAsAnExpiredOne() {
        long tenantId = tenantId(DEFAULT_TENANT);
        HttpHeaders admin = tenantAdmin();
        long paidEditionId = createPaidEdition("guard-pending", "39.00", null, 0, 0);

        // Assigning a paid package without a trial parks the subscription in PENDING_PAYMENT (S3).
        assignEditionOk(tenantId, paidEditionId, "MONTHLY", false);

        ResponseEntity<JsonNode> blocked = get(BUSINESS_ROUTE, admin);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blocked.getBody()).isNotNull();
        assertThat(blocked.getBody().path("code").asText()).isEqualTo("SUBSCRIPTION_INVALID");
    }

    @Test
    void aCancelledTenantKeepsAccessUntilItsPeriodEnds() {
        long tenantId = tenantId(DEFAULT_TENANT);
        HttpHeaders admin = tenantAdmin();
        long paidEditionId = createPaidEdition("guard-cancel", "39.00", null, 0, 0);
        assignEditionOk(tenantId, paidEditionId, "MONTHLY", false);
        post("/api/subscriptions/" + tenantId + "/activate");
        post("/api/subscriptions/" + tenantId + "/cancel");

        assertThat(get(BUSINESS_ROUTE, admin).getStatusCode())
                .as("cancellation is not expiry: the tenant paid for the period and keeps it")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void theGateAppliesBeforeAuthenticationAndOnlyOutsideTheExemptPaths() {
        long tenantId = tenantId(DEFAULT_TENANT);
        subscriptionService.transition(tenantId, SubscriptionStatus.EXPIRED, "TEST", "test");

        // No credentials at all: the filter runs first, so the subscription verdict wins over 401.
        assertThat(code(getWithTenantHeaderOnly(BUSINESS_ROUTE)))
                .isEqualTo("SUBSCRIPTION_INVALID");

        // Exempt paths fall through to the security chain, which answers 401 for a missing token.
        assertThat(getWithTenantHeaderOnly("/api/subscriptions/me").getStatusCode())
                .as("/api/subscriptions/me is exempt, so it must reach authentication instead")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aHostRequestIsNeverSubjectToTheGate() {
        subscriptionService.transition(
                tenantId(DEFAULT_TENANT), SubscriptionStatus.EXPIRED, "TEST", "test");

        assertThat(get(BUSINESS_ROUTE, host()).getStatusCode())
                .as("a host request carries no tenant, so no subscription applies to it")
                .isEqualTo(HttpStatus.OK);
    }

    // --- helpers ---

    private ResponseEntity<JsonNode> get(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> getWithTenantHeaderOnly(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TENANT_HEADER, DEFAULT_TENANT);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private String code(ResponseEntity<JsonNode> response) {
        assertThat(response.getBody())
                .as("expected a ProblemDetail body, got status %s", response.getStatusCode())
                .isNotNull();
        return response.getBody().path("code").asText();
    }

    private long editionIdByName(String name) {
        ResponseEntity<JsonNode> list = restTemplate.exchange("/api/editions?size=100",
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode node : pageContent(list.getBody())) {
            if (name.equals(node.path("name").asText())) {
                return node.path("id").asLong();
            }
        }
        throw new AssertionError("edition not found: " + name);
    }
}
