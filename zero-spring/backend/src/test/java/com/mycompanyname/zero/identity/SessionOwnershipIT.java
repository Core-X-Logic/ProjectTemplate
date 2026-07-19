package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.tenancy.Tenant;
import com.mycompanyname.zero.tenancy.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-39 / R-40 — the ownership bind between a caller's principal and the session artefact it acts on.
 *
 * <p>Both endpoints are reachable by <em>any</em> authenticated principal ({@code /api/auth/logout}
 * falls through to {@code anyRequest().authenticated()}; {@code /api/auth/impersonate/authenticate}
 * carries no {@code @PreAuthorize} beyond that). Authentication alone therefore says nothing about
 * <em>whose</em> refresh token or <em>whose</em> hand-off ticket is being presented. Without the
 * bind, holding the opaque string is the whole authorization:
 *
 * <ul>
 *   <li><b>R-39</b> — a cross-user availability attack: anyone who obtains another user's refresh
 *       token string can end that user's session.</li>
 *   <li><b>R-40</b> — a 30 second window in which a leaked impersonation ticket (proxy log, browser
 *       history) can be redeemed by an actor other than the one it was minted for.</li>
 * </ul>
 *
 * <p><b>Why two real users and two real tokens.</b> A single-user version of the logout test passes
 * whether or not the ownership check exists — it is the shape of test this repository has already
 * been burned by ("green for the wrong reason"). The assertion that carries the weight here is the
 * one made about the <em>victim's</em> token after the attacker's call, not the status code the
 * attacker receives.
 *
 * <p><b>Why the second actor sits in a different tenant.</b> The ticket test's attacker is a user of
 * the {@code acme} tenant while the ticket's actor is the host admin, so the two principals share
 * neither user id nor tenant. That keeps the test measuring the actor bind specifically, instead of
 * riding on a tenant-scoped check that may be added later.
 */
class SessionOwnershipIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";
    private static final String OTHER_TENANT = "acme";
    private static final String OUTSIDER_USERNAME = "r40-outsider";
    private static final String OUTSIDER_PASSWORD = "Outsider123!";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * R-39. Two real users, two real refresh tokens: the host admin presents the tenant admin's
     * refresh token to {@code /api/auth/logout}.
     *
     * <p>The response is expected to stay 204 — identical to what an unknown token already returns —
     * so that the endpoint cannot be used as an oracle for "this token exists but is not yours". The
     * behaviour under test is therefore not the status code but the victim's session: the tenant
     * admin's refresh token must still be redeemable afterwards.
     */
    @Test
    void logoutRejectsARefreshTokenBelongingToAnotherUser() {
        JsonNode attackerPair = loginOk(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        JsonNode victimPair = loginOk(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);

        String attackerAccess = attackerPair.path("accessToken").asText();
        String victimRefresh = victimPair.path("refreshToken").asText();
        assertThat(victimRefresh).isNotBlank();

        HttpHeaders attackerHeaders = bearerHeaders(attackerAccess, null);
        HttpHeaders victimHeaders = bearerHeaders(victimPair.path("accessToken").asText(), DEFAULT_TENANT);

        // Guard: two genuinely distinct principals. A single-user variant of this test passes with or
        // without the ownership check, which is exactly the failure mode being avoided.
        long attackerId = me(attackerHeaders).path("id").asLong();
        long victimId = me(victimHeaders).path("id").asLong();
        assertThat(attackerId)
                .as("the two refresh tokens must belong to two different users")
                .isNotEqualTo(victimId);
        assertThat(attackerPair.path("refreshToken").asText())
                .as("two independent logins must yield two distinct refresh tokens")
                .isNotEqualTo(victimRefresh);

        // The attack: an authenticated caller presents someone else's refresh token.
        ResponseEntity<JsonNode> attack = restTemplate.exchange(
                "/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", victimRefresh), attackerHeaders), JsonNode.class);
        assertThat(attack.getStatusCode())
                .as("a foreign token must be answered exactly like an unknown one (204), not distinguished")
                .isEqualTo(HttpStatus.NO_CONTENT);

        // The assertion that carries the weight: the victim's session survived.
        ResponseEntity<JsonNode> victimRefreshCall = restTemplate.exchange(
                "/api/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", victimRefresh), jsonHeaders(DEFAULT_TENANT)),
                JsonNode.class);
        assertThat(victimRefreshCall.getStatusCode())
                .as("another user's logout must not revoke the victim's refresh token")
                .isEqualTo(HttpStatus.OK);

        // Control: the owner's own logout still works, so the check is a bind and not a blanket veto.
        JsonNode ownPair = loginOk(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        String ownRefresh = ownPair.path("refreshToken").asText();
        HttpHeaders ownHeaders = bearerHeaders(ownPair.path("accessToken").asText(), DEFAULT_TENANT);
        ResponseEntity<JsonNode> ownLogout = restTemplate.exchange(
                "/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", ownRefresh), ownHeaders), JsonNode.class);
        assertThat(ownLogout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> afterOwnLogout = restTemplate.exchange(
                "/api/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", ownRefresh), jsonHeaders(DEFAULT_TENANT)),
                JsonNode.class);
        assertThat(afterOwnLogout.getStatusCode())
                .as("the owner's own logout must still revoke the token")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * R-40. The host admin mints an impersonation ticket; a user of another tenant presents it.
     *
     * <p>The rejection reuses the "invalid or expired" answer given to an unknown ticket, for the
     * same non-disclosure reason as above. The second half of the test is what makes the check a
     * bind rather than a burn: the rightful actor must still be able to redeem the ticket, i.e. the
     * failed redemption must not have consumed it.
     */
    @Test
    void impersonationTicketRejectsRedemptionByAnotherActor() {
        HttpHeaders actorHeaders = bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
        JsonNode actorMe = me(actorHeaders);
        long actorId = actorMe.path("id").asLong();
        assertThat(actorMe.path("tenantId").isNull() || actorMe.path("tenantId").isMissingNode())
                .as("the ticket's actor is the host admin (no tenant)")
                .isTrue();

        HttpHeaders targetHeaders = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
        JsonNode targetMe = me(targetHeaders);
        long targetUserId = targetMe.path("id").asLong();
        long targetTenantId = targetMe.path("tenantId").asLong();

        // The second actor: a user of a DIFFERENT tenant. It needs no permission at all —
        // /api/auth/impersonate/authenticate is open to every authenticated principal.
        long otherTenantId = ensureOutsiderExists();
        HttpHeaders outsiderHeaders = bearerHeaders(
                accessToken(OTHER_TENANT, OUTSIDER_USERNAME, OUTSIDER_PASSWORD), OTHER_TENANT);
        JsonNode outsiderMe = me(outsiderHeaders);
        assertThat(outsiderMe.path("id").asLong())
                .as("the redeeming actor must be a different user")
                .isNotEqualTo(actorId);
        assertThat(outsiderMe.path("tenantId").asLong())
                .as("the redeeming actor must sit in a different tenant than both the actor and the target")
                .isEqualTo(otherTenantId)
                .isNotEqualTo(targetTenantId);

        // The host actor mints a single-use ticket for the tenant admin.
        ResponseEntity<JsonNode> minted = restTemplate.exchange(
                "/api/auth/impersonate", HttpMethod.POST,
                new HttpEntity<>(Map.of("targetUserId", targetUserId, "targetTenantId", targetTenantId),
                        actorHeaders),
                JsonNode.class);
        assertThat(minted.getStatusCode()).isEqualTo(HttpStatus.OK);
        String ticket = minted.getBody().path("impersonationToken").asText();
        assertThat(ticket).isNotBlank();

        // The attack: the leaked ticket string is redeemed by an actor it was never minted for.
        ResponseEntity<JsonNode> stolen = restTemplate.exchange(
                "/api/auth/impersonate/authenticate", HttpMethod.POST,
                new HttpEntity<>(Map.of("impersonationToken", ticket), outsiderHeaders), JsonNode.class);
        assertThat(stolen.getStatusCode())
                .as("a ticket may only be redeemed by the actor it was minted for")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(stolen.getBody()).isNotNull();
        assertThat(stolen.getBody().path("detail").asText())
                .as("the rejection must be indistinguishable from an unknown ticket")
                .isEqualTo("Invalid or expired impersonation token");

        // The rightful actor can still redeem it: a foreign attempt must not burn the ticket,
        // otherwise the leak would become a denial of the legitimate hand-off.
        ResponseEntity<JsonNode> rightful = restTemplate.exchange(
                "/api/auth/impersonate/authenticate", HttpMethod.POST,
                new HttpEntity<>(Map.of("impersonationToken", ticket), actorHeaders), JsonNode.class);
        assertThat(rightful.getStatusCode())
                .as("the rejected attempt must not have consumed the ticket")
                .isEqualTo(HttpStatus.OK);
        assertThat(rightful.getBody().path("accessToken").asText()).isNotBlank();

        // Single use is unchanged: the second successful redemption fails.
        ResponseEntity<JsonNode> replay = restTemplate.exchange(
                "/api/auth/impersonate/authenticate", HttpMethod.POST,
                new HttpEntity<>(Map.of("impersonationToken", ticket), actorHeaders), JsonNode.class);
        assertThat(replay.getStatusCode())
                .as("the ticket stays single-use")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- helpers ---------------------------------------------------------

    private JsonNode me(HttpHeaders headers) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private HttpHeaders jsonHeaders(String tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (tenant != null) {
            headers.set(TENANT_HEADER, tenant);
        }
        return headers;
    }

    /**
     * Creates the {@code acme} tenant and a plain user inside it, idempotently (the Spring context and
     * its database are shared across IT classes). The user is written through the repository rather
     * than the API because a freshly created tenant has no user able to log in and no endpoint accepts
     * a foreign tenant id — the open Issue #1. The username differs from {@code admin} so that
     * {@code TenantIsolationIT}'s "a fresh tenant has nobody to log in with" assertion is untouched.
     *
     * @return the acme tenant id
     */
    private long ensureOutsiderExists() {
        HttpHeaders hostHeaders = bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", OTHER_TENANT, "displayName", "Acme Inc"), hostHeaders),
                JsonNode.class);
        assertThat(created.getStatusCode())
                .as("the second tenant must exist (freshly created or already present)")
                .isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);

        Tenant tenant = tenantRepository.findByNameIgnoreCase(OTHER_TENANT).orElseThrow();
        long tenantId = tenant.getId();
        if (userRepository.findByTenantIdAndUsernameIgnoreCase(tenantId, OUTSIDER_USERNAME).isEmpty()) {
            User outsider = new User();
            outsider.setTenantId(tenantId);
            outsider.setUsername(OUTSIDER_USERNAME);
            outsider.setEmail(OUTSIDER_USERNAME + "@acme.local");
            outsider.setPasswordHash(passwordEncoder.encode(OUTSIDER_PASSWORD));
            outsider.setActive(true);
            userRepository.save(outsider);
        }
        return tenantId;
    }
}
