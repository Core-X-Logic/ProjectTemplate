package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.config.FieldEncryptionService;
import com.mycompanyname.zero.identity.auth.JwtService;
import com.mycompanyname.zero.identity.auth.TokenRevocationService;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-R16 (access-token revocation), proven end to end against a real Redis (Testcontainers), with
 * the revocation validator wired into the running resource server.
 *
 * <p>Every scenario uses a freshly-created, uniquely-named HOST user, so nothing here touches the
 * shared seed admin every other IT logs in as (the {@code AbstractTwoFactorIT} discipline). Redis is
 * flushed before each test so the per-user "not before" markers cannot bleed across tests.
 *
 * <p>Two revocations that depend on the token's {@code iat} being strictly before the marker sleep
 * ~1.1s first, so the older token's second-precision {@code iat} is unambiguously below {@code notBefore}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "zero.jwt.revocation.enabled=true")
class TokenRevocationIT extends AbstractIntegrationIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PASSWORD = "Password123!";

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private FieldEncryptionService fieldEncryptionService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private TokenRevocationService revocationService;

    @BeforeEach
    void flushRedis() throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHALL");
    }

    @Test
    void aValidTokenIsAcceptedWhenRevocationIsEnabled() {
        long userId = createHostUser();
        String token = accessToken(null, usernameOf(userId), PASSWORD);

        assertThat(me(token).getStatusCode())
                .as("revocation is enabled but nothing is revoked — the ordinary path must still work")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void revokingAJtiRejectsThatTokenButNotAFreshOne() {
        long userId = createHostUser();
        String token = accessToken(null, usernameOf(userId), PASSWORD);
        Jwt decoded = jwtDecoder.decode(token);

        revocationService.revokeAccessToken(decoded.getId(), decoded.getExpiresAt());

        assertThat(me(token).getStatusCode())
                .as("the revoked jti must be refused")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(me(accessToken(null, usernameOf(userId), PASSWORD)).getStatusCode())
                .as("a freshly-issued token has a different jti and must still work")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void revokingAllForAUserRejectsOlderTokensButNotNewerOnes() throws Exception {
        long userId = createHostUser();
        String older = accessToken(null, usernameOf(userId), PASSWORD);

        Thread.sleep(1100); // ensure older.iat is strictly before the notBefore marker
        revocationService.revokeAllForUser(userId);

        assertThat(me(older).getStatusCode())
                .as("a token issued before revokeAllForUser must be refused")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(me(accessToken(null, usernameOf(userId), PASSWORD)).getStatusCode())
                .as("a token issued after it must still work")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void logoutRevokesThePresentedAccessToken() {
        long userId = createHostUser();
        JsonNode pair = loginOk(null, usernameOf(userId), PASSWORD);
        String token = pair.path("accessToken").asText();
        String refresh = pair.path("refreshToken").asText();

        ResponseEntity<Void> logout = restTemplate.exchange(
                "/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", refresh), bearerHeaders(token, null)), Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(me(token).getStatusCode())
                .as("after logout the access token can no longer call /api/auth/me")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void changingThePasswordRevokesOutstandingTokens() throws Exception {
        long userId = createHostUser();
        String older = accessToken(null, usernameOf(userId), PASSWORD);

        Thread.sleep(1100);
        ResponseEntity<Void> change = restTemplate.exchange(
                "/api/profile/change-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("currentPassword", PASSWORD, "newPassword", "Password456!"),
                        bearerHeaders(older, null)),
                Void.class);
        assertThat(change.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(me(older).getStatusCode())
                .as("a password change must kill every outstanding token for the user")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void disablingTwoFactorRevokesOutstandingTokens() throws Exception {
        long userId = createTwoFactorHostUser();
        // A 2FA user cannot log in for a token (login returns a challenge), so mint one directly.
        User user = userRepository.findById(userId).orElseThrow();
        String older = jwtService.issueAccessToken(user, Set.of());

        Thread.sleep(1100);
        ResponseEntity<Void> disable = restTemplate.exchange(
                "/api/profile/two-factor/disable", HttpMethod.POST,
                new HttpEntity<>(Map.of("password", PASSWORD), bearerHeaders(older, null)), Void.class);
        assertThat(disable.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(me(older).getStatusCode())
                .as("dropping the second factor is a credential change and must revoke outstanding tokens")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- helpers ---------------------------------------------------------------------------

    private ResponseEntity<JsonNode> me(String token) {
        HttpHeaders headers = bearerHeaders(token, null);
        return restTemplate.exchange("/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private long createHostUser() {
        return userRepository.saveAndFlush(newHostUser()).getId();
    }

    private long createTwoFactorHostUser() {
        User user = newHostUser();
        user.setTwoFactorEnabled(true);
        user.setTwoFactorSecret(fieldEncryptionService.encrypt("JBSWY3DPEHPK3PXP"));
        return userRepository.saveAndFlush(user).getId();
    }

    private User newHostUser() {
        String username = "revoke_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@host.local");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        return user;
    }

    private String usernameOf(long userId) {
        return userRepository.findById(userId).orElseThrow().getUsername();
    }
}
