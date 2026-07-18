package com.mycompanyname.zero.security;

import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence for C5 — the B6 lockdown must not depend on the {@code prod} profile being set.
 *
 * <p>{@code SecurityConfig} guarded the springdoc {@code permitAll} with {@code if (!production)},
 * and {@code application-prod.yml} disables springdoc only under {@code prod}. Both locks therefore
 * keyed off the same condition: a deployment that boots without {@code SPRING_PROFILES_ACTIVE}
 * — the single most common configuration accident there is — got neither. Live, with
 * {@code No active profile set} in the log, {@code GET /v3/api-docs} answered 200 with the complete
 * OpenAPI document.
 *
 * <p>That made B6 fail-<em>open</em> while B4 (seeding, in this very same codebase and against this
 * very same threat model) was deliberately made fail-<em>closed</em>: {@code zero.seed.enabled}
 * defaults to {@code false} in the base configuration and {@code dev}/{@code test} opt back in. The
 * asymmetry, not the exposure alone, is the finding — one profile mishap disabled a control that a
 * neighbouring control had already been hardened against.
 *
 * <p>The fix inverts the door: the base configuration is closed, and only {@code dev} and
 * {@code test} open it. This class boots with <em>no</em> active profile at all, which is exactly
 * what the live reproduction did. {@link ApiDocsExposureIT} and {@link DevProfileSecurityIT} assert
 * the other side — that the tooling which legitimately needs the document still has it.
 *
 * <p>The properties below are only what the base configuration genuinely requires from its
 * environment and would otherwise take from {@code application-test.yml}, which is not loaded here.
 * {@code inheritProfiles = false} is what makes that true: inheriting {@code test} would put the
 * profile under test back into the context and quietly turn this into a test of nothing.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 64 random bytes, base64. Test scaffolding only; this context mints no usable token.
                "zero.jwt.secret=ZGVmYXVsdC1wcm9maWxlLWFzc2VydGlvbi1rZXktbm90LXVzZWQtYnktYW55LWRlcGxve"
                        + "W1lbnQtMDEyMzQ1Njc4OWFiY2RlZmc=",
                "spring.cache.type=simple",
                "management.health.redis.enabled=false",
                "zero.saas.lifecycle.initial-delay=PT24H",
                "zero.saas.lifecycle.interval=PT24H"
        })
@ActiveProfiles(value = {}, inheritProfiles = false)
class DefaultProfileApiDocsExposureIT extends AbstractIntegrationIT {

    @Test
    void theOpenApiDocumentIsNotAnonymouslyReadableWithoutAnActiveProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode())
                .as("a deployment that lost its profile must not start handing out the route "
                        + "inventory; got %s", response.getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(response.getBody()))
                .as("not one route name may leak, whichever status is returned")
                .doesNotContain("/api/auth/login");
    }

    @Test
    void theGroupedOpenApiPathsAreNotAnonymouslyReadableEither() {
        assertThat(restTemplate.getForEntity("/v3/api-docs/swagger-config", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
    }

    @Test
    void swaggerUiIsNotAnonymouslyReachableWithoutAnActiveProfile() {
        assertThat(restTemplate.getForEntity("/swagger-ui/index.html", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
    }

    /**
     * The control has to be narrow. Closing the docs must not close the two endpoints a deployment
     * cannot function without, and both of them are anonymous by design.
     */
    @Test
    void healthAndLoginRemainAnonymous() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(login(null, "no-such-account", "definitely-not-the-password").getStatusCode())
                .as("a 401 means login was reached and refused the credentials, which is correct; "
                        + "a 403 would mean the lockdown had swallowed the endpoint itself")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
