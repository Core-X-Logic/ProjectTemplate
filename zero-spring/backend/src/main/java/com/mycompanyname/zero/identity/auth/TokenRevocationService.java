package com.mycompanyname.zero.identity.auth;

import com.mycompanyname.zero.config.JwtProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Access-token revocation store (PROD-R16), backed by Redis.
 *
 * <p>An HS512 access token cannot be un-issued once minted, so a logout, password change or 2FA
 * disable would otherwise leave a usable token alive for up to the access-token TTL. This store lets
 * the resource server reject such a token before its natural expiry. Two revocation shapes:
 *
 * <ul>
 *   <li>{@link #revokeAccessToken} — a single token by its {@code jti}. Used by logout (the caller
 *       revokes exactly the token it presented).</li>
 *   <li>{@link #revokeAllForUser} — every outstanding token for a user at once, via a per-user
 *       "not before" marker. Used by credential changes, which should kill all live sessions.</li>
 * </ul>
 *
 * <p>Every key is written with a TTL equal to the (remaining) lifetime of the token(s) it covers, so
 * it self-expires exactly when honouring it would stop mattering — the store never grows without
 * bound and needs no sweeper.
 *
 * <p><b>Reuses Spring's Redis.</b> Built on the auto-configured {@link StringRedisTemplate} (the same
 * Lettuce connection factory the cache and rate limiter share) — no second pool. Only published when
 * {@code zero.jwt.revocation.enabled} is true; when it is off no bean exists, the decoder gains no
 * revocation validator, and the hooks below become no-ops (the pre-PROD-R16 behaviour).
 *
 * <p><b>Fail-closed on the read path.</b> {@link #isRevoked} does <em>not</em> swallow a Redis error;
 * it lets it propagate so the validator can reject the request. Failing open would honour a revoked
 * token, and the store is the only source of truth for what is revoked. The write paths are
 * best-effort (a failed revoke is logged, not fatal — the token still expires naturally), because
 * their failure mode degrades safely: the token lives out its short TTL.
 */
@Component
@ConditionalOnProperty(prefix = "zero.jwt.revocation", name = "enabled", matchIfMissing = true)
@Slf4j
public class TokenRevocationService {

    private final StringRedisTemplate redis;
    private final JwtProperties properties;
    private final Clock clock;

    public TokenRevocationService(StringRedisTemplate redis, JwtProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Revokes a single access token by its {@code jti} until {@code expiresAt} (plus the clock-skew
     * margin the decoder tolerates), after which the token is dead anyway. Best-effort: a Redis error
     * is logged, never thrown, and never carries the token value.
     */
    public void revokeAccessToken(String jti, Instant expiresAt) {
        if (jti == null || jti.isBlank() || expiresAt == null) {
            return;
        }
        Duration ttl = Duration.between(clock.instant(), expiresAt).plus(properties.getClockSkew());
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            redis.opsForValue().set(jtiKey(jti), "1", ttl);
        } catch (RuntimeException ex) {
            log.warn("Could not record access-token revocation in Redis; the token will still expire "
                    + "naturally within its TTL", ex);
        }
    }

    /**
     * Revokes every access token issued to {@code userId} before now, by writing a per-user "not
     * before" marker at the current instant. Kept for the maximum access-token lifetime (plus skew),
     * so it outlives any token it must suppress. Best-effort, same as {@link #revokeAccessToken}.
     */
    public void revokeAllForUser(Long userId) {
        if (userId == null) {
            return;
        }
        Instant notBefore = clock.instant();
        Duration ttl = properties.getAccessTokenTtl().plus(properties.getClockSkew());
        try {
            redis.opsForValue().set(userKey(userId), Long.toString(notBefore.getEpochSecond()), ttl);
        } catch (RuntimeException ex) {
            log.warn("Could not record user-wide token revocation in Redis; outstanding tokens will "
                    + "still expire naturally within their TTL", ex);
        }
    }

    /**
     * Whether a token is revoked: true if its {@code jti} was individually revoked, OR if it was
     * issued before the user's "not before" marker. Does not catch Redis errors — the caller
     * (the decoder's validator) turns a failure into a rejection (fail-closed).
     */
    public boolean isRevoked(String jti, Long userId, Instant issuedAt) {
        if (jti != null && !jti.isBlank() && Boolean.TRUE.equals(redis.hasKey(jtiKey(jti)))) {
            return true;
        }
        if (userId != null && issuedAt != null) {
            String notBefore = redis.opsForValue().get(userKey(userId));
            if (notBefore != null && issuedAt.getEpochSecond() < Long.parseLong(notBefore)) {
                return true;
            }
        }
        return false;
    }

    private String jtiKey(String jti) {
        return properties.getRevocation().getKeyPrefix() + "jti:" + jti;
    }

    private String userKey(Long userId) {
        return properties.getRevocation().getKeyPrefix() + "user:" + userId;
    }
}
