package com.mycompanyname.zero.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "zero.jwt")
@Getter
@Setter
public class JwtProperties {

    /**
     * PROD-R16 (legacy single key). The one signing key used before the key ring existed. Still the
     * only knob a deployment must set: when {@link #keys} is empty this is synthesised into a one-key
     * ring under a default kid (see {@code JwtKeyRing}), so existing configurations keep working
     * unchanged. When {@link #keys} is non-empty this is ignored in favour of the ring.
     */
    private String secret;

    private Duration accessTokenTtl;

    private Duration refreshTokenTtl;

    private String issuer;

    /**
     * PROD-R16. Every access token carries this as its {@code aud} claim and the decoder rejects a
     * token that does not. Without it, any token signed with the same secret — a sibling service
     * sharing the key, a token minted for a different deployment — is accepted here.
     */
    private String audience;

    /**
     * PROD-R16 (key rotation). The kid of the key in {@link #keys} that signs newly issued tokens.
     * Every other key in the ring is verify-only — the grace window that lets tokens signed by a
     * just-retired key keep verifying until they expire. Required when {@link #keys} is set; ignored
     * (a synthesised default kid is used) when only the legacy {@link #secret} is configured.
     */
    private String activeKid;

    /**
     * PROD-R16 (key rotation). The signing/verification key ring. Empty by default: a deployment that
     * sets only {@link #secret} gets a one-key ring synthesised for it. The rotation procedure (add a
     * new key, flip {@link #activeKid} to it, keep the old key for a grace window ≥ access-token TTL,
     * then remove it) is documented in {@code JwtKeyRing} and RELEASE-RUNBOOK.
     */
    private List<Key> keys = new ArrayList<>();

    /**
     * PROD-R16. Clock skew tolerance applied to the {@code exp}/{@code nbf}/{@code iat} timestamp
     * checks in the decoder. Made explicit (rather than inheriting Spring's implicit 60s default) so
     * the tolerance is a documented, tunable number. Kept small on purpose.
     */
    private Duration clockSkew = Duration.ofSeconds(30);

    /**
     * PROD-R16 (access-token revocation). See {@link Revocation}.
     */
    private Revocation revocation = new Revocation();

    /** One entry in the signing key ring: an id and its HS512 secret (base64, ≥64 bytes). */
    @Getter
    @Setter
    public static class Key {

        /** Stable identifier carried in the JWT {@code kid} header so the decoder can select it. */
        private String kid;

        /** The HS512 secret, base64-encoded, decoding to at least 64 bytes. */
        private String secret;
    }

    /**
     * Access-token revocation (PROD-R16). A short-lived HS512 access token cannot be un-issued, so a
     * logout, a password change or a 2FA disable would otherwise leave a usable token in the wild for
     * up to the access-token TTL. When enabled, revoked {@code jti}s and per-user "not before" markers
     * are held in Redis and enforced on every authenticated request by the decoder's validator chain.
     *
     * <p><b>Fail-closed.</b> If Redis is unreachable during the check the request is REJECTED, not
     * waved through — the store is the only source of truth for what has been revoked, so failing open
     * would honour a revoked token. This is the conscious availability trade (a Redis outage denies
     * auth); access tokens are short-lived, so the window is small, and Redis is deliberately NOT in
     * the readiness group (a revocation-store blip must not take the whole instance out of rotation).
     */
    @Getter
    @Setter
    public static class Revocation {

        /**
         * Whether revocation is enforced. {@code true} by default. Set {@code false} for a deployment
         * or test that runs without Redis at all: no revocation bean is published, the decoder gains
         * no revocation validator, and the logout/credential-change hooks become no-ops. This mirrors
         * {@code zero.ratelimit.redis.enabled} — the automated test profile turns it off so the suite
         * needs no Redis container.
         */
        private boolean enabled = true;

        /** Prefix on every Redis key, so the revocation state is namespaced and visible to operators. */
        private String keyPrefix = "zero:jwt:revoked:";
    }
}
