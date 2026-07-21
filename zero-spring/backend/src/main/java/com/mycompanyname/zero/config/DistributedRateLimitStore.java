package com.mycompanyname.zero.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.cluster.RedisClusterClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The Redis-backed token-bucket store for {@link RateLimitFilter} (PROD-R6).
 *
 * <p>The filter's keys were always backend-agnostic strings ({@code "ip|"+path+"|"+client},
 * {@code "user|"+path+"|"+username}) precisely so the store could become this: a bucket4j
 * {@link ProxyManager} whose state lives in Redis, shared across every application instance, so
 * {@code capacity} is one cluster-wide limit rather than one-per-JVM. This class is the swap; it does
 * not touch how a key is derived, only where the count for that key is kept.
 *
 * <p><b>Lazy on purpose.</b> {@link LettuceBasedProxyManager#builderFor(RedisClient)} opens a Redis
 * connection the moment it is called, so the proxy manager is resolved on first use rather than at
 * bean creation. An unreachable Redis at boot must not stop the application starting — and when it is
 * unreachable, {@link #tryConsume} throws, which is exactly the signal {@link RateLimitFilter} turns
 * into a per-instance fallback. The resolved manager is memoised only on success, so a failed attempt
 * is retried on the next request and the store recovers by itself once Redis returns.
 *
 * <p><b>Connection reuse.</b> The proxy manager is built from the Lettuce client Spring already
 * created for {@code spring.data.redis.*} (its {@code RedisConnectionFactory}); bucket4j opens one
 * dedicated connection on that shared client rather than standing up a second client pool. The
 * connection uses a {@code byte[]} value codec, and a String-to-key mapper applies
 * {@code zero.ratelimit.redis.key-prefix} so the limiter's keys are namespaced and visible.
 */
public final class DistributedRateLimitStore {

    private final Supplier<ProxyManager<String>> proxyManagerSupplier;
    private final Supplier<BucketConfiguration> configurationSupplier;

    /** Written once, on the first successful resolution; read without locking afterwards. */
    private volatile ProxyManager<String> proxyManager;

    DistributedRateLimitStore(Supplier<ProxyManager<String>> proxyManagerSupplier,
                              Supplier<BucketConfiguration> configurationSupplier) {
        this.proxyManagerSupplier = proxyManagerSupplier;
        this.configurationSupplier = configurationSupplier;
    }

    /**
     * Consumes one token for {@code key} against the shared Redis bucket and returns the probe the
     * filter already knows how to read.
     *
     * <p>May throw {@link io.lettuce.core.RedisException} (or a timeout wrapped as one) when Redis is
     * unreachable. That is deliberate and load-bearing: the caller catches it and degrades to the
     * per-instance bucket. This method never swallows the failure itself, because swallowing it here
     * would have to choose between failing open and failing closed, and that choice belongs to the
     * filter where both the local fallback and the rejection contract live.
     */
    ConsumptionProbe tryConsume(String key) {
        return proxyManager()
                .builder()
                .build(key, configurationSupplier)
                .tryConsumeAndReturnRemaining(1);
    }

    private ProxyManager<String> proxyManager() {
        ProxyManager<String> resolved = this.proxyManager;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (this.proxyManager == null) {
                // Throws when Redis is unreachable; intentionally NOT memoised on failure, so the
                // next request retries and the store heals itself when Redis comes back.
                this.proxyManager = proxyManagerSupplier.get();
            }
            return this.proxyManager;
        }
    }

    // ---- Factories shared by RateLimitRedisConfig and the tests, so "the same bucket as today" is
    //      provably the same in both the local and the distributed path. -------------------------

    /**
     * The single {@link Bandwidth} definition, built identically to {@link RateLimitFilter}'s in-heap
     * bucket: {@code capacity} tokens, restored in one intervally refill at the end of each
     * {@code refill-period} (fixed window, not a trickle). Both stores are built from this so a change
     * to one cannot silently diverge from the other.
     */
    static Bandwidth bandwidth(RateLimitProperties properties) {
        return Bandwidth.builder()
                .capacity(properties.getCapacity())
                .refillIntervally(properties.getCapacity(), properties.getRefillPeriod())
                .build();
    }

    static Supplier<BucketConfiguration> configurationSupplier(RateLimitProperties properties) {
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(bandwidth(properties))
                .build();
        return () -> configuration;
    }

    /**
     * The Redis key expiry. A fixed {@code key-ttl} when one is configured; otherwise a TTL derived
     * from the refill period so a key lives exactly as long as its bucket needs to refill to full and
     * is then dropped — the distributed mirror of the in-heap sweeper's "gone once back at capacity".
     */
    static ExpirationAfterWriteStrategy expiration(RateLimitProperties properties) {
        Duration ttl = properties.getRedis().getKeyTtl();
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            return ExpirationAfterWriteStrategy.fixedTimeToLive(ttl);
        }
        return ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(properties.getRefillPeriod());
    }

    /**
     * Builds a String-keyed {@link ProxyManager} over the given Lettuce client, applying the
     * configured key prefix and expiry. Standalone (non-cluster) only; a cluster client is refused
     * with a clear message the filter turns into a per-instance fallback, because cluster support is
     * untested here and silently limiting per-instance is safer than pretending otherwise.
     */
    static ProxyManager<String> proxyManagerFor(AbstractRedisClient client, RateLimitProperties properties) {
        String prefix = properties.getRedis().getKeyPrefix();
        ExpirationAfterWriteStrategy expiration = expiration(properties);
        if (client instanceof RedisClient standalone) {
            ProxyManager<byte[]> byteKeyed = LettuceBasedProxyManager.builderFor(standalone)
                    .withExpirationStrategy(expiration)
                    .build();
            return byteKeyed.withMapper(key -> (prefix + key).getBytes(StandardCharsets.UTF_8));
        }
        if (client instanceof RedisClusterClient) {
            throw new IllegalStateException("Distributed rate limiting does not support a Redis Cluster "
                    + "client here; falling back to per-instance buckets. Set zero.ratelimit.redis.enabled=false "
                    + "to make that explicit, or run against a standalone Redis.");
        }
        throw new IllegalStateException("No usable Lettuce RedisClient for distributed rate limiting "
                + "(got " + (client == null ? "null" : client.getClass().getName()) + "); using per-instance buckets.");
    }
}
