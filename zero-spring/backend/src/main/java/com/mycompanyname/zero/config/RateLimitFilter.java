package com.mycompanyname.zero.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-IP and per-username throttle on the unauthenticated endpoints (PROD-R6).
 *
 * <p>The platform already locks an individual account after repeated failures, but nothing stopped a
 * single source from spraying thousands of guesses across many accounts, from enumerating tenants
 * through {@code /api/account/forgot-password}, or from burning CPU on bcrypt at will. This filter
 * closes that: {@code capacity} requests per {@code refill-period}, counted independently per client
 * IP and per submitted username, for each configured path.
 *
 * <p><b>Placement.</b> Installed after {@code CorsFilter} in the security chain, so a 429 still
 * carries the CORS and security headers a browser needs to read it, and preflight {@code OPTIONS} —
 * which {@code CorsFilter} answers itself — never consumes an allowance.
 *
 * <p><b>Client identity.</b> Resolved by {@link ClientAddressResolver}, which reads
 * {@code X-Forwarded-For} from the <em>right</em> — the end a trusted proxy appends — rather than
 * trusting {@code getRemoteAddr()}, which under {@code forward-headers-strategy=framework} returns
 * the leftmost entry and is therefore whatever the client typed (B3). The deployment must still
 * terminate at a proxy that overwrites client-supplied {@code X-Forwarded-*} headers; same trust
 * boundary as HSTS (PROD-R4). See {@code zero.ratelimit.trusted-proxy-count}.
 *
 * <p><b>Path identity.</b> Resolved by {@link ThrottledPathMatcher} against the decoded lookup path,
 * not the raw request URI (B1). {@code /api/auth/%6Cogin} decodes to the login endpoint and used to
 * be counted as a different, unthrottled one; a configured {@code server.servlet.context-path}
 * used to stop every path matching at once, disabling the limiter deployment-wide in silence.
 *
 * <p><b>Fail-closed on unparseable bodies.</b> A body larger than
 * {@code zero.ratelimit.max-body-bytes} is refused with {@code 413} instead of being forwarded
 * uninspected. Forwarding it meant the username bucket was never charged, which combined with a
 * rotating {@code X-Forwarded-For} left no limit in force at all (B2). That refusal now spends the
 * sender's IP allowance like any other request, because a rejection nobody is charged for is not a
 * limit (C4).
 *
 * <p><b>Body identity is decided the way Spring decides it.</b> {@link #extractUsername} accepts the
 * same scalars Jackson will bind to a {@code String} field; it used to be narrower than the
 * controller's own behaviour, and the gap was a way to reach bcrypt while charging nothing — an
 * unquoted numeric username (C2). Whenever this filter's reading of a request disagrees with the
 * framework's, the framework wins and the limit is what gets skipped.
 *
 * <p><b>Fail-closed on formats, not allowlisted (D1).</b> The filter used to ask "does this look
 * like JSON?" and, on a "no", skip both the size bound and the username bucket — an allowlist
 * guarding a security control, and therefore only as complete as its author's inventory of the
 * classpath. The inventory was wrong three times running (B2, C1, D1), and the third time it was
 * wrong about a dependency nobody chose: springdoc drags in {@code jackson-dataformat-yaml}, Boot
 * registers a YAML converter for it, and YAML 1.2 is a superset of JSON — so the identical login
 * body relabelled {@code application/yaml} bound to {@code LoginRequest} and reached bcrypt with no
 * limit charged in either dimension. Live, at capacity 3: ten of ten attempts on one account with a
 * rotating {@code X-Forwarded-For} answered {@code 401 LOGIN_FAILED}; the same body as
 * {@code application/json} was refused on the fourth. The 20 KB bound was gone the same way, and
 * eight {@code /api/account/forgot-password} sweeps answered 204 apiece.
 *
 * <p>So the media type no longer decides <em>whether</em> the filter does its work. Every body on a
 * throttled path is read under the size bound, whatever it claims to be; and a body the filter
 * cannot derive an identity from does not reach a handler at all. What the application can read is
 * asked of the application ({@link RequestBodyFormats}), so a format arriving on the classpath
 * tomorrow lands in the refused set by default rather than in an unguarded one.
 *
 * <p><b>Scope — shared across instances, degrading to per-instance.</b> The buckets are backed by
 * Redis ({@link DistributedRateLimitStore}), so {@code capacity} is one cluster-wide limit rather
 * than one-per-JVM: N instances behind a load balancer no longer permit N x capacity (PROD-R6). The
 * key derivation stayed deliberately backend-agnostic so this was a swap of the store alone — the
 * {@code "ip|"}/{@code "user|"} strings are the Redis keys unchanged. When Redis is unreachable the
 * store throws and {@link #tryConsume} falls back to the in-heap {@link #buckets} map for that
 * request — never failing open to unlimited, never failing closed to 503, just back to the old
 * per-instance bound until Redis returns. When {@code zero.ratelimit.redis.enabled=false} there is no
 * distributed store and the limiter is purely per-instance. The residual for PROD-R6 is now the
 * operational assumption that the proxy overwrites
 * client-supplied {@code X-Forwarded-*} (see {@link ClientAddressResolver}), not the store's scope.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    /** Body fields that carry the identity being attacked, in order of preference. */
    private static final List<String> USERNAME_FIELDS = List.of("usernameOrEmail", "username", "email");

    /** Where {@link #doFilterInternal} finds the path {@link #shouldNotFilter} already resolved. */
    private static final String LOOKUP_PATH_ATTRIBUTE = RateLimitFilter.class.getName() + ".lookupPath";

    /** WARN at most this often while Redis is unreachable, so an outage cannot flood the log. */
    private static final long DEGRADE_WARN_INTERVAL_NANOS = Duration.ofSeconds(30).toNanos();

    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final ThrottledPathMatcher pathMatcher;
    private final ClientAddressResolver clientAddressResolver;
    private final RequestBodyFormats bodyFormats;

    /**
     * The shared Redis-backed store, or {@code null} when Redis is switched off
     * ({@code zero.ratelimit.redis.enabled=false}). Null means "per-instance only", the pre-PROD-R6
     * behaviour; non-null means "distributed, degrading to the local map below on a Redis failure".
     * Either way the local map is the fallback store.
     */
    private final DistributedRateLimitStore distributedStore;

    private final ConcurrentHashMap<String, TrackedBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepNanos = new AtomicLong(System.nanoTime());
    /** Deadline gate for the degrade WARN; see {@link #warnDegrade}. Starts elapsed so the first fires. */
    private final AtomicLong degradeWarnDeadlineNanos = new AtomicLong(System.nanoTime());

    public RateLimitFilter(RateLimitProperties properties,
                           ObjectMapper objectMapper,
                           ObjectProvider<RequestMappingHandlerAdapter> handlerAdapters,
                           ObjectProvider<DistributedRateLimitStore> distributedStores) {
        this(properties, objectMapper, new RequestBodyFormats(handlerAdapters),
                distributedStores.getIfAvailable());
    }

    /** Seam for tests that supply the format inventory directly instead of a Spring context. */
    RateLimitFilter(RateLimitProperties properties,
                    ObjectMapper objectMapper,
                    RequestBodyFormats bodyFormats) {
        this(properties, objectMapper, bodyFormats, null);
    }

    /**
     * Seam for tests that also supply a distributed store — a real Redis-backed one, or a stand-in
     * that throws to drive the degrade path.
     */
    RateLimitFilter(RateLimitProperties properties,
                    ObjectMapper objectMapper,
                    RequestBodyFormats bodyFormats,
                    DistributedRateLimitStore distributedStore) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.bodyFormats = bodyFormats;
        this.distributedStore = distributedStore;
        this.pathMatcher = new ThrottledPathMatcher(properties.getPaths());
        this.clientAddressResolver = new ClientAddressResolver(properties.getTrustedProxyCount());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String lookupPath = pathMatcher.lookupPath(request);
        if (!pathMatcher.matches(lookupPath)) {
            return true;
        }
        // Normalising twice per request would be wasteful and, worse, could disagree with itself if
        // an earlier filter re-wrapped the request. Decide once, carry the decision.
        request.setAttribute(LOOKUP_PATH_ATTRIBUTE, lookupPath);
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Object cached = request.getAttribute(LOOKUP_PATH_ATTRIBUTE);
        String path = cached instanceof String value ? value : pathMatcher.lookupPath(request);
        String client = clientAddressResolver.resolve(request);

        // The IP allowance is consumed first because it is the one an attacker cannot avoid: rotating
        // the submitted username defeats the second bucket but never the first.
        //
        // C4. It is also charged *before* the body is read, which used to be the other way round. A
        // request refused for its size returned from rejectOversizedBody() without ever reaching this
        // line, so the refusal was free: live, eight 20 KB requests from one fixed address collected
        // eight 413s and left the allowance completely unspent, ready for an ordinary login. Refusing
        // a request and charging for it are separate decisions, and skipping the second one turns
        // "rejected" into nothing more than a different response to an unlimited request rate.
        ConsumptionProbe ipProbe = tryConsume("ip|" + path + "|" + client);
        if (!ipProbe.isConsumed()) {
            reject(response, path, client, "ip", ipProbe);
            return;
        }

        // D1. The body is read before anything is decided about its media type, and the size bound is
        // applied to it unconditionally. It used to be the other way round — the bound only applied to
        // bodies that had already been recognised as JSON — which made the Content-Type header the
        // switch that turned the bound off. Bounded read: never buffer more than the configured
        // maximum, so an oversized body on an unauthenticated endpoint cannot be turned into a
        // memory-exhaustion primitive. One extra byte is requested purely to detect there is more.
        int maxBodyBytes = properties.getMaxBodyBytes();
        byte[] body = BoundedBodyReader.readOneByteBeyond(request.getInputStream(), maxBodyBytes);
        if (body.length > maxBodyBytes) {
            // B2. This used to forward the request with username extraction skipped, on the reasoning
            // that "the IP limit still applies". It did not: pairing an oversized body with a rotating
            // X-Forwarded-For defeated the IP bucket too, and the username bucket was never reached —
            // six guesses at one account, six 401s, no limit anywhere. A request whose identity cannot
            // be established is refused rather than exempted. These endpoints carry a few short fields;
            // nothing legitimate is near the bound.
            rejectOversizedBody(response, path, client, maxBodyBytes);
            return;
        }

        // D1/D2. A media type this filter cannot parse means an identity it cannot read, and an
        // identity it cannot read must not reach a handler — a fail-CLOSED rule rather than an
        // allowlist that has to keep pace with the classpath.
        //
        // D2-residue. This check used to sit *after* the empty-body early return below, which meant a
        // request with no body was never checked at all. That was not a gap in coverage so much as a
        // hole straight through the control: `Content-Type: */*` with `Content-Length: 0` skipped the
        // filter entirely and reached the argument resolver, where HttpHeaders.setContentType throws
        // IllegalArgumentException before any handler runs — 500 and a full stack trace, ~189 log
        // lines, per anonymous request. Live on dev at capacity 3: five of five on every one of the
        // five throttled paths. The header is what the resolver cannot honour; the bytes after it
        // were never the point, so emptiness is no reason to skip the check.
        String rawContentType = request.getContentType();
        boolean labelled = rawContentType != null && !rawContentType.isBlank();
        MediaType mediaType = concreteContentType(request);
        boolean unaccountable = mediaType == null || !RequestBodyFormats.isAccountable(mediaType);

        // Two distinct conditions, deliberately not collapsed into one:
        //
        //   labelled && unaccountable  — a Content-Type that names no format this filter can read.
        //                                Refused whether or not a body follows it.
        //   !labelled && body present  — bytes with no label at all, which is the D1 case: an
        //                                identity that cannot be established.
        //
        // What is left out is the case that must NOT be refused: no Content-Type and no body. That is
        // overwhelmingly a wrong HTTP verb, and it has to keep reaching the framework so it gets the
        // 405 (with Allow) that C3 was fixed to produce. Refusing it would turn an honest 405 into a
        // misleading 415 — the same over-correction, in the opposite direction.
        if (unaccountable && (labelled || body.length > 0)) {
            rejectUnaccountableBody(response, path, client, rawContentType, mediaType);
            return;
        }

        // Nothing to account for. A missing body on a well-labelled request is the framework's to
        // report, not this filter's.
        if (body.length == 0) {
            filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
            return;
        }

        String username = extractUsername(body, mediaType);
        if (username != null) {
            ConsumptionProbe userProbe = tryConsume("user|" + path + "|" + username);
            if (!userProbe.isConsumed()) {
                reject(response, path, client, "username", userProbe);
                return;
            }
        }
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    /**
     * Makes {@link RequestBodyFormats}'s startup report an actual startup report.
     *
     * <p>The inventory resolves itself lazily, and its javadoc claimed it was "reported at startup" —
     * but the only thing that forced resolution was the first request that needed it. Observed: the
     * gap line appeared roughly two minutes after boot, on the first refused content type. A
     * deployment that never receives a malformed request never logs it at all, so the one operational
     * signal saying "this application deserializes a format the limiter cannot count" was visible
     * only where somebody was already probing for it.
     *
     * <p>Resolution stays lazy by design (the security chain is built before the MVC adapter exists);
     * this simply asks for the answer once the context is up, which is late enough for the adapter to
     * be available and early enough to precede any traffic. Skipped when the limiter is disabled,
     * because the report describes refusals that would not then happen.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reportBodyFormatInventory() {
        if (!properties.isEnabled()) {
            log.info("Rate limiter is disabled; no request body formats are refused and no inventory "
                    + "is reported.");
            return;
        }
        // Resolving is what logs the inventory — and the D1 gap, at WARN, when there is one.
        bodyFormats.unaccountableReadableFormats();
    }

    /** Clears every bucket. Test-only seam so one scenario cannot starve the next. */
    public void reset() {
        buckets.clear();
    }

    /**
     * One token for {@code key}, from the shared Redis bucket when it is reachable and from the
     * per-instance bucket when it is not.
     *
     * <p>This is the whole degrade policy, and the two ways it must NOT behave are the point of it.
     * Redis reachable &rarr; the distributed bucket, so the limit is one cluster-wide {@code capacity}
     * (PROD-R6). Redis throwing or timing out &rarr; the in-heap bucket that predates this backend,
     * for this request only. It never returns a consumed probe without charging some bucket — that
     * would fail open, and a Redis blip would hand an attacker unlimited login attempts — and it never
     * turns the failure into a rejection — that would fail closed, and a Redis blip would lock every
     * real user out of login. Degrade to the per-instance limit: never worse than before this backend
     * existed, better whenever Redis is up.
     */
    private ConsumptionProbe tryConsume(String key) {
        if (distributedStore != null) {
            try {
                return distributedStore.tryConsume(key);
            } catch (RuntimeException ex) {
                // Any failure reaching Redis (connection refused, timeout, a Lettuce RedisException):
                // degrade, never propagate. Catching broadly is deliberate — the invariant is "a Redis
                // problem must never decide the request", and a narrower catch that let one class of
                // failure through would break exactly that.
                warnDegrade(ex);
            }
        }
        return localBucketFor(key).tryConsumeAndReturnRemaining(1);
    }

    /**
     * Logs the degrade at WARN at most once per {@link #DEGRADE_WARN_INTERVAL_NANOS}, and at DEBUG the
     * rest of the time. A Redis outage would otherwise emit one WARN per throttled request — the very
     * log-flood the limiter exists to prevent, turned on the operator. The deadline starts already
     * elapsed, so the first degrade always warns.
     */
    private void warnDegrade(RuntimeException ex) {
        long now = System.nanoTime();
        long deadline = degradeWarnDeadlineNanos.get();
        if (now - deadline >= 0
                && degradeWarnDeadlineNanos.compareAndSet(deadline, now + DEGRADE_WARN_INTERVAL_NANOS)) {
            log.warn("Rate limiter could not reach Redis; degrading to per-instance buckets for now "
                    + "(the limit still applies, just not cluster-wide). Cause: {}", ex.toString());
        } else {
            log.debug("Rate limiter Redis degrade (deduplicated WARN): {}", ex.toString());
        }
    }

    private Bucket localBucketFor(String key) {
        sweepIfDue();
        TrackedBucket tracked = buckets.computeIfAbsent(key, ignored -> new TrackedBucket(newBucket()));
        tracked.lastUsedNanos = System.nanoTime();
        return tracked.bucket;
    }

    private Bucket newBucket() {
        // The SAME Bandwidth definition the Redis-backed store uses, not a second copy of it. This is
        // what makes DistributedRateLimitStore's "both stores are built from this so they cannot
        // diverge" claim actually true: if the shape ever changes (a burst, refillGreedy, ...), the
        // primary Redis path and this local fallback change together, so a Redis blip can never
        // silently swap one limit for a different one.
        return Bucket.builder()
                .addLimit(DistributedRateLimitStore.bandwidth(properties))
                .build();
    }

    /**
     * Drops buckets untouched for two refill periods — by then they have refilled to full capacity,
     * so discarding them grants nothing. Runs at most once per refill period, or immediately when the
     * map exceeds its bound.
     */
    private void sweepIfDue() {
        long now = System.nanoTime();
        long periodNanos = properties.getRefillPeriod().toNanos();
        long previous = lastSweepNanos.get();
        boolean overdue = now - previous > periodNanos;
        boolean oversized = buckets.size() > properties.getMaxTrackedKeys();
        if ((!overdue && !oversized) || !lastSweepNanos.compareAndSet(previous, now)) {
            return;
        }
        long idleCutoff = 2 * periodNanos;
        buckets.values().removeIf(tracked -> now - tracked.lastUsedNanos > idleCutoff);
    }

    /**
     * The request's media type when it is one that can be honoured at all, otherwise {@code null}.
     *
     * <p>Absent, unparseable and wildcard types collapse to the same answer deliberately: none of
     * them names a format, so none of them can be read, so all three are refused. Keeping them
     * distinct would only invite a per-case judgement, and this control has been reopened twice by
     * exactly that kind of judgement.
     *
     * <p><b>D2.</b> The wildcard case is not merely unreadable, it is actively dangerous downstream.
     * <code>&#42;/&#42;</code> and {@code application/*} parse fine, but {@code HttpHeaders.setContentType}
     * rejects a wildcard with {@code IllegalArgumentException}, and
     * {@code ServletServerHttpRequest.getHeaders()} calls it while the {@code @RequestBody} argument
     * is being resolved — before the handler runs, so {@code HttpMediaTypeNotSupportedException} is
     * never thrown, the 415 handler never sees it, and the {@code Exception} fallback answers 500
     * with a full stack trace at ERROR. Confirmed on dev and prod.
     *
     * <p><b>This is half the fix, and the smaller half.</b> Catching it here covers the throttled
     * paths only, and the crash belongs to the argument resolver, not to them: measured live with a
     * valid token, {@code POST /api/users} answered 500 twelve times out of twelve with no throttle
     * bounding the loop. The other half is a deliberately narrow handler in
     * {@code GlobalExceptionHandler}, which catches the same {@code IllegalArgumentException} by its
     * message and covers every {@code @RequestBody} endpoint in the application. Narrow because a
     * blanket {@code @ExceptionHandler(IllegalArgumentException)} would relabel genuine internal
     * faults from every controller as client errors — which is why this filter-side check is worth
     * keeping even with the handler in place: it stops the wildcard request at the edge, on a path
     * where an anonymous caller can drive it.
     *
     * <p>Deliberately does not consult {@code Content-Length}: a client may stream the body with
     * chunked transfer encoding, in which case the length is -1 and a length-based check would
     * silently skip inspection for every such request. The read is bounded instead.
     */
    private static MediaType concreteContentType(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            // isConcrete() is false for both */* and application/* — the two spellings that crash
            // the argument resolver.
            return mediaType.isConcrete() ? mediaType : null;
        } catch (InvalidMediaTypeException ex) {
            log.debug("Unparseable Content-Type on a throttled path: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Normalised to lower case so {@code Admin} and {@code admin} share one allowance — the lookups
     * these endpoints perform are case-insensitive, so treating them separately would hand an
     * attacker a free multiplier.
     *
     * <p><b>C2.</b> The test used to be {@code value.isTextual()}, which is a stricter reading of the
     * body than the controller's. Jackson coerces any JSON scalar into a {@code String} field without
     * complaint, so {@code {"usernameOrEmail":12345}} bound normally, reached the credential check
     * and answered {@code LOGIN_FAILED} — while charging no username bucket at all. Quoting the same
     * value was limited; leaving the quotes off was not. Any scalar the controller can bind is
     * therefore counted here, with {@code asText()} producing the same string the controller sees.
     *
     * <p><b>P2'-A.</b> Form-urlencoded bodies are parsed too, with the SAME field vocabulary —
     * {@link RequestBodyFormats#isAccountable} admits the format solely because this method reads
     * it (the PayTR webhook's transport). The invariant is unchanged: every media type accountable
     * there is parseable here, or D1 reopens.
     */
    private String extractUsername(byte[] body, MediaType mediaType) {
        if (body.length == 0) {
            return null;
        }
        if (mediaType != null && MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(mediaType)) {
            return extractFormUsername(body);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            for (String field : USERNAME_FIELDS) {
                JsonNode value = root.get(field);
                if (value == null || !value.isValueNode() || value.isNull()) {
                    continue;
                }
                String candidate = value.asText();
                if (!candidate.isBlank()) {
                    return candidate.trim().toLowerCase(Locale.ROOT);
                }
            }
        } catch (IOException ex) {
            // A malformed body is the controller's problem to report; the IP limit already applied.
            log.debug("Rate limiter could not parse the request body for a username: {}", ex.getMessage());
        }
        return null;
    }

    /**
     * The form-encoded reading of the same identity fields, FIRST occurrence wins, and — the part
     * that is load-bearing — malformed pairs are dropped PER PAIR, exactly as
     * {@code CachedBodyHttpServletRequest.parseFormBody} drops them. The two parsers must agree on
     * every input, not just on well-formed ones: when this method used to wrap the WHOLE loop in
     * one try, {@code username=...&x=%zz} charged no username bucket while the wrapper handed the
     * handler that same username intact — one junk pair appended to every request was a free
     * multiplier on the username dimension (the C2 gap, re-cut in the form format; pinned by
     * {@code RateLimitFormBodyAccountingTest.aMalformedPairDoesNotUncountTheUsername}).
     */
    private static String extractFormUsername(byte[] body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) {
                continue;
            }
            try {
                fields.putIfAbsent(
                        URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ex) {
                // Only THIS pair is undecodable. The rest of the body still carries the identity
                // the handler will read, so it must still be counted.
                log.debug("Rate limiter dropped an undecodable form pair: {}", ex.getMessage());
            }
        }
        for (String field : USERNAME_FIELDS) {
            String candidate = fields.get(field);
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private void reject(HttpServletResponse response,
                        String path,
                        String client,
                        String dimension,
                        ConsumptionProbe probe) throws IOException {
        long retryAfterSeconds = Math.max(1,
                Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        log.warn("Rate limit exceeded on {} ({} dimension), client={}, retry after {}s",
                path, dimension, client, retryAfterSeconds);

        ProblemDetail problem = FilterProblemWriter.problem(HttpStatus.TOO_MANY_REQUESTS, path,
                ErrorCode.TOO_MANY_REQUESTS,
                "Too many requests. Try again in " + retryAfterSeconds + " second(s).");
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);

        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        FilterProblemWriter.write(response, objectMapper, HttpStatus.TOO_MANY_REQUESTS, problem);
    }

    /**
     * D1. A body whose identity this filter cannot read does not get to reach a handler.
     *
     * <p>The log level is the interesting part. A format the application <em>would</em> have
     * deserialized is either somebody probing for the D1 bypass or a converter that arrived on the
     * classpath without anyone noticing — both are worth a {@code WARN} naming the format, because
     * both need a human decision. A format nothing here can read (say {@code text/plain}) was headed
     * for a 415 regardless; logging that at {@code WARN} would hand an anonymous caller a way to fill
     * the log, which is the fault C3 was about.
     *
     * <p>415 rather than 400 because that is what the request is: this endpoint reads JSON, and the
     * body is not JSON. The detail deliberately does not echo the submitted type — it tells the
     * sender nothing it does not know, and the real value goes to the log where it is useful.
     */
    private void rejectUnaccountableBody(HttpServletResponse response,
                                         String path,
                                         String client,
                                         String rawContentType,
                                         MediaType mediaType) throws IOException {
        if (mediaType != null && bodyFormats.isReadableByApplication(mediaType)) {
            log.warn("Refused a body on the throttled path {} in a format this application would "
                            + "deserialize but the limiter cannot read an identity from "
                            + "(Content-Type: {}), client={}. An identity that cannot be counted "
                            + "cannot be allowed through.",
                    path, rawContentType, client);
        } else {
            log.debug("Refused a body with an unusable Content-Type ({}) on the throttled path {}, "
                    + "client={}", rawContentType, path, client);
        }

        ProblemDetail problem = FilterProblemWriter.problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, path,
                ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "This endpoint accepts a JSON request body.");

        FilterProblemWriter.write(response, objectMapper, HttpStatus.UNSUPPORTED_MEDIA_TYPE, problem);
    }

    /**
     * B2. Deliberately says nothing about which endpoint or account was targeted — the point is to
     * stop the request, not to confirm anything to whoever sent it.
     */
    private void rejectOversizedBody(HttpServletResponse response,
                                     String path,
                                     String client,
                                     int maxBodyBytes) throws IOException {
        log.warn("Refused an oversized body on the throttled path {} (limit {} bytes), client={}",
                path, maxBodyBytes, client);

        ProblemDetail problem = FilterProblemWriter.problem(HttpStatus.PAYLOAD_TOO_LARGE, path,
                ErrorCode.PAYLOAD_TOO_LARGE,
                "Request body exceeds the " + maxBodyBytes + " byte limit for this endpoint.");
        problem.setProperty("maxBodyBytes", maxBodyBytes);

        FilterProblemWriter.write(response, objectMapper, HttpStatus.PAYLOAD_TOO_LARGE, problem);
    }

    /** A bucket plus the last time it was touched, so idle entries can be swept. */
    private static final class TrackedBucket {
        private final Bucket bucket;
        private volatile long lastUsedNanos;

        private TrackedBucket(Bucket bucket) {
            this.bucket = bucket;
            this.lastUsedNanos = System.nanoTime();
        }
    }

    /** Exposed for diagnostics/tests: how many keys are currently tracked. */
    public Map<String, Long> stats() {
        return Map.of(
                "trackedKeys", (long) buckets.size(),
                "sweepIntervalSeconds", TimeUnit.NANOSECONDS.toSeconds(properties.getRefillPeriod().toNanos()));
    }
}
