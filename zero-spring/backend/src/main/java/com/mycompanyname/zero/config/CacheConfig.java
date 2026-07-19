package com.mycompanyname.zero.config;

import com.mycompanyname.zero.saas.SaasCaches;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.time.Duration;

/**
 * Cache infrastructure. The cache names themselves are declared in
 * {@code spring.cache.cache-names} so the simple (dev/test) cache manager pre-creates them; this
 * class adds the Redis-specific configuration used when {@code spring.cache.type=redis} (prod).
 *
 * <p>The SaaS caches carry a short TTL purely as a safety net — correctness comes from explicit
 * eviction on every write that can change a resolved value, not from expiry. See
 * ARCHITECTURE-RULES.md — "Feature ve abonelik cache'i yazmadan sonra bayat kalmamalı".
 *
 * <p>Cache names in {@code spring.cache.cache-names} must stay in sync with what the code actually
 * reads through: {@code settings} ({@code SettingManager}), {@link SaasCaches#FEATURES}
 * ({@code FeatureValueLoader}) and {@link SaasCaches#SUBSCRIPTION_VALIDITY}
 * ({@code DefaultSubscriptionGuard}). A declared-but-unused {@code permission-tree} entry was
 * dropped in the PROD-R8 pass — the permission tree is computed per request from the in-memory
 * registry and never touched a cache.
 */
@Configuration
@EnableCaching
@Slf4j
public class CacheConfig implements CachingConfigurer {

    /** Safety-net TTL for the SaaS caches; eviction on write is the primary invalidation mechanism. */
    public static final Duration SAAS_CACHE_TTL = Duration.ofMinutes(10);

    @Bean
    public RedisCacheManagerBuilderCustomizer saasRedisCacheCustomizer() {
        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(SAAS_CACHE_TTL)
                .disableCachingNullValues();
        return builder -> builder
                .withCacheConfiguration(SaasCaches.FEATURES, configuration)
                .withCacheConfiguration(SaasCaches.SUBSCRIPTION_VALIDITY, configuration);
    }

    /**
     * PROD-R13: keeps a Redis outage out of the request path.
     *
     * <p>Spring's default handler rethrows, so with {@code spring.cache.type=redis} an unreachable
     * Redis turned every cached read — feature resolution, subscription validity, settings — into a
     * 500. That makes the cache a single point of failure for the whole platform. Swallowing the
     * error degrades to the uncached path instead: slower, still correct, because every cached value
     * here is derived from the database and recomputable.
     *
     * <p>Write and eviction failures are swallowed for the same reason, and that is the one case
     * worth stating plainly: a failed evict can leave a stale entry behind once Redis returns. The
     * TTL above bounds that staleness, and the alternative — failing the write the user just made —
     * is worse. Every failure is logged at WARN so an outage is visible rather than silent.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache read failed (cache={}, key={}), falling back to the uncached path: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("Cache write failed (cache={}, key={}), the value stays uncached: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache evict failed (cache={}, key={}); a stale entry may survive until its TTL: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("Cache clear failed (cache={}); stale entries may survive until their TTL: {}",
                        cache.getName(), exception.getMessage());
            }
        };
    }
}
