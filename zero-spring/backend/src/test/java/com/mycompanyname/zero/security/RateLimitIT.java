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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence for PROD-R6.
 *
 * <p>Runs in its own Spring context with {@code capacity=3} — the shared context keeps the limit far
 * above what the suite generates, so the other integration tests exercise the filter without ever
 * tripping it.
 *
 * <p>Every scenario uses a synthetic client address (via {@code X-Forwarded-For}, honoured because
 * {@code server.forward-headers-strategy=framework}) and a username that does not exist. That keeps
 * the tests from throttling each other and, more importantly, from tripping the per-account lockout
 * on the seeded {@code admin} — which would poison the shared database for every other IT class.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "zero.ratelimit.enabled=true",
                "zero.ratelimit.capacity=3",
                "zero.ratelimit.refill-period=PT1M"
        })
class RateLimitIT extends AbstractIntegrationIT {

    private static final int CAPACITY = 3;
    private static final String LOGIN_PATH = "/api/auth/login";

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @BeforeEach
    void clearBuckets() {
        rateLimitFilter.reset();
    }

    @Test
    void repeatedLoginAttemptsFromOneAddressAreThrottled() {
        String clientIp = "203.0.113.10";

        // Under the limit the endpoint behaves exactly as before: the credentials are wrong, so 401.
        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            ResponseEntity<JsonNode> response = attemptLogin(clientIp, "probe-user-" + attempt);
            assertThat(response.getStatusCode())
                    .as("attempt %d is within the allowance and must reach the authentication logic", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // A different username each time, so only the IP dimension can be what stops this one.
        ResponseEntity<JsonNode> throttled = attemptLogin(clientIp, "probe-user-overflow");

        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(throttled.getBody()).isNotNull();
        assertThat(throttled.getBody().path("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(throttled.getBody().path("status").asInt()).isEqualTo(429);
        assertThat(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .as("a client needs to be told when to come back")
                .isNotNull();
    }

    @Test
    void oneAddressBeingThrottledDoesNotAffectAnother() {
        String noisyIp = "203.0.113.20";
        for (int attempt = 0; attempt <= CAPACITY; attempt++) {
            attemptLogin(noisyIp, "noisy-user-" + attempt);
        }
        assertThat(attemptLogin(noisyIp, "noisy-user-final").getStatusCode())
                .as("arrange step must actually exhaust the noisy client's allowance")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        ResponseEntity<JsonNode> innocent = attemptLogin("203.0.113.21", "quiet-user");

        assertThat(innocent.getStatusCode())
                .as("throttling is per client, not global — one attacker must not deny service to everyone")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void oneUsernameIsThrottledAcrossDifferentAddresses() {
        // The dimension a botnet defeats by rotating source addresses. Each request comes from its
        // own IP, so only the username bucket can stop the last one.
        String targetedUser = "targeted-account";
        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            ResponseEntity<JsonNode> response = attemptLogin("198.51.100." + attempt, targetedUser);
            assertThat(response.getStatusCode())
                    .as("attempt %d from a fresh address is within the username allowance", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<JsonNode> throttled = attemptLogin("198.51.100.99", targetedUser);

        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void usernamesAreCountedCaseInsensitively() {
        // The login lookup is case-insensitive, so counting "Admin" and "admin" separately would hand
        // an attacker a free multiplier for every capitalisation of the same account.
        //
        // The spellings are written out rather than computed with String.toUpperCase(): that method
        // uses the default locale, and on a Turkish-locale JVM it maps 'i' to the dotted 'İ', which
        // no longer folds back under the filter's Locale.ROOT normalisation. The filter is right to
        // normalise locale-independently — this test simply must not disagree with it.
        List<String> spellings = List.of("mixedcaseuser", "MIXEDCASEUSER", "MixedCaseUser");
        assertThat(spellings).hasSize(CAPACITY);

        for (int attempt = 0; attempt < spellings.size(); attempt++) {
            attemptLogin("198.51.100.1" + (attempt + 1), spellings.get(attempt));
        }

        assertThat(attemptLogin("198.51.100.200", "MiXeDcAsEuSeR").getStatusCode())
                .as("every capitalisation must draw on the same allowance")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void unthrottledEndpointsAreUntouched() {
        String clientIp = "203.0.113.30";
        for (int attempt = 0; attempt <= CAPACITY * 3; attempt++) {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Forwarded-For", clientIp);
            ResponseEntity<String> response = restTemplate.exchange(
                    "/api/localization/languages", HttpMethod.GET, new HttpEntity<>(headers), String.class);
            assertThat(response.getStatusCode())
                    .as("only the configured paths are throttled; request %d", attempt)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void theRequestBodyStillReachesTheController() {
        // The filter buffers the body to read the username. If that buffering were wrong, the
        // controller would see an empty body and answer 400 instead of 401 — a silent regression on
        // the platform's most important endpoint.
        ResponseEntity<JsonNode> response = attemptLogin("203.0.113.40", "body-check-user");

        assertThat(response.getStatusCode())
                .as("a 400 here would mean the controller never received the JSON body")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("LOGIN_FAILED");
    }

    private ResponseEntity<JsonNode> attemptLogin(String clientIp, String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", clientIp);
        headers.set(TENANT_HEADER, "default");
        Map<String, String> body = Map.of(
                "usernameOrEmail", username,
                "password", "definitely-not-the-password");
        return restTemplate.exchange(
                LOGIN_PATH, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
