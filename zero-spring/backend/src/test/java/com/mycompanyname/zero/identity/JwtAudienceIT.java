package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.config.JwtProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Evidence for PROD-R16 (audience half).
 *
 * <p>The decoder previously validated timestamps and the issuer only. Any token signed with the same
 * HMAC secret was therefore accepted here — a sibling service configured with the same key, or a
 * staging deployment pointed at the same secret, could mint tokens this API honoured.
 */
class JwtAudienceIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void issuedAccessTokensCarryTheConfiguredAudience() throws Exception {
        String token = accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);

        JsonNode claims = decodePayload(token);
        JsonNode audience = claims.path("aud");

        assertThat(audience.isArray() ? audience.get(0).asText() : audience.asText())
                .isEqualTo(jwtProperties.getAudience());
        assertThat(claims.path("iss").asText()).isEqualTo(jwtProperties.getIssuer());
    }

    @Test
    void aTokenIssuedForThisAudienceIsAccepted() {
        String token = accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);

        assertThat(jwtDecoder.decode(token).getAudience()).contains(jwtProperties.getAudience());

        ResponseEntity<JsonNode> me = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token, DEFAULT_TENANT)), JsonNode.class);
        assertThat(me.getStatusCode())
                .as("adding audience validation must not break the ordinary authenticated path")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aTokenMintedForAnotherAudienceIsRejected() {
        // Same secret, same issuer, valid timestamps — only 'aud' differs. Before this change the
        // decoder had no reason to refuse it, which is what made a shared secret a shared identity.
        String foreignToken = mintWithAudience("some-other-service");

        assertThatThrownBy(() -> jwtDecoder.decode(foreignToken))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void aTokenWithNoAudienceAtAllIsRejected() {
        // Covers the upgrade path: tokens issued by the previous release carry no 'aud' claim.
        assertThatThrownBy(() -> jwtDecoder.decode(mintWithAudience(null)))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("audience");
    }

    private JsonNode decodePayload(String token) throws Exception {
        byte[] decoded = Base64.getUrlDecoder().decode(token.split("\\.")[1]);
        return objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
    }

    /**
     * Signs a structurally valid token with this deployment's own secret — the exact artefact a
     * second service sharing the key would produce.
     */
    private String mintWithAudience(String audience) {
        SecretKeySpec key = new SecretKeySpec(
                Base64.getDecoder().decode(jwtProperties.getSecret()), "HmacSHA512");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .subject("1")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300));
        if (audience != null) {
            claims.audience(List.of(audience));
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS512).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
