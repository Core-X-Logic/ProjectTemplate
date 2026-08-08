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
 * <p><b>What the isolation actually rests on.</b> The challenge resolves its user by primary key, and
 * the minted token's tenant comes authoritatively from THAT user's own {@code tenant_id} (JwtService
 * reads it), never from the X-Tenant header on the verify call. The header therefore cannot be used to
 * cross tenants.
 *
 * <p><b>V12 made the wrong-header case stricter, and that is a real behaviour change.</b> A
 * primary-key load bypasses the Hibernate tenant {@code @Filter}, so redeeming tenant A's challenge
 * under tenant B's header used to SUCCEED and hand back a tenant-A token — safe, because
 * {@code AuthenticatedTenantFilter} then only let tenant A use it, but a cross-tenant read all the
 * same. Row-level security has no {@code find()} exemption: under tenant B's setting the tenant-A user
 * row is simply not there, so {@code verifyTwoFactor} takes its "the challenge points at a user who
 * can no longer complete 2FA" path — burn the challenge, answer the same generic 401 every other 2FA
 * failure answers. Fail-closed, no oracle, and the header now buys strictly less than before. The two
 * tests below pin both halves: the correct header still mints the user's own tenant, and the wrong one
 * is refused outright.
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

    /**
     * Redeeming tenant A's challenge under tenant B's header. Before V12 this SUCCEEDED and returned a
     * tenant-A token (harmless downstream, but a cross-tenant read: the primary-key load bypassed the
     * Hibernate filter). The row-level policy has no such exemption, so the verify path can no longer
     * see that user at all and fails closed with the generic 2FA 401.
     *
     * <p>Both properties are asserted, because "it is refused" alone would also be satisfied by an
     * endpoint that had simply broken: the SAME user, with the SAME secret, must still complete a fresh
     * challenge under its own header. That control is what makes the refusal about the header.
     */
    @Test
    void redeemingAChallengeUnderAnotherTenantsHeaderIsRefusedAndMintsNothing() {
        long tenantA = ensureTenant(TENANT_A);
        long tenantB = ensureTenant(TENANT_B);
        assertThat(tenantA).isNotEqualTo(tenantB);

        TwoFactorUser user = createUserWithTwoFactor(tenantA, PASSWORD, 2);

        ResponseEntity<JsonNode> underWrongTenant =
                verify(TENANT_B, loginForChallenge(TENANT_A, user), currentTotp(user.secret()));
        assertThat(underWrongTenant.getStatusCode())
                .as("under tenant B's setting the tenant-A user is not visible to the policy, so the "
                        + "challenge resolves to nobody and the answer is the generic 2FA rejection — "
                        + "no token, and no hint that the user exists in another tenant")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(underWrongTenant.getBody().path("accessToken").isMissingNode()
                || underWrongTenant.getBody().path("accessToken").asText("").isBlank())
                .as("a refused verification must mint nothing at all")
                .isTrue();

        // Control: the same user completes a fresh challenge under its OWN header, and the token it
        // gets is tenant A's — usable there, a 403 mismatch anywhere else.
        ResponseEntity<JsonNode> underOwnTenant =
                verify(TENANT_A, loginForChallenge(TENANT_A, user), currentTotp(user.secret()));
        assertThat(underOwnTenant.getStatusCode())
                .as("control: the refusal above is about the header, not about this user or its secret")
                .isEqualTo(HttpStatus.OK);
        String accessToken = underOwnTenant.getBody().path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
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
