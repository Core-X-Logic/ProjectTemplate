package com.mycompanyname.zero.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.architecture.EndpointInventory;
import com.mycompanyname.zero.shared.web.EndpointPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the R-38A inversion: {@code AuditLogInterceptor} no longer holds any path owned by another
 * module, and behaves exactly as it did when it did.
 *
 * <p>Before this change the interceptor read
 * {@code uri.startsWith("/api/auth/login") || uri.startsWith("/api/auth/refresh")} — two strings
 * belonging to {@code identity}, hardcoded inside a module whose
 * {@code allowedDependencies = {"shared"}} says it depends on nothing else. It was the tightest
 * boundary in the codebase and it was not true. Nothing could see that: Modulith compares package
 * references, and a URL is not one.
 *
 * <p>This is the one of the five measured edges that is DELETED rather than cross-checked. The
 * container already resolves the {@code HandlerMethod} before {@code preHandle}, so the handler can
 * state the exemption itself and {@code audit}'s declared boundary becomes true rather than merely
 * unfalsified.
 */
class AuditExemptionIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";

    // Actuator contributes controllerEndpointHandlerMapping, which is also a
    // RequestMappingHandlerMapping; the qualifier picks the MVC one that serves /api.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /**
     * Behavioural equivalence. Login must still leave no audit row (its parameters are credentials),
     * and a neighbouring endpoint on the same controller must still be recorded — otherwise the
     * annotation read could be skipping everything and this test would pass on a broken interceptor.
     */
    @Test
    @DisplayName("login is not audited, logout on the same controller still is")
    void theAnnotationDrivenSkipBehavesLikeTheDeletedLiterals() {
        HttpHeaders admin = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);

        String marker = "auditexempt" + System.nanoTime();

        // Anonymous, AUDIT_EXEMPT: must leave no row even though it is under /api/**.
        HttpHeaders anonymous = new HttpHeaders();
        anonymous.setContentType(MediaType.APPLICATION_JSON);
        anonymous.set(TENANT_HEADER, DEFAULT_TENANT);
        restTemplate.exchange("/api/auth/login?auditMarker=" + marker, HttpMethod.POST,
                new HttpEntity<>(Map.of("usernameOrEmail", SEED_ADMIN_USERNAME,
                        "password", SEED_ADMIN_PASSWORD), anonymous), JsonNode.class);

        // Not exempt: proves the interceptor is still recording on this controller at all.
        String controlMarker = "auditcontrol" + System.nanoTime();
        ResponseEntity<JsonNode> control = restTemplate.exchange(
                "/api/users?auditMarker=" + controlMarker, HttpMethod.GET,
                new HttpEntity<>(admin), JsonNode.class);
        assertThat(control.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(auditRowExists(admin, controlMarker, 15))
                .describedAs("the control call was not recorded, so the absence of a login row "
                        + "would prove nothing about the exemption — it would only prove auditing "
                        + "is off")
                .isTrue();
        // The login happened BEFORE the control call and the log is sorted execution_time DESC, so
        // by the time the control row is visible the login row would be too, had it been written.
        assertThat(auditRowExists(admin, marker, 2))
                .describedAs("POST /api/auth/login was recorded in audit_logs. Its parameters are "
                        + "credentials; AUDIT_EXEMPT on the handler is what keeps them out")
                .isFalse();
    }

    /**
     * Inversion decentralises the decision, so this is what keeps it reviewable: the COMPLETE set of
     * audit exemptions in the codebase is pinned here. Adding one anywhere becomes a required, visible
     * edit to one enumerated list — the same single-point review the deleted string list provided,
     * without the cross-module string.
     */
    @Test
    @DisplayName("the audit-exempt set is exactly login, refresh and the 2FA verify")
    void theAuditExemptSetIsExactly() {
        Set<String> exempt = new EndpointInventory(handlerMapping)
                .handlerKeysClaiming(EndpointPolicy.Exposure.AUDIT_EXEMPT);

        assertThat(exempt)
                .describedAs("audit exemptions are decentralised onto the handlers, so this list is "
                        + "the review point. An endpoint added here escapes the audit trail: that is "
                        + "a deliberate decision and must be made in a diff someone reads")
                // AuthController#verifyTwoFactor is exempt for the same reason as login: its body
                // carries a live second-factor code (TOTP or recovery code), which must never be
                // persisted in audit_logs. Reviewed and intended.
                .containsExactlyInAnyOrder("AuthController#login", "AuthController#refresh",
                        "AuthController#verifyTwoFactor");
    }

    /** size<=max-page-size(100); the service sorts execution_time DESC, so recent calls are page 0. */
    private boolean auditRowExists(HttpHeaders admin, String marker, int attempts) {
        for (int attempt = 0; attempt < attempts; attempt++) {
            ResponseEntity<JsonNode> page = restTemplate.exchange(
                    "/api/audit-logs?page=0&size=100", HttpMethod.GET,
                    new HttpEntity<>(admin), JsonNode.class);
            if (page.getStatusCode() == HttpStatus.OK && page.getBody() != null) {
                for (JsonNode entry : pageContent(page.getBody())) {
                    if (entry.path("url").asText("").contains(marker)
                            || entry.path("parameters").asText("").contains(marker)) {
                        return true;
                    }
                }
            }
            try {
                Thread.sleep(400L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }
}
