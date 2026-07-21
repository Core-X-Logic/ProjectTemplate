package com.mycompanyname.zero.identity.auth;

import com.mycompanyname.zero.config.JwtProperties;
import com.mycompanyname.zero.identity.domain.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class JwtService {

    private final JwtProperties properties;
    private final JwtKeyRing keyRing;
    private final Clock clock;
    private final JwtEncoder encoder;

    public JwtService(JwtProperties properties, JwtKeyRing keyRing, Clock clock) {
        this.properties = properties;
        this.keyRing = keyRing;
        this.clock = clock;
        // Signs from the ACTIVE key only. The header carries its kid so the decoder can select the
        // matching verification key; the rest of the ring exists solely to verify grace-window tokens.
        this.encoder = new NimbusJwtEncoder(keyRing.signingJwkSource());
    }

    public String issueAccessToken(User user, Set<String> authorities) {
        return issueAccessToken(user, authorities, null, null);
    }

    /**
     * Issues an access token that additionally carries the impersonation {@code act} (actor) claim
     * set: {@code act} = the real user's id and {@code actTenant} = the real user's tenant id.
     * When {@code actorUserId} is {@code null} this behaves exactly like the two-argument overload
     * (no impersonation claims), so existing callers are unaffected.
     *
     * <p>Every token also carries a random {@code jti} — the handle the revocation store keys on
     * (PROD-R16). It is additive: decoders that read claims by name are unaffected.
     */
    public String issueAccessToken(User user, Set<String> authorities, Long actorUserId, Long actorTenantId) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                // PROD-R16: binds the token to this API. JwtAudienceValidator enforces the other half.
                .audience(List.of(properties.getAudience()))
                .subject(String.valueOf(user.getId()))
                // PROD-R16: the revocation handle. Random per token, so revoking one leaves siblings alone.
                .id(UUID.randomUUID().toString())
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
        // PROD-R16: the kid lets the decoder pick the right key across a rotation. Transparent to clients.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS512).keyId(keyRing.activeKid()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
