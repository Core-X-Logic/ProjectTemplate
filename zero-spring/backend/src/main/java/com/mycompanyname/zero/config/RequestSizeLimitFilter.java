package com.mycompanyname.zero.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The application-wide bound on request body size (F1).
 *
 * <p><b>The gap this closes.</b> A body limit already existed — {@code zero.ratelimit.max-body-bytes},
 * 16 KB, added by B2 — but it was enforced by {@link RateLimitFilter}, and that filter runs on
 * {@code zero.ratelimit.paths}: five anonymous endpoints. Every other endpoint in the application had
 * no bound at all. Tomcat supplies none for a JSON body either ({@code maxPostSize} governs form
 * encoding only), so the size of the byte array Jackson allocated was chosen by the caller.
 *
 * <p><b>Who could drive it.</b> Not an administrator, and not even a caller with one permission.
 * {@code @RequestBody} binding happens during argument resolution, which runs <em>before</em>
 * {@code @PreAuthorize} — the ordering {@code SaasAuthorizationIT} depends on to prove its 403s are
 * genuine. Measured on the shipped configuration: a principal holding a valid token and no
 * permissions whatsoever posted 1.5 MB to {@code POST /api/users} and was answered {@code 403} — the
 * 403 arriving <em>after</em> the whole body had been buffered and deserialized. Authentication was
 * the only gate, and on a platform that provisions its own users authentication is not a gate.
 *
 * <p><b>Two layers, and this is the second one.</b> The control that matters belongs at the reverse
 * proxy: {@code client_max_body_size} refuses the bytes at the edge, before a JVM thread, a heap
 * allocation or a TLS buffer is spent on them. This filter is what stands between a deployment and
 * the day that proxy is misconfigured, replaced, or bypassed by something inside the perimeter. See
 * {@code docs/RELEASE-RUNBOOK.md}.
 *
 * <p><b>Placement: after {@link RateLimitFilter}, deliberately.</b> Two reasons, and both are
 * regressions if it moves.
 * <ul>
 *   <li><b>The stricter rule has to keep winning.</b> The anonymous paths are bounded at 16 KB, 64x
 *       tighter than the global 1 MB. Running ahead of the limiter would let a 1 MB body through to
 *       it — handing B2 back its 20 KB pad field and re-opening the username bucket bypass.</li>
 *   <li><b>C4: a refusal must still cost the sender.</b> {@link RateLimitFilter} charges the IP
 *       bucket before it inspects anything, precisely because an unpriced rejection is just a
 *       different response to an unlimited request rate. Rejecting a throttled path's oversized body
 *       here, first, would make that refusal free again.</li>
 * </ul>
 *
 * <p><b>Content-Length is trusted downward, never upward.</b> When a length is declared and exceeds
 * the bound the request is refused on the header alone — no body is read, which is the whole point:
 * the cheapest possible refusal. When the declared length is within the bound the request is passed
 * through <em>unbuffered</em>, because the container will not hand the application more bytes than
 * the declared length however many the client actually sends. A client that lies low gets truncated
 * by Tomcat; a client that lies high gets refused here. Neither direction is a bypass.
 *
 * <p><b>Chunked bodies are the case that has to be read.</b> {@code Transfer-Encoding: chunked}
 * declares no length, so there is nothing to check a header against — and a length-based check that
 * silently skipped them would be an opt-out any client could take by setting one header. That is
 * exactly the shape of D1 (a {@code Content-Type} the caller chose turning the bound off), and it is
 * not repeated here: the body is read under the bound instead, one byte past it to tell "at the
 * limit" from "over" it.
 *
 * <p><b>Refusal is a client error, and is logged like one.</b> {@code 413} with the same
 * {@code ProblemDetail} shape as every other error in the application, {@code WARN}, no stack trace.
 * E1/E4 established that a caller able to manufacture {@code ERROR} lines on demand can bury a real
 * fault in noise; an oversized body must not become a way to spend the log budget.
 */
@Slf4j
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** Where {@link #doFilterInternal} finds the path {@link #shouldNotFilter} already resolved. */
    private static final String LOOKUP_PATH_ATTRIBUTE =
            RequestSizeLimitFilter.class.getName() + ".lookupPath";

    private final RequestLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final ThrottledPathMatcher pathMatcher;
    private final ClientAddressResolver clientAddressResolver;

    /**
     * {@link RateLimitProperties} appears here for exactly one field:
     * {@code zero.ratelimit.trusted-proxy-count}. That value is a fact about the deployment's proxy
     * chain rather than a rate-limiting parameter, and it is read here rather than duplicated under
     * {@code zero.request} so the two edge filters cannot end up disagreeing about which entry of
     * {@code X-Forwarded-For} is the real client. B3 was the cost of believing the wrong one; two
     * independently configured answers would be that bug with a way to reach it by configuration.
     */
    public RequestSizeLimitFilter(RequestLimitProperties properties,
                                  RateLimitProperties rateLimitProperties,
                                  ObjectMapper objectMapper) {
        this(properties, objectMapper,
                new ClientAddressResolver(rateLimitProperties.getTrustedProxyCount()));
    }

    /** Seam for tests that supply the address resolution directly instead of a Spring context. */
    RequestSizeLimitFilter(RequestLimitProperties properties,
                           ObjectMapper objectMapper,
                           ClientAddressResolver clientAddressResolver) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.pathMatcher = new ThrottledPathMatcher(properties.getPaths());
        this.clientAddressResolver = clientAddressResolver;
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
        request.setAttribute(LOOKUP_PATH_ATTRIBUTE, lookupPath);
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // A request that RateLimitFilter has already read and bounded arrives here wrapped, and it is
        // tempting to skip it on the grounds that it has been measured against a stricter limit
        // already. That is true of the shipped values (16 KB against 1 MB) and only of those values:
        // raise zero.ratelimit.max-body-bytes above zero.request.max-body-bytes and the skip would
        // hand the five anonymous paths the *looser* of the two bounds — turning "the stricter rule
        // wins" from a property of the design into a coincidence of the defaults, on exactly the five
        // paths where it matters most. So the check runs unconditionally. What it costs on an
        // already-buffered request is a header comparison, or a re-read of an in-memory array.
        Object cached = request.getAttribute(LOOKUP_PATH_ATTRIBUTE);
        String path = cached instanceof String value ? value : pathMatcher.lookupPath(request);
        int maxBodyBytes = properties.getMaxBodyBytes();
        long declaredLength = request.getContentLengthLong();

        if (declaredLength > maxBodyBytes) {
            reject(response, path, request, declaredLength, maxBodyBytes);
            return;
        }
        if (declaredLength >= 0) {
            // A declared length within the bound needs nothing further: the container stops the
            // application's view of the body at that length, so there is no way for more to arrive.
            filterChain.doFilter(request, response);
            return;
        }

        // No declared length — chunked. One byte past the bound is enough to know it was exceeded.
        byte[] body = BoundedBodyReader.readOneByteBeyond(request.getInputStream(), maxBodyBytes);
        if (body.length > maxBodyBytes) {
            reject(response, path, request, -1, maxBodyBytes);
            return;
        }
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    /**
     * The log line carries the declared size and the resolved client; the response body carries
     * neither. Same split as everywhere else in this codebase: the caller already knows what it sent,
     * and the operator is the one who needs to see who is sending it.
     */
    private void reject(HttpServletResponse response,
                        String path,
                        HttpServletRequest request,
                        long declaredLength,
                        int maxBodyBytes) throws IOException {
        String declared = declaredLength >= 0 ? declaredLength + " bytes" : "chunked, undeclared";
        log.warn("Refused an oversized request body on {} (declared: {}, limit {} bytes), client={}",
                path, declared, maxBodyBytes, clientAddressResolver.resolve(request));

        ProblemDetail problem = FilterProblemWriter.problem(HttpStatus.PAYLOAD_TOO_LARGE, path,
                ErrorCode.PAYLOAD_TOO_LARGE,
                "Request body exceeds the " + maxBodyBytes + " byte limit.");
        problem.setProperty("maxBodyBytes", maxBodyBytes);

        FilterProblemWriter.write(response, objectMapper, HttpStatus.PAYLOAD_TOO_LARGE, problem);
    }
}
