package com.mycompanyname.zero.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The degrade policy is the crux of the Redis swap, so it gets its own proof: when the distributed
 * store cannot reach Redis, the filter must fall back to the per-instance bucket — and it must fall
 * back to exactly that, not to either of the two failure modes that would be worse than the bug it is
 * fixing.
 *
 * <ul>
 *   <li><b>Not fail-open.</b> A Redis blip must not hand an attacker unlimited login attempts. At
 *       capacity+1 the request is still 429, charged by the local bucket.</li>
 *   <li><b>Not fail-closed.</b> A Redis blip must not lock every real user out of login. No response
 *       is 503; the requests inside the allowance are served.</li>
 * </ul>
 *
 * <p><b>Negative evidence (recorded).</b> This test was run against a deliberately fail-OPEN degrade
 * branch — the {@code catch} returning a stand-in "consumed" probe instead of falling through to the
 * local bucket — and {@link #aRedisOutageDegradesToTheLocalBucketAndStillThrottles} went red: the
 * capacity+1 request answered 200 (reached the chain) rather than 429. The assertion below is what
 * fails on that mutation.
 *
 * <p>The spoof cases pin the other half the scope-lock is emphatic about: the swap must not have
 * disturbed how a client address is resolved. They run on the per-instance path (Redis off) because
 * {@link ClientAddressResolver} is store-agnostic — the resolved string is the bucket key whatever the
 * store is — so exercising it through the filter here proves it for the distributed path too.
 */
class RateLimitDegradeTest {

    private static final String LOGIN = "/api/auth/login";
    private static final int CAPACITY = 3;

    private Logger filterLogger;
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void captureLog() {
        filterLogger = (Logger) LoggerFactory.getLogger(RateLimitFilter.class);
        captured = new ListAppender<>();
        captured.start();
        filterLogger.addAppender(captured);
    }

    @AfterEach
    void releaseLog() {
        filterLogger.detachAppender(captured);
        captured.stop();
    }

    @Test
    @DisplayName("a Redis outage degrades to the per-instance bucket: throttled at capacity+1, never 503")
    void aRedisOutageDegradesToTheLocalBucketAndStillThrottles() throws Exception {
        RateLimitFilter filter = filter(CAPACITY, 0, unreachableRedis());
        String clientIp = "192.0.2.10";

        // Inside the allowance: served by the LOCAL bucket even though every Redis call throws.
        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = loginThrough(filter, chain, clientIp, "degrade-user");
            assertThat(response.getStatus())
                    .as("attempt %d must be served by the local fallback, not locked out (fail-closed)", attempt)
                    .isEqualTo(HttpStatus.OK.value());
            assertThat(chain.getRequest()).as("the request must reach the chain").isNotNull();
        }

        // capacity+1: the LOCAL bucket is now empty, so this is refused — not waved through.
        MockFilterChain overflowChain = new MockFilterChain();
        MockHttpServletResponse overflow = loginThrough(filter, overflowChain, clientIp, "degrade-user");

        assertThat(overflow.getStatus())
                .as("fail-open would answer 200 here; the local bucket must still bite -> 429")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(overflowChain.getRequest())
                .as("a throttled request must not reach the chain")
                .isNull();

        assertThat(captured.list)
                .as("the operator must be told the limiter is degrading, at WARN, at least once")
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().toLowerCase().contains("redis"));
    }

    @Test
    @DisplayName("the degrade WARN is deduplicated so a Redis outage cannot flood the log")
    void theDegradeWarnIsDeduplicated() throws Exception {
        RateLimitFilter filter = filter(CAPACITY, 0, unreachableRedis());

        // Two served requests => four throwing Redis calls (ip+user each), yet only one WARN.
        for (int attempt = 1; attempt <= 2; attempt++) {
            loginThrough(filter, new MockFilterChain(), "192.0.2.20", "dedup-user");
        }

        long warnings = captured.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .count();
        assertThat(warnings)
                .as("many degraded requests, one WARN — the flood the limiter exists to prevent must "
                        + "not be turned on the operator")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a forged leading X-Forwarded-For is charged to the real client (trusted-proxy-count=1)")
    void aForgedLeadingForwardedEntryCannotMintAFreshBucket() throws Exception {
        RateLimitFilter filter = filter(CAPACITY, 1, null);
        String realClient = "198.51.100.5";

        // Rotating forged leading entry, real client fixed on the right, a fresh username each time so
        // only the IP dimension can stop the overflow.
        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            MockHttpServletResponse response = loginThrough(filter, new MockFilterChain(),
                    "10.0.0." + attempt + ", " + realClient, "spoof-user-" + attempt);
            assertThat(response.getStatus())
                    .as("attempt %d is inside the real client's allowance", attempt)
                    .isEqualTo(HttpStatus.OK.value());
        }

        MockHttpServletResponse overflow = loginThrough(filter, new MockFilterChain(),
                "10.0.0.99, " + realClient, "spoof-user-overflow");

        assertThat(overflow.getStatus())
                .as("the forged leading entry changes every request; only the real trailing entry "
                        + "counts, so the bucket is exhausted and this is 429")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("trusted-proxy-count=0 ignores X-Forwarded-For entirely and charges the transport peer")
    void withNoTrustedProxyTheForwardedHeaderIsIgnored() throws Exception {
        RateLimitFilter filter = filter(CAPACITY, 0, null);
        String peer = "192.0.2.77";

        // A different forged header on every request. If it were honoured each would get its own
        // bucket and nothing would ever throttle; ignored, they all share the transport peer's bucket.
        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            MockHttpServletRequest request = loginRequest("10.9.8." + attempt, "peer-user-" + attempt);
            request.setRemoteAddr(peer);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus())
                    .as("attempt %d is inside the peer's allowance", attempt)
                    .isEqualTo(HttpStatus.OK.value());
        }

        MockHttpServletRequest overflowRequest = loginRequest("10.9.8.99", "peer-user-overflow");
        overflowRequest.setRemoteAddr(peer);
        MockHttpServletResponse overflow = new MockHttpServletResponse();
        filter.doFilter(overflowRequest, overflow, new MockFilterChain());

        assertThat(overflow.getStatus())
                .as("the header was ignored, so every attempt drew on the one peer bucket -> 429")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    // --- helpers ---------------------------------------------------------

    private static MockHttpServletResponse loginThrough(RateLimitFilter filter, MockFilterChain chain,
                                                        String forwardedFor, String username) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(loginRequest(forwardedFor, username), response, chain);
        return response;
    }

    private static MockHttpServletRequest loginRequest(String forwardedFor, String username) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", LOGIN);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.addHeader("X-Forwarded-For", forwardedFor);
        request.setContent(("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"x\"}")
                .getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /**
     * A store whose proxy manager can never be resolved — the exact shape of an unreachable Redis,
     * where {@link DistributedRateLimitStore#tryConsume} throws a {@link RedisException} on every call.
     */
    private static DistributedRateLimitStore unreachableRedis() {
        RateLimitProperties properties = properties(CAPACITY, 0);
        return new DistributedRateLimitStore(
                () -> {
                    throw new RedisException("simulated Redis outage");
                },
                DistributedRateLimitStore.configurationSupplier(properties));
    }

    private static RateLimitFilter filter(int capacity, int trustedProxyCount, DistributedRateLimitStore store) {
        RateLimitProperties properties = properties(capacity, trustedProxyCount);
        RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
        adapter.setMessageConverters(List.of(
                new StringHttpMessageConverter(), new MappingJackson2HttpMessageConverter()));
        return new RateLimitFilter(properties, new ObjectMapper(),
                new RequestBodyFormats(new StubProvider(adapter)), store);
    }

    private static RateLimitProperties properties(int capacity, int trustedProxyCount) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setCapacity(capacity);
        properties.setRefillPeriod(Duration.ofMinutes(1));
        properties.setPaths(List.of(LOGIN));
        properties.setTrustedProxyCount(trustedProxyCount);
        return properties;
    }

    /** Minimal {@link ObjectProvider}; the production code only ever calls {@code getIfAvailable}. */
    private record StubProvider(RequestMappingHandlerAdapter adapter)
            implements ObjectProvider<RequestMappingHandlerAdapter> {

        @Override
        public RequestMappingHandlerAdapter getObject() throws BeansException {
            return adapter;
        }

        @Override
        public RequestMappingHandlerAdapter getObject(Object... args) throws BeansException {
            return adapter;
        }

        @Override
        public RequestMappingHandlerAdapter getIfAvailable() throws BeansException {
            return adapter;
        }

        @Override
        public RequestMappingHandlerAdapter getIfUnique() throws BeansException {
            return adapter;
        }
    }
}
