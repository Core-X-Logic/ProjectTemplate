package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFlowIT extends AbstractIntegrationIT {

    @Test
    void hostAdminLoginReturnsFilledTokenPair() {
        JsonNode pair = loginOk(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);

        assertThat(pair.path("accessToken").asText()).isNotBlank();
        assertThat(pair.path("refreshToken").asText()).isNotBlank();
        assertThat(pair.path("expiresInSeconds").asLong()).isPositive();
    }

    @Test
    void wrongPasswordReturns401ProblemDetail() {
        ResponseEntity<JsonNode> response = login(null, SEED_ADMIN_USERNAME, "definitely-wrong-1!");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("status").asInt()).isEqualTo(401);
    }

    @Test
    void meWithBearerTokenReturnsHostAdminIdentity() {
        String token = accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        HttpHeaders headers = bearerHeaders(token, null);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("username").asText()).isEqualTo(SEED_ADMIN_USERNAME);

        JsonNode tenantId = body.path("tenantId");
        assertThat(tenantId.isMissingNode() || tenantId.isNull())
                .as("host admin tenantId must be null")
                .isTrue();

        List<String> permissions = new ArrayList<>();
        body.path("permissions").forEach(node -> permissions.add(node.asText()));
        assertThat(permissions).contains("users.read");
    }

    @Test
    void refreshRotatesTokensAndOldRefreshIsRejected() {
        JsonNode pair = loginOk(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        String oldRefresh = pair.path("refreshToken").asText();
        assertThat(oldRefresh).isNotBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<JsonNode> refreshed = restTemplate.exchange(
                "/api/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", oldRefresh), headers), JsonNode.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode newPair = refreshed.getBody();
        assertThat(newPair).isNotNull();
        assertThat(newPair.path("accessToken").asText()).isNotBlank();
        assertThat(newPair.path("refreshToken").asText())
                .isNotBlank()
                .isNotEqualTo(oldRefresh);

        ResponseEntity<JsonNode> reuse = restTemplate.exchange(
                "/api/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", oldRefresh), headers), JsonNode.class);

        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void revokedRefreshReuseCascadesRevocationToAllUserTokens() {
        // two independent sessions for the same user
        JsonNode first = loginOk(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        JsonNode second = loginOk(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        String refreshA = first.path("refreshToken").asText();
        String refreshB = second.path("refreshToken").asText();
        assertThat(refreshA).isNotBlank();
        assertThat(refreshB).isNotBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // rotate A once: A becomes revoked
        ResponseEntity<JsonNode> rotated = restTemplate.exchange(
                "/api/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", refreshA), headers), JsonNode.class);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);

        // presenting the revoked A again -> 401 (reuse detected)
        ResponseEntity<JsonNode> reuse = restTemplate.exchange(
                "/api/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", refreshA), headers), JsonNode.class);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // cascade: the user's OTHER, previously valid refresh token must now be revoked too
        ResponseEntity<JsonNode> cascaded = restTemplate.exchange(
                "/api/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", refreshB), headers), JsonNode.class);
        assertThat(cascaded.getStatusCode())
                .as("reuse detection must revoke every refresh token of the user")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void usersEndpointWithoutTokenReturns401() {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/users", HttpMethod.GET, HttpEntity.EMPTY, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
