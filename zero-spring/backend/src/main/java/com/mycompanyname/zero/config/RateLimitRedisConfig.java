package com.mycompanyname.zero.config;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.AbstractRedisClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.function.Supplier;

/**
 * Wires the Redis-backed bucket store for the rate limiter (PROD-R6), reusing the Lettuce client
 * Spring already built for {@code spring.data.redis.*}.
 *
 * <p><b>When it is active.</b> Only when {@code zero.ratelimit.redis.enabled} is true (the default)
 * and the bucket4j Redis/Lettuce classes are on the classpath. When it is off, no store bean is
 * published, {@link RateLimitFilter} receives none through its {@code ObjectProvider}, and the
 * throttle runs purely per-instance — the pre-PROD-R6 behaviour.
 *
 * <p>The {@link RedisConnectionFactory} the bean depends on is injected directly, not guarded with
 * {@code @ConditionalOnBean}. It does not need guarding: {@code @ConditionalOnClass(LettuceConnectionFactory)}
 * above already implies {@code spring-boot-starter-data-redis}, which always auto-configures a
 * factory. And it must not be guarded that way — {@code @ConditionalOnBean} is unreliable on a
 * component-scanned user {@code @Configuration}, evaluated before the auto-configured factory's bean
 * definition is registered, so it would silently drop the store and degrade the whole deployment to
 * per-instance limits (observed: it fails every assertion in {@code DistributedRateLimitWiringIT}). In
 * the pathological case where someone excludes {@code RedisAutoConfiguration} while keeping the
 * starter, context startup fails loudly for the missing factory — the right failure, not a limiter
 * that quietly stops being distributed.
 *
 * <p><b>Why the store is lazy.</b> The proxy manager opens a Redis connection the first time it is
 * used, never here: this bean creation performs no I/O, so an unreachable Redis at boot cannot stop
 * the application starting. The first throttled request resolves the connection; if Redis is down at
 * that point the store throws and the filter degrades to its in-heap bucket, retrying on the next
 * request. That is the same "degrade, do not depend" stance the cache takes (PROD-R13), applied to
 * the limiter.
 */
@Configuration
@ConditionalOnClass({LettuceBasedProxyManager.class, LettuceConnectionFactory.class})
@ConditionalOnProperty(prefix = "zero.ratelimit.redis", name = "enabled", matchIfMissing = true)
@Slf4j
class RateLimitRedisConfig {

    /**
     * The distributed store, built lazily from the shared {@link RedisConnectionFactory}. The factory
     * is injected rather than the native client so nothing forces the client into existence at
     * configuration time; {@link LettuceConnectionFactory#getNativeClient()} is read inside the
     * supplier, on the first throttled request.
     */
    @Bean
    DistributedRateLimitStore distributedRateLimitStore(RedisConnectionFactory connectionFactory,
                                                        RateLimitProperties properties) {
        Supplier<ProxyManager<String>> proxyManagerSupplier = () -> {
            if (!(connectionFactory instanceof LettuceConnectionFactory lettuce)) {
                throw new IllegalStateException("Distributed rate limiting requires a Lettuce "
                        + "RedisConnectionFactory; got " + connectionFactory.getClass().getName()
                        + ". Using per-instance buckets.");
            }
            AbstractRedisClient client = lettuce.getNativeClient();
            return DistributedRateLimitStore.proxyManagerFor(client, properties);
        };
        log.info("Rate limiter buckets are backed by Redis (key-prefix={}); on a Redis outage the "
                        + "limiter degrades to per-instance buckets rather than failing open or closed.",
                properties.getRedis().getKeyPrefix());
        return new DistributedRateLimitStore(proxyManagerSupplier,
                DistributedRateLimitStore.configurationSupplier(properties));
    }
}
