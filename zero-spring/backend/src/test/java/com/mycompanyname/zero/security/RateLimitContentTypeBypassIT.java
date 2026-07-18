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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live-reproduction evidence for C1 and C2 — the two ways the throttle could still be walked past
 * after B2 was reported closed.
 *
 * <p><b>Why the B2 fix did not hold (C1).</b> The filter decided a request carried JSON with
 * {@code contentType.toLowerCase().contains("application/json")}. Spring's
 * {@code MappingJackson2HttpMessageConverter} registers {@code application/*+json} as well, so it
 * deserializes {@code application/vnd.attack+json}, {@code application/hal+json} and
 * {@code application/problem+json} into exactly the same request object — and the controller runs
 * bcrypt on the result. Under those spellings the filter skipped both halves of its work: the size
 * refusal and the username extraction. Live, at capacity 3: a 20 KB body sent as
 * {@code application/json} answered 413 six times, and the identical body sent as
 * {@code application/vnd.attack+json} answered {@code 401 LOGIN_FAILED} six times. B2 was still open,
 * behind one header value.
 *
 * <p><b>Why the tests missed it (C7).</b> Every rate-limit test in the suite built its headers with
 * {@code MediaType.APPLICATION_JSON} — the one spelling that was handled. A search for {@code +json}
 * under {@code src/test} returned nothing. The bypass passed under green tests because the tests and
 * the bug shared an assumption, which is why the matrix below is written out by hand rather than
 * derived from a constant.
 *
 * <p><b>And why writing it out by hand was not enough either (D5).</b> Every spelling in this class
 * is a JSON one, because the class was written under the assumption that the formats this
 * application reads <em>are</em> JSON formats. That assumption was false at the time it was written:
 * springdoc had already put a YAML converter on the classpath, and {@code application/yaml} walked
 * past the limiter entirely (D1). A hand-written matrix cannot cover a format its author does not
 * know is there, so it was the wrong tool for this job — it fixed the spellings that had been
 * reported and left the next one open, which is exactly what happened.
 *
 * <p>The derived matrix therefore lives in {@link RateLimitMediaTypeFailClosedIT}, which asks the
 * running application which media types it will bind a login body from and drives every one of them.
 * This class is kept as-is: it is the regression evidence for C1 and C2 specifically, and those are
 * about JSON spellings. Read the two together — this one proves the {@code +json} suffixes are
 * counted, that one proves nothing outside the counted set can reach the credential check.
 *
 * <p><b>The coercion gap (C2).</b> Username extraction required {@code value.isTextual()}, while
 * Jackson coerces a JSON number into the controller's {@code String} field without complaint. So
 * {@code {"usernameOrEmail":12345}} reached the credential check with no username bucket charged,
 * whereas {@code "12345"} quoted was limited normally — a free multiplier for any account whose name
 * is numeric, and a free lane for spraying.
 *
 * <p>Shares {@link RateLimitBypassIT}'s context configuration verbatim so Spring reuses the cached
 * context rather than booting a second one; the {@code @BeforeEach} reset is what keeps the two
 * classes from spending each other's allowances.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "zero.ratelimit.enabled=true",
                "zero.ratelimit.capacity=2",
                "zero.ratelimit.refill-period=PT1M",
                "zero.ratelimit.trusted-proxy-count=1"
        })
class RateLimitContentTypeBypassIT extends AbstractIntegrationIT {

    private static final int CAPACITY = 2;
    private static final String LOGIN_PATH = "/api/auth/login";

    /** The pad from the live reproduction: ~20 KB, comfortably past the 16 KB inspection bound. */
    private static final String PAD = "A".repeat(20 * 1024);

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @BeforeEach
    void clearBuckets() {
        rateLimitFilter.reset();
    }

    // --- C1: structured +json suffixes -----------------------------------

    /**
     * The headline finding. Same oversized body, same rotating {@code X-Forwarded-For}, three media
     * types the Jackson converter accepts and the old check did not recognise. A 401 here is the B2
     * bypass still live: the body was forwarded uninspected, the username bucket was never charged,
     * and the rotating header defeated the IP bucket.
     */
    @Test
    void aStructuredJsonSuffixCannotSkipTheOversizedBodyRefusal() {
        int address = 0;
        for (String contentType : List.of(
                "application/vnd.attack+json", "application/hal+json", "application/problem+json")) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                ResponseEntity<JsonNode> response = postOversized("198.51.100." + (++address), contentType);

                assertThat(response.getStatusCode())
                        .as("%s attempt %d: the converter deserializes this exactly like "
                                + "application/json, so the limiter has to inspect it exactly like "
                                + "application/json — a 401 here is unlimited credential stuffing",
                                contentType, attempt)
                        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                assertThat(String.valueOf(response.getBody()))
                        .as("%s must not reach the credential check", contentType)
                        .doesNotContain("LOGIN_FAILED");
            }
        }
    }

    /**
     * The normal-sized half of the same bypass, and the one that matters most: every request below
     * comes from a different address, so nothing but the username bucket can stop the last one.
     * Live before the fix: six attempts at one account, six 401s.
     */
    @Test
    void aStructuredJsonSuffixCannotSkipTheUsernameBucket() {
        String victim = "vendor-json-victim";

        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            assertThat(post("203.0.114." + attempt, victim, "application/vnd.attack+json").getStatusCode())
                    .as("attempt %d from a fresh address is inside the username allowance", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(post("203.0.114.99", victim, "application/vnd.attack+json").getStatusCode())
                .as("the address changed on every request, so only the username bucket can refuse "
                        + "this one — a 401 means an attacker rotating source addresses had no limit "
                        + "at all as long as it renamed the media type")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * The two spellings must share one allowance, not two. Counting them separately would leave the
     * limit in place and still hand out a fresh capacity for every media type an attacker invents.
     */
    @Test
    void aVendorSpellingDrawsOnTheSameAllowanceAsPlainJson() {
        String victim = "shared-allowance-victim";

        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            assertThat(post("203.0.116." + attempt, victim, MediaType.APPLICATION_JSON_VALUE).getStatusCode())
                    .as("arrange step: attempt %d spends the plain-JSON allowance", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(post("203.0.116.99", victim, "application/vnd.acme+json").getStatusCode())
                .as("renaming the media type must not mint a second allowance for the same account")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * The spelling matrix the suite never had (C7). Case and parameters are part of the grammar of a
     * media type, not decoration: a check that folds case but not structure, or structure but not
     * parameters, leaves a lane open. All four spellings below reach the same controller.
     */
    @Test
    void everyAcceptedContentTypeSpellingChargesTheSameUsernameBucket() {
        String victim = "spelling-matrix-victim";
        List<String> spellings = List.of("APPLICATION/JSON", "application/json;charset=UTF-8");
        assertThat(spellings).hasSize(CAPACITY);

        for (int attempt = 0; attempt < spellings.size(); attempt++) {
            assertThat(post("203.0.117." + (attempt + 1), victim, spellings.get(attempt)).getStatusCode())
                    .as("arrange step: %s is inside the allowance", spellings.get(attempt))
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(post("203.0.117.98", victim, "Application/VND.Acme+JSON;charset=UTF-8").getStatusCode())
                .as("a suffixed type with a parameter and mixed case is still JSON to the converter")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // --- C2: numeric username coercion ------------------------------------

    /**
     * {@code {"usernameOrEmail":12345}} and {@code {"usernameOrEmail":"12345"}} are the same account
     * to the controller, because Jackson coerces the number into the {@code String} field. They must
     * be the same account to the limiter too. Live before the fix: the numeric spelling answered
     * {@code LOGIN_FAILED} — a real credential check — while charging nothing.
     */
    @Test
    void aNumericUsernameChargesTheSameBucketAsItsQuotedSpelling() {
        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            ResponseEntity<JsonNode> response = postNumericUsername("203.0.115." + attempt);

            assertThat(response.getStatusCode())
                    .as("attempt %d is inside the allowance and reaches the credential check", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().path("code").asText())
                    .as("the premise of this test: an unquoted number really is bound as a username")
                    .isEqualTo("LOGIN_FAILED");
        }

        assertThat(post("203.0.115.99", "12345", MediaType.APPLICATION_JSON_VALUE).getStatusCode())
                .as("the quoted spelling of the same account must find the allowance already spent "
                        + "— a 401 means the unquoted attempts were never counted")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Sets {@code Content-Type} as a raw header rather than through
     * {@link HttpHeaders#setContentType} on purpose: the latter stores a parsed, normalised
     * {@code MediaType}, so the exact spelling under test would never reach the wire.
     */
    private ResponseEntity<JsonNode> post(String forwardedFor, Object username, String contentType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("usernameOrEmail", username);
        body.put("password", "definitely-not-the-password");
        return exchange(body, forwardedFor, contentType);
    }

    private ResponseEntity<JsonNode> postNumericUsername(String forwardedFor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("usernameOrEmail", 12345);
        body.put("password", "definitely-not-the-password");
        return exchange(body, forwardedFor, MediaType.APPLICATION_JSON_VALUE);
    }

    private ResponseEntity<JsonNode> postOversized(String forwardedFor, String contentType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("usernameOrEmail", "victim-big");
        body.put("password", "definitely-not-the-password");
        body.put("pad", PAD);
        return exchange(body, forwardedFor, contentType);
    }

    private ResponseEntity<JsonNode> exchange(Map<String, Object> body, String forwardedFor, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        headers.set("X-Forwarded-For", forwardedFor);
        headers.set(TENANT_HEADER, "default");
        return restTemplate.exchange(
                LOGIN_PATH, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
