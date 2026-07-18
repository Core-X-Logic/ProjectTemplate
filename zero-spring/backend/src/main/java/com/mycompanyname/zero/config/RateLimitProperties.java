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
}
