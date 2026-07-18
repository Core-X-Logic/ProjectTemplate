package com.mycompanyname.zero.security;

import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of B6 that must keep working.
 *
 * <p>Closing an exposure by breaking the tooling that depends on it is not closing it — it is
 * trading one incident for another. The CI typed-client gate generates the frontend API client from
 * {@code /v3/api-docs} on the default profile, and Swagger UI is how the API is explored locally.
 * Both are asserted here so the prod lockdown in {@link ProdApiDocsExposureIT} cannot be quietly
 * widened into every environment.
 */
class ApiDocsExposureIT extends AbstractIntegrationIT {

    @Test
    void theOpenApiDocumentIsServedOutsideProduction() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode())
                .as("the CI typed-client gate generates the frontend client from this document")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"openapi\"").contains("/api/auth/login");
    }

    @Test
    void swaggerUiIsServedOutsideProduction() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
