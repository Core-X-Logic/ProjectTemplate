package com.mycompanyname.zero.security;

import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence for B6 — the API description is not readable by anonymous callers in production.
 *
 * <p>Live before the fix: {@code GET /v3/api-docs} answered {@code 200} on every profile and
 * enumerated all 54 {@code /api/} routes with their parameters and response shapes;
 * {@code GET /swagger-ui/index.html} answered {@code 200} with no credentials. Free reconnaissance,
 * served before authentication — and worth nothing to a prod operator, because the strict
 * {@code default-src 'none'} CSP there already prevents Swagger UI from executing.
 *
 * <p>Two independent locks, and this test would pass with either one alone, which is the point:
 * {@code application-prod.yml} turns springdoc off, and {@code SecurityConfig} drops the
 * {@code permitAll} under the {@code prod} profile. Re-enabling springdoc by itself cannot reopen
 * the hole.
 *
 * <p>Runs {@code prod} on top of {@code test} in its own context. The overrides below are the ones
 * prod legitimately requires from its environment — a signing key that is not a committed one
 * (which {@code JwtSecretValidator} would reject under this profile), explicit CORS origins, and no
 * Redis — so the profile under test is the real one rather than a stand-in.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 64 random bytes, base64. Generated once and committed only as test scaffolding:
                // this context never mints a token anyone can use.
                "zero.jwt.secret=cHJvZC1wcm9maWxlLWFzc2VydGlvbi1rZXktbm90LXVzZWQtYnktYW55LWRlcGxveW1lbnQtMDE"
                        + "yMzQ1Njc4OWFiY2RlZmdoaWprbG1ub3A=",
                // A non-committed 32-byte AES key: FieldEncryptionKeyValidator rejects the committed
                // dev/test keys under the prod profile, exactly as JwtSecretValidator rejects theirs.
                "zero.crypto.field-key=fWKX4GJZ2DR+YBl4sMrvUlrYVdnNCrRfTJl4yGGqs4E=",
                "zero.cors.allowed-origins=https://app.prod.example.test",
                "spring.cache.type=simple",
                "management.health.redis.enabled=false",
                "zero.saas.lifecycle.initial-delay=PT24H",
                "zero.saas.lifecycle.interval=PT24H"
        })
@ActiveProfiles({"test", "prod"})
class ProdApiDocsExposureIT extends AbstractIntegrationIT {

    @Test
    void theOpenApiDocumentIsNotAnonymouslyReadableInProduction() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode())
                .as("an anonymous 200 here hands over the whole route inventory before "
                        + "authentication; got %s", response.getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
        // String.valueOf, because the lockdown's own success case is an empty body — and a null
        // actual would make doesNotContain fail on the very outcome this test wants.
        assertThat(String.valueOf(response.getBody()))
                .as("not one route name may leak, whichever status is returned")
                .doesNotContain("/api/auth/login");
    }

    @Test
    void theGroupedOpenApiPathsAreNotAnonymouslyReadableEitherInProduction() {
        // /v3/api-docs/** was a wildcard permit, so the grouped and swagger-config sub-paths were
        // just as open as the root document. Closing only the root would be theatre.
        assertThat(restTemplate.getForEntity("/v3/api-docs/swagger-config", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
    }

    @Test
    void swaggerUiIsNotAnonymouslyReachableInProduction() {
        assertThat(restTemplate.getForEntity("/swagger-ui/index.html", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
    }

    @Test
    void healthRemainsAnonymousInProduction() {
        // The control has to be narrow. Load balancers and orchestrators poll this without
        // credentials, so sweeping it up alongside the docs would take the deployment down.
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void loginRemainsAnonymousInProduction() {
        // Host context and a username that does not exist, so this holds whatever order the IT
        // classes run in — the prod profile does not seed, and asserting against a seeded account
        // would make the test depend on another class having gone first.
        assertThat(login(null, "no-such-account", "definitely-not-the-password").getStatusCode())
                .as("a 401 means the endpoint was reached and the credentials refused, which is the "
                        + "point — the fix must not have made login itself unreachable")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
