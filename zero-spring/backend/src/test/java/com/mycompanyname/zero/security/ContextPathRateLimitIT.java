package com.mycompanyname.zero.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.config.RateLimitFilter;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence for the quietest half of B1: the throttle survives a servlet context path.
 *
 * <p>The old matcher compared {@code request.getRequestURI()} — which includes the context path —
 * against configured paths that do not. Setting {@code server.servlet.context-path} therefore made
 * every configured path stop matching at once: the limiter was disabled across the entire
 * deployment, with no error, no warning, and a configuration file that still listed four throttled
 * endpoints. Worse than the encoded-path bypass, because it needs no attacker at all — just an
 * operator mounting the API under a prefix, which is an ordinary thing to do.
 *
 * <p>This needs its own context because the context path is fixed at startup. It is the only reason
 * this class is not a method in {@code RateLimitBypassIT}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/zero",
                "zero.ratelimit.enabled=true",
                "zero.ratelimit.capacity=2",
                "zero.ratelimit.refill-period=PT1M",
                "zero.ratelimit.trusted-proxy-count=1"
        })
class ContextPathRateLimitIT extends AbstractIntegrationIT {

    private static final int CAPACITY = 2;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @BeforeEach
    void clearBuckets() {
        rateLimitFilter.reset();
    }

    @Test
    void theThrottleStillAppliesUnderAServletContextPath() {
        String clientIp = "203.0.113.90";

        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            assertThat(attemptLogin(clientIp, "ctx-user-" + attempt).getStatusCode())
                    .as("attempt %d is within the allowance and must reach the authentication logic "
                            + "— a 404 here would mean the request never routed", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(attemptLogin(clientIp, "ctx-user-overflow").getStatusCode())
                .as("the configured path is /api/auth/login and the request URI is "
                        + "/zero/api/auth/login; matching the raw URI meant matching neither, and "
                        + "the limiter silently protected nothing")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void unthrottledPathsAreStillUntouchedUnderAContextPath() {
        // Stripping the context path must not turn into matching too much.
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", "203.0.113.91");
        for (int attempt = 0; attempt <= CAPACITY * 3; attempt++) {
            assertThat(restTemplate.exchange("/api/localization/languages", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class).getStatusCode())
                    .as("request %d", attempt)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    /** {@code TestRestTemplate}'s root URI already carries the context path, so this stays relative. */
    private ResponseEntity<JsonNode> attemptLogin(String clientIp, String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", clientIp);
        headers.set(TENANT_HEADER, "default");
        Map<String, String> body = Map.of(
                "usernameOrEmail", username,
                "password", "definitely-not-the-password");
        return restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
