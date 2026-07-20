package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DEFAULT context — {@code zero.billing.iyzico.enabled} at its base {@code false} — is where
 * the off-state has to be proven, the {@code PayTRDisabledSurfaceIT} pattern for the third
 * provider and BOTH its routes: a fresh clone serves them (every grant and throttle entry must
 * resolve to a live route, {@code SecurityPathBindingIT}) but each answers 404, because no
 * provider bean is registered.
 *
 * <p>404 and not 401 is the half worth reading twice: the anonymous grant IS in force on both
 * routes (the chain lets the credential-free request through to the handler), and the 404 is the
 * application's own "this surface does not exist here" — from {@code BillingWebhookService} on the
 * webhook and from {@code BillingCallbackController}'s registry miss on the callback.
 */
class IyzicoDisabledSurfaceIT extends AbstractIntegrationIT {

    @Test
    @DisplayName("with iyzico disabled the webhook route exists and answers 404, not 401 and not 200")
    void disabledIyzicoWebhookAnswers404() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-IYZ-SIGNATURE-V3", "00");
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/billing/webhook/iyzico", HttpMethod.POST,
                new HttpEntity<>("{\"token\":\"t\",\"status\":\"SUCCESS\"}", headers),
                JsonNode.class);

        assertThat(response.getStatusCode())
                .as("the surface must not exist with the flag off — and it must not be 401 "
                        + "either, which would mean the anonymous grant fell off the route")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("with iyzico disabled the browser callback answers 404 on GET and POST alike")
    void disabledIyzicoCallbackAnswers404() {
        ResponseEntity<JsonNode> get = restTemplate.exchange(
                "/api/billing/callback/iyzico?token=t", HttpMethod.GET,
                HttpEntity.EMPTY, JsonNode.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get.getBody()).isNotNull();
        assertThat(get.getBody().path("code").asText()).isEqualTo("NOT_FOUND");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<JsonNode> post = restTemplate.exchange(
                "/api/billing/callback/iyzico", HttpMethod.POST,
                new HttpEntity<>("token=t", headers), JsonNode.class);
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
