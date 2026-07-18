package com.mycompanyname.zero.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.config.RateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live-reproduction evidence for B1, B2 and B3 — the three ways the throttle could be walked past.
 *
 * <p>Each test is written as the bypass it replays, not as the fix it verifies: the arrange step
 * exhausts the allowance, and the assertion is that the trick which used to keep returning 401
 * (i.e. reaching the authentication logic, unlimited) now cannot.
 *
 * <p>Requests are addressed with an absolute {@link URI} rather than a template string, because
 * {@code RestTemplate} would percent-encode the {@code %} in {@code %6Cogin} and the {@code ;} in
 * {@code login;x=1} — turning the attack into a different, harmless request and quietly making the
 * test pass against the vulnerable code.
 *
 * <p>One correction to the original report is recorded in
 * {@link #pathParameterAndSlashSpellingsNeverReachTheCredentialCheck}: two of the reported
 * spellings turned out to be stopped by an existing control, and the test says so rather than
 * asserting the outcome the report predicted.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "zero.ratelimit.enabled=true",
                "zero.ratelimit.capacity=2",
                "zero.ratelimit.refill-period=PT1M",
                "zero.ratelimit.trusted-proxy-count=1"
        })
class RateLimitBypassIT extends AbstractIntegrationIT {

    private static final int CAPACITY = 2;
    private static final String LOGIN_PATH = "/api/auth/login";

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @LocalServerPort
    private int port;

    @BeforeEach
    void clearBuckets() {
        rateLimitFilter.reset();
    }

    // --- B1: path normalisation -----------------------------------------

    /**
     * The headline bypass. {@code %6C} is {@code l}, so the container decodes this to
     * {@code /api/auth/login} and Spring routes it to the login controller — but the old
     * {@code throttledPaths.contains(request.getRequestURI())} compared the raw, still-encoded URI
     * and found no match. Live: 401, 401, 401, 401 with capacity 2. Unlimited credential stuffing
     * through a single character substitution.
     */
    @Test
    void aPercentEncodedPathCannotMintAFreshAllowance() {
        String clientIp = "203.0.113.60";
        exhaust(clientIp, LOGIN_PATH);

        ResponseEntity<JsonNode> response = post(clientIp, "/api/auth/%6Cogin", "encoded-user");

        assertThat(response.getStatusCode())
                .as("%%6Cogin decodes to 'login' and reaches the same controller, so it must draw on "
                        + "the same allowance — a 401 here means the limiter was bypassed outright")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * Servlet routing is case-sensitive, so this spelling would be refused anyway. The limiter
     * matches case-insensitively regardless: being wrong here costs one throttled error response,
     * and being wrong the other way costs the whole control.
     */
    @Test
    void aCaseVariantCannotMintAFreshAllowance() {
        String clientIp = "203.0.113.63";
        exhaust(clientIp, LOGIN_PATH);

        assertThat(post(clientIp, "/API/AUTH/Login", "case-variant-user").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * The path-parameter and duplicate-slash spellings, and what running them actually showed.
     *
     * <p>The original report recorded {@code /api/auth/login;x=N} returning 401 without limit and
     * read that as unlimited credential stuffing. Driving it here says otherwise: Spring Security's
     * {@link org.springframework.security.web.firewall.StrictHttpFirewall} rejects {@code ;},
     * {@code %3B} and {@code //} in the URL before the security chain runs at all, and the 401 is
     * the error dispatch to {@code /error} — no controller, no password check, no bcrypt. Recording
     * that here rather than asserting a 429 the fix does not produce: an assertion written to the
     * theory instead of the observation is how a test ends up certifying something untrue.
     *
     * <p>What matters is the property, not which layer supplies it: these spellings must never reach
     * the credential check. {@code ThrottledPathMatcherTest} proves the limiter normalises all three
     * on its own, so the guarantee does not rest on the firewall keeping its default configuration.
     */
    @Test
    void pathParameterAndSlashSpellingsNeverReachTheCredentialCheck() {
        String clientIp = "203.0.113.61";
        exhaust(clientIp, LOGIN_PATH);

        for (String rawPath : new String[]{
                "/api/auth/login;x=1", "/api/auth/login;x=2", "/api/auth/login%3Bx=1", "/api//auth/login"}) {
            ResponseEntity<JsonNode> response = post(clientIp, rawPath, "matrix-user");

            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("%s must never authenticate", rawPath)
                    .isFalse();
            assertThat(String.valueOf(response.getBody()))
                    .as("%s reached the login controller — an unlimited, unthrottled path to bcrypt",
                            rawPath)
                    .doesNotContain("LOGIN_FAILED");
        }
    }

    /**
     * The reverse direction, and the one that keeps the fix honest: normalising must not drag
     * unrelated endpoints into the throttle. {@code /api/localization/languages} is not configured,
     * and no amount of exhausting the login allowance may touch it.
     */
    @Test
    void normalisationDoesNotThrottleUnconfiguredPaths() {
        String clientIp = "203.0.113.65";
        exhaust(clientIp, LOGIN_PATH);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        for (int attempt = 0; attempt <= CAPACITY * 3; attempt++) {
            ResponseEntity<String> response = restTemplate.exchange(
                    "/api/localization/languages", HttpMethod.GET, new HttpEntity<>(headers), String.class);
            assertThat(response.getStatusCode())
                    .as("request %d to an unthrottled path", attempt)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    // --- B2: oversized body ---------------------------------------------

    /**
     * The exact live bypass: one victim account, a 20 KB pad field, and a rotating
     * {@code X-Forwarded-For}. The body was too large to parse, so the username bucket was skipped;
     * the rotating header defeated the IP bucket. Six guesses at one account, six 401s, no limit in
     * force anywhere. The fix refuses a body it cannot inspect instead of exempting it.
     */
    @Test
    void anOversizedBodyIsRefusedRatherThanExemptedFromTheUsernameBucket() {
        String pad = "A".repeat(20 * 1024);

        for (int attempt = 1; attempt <= 6; attempt++) {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("usernameOrEmail", "victim-user");
            body.put("password", "guess-" + attempt);
            body.put("pad", pad);

            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    URI.create("http://localhost:" + port + LOGIN_PATH), HttpMethod.POST,
                    new HttpEntity<>(body, jsonHeaders("198.51.100." + attempt)), JsonNode.class);

            assertThat(response.getStatusCode())
                    .as("attempt %d: an unparseable body must not be a free pass to the credential "
                            + "check — a 401 here is the bypass", attempt)
                    .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().path("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        }
    }

    /**
     * C4. Refusing a request and charging a request are separate decisions, and the filter used to
     * make only the first one: {@code rejectOversizedBody} returned before the IP bucket was
     * consumed, so an oversized body cost its sender nothing. Live: eight 20 KB requests from one
     * fixed address answered 413 eight times, and an ordinary login from that same address then
     * answered 401 — the allowance sat untouched after a refused flood. A rejection has to be
     * priced, otherwise "refused" is just a different response to an unlimited request rate.
     *
     * <p>The closing assertion is the load-bearing one; the loop above it documents the shape (the
     * first {@code CAPACITY} requests are refused for their size, the rest for their number).
     */
    @Test
    void anOversizedBodyStillSpendsTheSendersAllowance() {
        String clientIp = "203.0.113.70";

        for (int attempt = 1; attempt <= 8; attempt++) {
            assertThat(postOversized(clientIp).getStatusCode())
                    .as("attempt %d from a fixed address", attempt)
                    .isEqualTo(attempt <= CAPACITY
                            ? HttpStatus.PAYLOAD_TOO_LARGE
                            : HttpStatus.TOO_MANY_REQUESTS);
        }

        assertThat(post(clientIp, LOGIN_PATH, "after-the-flood").getStatusCode())
                .as("a 401 here means eight refused requests bought the sender a full, unspent "
                        + "allowance — the 413 was free")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** The bound has to leave real logins alone; a normal body is nowhere near it. */
    @Test
    void anOrdinarySizedBodyStillReachesTheController() {
        ResponseEntity<JsonNode> response = post("203.0.113.66", LOGIN_PATH, "ordinary-body-user");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("LOGIN_FAILED");
    }

    // --- B3: X-Forwarded-For trust boundary ------------------------------

    /**
     * The append-shaped header nginx actually produces: {@code proxy_add_x_forwarded_for} puts the
     * address it saw on the <em>right</em>, after whatever the client supplied. Spring's
     * {@code getRemoteAddr()} reads the <em>left</em>, so the real address was never counted and a
     * single host rotating the leading entry was unthrottled. Live: capacity 2, six requests, six
     * 401s.
     */
    @Test
    void aRotatingLeftmostForwardedEntryCannotEvadeTheRealAddress() {
        String realClient = "203.0.113.250";

        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            ResponseEntity<JsonNode> response = post(
                    "172.16.0." + attempt + ", " + realClient, LOGIN_PATH, "spray-user-" + attempt);
            assertThat(response.getStatusCode())
                    .as("attempt %d is inside the real client's allowance", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<JsonNode> throttled = post(
                "172.16.0.99, " + realClient, LOGIN_PATH, "spray-user-overflow");

        assertThat(throttled.getStatusCode())
                .as("the forged leading entry changed on every request; only the proxy-appended "
                        + "trailing entry is real, and it is the same host throughout")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** Two genuinely different clients behind the same proxy must not share an allowance. */
    @Test
    void distinctRealAddressesBehindOneProxyKeepSeparateAllowances() {
        for (int attempt = 0; attempt <= CAPACITY; attempt++) {
            post("172.16.0.5, 203.0.113.251", LOGIN_PATH, "noisy-" + attempt);
        }
        assertThat(post("172.16.0.5, 203.0.113.251", LOGIN_PATH, "noisy-final").getStatusCode())
                .as("arrange step must actually exhaust the noisy client")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(post("172.16.0.5, 203.0.113.252", LOGIN_PATH, "quiet-user").getStatusCode())
                .as("throttling one client behind the proxy must not deny service to its neighbour")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * A header with fewer entries than the configured proxy chain means the request did not arrive
     * the way the configuration says it does. Falling back to the TCP peer is the only honest
     * reading — and it must still be a limit, not an exemption.
     */
    @Test
    void anAbsentForwardedHeaderFallsBackToTheTransportPeerAndStillCounts() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TENANT_HEADER, "default");

        HttpStatus last = null;
        for (int attempt = 0; attempt <= CAPACITY; attempt++) {
            Map<String, String> body = Map.of(
                    "usernameOrEmail", "no-header-user-" + attempt,
                    "password", "definitely-not-the-password");
            last = HttpStatus.valueOf(restTemplate.exchange(
                    URI.create("http://localhost:" + port + LOGIN_PATH), HttpMethod.POST,
                    new HttpEntity<>(body, headers), JsonNode.class).getStatusCode().value());
        }

        assertThat(last)
                .as("no X-Forwarded-For at all must not mean no limit")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // --- helpers ---------------------------------------------------------

    /** Spends the whole allowance for {@code clientIp} on {@code path}, asserting it really did. */
    private void exhaust(String clientIp, String path) {
        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            ResponseEntity<JsonNode> response = post(clientIp, path, "warmup-user-" + attempt);
            assertThat(response.getStatusCode())
                    .as("arrange step: attempt %d must be inside the allowance", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(post(clientIp, path, "warmup-overflow").getStatusCode())
                .as("arrange step: the canonical path itself must be throttled, otherwise the "
                        + "bypass assertions below prove nothing")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private ResponseEntity<JsonNode> postOversized(String forwardedFor) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("usernameOrEmail", "victim-user");
        body.put("password", "definitely-not-the-password");
        body.put("pad", "A".repeat(20 * 1024));
        return restTemplate.exchange(
                URI.create("http://localhost:" + port + LOGIN_PATH), HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(forwardedFor)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String forwardedFor, String rawPath, String username) {
        Map<String, String> body = Map.of(
                "usernameOrEmail", username,
                "password", "definitely-not-the-password");
        return restTemplate.exchange(
                URI.create("http://localhost:" + port + rawPath), HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(forwardedFor)), JsonNode.class);
    }

    private HttpHeaders jsonHeaders(String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        headers.set(TENANT_HEADER, "default");
        return headers;
    }
}
