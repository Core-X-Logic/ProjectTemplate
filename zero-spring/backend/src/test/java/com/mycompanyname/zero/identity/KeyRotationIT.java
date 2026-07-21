package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.config.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PROD-R16 (key rotation). Boots with an explicit two-key ring and pins the four kid outcomes the
 * decoder must honour for a zero-downtime rotation:
 *
 * <ul>
 *   <li>the ACTIVE key (what login signs with) verifies and serves requests;</li>
 *   <li>a PREVIOUS (grace-window) key still in the ring verifies — the whole point of a grace window;</li>
 *   <li>a token whose kid is NOT in the ring (a retired or foreign key) is rejected — fail-closed;</li>
 *   <li>a NO-kid token (an in-flight token minted by the pre-rotation code during a rolling deploy)
 *       verifies via the active-key fallback — and only via the active key, not "try every key".</li>
 * </ul>
 *
 * <p>The ring is supplied through {@code @SpringBootTest} properties, so the legacy test secret in
 * {@code application-test.yml} is ignored here and this context stands alone.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "zero.jwt.active-kid=k-active-2026",
                "zero.jwt.keys[0].kid=k-active-2026",
                "zero.jwt.keys[0].secret=" + KeyRotationIT.KEY_ACTIVE,
                "zero.jwt.keys[1].kid=k-grace-2025",
                "zero.jwt.keys[1].secret=" + KeyRotationIT.KEY_GRACE
        })
class KeyRotationIT extends AbstractIntegrationIT {

    static final String KEY_ACTIVE =
            "0RHdZWWiWSkAy7eqRHT/VAloxKrgRO5gtwRSkNqx9lwG3ijPrAWdJaCHzbzJ+/PLyc1HnFUsw6CY4R+f5/pPcQ==";
    static final String KEY_GRACE =
            "2S/3JQku8aklmMRxJ4KJ/YZKaw/KKsmu8QHeoUzVCdSSueLW3+XgNwxi2uCDyHwT8+7biKR5dD+esj+BqLZD5w==";
    // NOT in the ring — the retired key an unknown-kid token would be signed with.
    private static final String KEY_RETIRED =
            "xUDH5OP1YHJ6ZF/WzuMOk6t3l0Qe3HItDn+R60cQbyrzyeARjPB2t4SJ7CMzDn02xDeH9ZtYjr/dLw/RH/SY4Q==";

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    void theActiveKeyVerifiesAndServesRequests() {
        String token = accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);

        Jwt decoded = jwtDecoder.decode(token);
        assertThat(decoded.getHeaders().get("kid"))
                .as("login must sign with the active kid")
                .isEqualTo("k-active-2026");

        ResponseEntity<JsonNode> me = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(bearerHeaders(token, null)), JsonNode.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aGraceWindowKeyStillVerifies() {
        String graceToken = mint(KEY_GRACE, "k-grace-2025");

        assertThatCode(() -> jwtDecoder.decode(graceToken))
                .as("a token signed by a previous key still in the ring must verify")
                .doesNotThrowAnyException();
        assertThat(jwtDecoder.decode(graceToken).getHeaders().get("kid")).isEqualTo("k-grace-2025");
    }

    @Test
    void anUnknownKidIsRejected() {
        String retiredToken = mint(KEY_RETIRED, "k-retired-2024");

        assertThatThrownBy(() -> jwtDecoder.decode(retiredToken))
                .as("a kid that is not in the ring must be rejected at the signature step")
                .isInstanceOf(JwtException.class);

        ResponseEntity<JsonNode> me = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(retiredToken, null)), JsonNode.class);
        assertThat(me.getStatusCode())
                .as("and the request path returns 401, never 200")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aNoKidTokenVerifiesViaTheActiveKeyFallback() {
        String noKidActive = mint(KEY_ACTIVE, null);

        assertThatCode(() -> jwtDecoder.decode(noKidActive))
                .as("an in-flight no-kid token signed by the active key must still verify (rolling deploy)")
                .doesNotThrowAnyException();
    }

    @Test
    void aNoKidTokenSignedByANonActiveKeyIsRejected() {
        // Proves the fallback is the ACTIVE key only, not a try-every-key search: a no-kid token
        // signed with the grace key does NOT verify against the active key.
        String noKidGrace = mint(KEY_GRACE, null);

        assertThatThrownBy(() -> jwtDecoder.decode(noKidGrace))
                .isInstanceOf(JwtException.class);
    }

    // --- F2: algorithm-confusion. The decoder pins HS512; a token in any other algorithm is refused. --

    @Test
    void anUnsignedNoneAlgorithmTokenIsRejected() {
        String plain = new PlainJWT(nimbusClaims()).serialize();

        assertThatThrownBy(() -> jwtDecoder.decode(plain))
                .as("an alg=none (unsigned) token must never be accepted")
                .isInstanceOf(JwtException.class);
        assertThat(me(plain).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anHs256DowngradeSignedWithTheRealKeyIsRejectedByTheAlgorithmPin() throws Exception {
        // Signed with the ACTIVE key and its kid, valid claims — the ONLY thing wrong is HS256 in place
        // of HS512. So it must be the algorithm PIN, not the key, that rejects it: the 64-byte HS512 key
        // is a perfectly valid HS256 key, so without the pin this would verify (classic downgrade).
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k-active-2026").build(),
                nimbusClaims());
        jwt.sign(new MACSigner(Base64.getDecoder().decode(KEY_ACTIVE)));
        String token = jwt.serialize();

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .as("an HS256 downgrade must be refused even though it is signed with the real HS512 key")
                .isInstanceOf(JwtException.class);
        assertThat(me(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anRs256TokenIsRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPrivateKey privateKey = (RSAPrivateKey) generator.generateKeyPair().getPrivate();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k-active-2026").build(),
                nimbusClaims());
        jwt.sign(new RSASSASigner(privateKey));
        String token = jwt.serialize();

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .as("an RS256 token must be refused; the ring holds only symmetric HS512 keys")
                .isInstanceOf(JwtException.class);
        assertThat(me(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Nimbus claims valid on issuer/audience/exp, so only the algorithm can be the reason for rejection. */
    private JWTClaimsSet nimbusClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(jwtProperties.getIssuer())
                .audience(jwtProperties.getAudience())
                .subject("1")
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
    }

    private ResponseEntity<JsonNode> me(String token) {
        return restTemplate.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token, null)), JsonNode.class);
    }

    /**
     * Mints a structurally valid token signed with {@code base64Secret}. When {@code kid} is non-null
     * it is stamped on both the signing JWK and the header (so a real kid'd token results); when null,
     * a no-kid token is produced. Carries this deployment's issuer and audience so a verified token
     * also clears the claim validators.
     */
    private String mint(String base64Secret, String kid) {
        SecretKey key = new SecretKeySpec(Base64.getDecoder().decode(base64Secret), "HmacSHA512");
        OctetSequenceKey.Builder jwkBuilder = new OctetSequenceKey.Builder(key).algorithm(JWSAlgorithm.HS512);
        if (kid != null) {
            jwkBuilder.keyID(kid);
        }
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwkBuilder.build())));

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .audience(List.of(jwtProperties.getAudience()))
                .subject("1")
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        JwsHeader.Builder header = JwsHeader.with(MacAlgorithm.HS512);
        if (kid != null) {
            header.keyId(kid);
        }
        return encoder.encode(JwtEncoderParameters.from(header.build(), claims)).getTokenValue();
    }
}
