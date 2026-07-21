package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.tenancy.Tenant;
import com.mycompanyname.zero.tenancy.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant isolation for the 2FA login flow. The design deliberately gives the challenge/recovery tables
 * NO {@code tenant_id} column (a Hibernate {@code @Filter} would fail closed on the anonymous,
 * header-optional verify path) — so the tenant of a completed 2FA login must instead come, correctly,
 * from the USER the challenge resolves to. These tests prove exactly that, in both directions.
 *
 * <p>Tenant users are enrolled through the repository so they need no subscription (the pre-login
 * endpoints are subscription-exempt, so login/verify/me all work for them regardless).
 *
 * <p><b>What the isolation actually rests on.</b> The challenge resolves its user by primary key, and a
 * primary-key load bypasses the Hibernate tenant {@code @Filter} — so the resolution is by id, and the
 * minted token's tenant comes authoritatively from THAT user's own {@code tenant_id} (JwtService reads
 * it), never from the X-Tenant header on the verify call. The header therefore cannot be used to cross
 * tenants: redeeming tenant A's challenge under tenant B's header still yields a tenant-A token, which
 * {@code AuthenticatedTenantFilter} then only lets tenant A read. That is the property proven below.
 */
class TwoFactorTenantIsolationIT extends AbstractTwoFactorIT {

    private static final String TENANT_A = "twofa-a";
    private static final String TENANT_B = "twofa-b";
    private static final String PASSWORD = "Tenant-2FA-1!";

    @Autowired
    private TenantRepository tenantRepository;

    @Test
    void aTenantUsersTwoFactorLoginMintsATokenCarryingItsOwnTenant() {
        long tenantId = ensureTenant(TENANT_A);
        TwoFactorUser user = createUserWithTwoFactor(tenantId, PASSWORD, 2);

        String challenge = loginForChallenge(TENANT_A, user);
        ResponseEntity<JsonNode> verified = verify(TENANT_A, challenge, currentTotp(user.secret()));
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);

        String accessToken = verified.getBody().path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        JsonNode identity = me(TENANT_A, accessToken).getBody();
        assertThat(identity.path("username").asText()).isEqualTo(user.username());
        assertThat(identity.path("tenantId").asLong())
                .as("the minted token must carry the tenant of the user the challenge resolved to")
                .isEqualTo(tenantId);
    }

    @Test
    void theMintedTokensTenantComesFromTheUserNotTheVerifyHeader() {
        long tenantA = ensureTenant(TENANT_A);
        long tenantB = ensureTenant(TENANT_B);
        assertThat(tenantA).isNotEqualTo(tenantB);

        TwoFactorUser user = createUserWithTwoFactor(tenantA, PASSWORD, 2);
        String challenge = loginForChallenge(TENANT_A, user);

        // Redeem tenant A's challenge while sending tenant B's header. The challenge resolves its user
        // by id, so verification succeeds — but the token it mints is tenant A's, never tenant B's.
        ResponseEntity<JsonNode> verified = verify(TENANT_B, challenge, currentTotp(user.secret()));
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = verified.getBody().path("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        // Proof the token is tenant A's, not B's: tenant B's header is a mismatch (403); tenant A's is
        // accepted and reports tenant A. The X-Tenant header on verify bought no cross-tenant access.
        assertThat(me(TENANT_B, accessToken).getStatusCode())
                .as("the minted token must NOT be usable as a tenant-B token")
                .isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode identity = me(TENANT_A, accessToken).getBody();
        assertThat(identity.path("tenantId").asLong()).isEqualTo(tenantA);
        assertThat(identity.path("username").asText()).isEqualTo(user.username());
    }

    @Test
    void usersInDifferentTenantsResolveIndependently() {
        long tenantA = ensureTenant(TENANT_A);
        long tenantB = ensureTenant(TENANT_B);
        TwoFactorUser userA = createUserWithTwoFactor(tenantA, PASSWORD, 2);
        TwoFactorUser userB = createUserWithTwoFactor(tenantB, PASSWORD, 2);

        JsonNode meA = completeTwoFactor(TENANT_A, userA);
        JsonNode meB = completeTwoFactor(TENANT_B, userB);

        assertThat(meA.path("username").asText()).isEqualTo(userA.username());
        assertThat(meA.path("tenantId").asLong()).isEqualTo(tenantA);
        assertThat(meB.path("username").asText()).isEqualTo(userB.username());
        assertThat(meB.path("tenantId").asLong()).isEqualTo(tenantB);
        assertThat(tenantA).isNotEqualTo(tenantB);
    }

    // --- helpers ---------------------------------------------------------------------------

    private JsonNode completeTwoFactor(String tenant, TwoFactorUser user) {
        String challenge = loginForChallenge(tenant, user);
        ResponseEntity<JsonNode> verified = verify(tenant, challenge, currentTotp(user.secret()));
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        return me(tenant, verified.getBody().path("accessToken").asText()).getBody();
    }

    private String loginForChallenge(String tenant, TwoFactorUser user) {
        ResponseEntity<JsonNode> response = login(tenant, user.username(), user.password());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("twoFactorRequired").asBoolean()).isTrue();
        String token = response.getBody().path("twoFactor").path("challengeToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    /** Creates the tenant idempotently (context is shared) and returns its id. */
    private long ensureTenant(String name) {
        HttpHeaders hostHeaders = new HttpHeaders();
        hostHeaders.setBearerAuth(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD));
        hostHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name, "displayName", name,
                        "adminEmail", "admin@" + name + ".local"), hostHeaders), JsonNode.class);
        assertThat(created.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
        Tenant tenant = tenantRepository.findByNameIgnoreCase(name).orElseThrow();
        return tenant.getId();
    }
}
