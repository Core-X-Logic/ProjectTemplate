package com.mycompanyname.zero.identity.auth;

import com.mycompanyname.zero.config.JwtProperties;
import com.mycompanyname.zero.identity.domain.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

@Service
public class JwtService {

    /** HS512 requires a key of at least 512 bits (64 bytes). */
    static final int MIN_SECRET_KEY_BYTES = 64;

    private final JwtProperties properties;
    private final JwtEncoder encoder;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        SecretKeySpec secretKey = buildSecretKey(properties.getSecret());
        JWKSource<SecurityContext> jwkSource = new ImmutableSecret<>(secretKey);
        this.encoder = new NimbusJwtEncoder(jwkSource);
    }

    public String issueAccessToken(User user, Set<String> authorities) {
        return issueAccessToken(user, authorities, null, null);
    }

    /**
     * Issues an access token that additionally carries the impersonation {@code act} (actor) claim
     * set: {@code act} = the real user's id and {@code actTenant} = the real user's tenant id.
     * When {@code actorUserId} is {@code null} this behaves exactly like the two-argument overload
     * (no impersonation claims), so existing callers are unaffected.
     */
    public String issueAccessToken(User user, Set<String> authorities, Long actorUserId, Long actorTenantId) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                // PROD-R16: binds the token to this API. JwtAudienceValidator enforces the other half.
                .audience(List.of(properties.getAudience()))
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(now.plus(properties.getAccessTokenTtl()))
                .claim("username", user.getUsername())
                .claim("authorities", List.copyOf(authorities));
        if (user.getTenantId() != null) {
            claims.claim("tenant", user.getTenantId());
        }
        if (actorUserId != null) {
            claims.claim("act", actorUserId);
        }
        if (actorTenantId != null) {
            claims.claim("actTenant", actorTenantId);
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS512).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    /**
     * Builds the HS512 key from the configured secret. Runs at bean init (JwtService constructor
     * and the JwtDecoder bean), so a missing, non-base64 or too-short secret fails startup fast
     * instead of silently degrading key strength.
     */
    static SecretKeySpec buildSecretKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("zero.jwt.secret is not configured");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "zero.jwt.secret must be valid base64; raw text secrets are not accepted", ex);
        }
        if (keyBytes.length < MIN_SECRET_KEY_BYTES) {
            throw new IllegalStateException("zero.jwt.secret must decode to at least "
                    + MIN_SECRET_KEY_BYTES + " bytes for HS512, but was " + keyBytes.length + " bytes");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA512");
    }
}
