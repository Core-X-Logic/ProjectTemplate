package com.mycompanyname.zero.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-R6 closure, proven the only way it can be: with two independent bucket managers standing in for
 * two application replicas, pointed at ONE Redis.
 *
 * <p>The whole defect was that the buckets lived in each JVM's heap, so N instances behind a load
 * balancer added up to N x capacity. This test builds two {@link DistributedRateLimitStore}s over
 * separate Lettuce clients against the same Testcontainers Redis and shows the combined consumption
 * across both respects ONE shared {@code capacity}. The negative evidence sits in the same test: two
 * in-heap buckets (the old store) built from the identical {@link Bandwidth} allow 2 x capacity for
 * the same key, which is the leak being closed — the numbers are asserted side by side.
 *
 * <p>Runs at the store level rather than by booting two Spring contexts on purpose: two replicas are
 * exactly two proxy managers on shared state, and constructing them directly is both the honest model
 * and the fast one. The Spring wiring — that the filter actually receives a Redis-backed store — is
 * proven end to end in {@code DistributedRateLimitWiringIT}.
 */
class DistributedRateLimitIT {

    private static final int CAPACITY = 5;
    private static final Duration REFILL = Duration.ofMinutes(1);
    private static final String LOGIN = "/api/auth/login";

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private RedisClient replicaAClient;
    private RedisClient replicaBClient;
    private DistributedRateLimitStore replicaA;
    private DistributedRateLimitStore replicaB;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @BeforeEach
    void buildReplicas() {
        RateLimitProperties properties = properties();
        replicaAClient = RedisClient.create(redisUri());
        replicaBClient = RedisClient.create(redisUri());
        replicaA = storeOver(replicaAClient, properties);
        replicaB = storeOver(replicaBClient, properties);
    }

    @AfterEach
    void closeReplicas() {
        if (replicaAClient != null) {
            replicaAClient.shutdown();
        }
        if (replicaBClient != null) {
            replicaBClient.shutdown();
        }
    }

    /**
     * The headline: two replicas, one key, one shared limit. Replica A spends 3 and replica B spends
     * 3, six attempts against a capacity of 5 — and exactly 5 succeed cluster-wide, with the sixth
     * refused whichever replica makes it. Against the old in-heap store the same six attempts would
     * all succeed (3 on each JVM's own bucket).
     */
    @Test
    void twoReplicasShareOneClusterWideLimit() {
        String key = ipKey();

        int consumedOnA = consume(replicaA, key, 3);
        int consumedOnB = consume(replicaB, key, 3);

        assertThat(consumedOnA)
                .as("replica A is first to the shared bucket and takes 3 of the 5 tokens")
                .isEqualTo(3);
        assertThat(consumedOnB)
                .as("replica B sees the tokens replica A already spent — only 2 are left")
                .isEqualTo(2);
        assertThat(consumedOnA + consumedOnB)
                .as("PROD-R6: the two replicas together honour a SINGLE capacity of %d, not 2x%d",
                        CAPACITY, CAPACITY)
                .isEqualTo(CAPACITY);

        assertThat(replicaB.tryConsume(key).isConsumed())
                .as("the bucket is already empty cluster-wide; a further attempt on either replica is refused")
                .isFalse();
    }

    /**
     * The negative evidence, made concrete and kept in the same test so the contrast is unmissable:
     * the OLD store was two in-heap buckets, one per JVM, built from the very same {@link Bandwidth}.
     * The identical two-replica scenario against them lets 2 x capacity through — the leak PROD-R6 was
     * about. Recorded numbers: distributed = {@value #CAPACITY} shared, local = 2 x {@value #CAPACITY}.
     */
    @Test
    void twoInHeapBucketsLeakTwiceTheLimit_theBugBeingClosed() {
        Bandwidth bandwidth = DistributedRateLimitStore.bandwidth(properties());
        Bucket inHeapReplicaA = Bucket.builder().addLimit(bandwidth).build();
        Bucket inHeapReplicaB = Bucket.builder().addLimit(bandwidth).build();

        int leakedOnA = 0;
        int leakedOnB = 0;
        for (int i = 0; i < CAPACITY; i++) {
            if (inHeapReplicaA.tryConsume(1)) {
                leakedOnA++;
            }
            if (inHeapReplicaB.tryConsume(1)) {
                leakedOnB++;
            }
        }

        assertThat(leakedOnA + leakedOnB)
                .as("the old per-instance store lets each JVM spend a full capacity of %d — %d in "
                        + "aggregate for one key, which the distributed store above caps at %d",
                        CAPACITY, 2 * CAPACITY, CAPACITY)
                .isEqualTo(2 * CAPACITY);
    }

    /**
     * Isolation: different keys are different buckets in Redis, so exhausting one identity — even
     * across both replicas — leaves another untouched. This is the per-username / per-IP separation
     * the filter depends on; a shared backend must not collapse distinct keys into one allowance.
     */
    @Test
    void distinctKeysGetSeparateBucketsAcrossReplicas() {
        String alice = userKey("alice");
        String bob = userKey("bob");

        // Exhaust alice, spending across BOTH replicas to prove it is one shared bucket for that key.
        consume(replicaA, alice, 3);
        consume(replicaB, alice, 2);
        assertThat(replicaA.tryConsume(alice).isConsumed())
                .as("alice's shared allowance is spent")
                .isFalse();

        // bob is a different key and must be completely unaffected, from either replica.
        for (int i = 0; i < CAPACITY; i++) {
            assertThat(replicaB.tryConsume(bob).isConsumed())
                    .as("bob attempt %d must not be charged to alice's exhausted bucket", i + 1)
                    .isTrue();
        }
        assertThat(replicaA.tryConsume(bob).isConsumed())
                .as("only now is bob's own allowance spent — it never borrowed from alice's")
                .isFalse();
    }

    private static int consume(DistributedRateLimitStore store, String key, int attempts) {
        int consumed = 0;
        for (int i = 0; i < attempts; i++) {
            if (store.tryConsume(key).isConsumed()) {
                consumed++;
            }
        }
        return consumed;
    }

    private static DistributedRateLimitStore storeOver(RedisClient client, RateLimitProperties properties) {
        ProxyManager<String> proxyManager = DistributedRateLimitStore.proxyManagerFor(client, properties);
        return new DistributedRateLimitStore(() -> proxyManager,
                DistributedRateLimitStore.configurationSupplier(properties));
    }

    private static RateLimitProperties properties() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setCapacity(CAPACITY);
        properties.setRefillPeriod(REFILL);
        return properties;
    }

    private static RedisURI redisUri() {
        return RedisURI.builder()
                .withHost(REDIS.getHost())
                .withPort(REDIS.getMappedPort(6379))
                .build();
    }

    /** Unique per test so buckets from earlier tests in this JVM cannot bleed into a later one. */
    private static String ipKey() {
        return "ip|" + LOGIN + "|203.0.113.7|" + UUID.randomUUID();
    }

    private static String userKey(String username) {
        return "user|" + LOGIN + "|" + username + "|" + UUID.randomUUID();
    }
}
