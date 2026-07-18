package com.mycompanyname.zero.security;

import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence for PROD-R3. The allowed origin comes from {@code application-test.yml}
 * ({@code zero.cors.allowed-origins}), so these tests exercise the configured path rather than a
 * hard-coded one.
 */
class CorsPolicyIT extends AbstractIntegrationIT {

    private static final String ALLOWED_ORIGIN = "https://app.test.example.com";
    private static final String FOREIGN_ORIGIN = "https://evil.example.com";

    @Test
    void preflightFromAConfiguredOriginIsApproved() {
        ResponseEntity<String> response = preflight(ALLOWED_ORIGIN, "POST");

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("without this the SPA cannot make a single call, got %s", response.getStatusCode())
                .isTrue();
        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
        assertThat(headers.getAccessControlAllowMethods())
                .contains(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
                        HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS);
        // Spring echoes back the intersection of what was requested and what is allowed, so the
        // preflight above asks for all four: an approval of the whole set is the actual evidence.
        assertThat(headers.getAccessControlAllowHeaders())
                .contains(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "X-Tenant", HttpHeaders.ACCEPT_LANGUAGE);
        assertThat(headers.getAccessControlExposeHeaders())
                .as("the Excel export filename must be readable by the browser")
                .contains(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(headers.getAccessControlMaxAge()).isEqualTo(1800L);
    }

    @Test
    void credentialsAreNeverAllowed() {
        // The SPA authenticates with a bearer token, so no cookie needs to cross origins. Keeping
        // this false is what makes a future misconfigured origin list survivable rather than fatal.
        assertThat(preflight(ALLOWED_ORIGIN, "GET").getHeaders().getAccessControlAllowCredentials())
                .isFalse();
    }

    @Test
    void preflightFromAnUnlistedOriginIsRefused() {
        ResponseEntity<String> response = preflight(FOREIGN_ORIGIN, "POST");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin())
                .as("an unlisted origin must never be echoed back")
                .isNull();
    }

    @Test
    void anUnlistedOriginGetsNoAllowOriginHeaderOnARealRequest() {
        // A rejected preflight is not enough on its own: a simple GET skips preflight entirely, so
        // the actual response must also refuse to name the origin.
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(FOREIGN_ORIGIN);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/localization/languages", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    private ResponseEntity<String> preflight(String origin, String method) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.setAccessControlRequestMethod(HttpMethod.valueOf(method));
        headers.setAccessControlRequestHeaders(List.of(
                HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "X-Tenant", HttpHeaders.ACCEPT_LANGUAGE));
        return restTemplate.exchange(
                "/api/users", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }
}
