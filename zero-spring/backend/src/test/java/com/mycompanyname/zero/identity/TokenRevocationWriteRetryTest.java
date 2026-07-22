package com.mycompanyname.zero.identity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mycompanyname.zero.config.JwtProperties;
import com.mycompanyname.zero.identity.auth.TokenRevocationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * PROD-R16 F4 — the revocation WRITE path. It used to swallow every Redis error, so a transient write
 * blip during a credential change left outstanding tokens un-revoked (a write-side fail-open against a
 * fail-closed read) with no trace. This narrows it two ways, both proven here without a Redis:
 *
 * <ul>
 *   <li>a bounded retry rides out a transient failure, so the revocation is still recorded; and</li>
 *   <li>a sustained failure (retries exhausted) does NOT throw — the credential change is already
 *       DB-committed — but is now observable: it increments {@code jwt.revocation.write_failures}
 *       (tagged by operation) and logs the stable, greppable {@code REVOCATION_WRITE_FAILED} marker,
 *       which carries no token/jti value.</li>
 * </ul>
 *
 * <p>The read path is untouched here and stays fail-closed (see {@code TokenRevocationDegradeIT} /
 * {@code RevokedTokenValidatorTest}). No Mockito: the store is a hand-rolled {@link StringRedisTemplate}
 * whose {@code set} fails a configurable number of times before it starts succeeding.
 */
class TokenRevocationWriteRetryTest {

    private static final Instant FIXED = Instant.parse("2026-07-20T10:15:30.123Z");

    @Test
    void aTransientWriteFailureIsRetriedUntilItSucceeds() {
        FailingRedisTemplate redis = new FailingRedisTemplate(2); // fail twice, then succeed
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TokenRevocationService service = new TokenRevocationService(redis, props(), fixedClock(), meters);

        service.revokeAllForUser(42L);

        assertThat(redis.store())
                .as("the retry must ride out the two transient failures and still record the revocation")
                .containsEntry("zero:jwt:revoked:user:42", Long.toString(FIXED.toEpochMilli()));
        assertThat(meters.find("jwt.revocation.write_failures").counter())
                .as("a write that ultimately succeeded is not a failure")
                .isNull();
    }

    @Test
    void aSustainedWriteFailureIncrementsTheCounterAndWarnsWithoutThrowing() {
        FailingRedisTemplate redis = new FailingRedisTemplate(Integer.MAX_VALUE); // never succeeds
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TokenRevocationService service = new TokenRevocationService(redis, props(), fixedClock(), meters);
        ListAppender<ILoggingEvent> logs = attachAppender();

        try {
            assertThatCode(() -> service.revokeAllForUser(42L))
                    .as("a committed credential change must not be undone by a cache write failure")
                    .doesNotThrowAnyException();

            assertThat(redis.store()).as("nothing was recorded").isEmpty();
            assertThat(meters.get("jwt.revocation.write_failures")
                    .tag("operation", "revoke_all_for_user").counter().count())
                    .as("the exhausted write is now observable")
                    .isEqualTo(1.0);
            assertThat(logs.list)
                    .as("a stable, greppable WARN marker is emitted")
                    .anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("REVOCATION_WRITE_FAILED"));
        } finally {
            detachAppender(logs);
        }
    }

    @Test
    void theAccessTokenWriteFailureIsTaggedSeparatelyAndNeverLogsTheJti() {
        FailingRedisTemplate redis = new FailingRedisTemplate(Integer.MAX_VALUE);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TokenRevocationService service = new TokenRevocationService(redis, props(), fixedClock(), meters);
        ListAppender<ILoggingEvent> logs = attachAppender();

        String secretJti = "super-secret-jti-value-must-not-leak";
        try {
            service.revokeAccessToken(secretJti, FIXED.plusSeconds(600));

            assertThat(meters.get("jwt.revocation.write_failures")
                    .tag("operation", "revoke_access_token").counter().count())
                    .as("the two operations are counted under distinct tags")
                    .isEqualTo(1.0);
            assertThat(logs.list)
                    .anyMatch(e -> e.getFormattedMessage().contains("REVOCATION_WRITE_FAILED"));
            assertThat(logs.list)
                    .as("the failure log must never carry the token value")
                    .noneMatch(e -> e.getFormattedMessage().contains(secretJti));
        } finally {
            detachAppender(logs);
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private static JwtProperties props() {
        JwtProperties p = new JwtProperties();
        p.setAccessTokenTtl(Duration.ofMinutes(15));
        // clockSkew defaults to 30s; revocation defaults (keyPrefix "zero:jwt:revoked:") are fine.
        return p;
    }

    private static Clock fixedClock() {
        return Clock.fixed(FIXED, ZoneOffset.UTC);
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(TokenRevocationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(TokenRevocationService.class)).detachAppender(appender);
    }

    /**
     * A {@link StringRedisTemplate} whose value writes fail a configured number of times before they
     * begin succeeding into an in-memory map. Only {@code set}/{@code get} are meaningful; everything
     * else the service never calls on the write path.
     */
    private static final class FailingRedisTemplate extends StringRedisTemplate {

        private final Map<String, String> store = new ConcurrentHashMap<>();
        private final AtomicInteger failuresRemaining;
        private final ValueOperations<String, String> ops;

        @SuppressWarnings("unchecked")
        FailingRedisTemplate(int initialFailures) {
            this.failuresRemaining = new AtomicInteger(initialFailures);
            this.ops = (ValueOperations<String, String>) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ValueOperations.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "FailingValueOperations";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> null;
                            };
                        }
                        return switch (method.getName()) {
                            case "set" -> {
                                if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                                    throw new RedisConnectionFailureException("simulated transient write failure");
                                }
                                store.put((String) args[0], (String) args[1]);
                                yield null;
                            }
                            case "get" -> store.get(args[0]);
                            default -> null;
                        };
                    });
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return ops;
        }

        Map<String, String> store() {
            return store;
        }
    }
}
