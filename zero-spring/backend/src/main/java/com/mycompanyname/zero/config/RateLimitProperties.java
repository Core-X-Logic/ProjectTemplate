package com.mycompanyname.zero.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Throttle applied to the unauthenticated endpoints (PROD-R6).
 *
 * <p>{@code capacity} requests are allowed per {@code refill-period}, counted separately per client
 * IP and per submitted username, for each listed path.
 */
@Component
@ConfigurationProperties(prefix = "zero.ratelimit")
@Getter
@Setter
public class RateLimitProperties {

    private boolean enabled = true;

    /** Requests allowed within one {@link #refillPeriod}. */
    private int capacity = 10;

    /** The whole allowance is restored at once when this elapses (fixed window, not a trickle). */
    private Duration refillPeriod = Duration.ofMinutes(1);

    /**
     * Paths to throttle, as Ant patterns; anything else passes through untouched. Matched against
     * the decoded, context-path-free lookup path and case-insensitively — see
     * {@link ThrottledPathMatcher} for why the raw request URI is not usable here (B1).
     */
    private List<String> paths = new ArrayList<>();

    /**
     * Upper bound on tracked keys. Reaching it triggers a sweep of idle buckets, so a distributed
     * source-address flood cannot grow the map without limit.
     */
    private int maxTrackedKeys = 50_000;

    /**
     * How many reverse proxies sit between the internet and this application (B3).
     *
     * <p>Each appends one {@code X-Forwarded-For} entry, so the client address is this many entries
     * from the <em>right</em>. The default of 1 matches the documented deployment: a single TLS
     * terminating proxy using {@code proxy_add_x_forwarded_for}. Set it to {@code 0} when the
     * application is exposed directly, which makes {@code X-Forwarded-For} be ignored outright —
     * otherwise every client picks its own bucket.
     *
     * <p>The same trust boundary carries HSTS (PROD-R4): the proxy must overwrite, not forward,
     * client-supplied {@code X-Forwarded-*}.
     */
    private int trustedProxyCount = 1;

    /**
     * Largest request body the filter will accept on a throttled path, in bytes (B2).
     *
     * <p>Bodies above this are refused with {@code 413} rather than waved through unparsed. These
     * endpoints take a handful of short JSON fields; nothing legitimate comes close, and a body that
     * cannot be parsed is a body whose username bucket cannot be charged.
     */
    private int maxBodyBytes = 16 * 1024;

    /**
     * Where the token buckets live (PROD-R6). See {@link Redis}.
     */
    private Redis redis = new Redis();

    /**
     * Distributed-bucket backend for the throttle.
     *
     * <p><b>Why it exists.</b> The buckets used to live only in this JVM's heap, so N application
     * instances behind a load balancer permitted N x capacity in aggregate — a hard bound, but a
     * weakened one. Backing them with Redis makes {@code capacity} a single cluster-wide limit.
     *
     * <p><b>Degrade, not depend.</b> Redis is the primary store, never a hard dependency of the
     * request path: when it is reachable the limit is exact and shared; when it throws or times out
     * {@link RateLimitFilter} falls back to the per-instance in-heap bucket (the old behaviour) for
     * that request and logs a deduplicated {@code WARN}. It never fails open to unlimited (a Redis
     * blip must not open login to brute force) and never fails closed to 503 (a Redis blip must not
     * lock everyone out). "Never worse than before, better when Redis is up." This is also why the
     * store is built lazily — an unreachable Redis at boot must not stop the application starting.
     */
    @Getter
    @Setter
    public static class Redis {

        /**
         * Whether the buckets are shared through Redis. {@code true} by default (distributed on).
         *
         * <p>Set to {@code false} for a deployment or test that deliberately skips Redis — the
         * throttle then runs purely per-instance, exactly as it did before this backend existed. The
         * automated test profile sets it false so the suite never needs a Redis container; a running
         * Redis being merely <em>unreachable</em> is handled by the degrade path above, this switch is
         * the explicit "there is no Redis here at all" answer.
         */
        private boolean enabled = true;

        /**
         * Prefix on every Redis key. Namespaces the rate-limit state so two applications (or two
         * clones of this template — {@code container_name} is deliberately absent, R-01b) can share
         * one Redis without colliding, and so an operator can see the limiter's keys at a glance.
         */
        private String keyPrefix = "zero:rl:";

        /**
         * How long an idle bucket key survives in Redis. Left unset by default, in which case the TTL
         * is derived from {@code refill-period} via
         * {@code ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax}: a key lives
         * exactly as long as a partially-consumed bucket needs to refill to full and is then dropped,
         * which mirrors the in-heap sweeper's "gone once refilled to capacity" intent and cannot
         * expire a bucket mid-window (that would reset a count and weaken the limit).
         *
         * <p>Set an explicit fixed value only if you have a reason to; it must stay comfortably above
         * {@code refill-period} for the same reason. Env-overridable as {@code RATE_LIMIT_REDIS_KEY_TTL}.
         */
        private Duration keyTtl;
    }
}
