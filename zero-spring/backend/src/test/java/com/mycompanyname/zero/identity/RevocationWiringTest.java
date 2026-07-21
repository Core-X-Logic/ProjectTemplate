package com.mycompanyname.zero.identity;

import com.mycompanyname.zero.config.JwtProperties;
import com.mycompanyname.zero.identity.auth.JwtKeyRing;
import com.mycompanyname.zero.identity.auth.SecurityConfig;
import com.mycompanyname.zero.identity.auth.TokenRevocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F1 (PROD-R16): the "enabled ⟹ enforced" invariant for revocation has a fail-fast, and when a service
 * IS present the {@link com.mycompanyname.zero.identity.auth.RevokedTokenValidator} is actually in the
 * decoder's chain (proven behaviourally — a revoked token is refused).
 *
 * <p>Exercises {@link SecurityConfig#jwtDecoder} directly with a stub {@link ObjectProvider}, so the
 * three wiring states are pinned without booting a context:
 * <ul>
 *   <li>revocation enabled + service ABSENT → the factory refuses to build (fail-fast);</li>
 *   <li>revocation disabled + service absent → builds fine, no revocation;</li>
 *   <li>revocation enabled + service present → builds, and a revoked token is rejected.</li>
 * </ul>
 */
class RevocationWiringTest {

    private static final String SECRET =
            "0RHdZWWiWSkAy7eqRHT/VAloxKrgRO5gtwRSkNqx9lwG3ijPrAWdJaCHzbzJ+/PLyc1HnFUsw6CY4R+f5/pPcQ==";
    private static final String ISSUER = "zero-platform";
    private static final String AUDIENCE = "zero-platform-api";

    @Test
    void revocationEnabledButServiceMissingRefusesToBuildTheDecoder() {
        JwtProperties props = baseProps();
        props.getRevocation().setEnabled(true);

        assertThatThrownBy(() -> new SecurityConfig()
                .jwtDecoder(props, new JwtKeyRing(props), providerOf(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("silently unenforced");
    }

    @Test
    void revocationDisabledBuildsADecoderWithNoRevocation() {
        JwtProperties props = baseProps();
        props.getRevocation().setEnabled(false);

        JwtDecoder decoder = new SecurityConfig().jwtDecoder(props, new JwtKeyRing(props), providerOf(null));

        // Even a service that would say "revoked" cannot matter: none is wired, so a good token decodes.
        assertThatCode(() -> decoder.decode(mint(props, Instant.now())))
                .doesNotThrowAnyException();
    }

    @Test
    void revocationEnabledWithAServicePresentPutsTheValidatorInTheChain() {
        JwtProperties props = baseProps();
        props.getRevocation().setEnabled(true);
        JwtKeyRing ring = new JwtKeyRing(props);
        String token = mint(props, Instant.now());

        // service says NOT revoked -> the same token decodes
        JwtDecoder permissive = new SecurityConfig().jwtDecoder(props, ring, providerOf(service(false)));
        assertThatCode(() -> permissive.decode(token)).doesNotThrowAnyException();

        // service says REVOKED -> the validator is in the chain and rejects it
        JwtDecoder enforcing = new SecurityConfig().jwtDecoder(props, ring, providerOf(service(true)));
        assertThatThrownBy(() -> enforcing.decode(token)).isInstanceOf(JwtException.class);
    }

    // --- helpers ---------------------------------------------------------------------------

    private static JwtProperties baseProps() {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setIssuer(ISSUER);
        props.setAudience(AUDIENCE);
        props.setAccessTokenTtl(Duration.ofMinutes(15));
        return props;
    }

    /** A token signed by the ring's active key, valid on issuer/audience/exp, so only revocation can refuse it. */
    private static String mint(JwtProperties props, Instant now) {
        JwtKeyRing ring = new JwtKeyRing(props);
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(ring.signingJwkSource());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.getIssuer())
                .audience(List.of(props.getAudience()))
                .subject("1")
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS512).keyId(ring.activeKid()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static TokenRevocationService service(boolean revoked) {
        return new TokenRevocationService(null, null, null) {
            @Override
            public boolean isRevoked(String jti, Long userId, Instant issuedAt) {
                return revoked;
            }
        };
    }

    private static ObjectProvider<TokenRevocationService> providerOf(TokenRevocationService svc) {
        return new ObjectProvider<>() {
            @Override
            public TokenRevocationService getObject() {
                if (svc == null) {
                    throw new NoSuchBeanDefinitionException(TokenRevocationService.class);
                }
                return svc;
            }

            @Override
            public TokenRevocationService getObject(Object... args) {
                return getObject();
            }

            @Override
            public TokenRevocationService getIfAvailable() {
                return svc;
            }

            @Override
            public TokenRevocationService getIfUnique() {
                return svc;
            }
        };
    }
}
