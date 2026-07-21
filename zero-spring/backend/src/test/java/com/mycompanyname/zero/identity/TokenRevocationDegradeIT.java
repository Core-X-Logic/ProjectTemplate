package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-R16 fail-closed degrade. Revocation is ENABLED but Redis is pointed at a closed port, so the
 * revocation check cannot reach its store. The conscious trade is that an authenticated request is
 * then REJECTED (401), never waved through — the store is the only source of truth for what has been
 * revoked, so honouring the token would risk honouring a revoked one.
 *
 * <p>Login itself still works (it is anonymous — no token to decode, no revocation check), which is
 * how a token even exists to present. It is the authenticated call that fails closed.
 *
 * <p>The complementary negative — that a fail-OPEN variant would instead let the token through — is
 * recorded deterministically in {@code RevokedTokenValidatorTest#aFailOpenVariantWouldLetThePossibly
 * RevokedTokenThrough}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "zero.jwt.revocation.enabled=true",
                // A port with nothing listening: connection refused, fast, on every request.
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6399",
                "spring.data.redis.timeout=500ms",
                "spring.data.redis.connect-timeout=500ms"
        })
class TokenRevocationDegradeIT extends AbstractIntegrationIT {

    @Test
    void anAuthenticatedRequestIsRejectedWhenTheRevocationStoreIsUnreachable() {
        // Login is anonymous, so it succeeds despite Redis being down and yields a real token.
        String token = accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);

        ResponseEntity<JsonNode> me = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(bearerHeaders(token, null)), JsonNode.class);

        assertThat(me.getStatusCode())
                .as("with the revocation store unreachable the request must fail CLOSED (401), not be allowed")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
