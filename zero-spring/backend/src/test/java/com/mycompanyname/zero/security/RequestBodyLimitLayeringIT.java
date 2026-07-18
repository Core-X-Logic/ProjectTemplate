package com.mycompanyname.zero.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 — that "the stricter of the two body limits wins" is a property of the design and not an
 * accident of the shipped numbers.
 *
 * <p>Two limits apply to {@code /api/auth/login}: {@code zero.ratelimit.max-body-bytes} (16 KB,
 * enforced by {@code RateLimitFilter}) and {@code zero.request.max-body-bytes} (1 MB, enforced by
 * {@code RequestSizeLimitFilter} immediately after it). {@code RequestBodyLimitIT} shows the first
 * one answering, because with the shipped values it is the stricter.
 *
 * <p>This class <b>inverts</b> them — throttled bound 2 MB, global bound 256 KB — and asserts the
 * other one answers. The inversion is the whole point. With the ordering these two filters are in,
 * the obvious optimisation is for the second to skip a body the first has already measured; that
 * costs nothing at the default values and silently grants the five anonymous paths the <em>looser</em>
 * bound the moment an operator raises the throttled one. It would be a bypass reachable by editing
 * configuration, on exactly the paths B2 and D1 were both about, and it would pass every other test
 * in this suite.
 *
 * <p>Its own context because the point cannot be made without changing the two properties, and they
 * are read at startup.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Deliberately inverted: the anonymous-path bound is now the looser of the two.
                "zero.ratelimit.max-body-bytes=2097152",
                "zero.request.max-body-bytes=262144"
        })
class RequestBodyLimitLayeringIT extends AbstractIntegrationIT {

    private static final int GLOBAL_LIMIT_BYTES = 256 * 1024;

    /** Inside the throttled 2 MB bound, outside the global 256 KB one. */
    private static final int PAD_BYTES = 512 * 1024;

    @Test
    void theGlobalBoundStillAnswersWhenItIsTheStricterOfTheTwo() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TENANT_HEADER, "default");

        Map<String, String> body = new LinkedHashMap<>();
        body.put("usernameOrEmail", "victim-user");
        body.put("password", "definitely-not-the-password");
        body.put("pad", "A".repeat(PAD_BYTES));

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("the rate limiter would wave a 512 KB body through at this configuration; the "
                        + "global bound is what has to stop it, and a 401 here means it never ran")
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("maxBodyBytes").asInt())
                .as("whichever bound is stricter must be the one that answers — here that is the "
                        + "global one, and the 413 has to say so")
                .isEqualTo(GLOBAL_LIMIT_BYTES);
    }

    /** The layering must not cost the anonymous paths their ordinary traffic at any configuration. */
    @Test
    void anOrdinaryLoginStillSucceedsUnderTheInvertedConfiguration() {
        assertThat(login("default", SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
