package com.mycompanyname.zero.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.config.RateLimitFilter;
import com.mycompanyname.zero.config.RateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code dev} profile, asserted as a profile rather than as a collection of defaults.
 *
 * <p><b>C6.</b> {@code zero.ratelimit.trusted-proxy-count} defaults to {@code 1} in the base
 * configuration, which is right for the documented production shape — one TLS-terminating proxy that
 * appends the address it saw. A laptop, and a CI runner, have no proxy in front of them at all.
 * Inheriting the default there means {@code X-Forwarded-For} is honoured on a directly exposed
 * listener, so any caller picks its own bucket just by rotating the header: live-proven, six
 * unthrottled login attempts with a rotating rightmost entry. {@code application-dev.yml} now says
 * {@code trusted-proxy-count: 0}, and both halves of that are checked below — the bound value, and
 * what it actually does to a request.
 *
 * <p><b>C5, the other side.</b> Closing an exposure by breaking the tooling that depends on it is
 * not closing it. Since the springdoc {@code permitAll} is now granted only under {@code dev} and
 * {@code test}, and CI's typed-client gate boots the packaged jar under {@code dev}, the assertion
 * that the document is still served here is what stops that gate from breaking silently.
 *
 * <p>{@code inheritProfiles = false} keeps {@code test} out of the context: with it, the properties
 * under test would come from {@code application-test.yml} instead of {@code application-dev.yml}.
 * The capacity override is only to keep the run short — {@code trusted-proxy-count} is deliberately
 * <em>not</em> overridden, because it is the subject.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "zero.ratelimit.enabled=true",
                "zero.ratelimit.capacity=3",
                "zero.ratelimit.refill-period=PT1M",
                "spring.cache.type=simple",
                "management.health.redis.enabled=false",
                "zero.saas.lifecycle.initial-delay=PT24H",
                "zero.saas.lifecycle.interval=PT24H",
                "logging.level.org.hibernate.SQL=WARN"
        })
@ActiveProfiles(value = "dev", inheritProfiles = false)
class DevProfileSecurityIT extends AbstractIntegrationIT {

    private static final int CAPACITY = 3;
    private static final String LOGIN_PATH = "/api/auth/login";

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @BeforeEach
    void clearBuckets() {
        rateLimitFilter.reset();
    }

    @Test
    void theDevProfileTrustsNoProxy() {
        assertThat(rateLimitProperties.getTrustedProxyCount())
                .as("dev exposes the listener directly, so every X-Forwarded-For entry is "
                        + "client-supplied fiction and none of them may select a bucket")
                .isZero();
    }

    /**
     * The behaviour behind the property. Every request below carries a different
     * {@code X-Forwarded-For}, and all of them arrive from the same transport peer — which is the
     * only address that means anything here.
     */
    @Test
    void aRotatingForwardedHeaderCannotEvadeTheLimitOnADirectlyExposedListener() {
        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            assertThat(attemptLogin("203.0.114." + attempt, "dev-probe-" + attempt).getStatusCode())
                    .as("attempt %d is inside the peer's allowance", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(attemptLogin("203.0.114.99", "dev-probe-overflow").getStatusCode())
                .as("with trusted-proxy-count inherited as 1, each forged entry was its own bucket "
                        + "and this stayed 401 forever")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void theOpenApiDocumentIsStillServedUnderTheDevProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode())
                .as("CI's typed-client gate regenerates the frontend client from this document "
                        + "against a jar booted with this profile")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"openapi\"").contains("/api/auth/login");
    }

    private ResponseEntity<JsonNode> attemptLogin(String forwardedFor, String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        headers.set(TENANT_HEADER, "default");
        Map<String, String> body = Map.of(
                "usernameOrEmail", username,
                "password", "definitely-not-the-password");
        return restTemplate.exchange(
                LOGIN_PATH, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
