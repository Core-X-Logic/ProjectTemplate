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
 * The DEFAULT context — {@code zero.billing.paytr.enabled} at its base {@code false} — is where the
 * off-state has to be proven: a fresh clone serves the PayTR route (it must exist on every profile,
 * {@code SecurityPathBindingIT} requires every grant and every throttle entry to resolve to a live
 * route) but the surface answers 404, because no provider bean is registered.
 *
 * <p>404 and not 401 is the half worth reading twice: the anonymous grant IS in force on the route
 * (the chain lets the credential-free POST through to the handler), and the 404 is the
 * application's own "this surface does not exist here" from {@code BillingWebhookService} — the
 * same shape the Stripe webhook has always had with billing off.
 */
class PayTRDisabledSurfaceIT extends AbstractIntegrationIT {

    @Test
    @DisplayName("with paytr disabled the webhook route exists and answers 404, not 401 and not 200")
    void disabledPayTRWebhookAnswers404() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/billing/webhook/paytr", HttpMethod.POST,
                new HttpEntity<>("merchant_oid=ZPX&status=success&total_amount=1&hash=x", headers),
                JsonNode.class);

        assertThat(response.getStatusCode())
                .as("the surface must not exist with the flag off — and it must not be 401 "
                        + "either, which would mean the anonymous grant fell off the route")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("NOT_FOUND");
    }
}
