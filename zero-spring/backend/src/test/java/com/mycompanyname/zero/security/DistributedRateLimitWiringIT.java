package com.mycompanyname.zero.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.config.DistributedRateLimitStore;
import com.mycompanyname.zero.config.RateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Spring wiring the store-level {@code DistributedRateLimitIT} deliberately skips: that
 * with {@code zero.ratelimit.redis.enabled=true} the application actually builds a Redis-backed store
 * from its own {@code RedisConnectionFactory} and hands it to {@link RateLimitFilter} — and that the
 * running filter keeps its counters in Redis, not in this JVM's heap.
 *
 * <p>The decisive assertion is not "a request is throttled" (the local store does that too) but
 * <em>where the count lives</em>: after driving the limiter, Redis holds keys under the configured
 * prefix and the filter's in-heap map is still empty. If the config bean had failed to wire — or if
 * the store had silently degraded to local — the heap map would hold the keys and Redis would be
 * empty, and this test would say so.
 *
 * <p>Boots with the test profile's shared Postgres (via {@link AbstractIntegrationIT}) but overrides
 * {@code redis.enabled} back to true and points {@code spring.data.redis.*} at a throwaway Redis
 * container. Every scenario flushes Redis first so the methods do not bleed into one another.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "zero.ratelimit.enabled=true",
                "zero.ratelimit.redis.enabled=true",
                "zero.ratelimit.capacity=3",
                "zero.ratelimit.refill-period=PT1M"
        })
class DistributedRateLimitWiringIT extends AbstractIntegrationIT {

    private static final int CAPACITY = 3;
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String KEY_PREFIX = "zero:rl:";

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Autowired(required = false)
    private DistributedRateLimitStore distributedRateLimitStore;

    @BeforeEach
    void flushRedisAndLocalMap() throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHALL");
        rateLimitFilter.reset();
    }

    @Test
    void theRedisBackedStoreBeanIsWiredWhenDistributedLimitingIsOn() {
        assertThat(distributedRateLimitStore)
                .as("with zero.ratelimit.redis.enabled=true the store bean must exist and be injectable")
                .isNotNull();
    }

    @Test
    void theRunningLimiterKeepsItsCountInRedisNotInThisHeap() throws Exception {
        String clientIp = "203.0.113.150";

        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            assertThat(attemptLogin(clientIp, "wired-user-" + attempt).getStatusCode())
                    .as("attempt %d is inside the allowance", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(attemptLogin(clientIp, "wired-overflow").getStatusCode())
                .as("the distributed bucket must throttle at capacity+1, exactly as the local one did")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(redisKeys())
                .as("the count must live in Redis under the configured prefix — this is the proof the "
                        + "wired filter used the distributed store rather than the heap")
                .contains(KEY_PREFIX);
        assertThat(rateLimitFilter.stats().get("trackedKeys"))
                .as("Redis was reachable throughout, so the in-heap fallback map must be untouched — a "
                        + "non-zero here would mean the filter degraded to local instead of going to Redis")
                .isEqualTo(0L);
    }

    /**
     * Spoof protection on the distributed path: a forged, rotating leading {@code X-Forwarded-For}
     * entry is charged to the real trailing client, so it cannot mint a fresh Redis bucket. The key
     * that ends up in Redis names the real client and never the last forged entry.
     */
    @Test
    void aForgedForwardedEntryIsChargedToTheRealClientOnTheDistributedPath() throws Exception {
        String realClient = "198.51.100.200";

        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            assertThat(attemptLogin("10.0.0." + attempt + ", " + realClient, "dist-spoof-" + attempt)
                    .getStatusCode())
                    .as("attempt %d is inside the real client's shared allowance", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(attemptLogin("10.0.0.99, " + realClient, "dist-spoof-overflow").getStatusCode())
                .as("only the real trailing entry counts; the rotating forged one cannot get a fresh bucket")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        String keys = redisKeys();
        assertThat(keys)
                .as("the Redis key is charged to the real client")
                .contains(realClient);
        assertThat(keys)
                .as("and never to the forged leading entry")
                .doesNotContain("10.0.0.99");
    }

    private String redisKeys() throws Exception {
        return REDIS.execInContainer("redis-cli", "KEYS", KEY_PREFIX + "*").getStdout();
    }

    private ResponseEntity<JsonNode> attemptLogin(String forwardedFor, String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        headers.set(TENANT_HEADER, "default");
        Map<String, String> body = Map.of(
                "usernameOrEmail", username,
                "password", "definitely-not-the-password");
        return restTemplate.exchange(
                LOGIN_PATH, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
