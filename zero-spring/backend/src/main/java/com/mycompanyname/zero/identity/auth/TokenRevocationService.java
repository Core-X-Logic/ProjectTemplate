package com.mycompanyname.zero.identity.auth;

import com.mycompanyname.zero.config.JwtProperties;
import io.micrometer.core.instrument.MeterRegistry;
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
 * <p><b>Millisecond "not before" marker (PROD-R16 F3).</b> The per-user marker is stored in
 * milliseconds and compared against the token's millisecond issue time ({@code ims} claim, added by
 * {@link JwtService}). This shrinks the window in which a token minted in the same wall-clock second
 * just before a credential change would survive, from a full second down to clock resolution, without
 * revoking a legitimate same-second re-login (which would loop the user). A marker written before this
 * upgrade holds seconds; {@link #asMillis} normalises it by magnitude so it keeps being honoured.
 *
 * <p><b>Fail-closed on the read path.</b> {@link #isRevoked} does <em>not</em> swallow a Redis error;
 * it lets it propagate so the validator can reject the request. Failing open would honour a revoked
 * token, and the store is the only source of truth for what is revoked.
 *
 * <p><b>Bounded retry on the write path (PROD-R16 F4).</b> The write paths are best-effort — a
 * credential change is already DB-committed and must not roll back because a cache write failed, so a
 * write failure is never thrown. But a swallowed transient Redis blip would leave outstanding tokens
 * un-revoked (a write-side fail-open against a fail-closed read). {@link #writeWithRetry} narrows that
 * blip window with a small bounded retry, and on final failure increments
 * {@code jwt.revocation.write_failures} (tagged by operation) and emits a stable, greppable
 * {@code REVOCATION_WRITE_FAILED} warning — never carrying a token or jti value. The read path stays
 * fail-closed; no fail-open is introduced. A durable outbox (DB-committed revocation intent drained by
 * a background job) is the remaining stronger hardening, deferred out of this slice.
 */
@Component
@ConditionalOnProperty(prefix = "zero.jwt.revocation", name = "enabled", matchIfMissing = true)
@Slf4j
public class TokenRevocationService {

    /** How many times a Redis write is attempted before it is recorded as a failure. Bounded on purpose. */
    private static final int WRITE_MAX_ATTEMPTS = 3;

    /**
     * Base backoff between write attempts; the nth retry waits {@code BASE * n} (50ms then 100ms), so
     * the worst case adds ~150ms total across the two retries before the failure is recorded.
     */
    private static final Duration WRITE_BACKOFF = Duration.ofMillis(50);

    /** Micrometer counter for write failures that survived the retry (PROD-R16 F4). */
    private static final String WRITE_FAILURE_METRIC = "jwt.revocation.write_failures";

    /** Stable, greppable marker on the final-failure warning. Carries no token/jti/secret value. */
    private static final String WRITE_FAILURE_MARKER = "REVOCATION_WRITE_FAILED";

    /**
     * Below this a stored "not before" marker is a legacy SECONDS value, not milliseconds. Milliseconds
     * since 1970 crossed 1e11 in 1973; seconds do not cross it until the year ~5138 — so the two ranges
     * never overlap for any realistic instant, and the check is unambiguous.
     */
    private static final long SECONDS_MARKER_CEILING = 100_000_000_000L;

    private final StringRedisTemplate redis;
    private final JwtProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public TokenRevocationService(StringRedisTemplate redis, JwtProperties properties, Clock clock,
                                  MeterRegistry meterRegistry) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Revokes a single access token by its {@code jti} until {@code expiresAt} (plus the clock-skew
     * margin the decoder tolerates), after which the token is dead anyway. Best-effort with a bounded
     * retry (PROD-R16 F4): a Redis error is never thrown, and never carries the token value.
     */
    public void revokeAccessToken(String jti, Instant expiresAt) {
        if (jti == null || jti.isBlank() || expiresAt == null) {
            return;
        }
        Duration ttl = Duration.between(clock.instant(), expiresAt).plus(properties.getClockSkew());
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        writeWithRetry("revoke_access_token", () -> redis.opsForValue().set(jtiKey(jti), "1", ttl));
    }

    /**
     * Revokes every access token issued to {@code userId} before now, by writing a per-user "not
     * before" marker at the current instant in MILLISECONDS (PROD-R16 F3). Kept for the maximum
     * access-token lifetime (plus skew), so it outlives any token it must suppress. Best-effort with a
     * bounded retry, same as {@link #revokeAccessToken}.
     */
    public void revokeAllForUser(Long userId) {
        if (userId == null) {
            return;
        }
        Instant notBefore = clock.instant();
        Duration ttl = properties.getAccessTokenTtl().plus(properties.getClockSkew());
        writeWithRetry("revoke_all_for_user",
                () -> redis.opsForValue().set(userKey(userId), Long.toString(notBefore.toEpochMilli()), ttl));
    }

    /**
     * Whether a token is revoked: true if its {@code jti} was individually revoked, OR if it was
     * issued before the user's "not before" marker. {@code issuedAt} is the effective issue time the
     * validator supplies — millisecond-precise for an {@code ims}-bearing token, or a second-granular
     * value for a legacy pre-upgrade token (see {@code RevokedTokenValidator#issueInstant}). The
     * comparison is at millisecond resolution (PROD-R16 F3), strict {@code <} so a legitimate
     * same-instant re-login is not revoked. Does not catch Redis errors — the caller (the decoder's
     * validator) turns a failure into a rejection (fail-closed).
     */
    public boolean isRevoked(String jti, Long userId, Instant issuedAt) {
        if (jti != null && !jti.isBlank() && Boolean.TRUE.equals(redis.hasKey(jtiKey(jti)))) {
            return true;
        }
        if (userId != null && issuedAt != null) {
            String notBefore = redis.opsForValue().get(userKey(userId));
            if (notBefore != null && issuedAt.toEpochMilli() < asMillis(notBefore)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalises a stored "not before" marker to milliseconds. New markers are already milliseconds; a
     * marker written before the F3 upgrade holds seconds and is scaled up by magnitude, so a
     * credential change made just before a rolling deploy keeps being enforced across it instead of
     * being silently ignored until the key expires.
     */
    private static long asMillis(String marker) {
        long value = Long.parseLong(marker);
        return value < SECONDS_MARKER_CEILING ? value * 1000L : value;
    }

    /**
     * Runs a best-effort Redis write with a small bounded retry (PROD-R16 F4). Returns normally on the
     * first success. If every attempt fails it does NOT throw — the caller's primary operation is
     * already committed — but records the failure on {@link #WRITE_FAILURE_METRIC} and logs the stable
     * {@link #WRITE_FAILURE_MARKER}, so a sustained write outage is observable rather than silent.
     */
    private void writeWithRetry(String operation, Runnable write) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= WRITE_MAX_ATTEMPTS; attempt++) {
            try {
                write.run();
                return;
            } catch (RuntimeException ex) {
                last = ex;
                if (attempt < WRITE_MAX_ATTEMPTS && !backoff(attempt)) {
                    break; // interrupted while waiting to retry — stop and record the failure
                }
            }
        }
        meterRegistry.counter(WRITE_FAILURE_METRIC, "operation", operation).increment();
        log.warn("{} operation={} attempts={} cause={}: could not record token revocation in Redis; the "
                        + "affected token(s) will still expire naturally within their TTL",
                WRITE_FAILURE_MARKER, operation, WRITE_MAX_ATTEMPTS,
                last == null ? "unknown" : last.getClass().getName());
    }

    /** Waits before the next attempt. Returns false if the wait was interrupted (do not retry further). */
    private boolean backoff(int attempt) {
        try {
            Thread.sleep(WRITE_BACKOFF.toMillis() * attempt);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String jtiKey(String jti) {
        return properties.getRevocation().getKeyPrefix() + "jti:" + jti;
    }

    private String userKey(Long userId) {
        return properties.getRevocation().getKeyPrefix() + "user:" + userId;
    }
}
