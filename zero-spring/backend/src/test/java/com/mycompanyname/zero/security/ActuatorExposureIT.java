package com.mycompanyname.zero.security;

import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-R17 — the operational endpoints were authenticated but not authorized.
 *
 * <p>Found by the closing live smoke, not by the suite: {@code /actuator/prometheus} answered 401 to an
 * anonymous caller, which is what everyone checks, and <b>200 to a tenant user holding zero
 * permissions</b>, which nobody had. {@code /actuator/health/**} was the only actuator path anything
 * claimed; the rest fell through to {@code anyRequest().authenticated()}, and "authenticated" is the
 * weakest possible bar in a multi-tenant product where any customer can create users.
 *
 * <p>It is production behaviour, not a dev artefact. {@code management.endpoints.web.exposure.include}
 * lists {@code health,info,metrics,prometheus} in the <em>base</em> config and neither
 * {@code application-prod.yml} nor {@code application-dev.yml} overrides it, so every profile serves
 * the same set. What leaks is not a password but a map: JVM and heap state, every route name,
 * per-endpoint request counters, and a tenant count derivable from the datasource and cache gauges.
 * Reconnaissance rather than escalation — which is why it is fixed here and rated Medium rather than
 * treated as a stop-ship.
 *
 * <p>The tenant-admin case is the one worth stating out loud. It is not a zero-permission account: it
 * holds the full tenant-side permission set and is the most privileged principal a customer ever gets.
 * It must still be refused, because the boundary being drawn is host-versus-tenant, not
 * privileged-versus-not. {@code settings.host.manage} is host-only by construction, so no tenant role
 * can be edited into holding it.
 *
 * <p>{@code @AutoConfigureObservability} is load-bearing, not decoration. Spring Boot disables metrics
 * export under {@code @SpringBootTest}, so without it {@code PrometheusScrapeEndpoint} is never
 * registered and {@code /actuator/prometheus} answers 404 in the test JVM while answering 200 in dev
 * and prod. The trap is that authorization runs <em>before</em> the handler: the anonymous and tenant
 * assertions below would have gone green against a path that does not exist, proving nothing about the
 * one that does. Measured — the first run of this class failed only on the host-admin control, which
 * is the single assertion an absent endpoint cannot fake.
 *
 * <p>{@code /actuator/health} stays anonymous on purpose and is asserted here rather than left to
 * inference: the liveness and readiness probes call it with no credentials, so an over-broad rule on
 * {@code /actuator/**} would not fail loudly — it would fail as a pod that never becomes ready. That is
 * the same self-lock shape the subscription guard had to avoid, and it is worth a test in both places.
 */
@AutoConfigureObservability
class ActuatorExposureIT extends AbstractIntegrationIT {

    /** Everything under /actuator that is exposed and is not a probe. */
    private static final List<String> OPERATIONAL_PATHS =
            List.of("/actuator/metrics", "/actuator/prometheus", "/actuator/info");

    /** The probe paths. Kubernetes calls these with no credentials. */
    private static final List<String> PROBE_PATHS =
            List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");

    @Test
    void theProbesStayAnonymous() {
        for (String path : PROBE_PATHS) {
            ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

            assertThat(response.getStatusCode())
                    .as("%s is called by the liveness/readiness probes with no credentials. If the "
                            + "PROD-R17 rule ever widens to cover it, nothing here throws — the pod "
                            + "simply never becomes ready, at 03:00, during a rollout", path)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void anonymousCallersCannotReadTheOperationalEndpoints() {
        for (String path : OPERATIONAL_PATHS) {
            ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

            assertThat(response.getStatusCode())
                    .as("%s to an anonymous caller", path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    /**
     * The finding itself. This is the assertion that fails on the unfixed code — verified by reverting
     * the {@code /actuator/**} matcher, at which point every path here answers 200.
     */
    @Test
    void aTenantUserWithNoPermissionsCannotReadTheOperationalEndpoints() {
        HttpHeaders headers = headersFor(newTenantUserWithNoPermissions());

        for (String path : OPERATIONAL_PATHS) {
            ResponseEntity<String> response = restTemplate.exchange(
                    path, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            assertThat(response.getStatusCode())
                    .as("%s to a tenant user holding zero permissions — this answered 200 live, "
                            + "exposing heap state, route names and request counters. Body: %s",
                            path, response.getBody())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    /**
     * The tenant <em>admin</em>, not a stripped-down account: the most privileged principal a customer
     * can hold. Refused for what it is (tenant-side), not for what it lacks.
     */
    @Test
    void theTenantAdminIsAlsoRefused() {
        HttpHeaders headers = bearerHeaders(
                accessToken("default", SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), "default");

        for (String path : OPERATIONAL_PATHS) {
            ResponseEntity<String> response = restTemplate.exchange(
                    path, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            assertThat(response.getStatusCode())
                    .as("%s to the tenant admin — the host/tenant boundary is the point, so the "
                            + "customer's most privileged account is refused too", path)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    /**
     * The control. A rule that refused everyone would pass all three tests above and leave the
     * operator with no metrics at all — a worse outcome than the finding.
     */
    @Test
    void theHostAdminCanStillOperateTheInstallation() {
        HttpHeaders headers = bearerHeaders(
                accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);

        for (String path : OPERATIONAL_PATHS) {
            ResponseEntity<String> response = restTemplate.exchange(
                    path, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            assertThat(response.getStatusCode())
                    .as("%s to the host admin — settings.host.manage is the authority the rule is "
                            + "keyed on, and monitoring has to keep working", path)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    /** A tenant user created through the API with no roles, so it carries no authorities at all. */
    private String newTenantUserWithNoPermissions() {
        HttpHeaders tenantAdmin = bearerHeaders(
                accessToken("default", SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), "default");

        String username = "actuator_probe_" + System.nanoTime();
        Map<String, Object> body = Map.of(
                "username", username,
                "email", username + "@example.com",
                "password", "Password123!",
                "roleNames", Set.of());

        ResponseEntity<String> created = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(body, tenantAdmin), String.class);
        assertThat(created.getStatusCode())
                .as("test setup: creating the zero-permission user. Body: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);

        return accessToken("default", username, "Password123!");
    }

    private HttpHeaders headersFor(String token) {
        return bearerHeaders(token, "default");
    }
}
