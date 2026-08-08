package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.config.JwtProperties;
import com.mycompanyname.zero.identity.auth.JwtKeyRing;
import com.mycompanyname.zero.identity.auth.JwtService;
import com.mycompanyname.zero.identity.auth.TokenRevocationService;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-R16 F3 — sub-second revocation, proven end to end against a real Redis (Testcontainers).
 *
 * <p>The old marker was second-granular: a token minted in the SAME wall-clock second, just before a
 * credential change, tied the "not before" marker and — under the strict {@code <} — survived its full
 * TTL. Widening the comparison to {@code <=} would instead revoke a legitimate same-second re-login and
 * loop the user. The fix is a millisecond issue time ({@code ims}) compared against a millisecond
 * marker: the earlier token is revoked, the later re-login survives, all inside one second.
 *
 * <p>Determinism comes from a {@link MutableClock} that replaces the app {@code Clock} bean, so all
 * three events (mint older, revoke, mint newer) land in the same second at known milliseconds. It is
 * seeded near real time so the decoder's real-clock timestamp checks still pass.
 *
 * <p>Negative evidence for this class is recorded by temporarily collapsing
 * {@code TokenRevocationService#isRevoked} back to second granularity: {@link
 * #aSameSecondTokenIsRevokedWhileALaterReloginSurvives} then fails on the "older is rejected" assertion
 * (the same-second token survives), which is the exact defect this closes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "zero.jwt.revocation.enabled=true")
class TokenRevocationSubSecondIT extends AbstractIntegrationIT {

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
    private JwtService jwtService;

    @Autowired
    private TokenRevocationService revocationService;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void flushRedis() throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHALL");
    }

    @Test
    void aSameSecondTokenIsRevokedWhileALaterReloginSurvives() {
        User user = createHostUser();

        // Pin the clock to .100 of a whole second near real now, then keep every event inside that
        // single second. Seeding near real now keeps the decoder's real-clock timestamp checks happy.
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusMillis(100);

        clock.set(base);                                             // S.100
        String older = jwtService.issueAccessToken(user, Set.of()); // ims = S.100

        clock.set(base.plusMillis(300));                            // S.400 — SAME second, later millis
        revocationService.revokeAllForUser(user.getId());           // notBefore = S.400

        assertThat(me(older).getStatusCode())
                .as("a token minted in the same second, 300ms before the credential change, must be revoked")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        clock.set(base.plusMillis(600));                            // S.700 — still the SAME second
        String reissued = jwtService.issueAccessToken(user, Set.of()); // ims = S.700 > notBefore

        assertThat(me(reissued).getStatusCode())
                .as("a re-login issued after the change — even in the same second — must survive (no login loop)")
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * The mixed-version deploy window: an upgraded instance writes a sub-second (millis) "not before",
     * while a pre-upgrade instance still mints tokens WITHOUT {@code ims}. A same-second legacy re-login
     * after the change must SURVIVE — the fallback compares at second granularity (pre-F3 behaviour), so
     * no deploy-window login loop. The tolerance is bounded to the second: an earlier legacy token is
     * still revoked.
     */
    @Test
    void aSameSecondLegacyTokenWithoutImsSurvivesAReloginAfterRevocation() {
        User user = createHostUser();
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusMillis(100);

        clock.set(base.plusMillis(300));                  // S.400 — a sub-second notBefore
        revocationService.revokeAllForUser(user.getId()); // notBefore = S*1000 + 400 (frac > 0)

        // Rolling deploy: an OLD instance re-issues a token with NO ims, same second, just after the change.
        String legacyReissue = mintWithoutIms(user, base.plusMillis(500)); // iat floors to second S

        assertThat(me(legacyReissue).getStatusCode())
                .as("a same-second legacy (no-ims) re-login after the change must SURVIVE — no deploy-window loop")
                .isEqualTo(HttpStatus.OK);

        // The tolerance is bounded to the second: a legacy token from an EARLIER second is still revoked.
        String legacyOlder = mintWithoutIms(user, base.minusSeconds(1)); // iat = second S-1
        assertThat(me(legacyOlder).getStatusCode())
                .as("a legacy token from a prior second is still revoked — the fallback does not over-permit")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Forges a validly-signed access token that deliberately OMITS the {@code ims} claim — a pre-F3
     * token as a pre-upgrade instance would still mint during a rolling deploy. Signed by the same key
     * ring the app's decoder verifies against, so only revocation can refuse it. {@code iat} is written
     * at second granularity (the JWT norm), matching the fallback path under test.
     */
    private String mintWithoutIms(User user, Instant iat) {
        JwtKeyRing ring = new JwtKeyRing(jwtProperties);
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(ring.signingJwkSource());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .audience(List.of(jwtProperties.getAudience()))
                .subject(String.valueOf(user.getId()))
                .id(UUID.randomUUID().toString())
                .issuedAt(iat)
                .expiresAt(iat.plus(jwtProperties.getAccessTokenTtl()))
                .claim("username", user.getUsername())
                .claim("authorities", List.of())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS512).keyId(ring.activeKid()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private ResponseEntity<JsonNode> me(String token) {
        return restTemplate.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token, null)), JsonNode.class);
    }

    private User createHostUser() {
        String username = "subsec_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@host.local");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        // Host-global row: only the host branch of the V12 policy can write it, and a test thread
        // announces no context of its own.
        return asHostDatabase(() -> userRepository.saveAndFlush(user));
    }

    /**
     * Replaces the app {@code Clock} bean with a mutable one so mint/revoke instants are controlled to
     * the millisecond. {@code @Primary} wins over the production {@code Clock} for every injection point
     * (JwtService, TokenRevocationService) without needing bean-definition overriding.
     */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(ZoneOffset.UTC);
        }
    }

    /** A {@link Clock} whose instant the test can move at will. */
    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        private final ZoneId zone;

        MutableClock(ZoneId zone) {
            this.zone = zone;
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            MutableClock copy = new MutableClock(newZone);
            copy.set(now.get());
            return copy;
        }
    }
}
