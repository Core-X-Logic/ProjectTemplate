package com.mycompanyname.zero.security;

import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence for PROD-R4 (HSTS behind a TLS-terminating proxy) and PROD-R5 (CSP, Referrer-Policy,
 * Permissions-Policy, frame-options).
 */
class SecurityHeadersIT extends AbstractIntegrationIT {

    /** permitAll, so the assertions are about headers and not about authentication. */
    private static final String PUBLIC_PATH = "/api/localization/languages";

    @Test
    void everyResponseCarriesTheHardeningHeaders() {
        HttpHeaders headers = get(PUBLIC_PATH, new HttpHeaders()).getHeaders();

        assertThat(headers.getFirst("Content-Security-Policy"))
                .as("PROD-R5: a JSON API must declare that nothing may be loaded")
                .contains("default-src 'none'")
                .contains("frame-ancestors 'none'")
                .contains("base-uri 'self'")
                .contains("form-action 'self'");
        assertThat(headers.getFirst("Referrer-Policy"))
                .isEqualTo("strict-origin-when-cross-origin");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("Permissions-Policy"))
                .as("PROD-R5: powerful browser features are switched off explicitly")
                .contains("camera=()")
                .contains("geolocation=()")
                .contains("microphone=()");
    }

    @Test
    void hstsIsWrittenWhenTheProxyReportsATlsRequest() {
        // PROD-R4 in one assertion. Spring Security writes HSTS only when request.isSecure() is true.
        // Behind a TLS-terminating proxy the hop into Tomcat is plain HTTP, so without
        // server.forward-headers-strategy=framework the header is silently dropped in exactly the
        // deployment shape that needs it. This request mimics that proxy.
        HttpHeaders proxied = new HttpHeaders();
        proxied.set("X-Forwarded-Proto", "https");
        proxied.set("X-Forwarded-Host", "api.example.com");

        String hsts = get(PUBLIC_PATH, proxied).getHeaders().getFirst("Strict-Transport-Security");

        assertThat(hsts)
                .as("HSTS must survive the proxy hop")
                .isNotNull()
                .contains("max-age=31536000")
                .contains("includeSubDomains")
                .contains("preload");
    }

    @Test
    void hstsIsOmittedOnAPlainHttpRequest() {
        // The other half of the same behaviour: without a TLS signal the header would be a lie, and
        // a browser that honoured it would lock itself out of a plain-HTTP local environment.
        assertThat(get(PUBLIC_PATH, new HttpHeaders()).getHeaders().getFirst("Strict-Transport-Security"))
                .isNull();
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
