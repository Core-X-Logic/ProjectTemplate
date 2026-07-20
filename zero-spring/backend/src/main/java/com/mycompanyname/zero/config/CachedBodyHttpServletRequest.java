package com.mycompanyname.zero.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays a request body that {@link RateLimitFilter} has already read.
 *
 * <p>The filter needs the submitted username <em>before</em> the controller runs, and a servlet body
 * is a one-shot stream: reading it in a filter would leave the controller with nothing.
 *
 * <p>The buffer is bounded, which is the point of the design. Buffering an arbitrary body would hand
 * an attacker a memory-exhaustion primitive on an unauthenticated endpoint. The filter therefore
 * reads at most {@code zero.ratelimit.max-body-bytes} — and, since B2, refuses anything larger with
 * {@code 413} rather than streaming the remainder through uninspected. So by the time this wrapper
 * is constructed the buffer is always the complete body, and the stream is fully repeatable.
 *
 * <p><b>Form POSTs replay through the PARAMETER API too (P2'-A), and that is load-bearing.</b> For
 * {@code application/x-www-form-urlencoded} the body and the request parameters are the same data
 * by servlet contract, and downstream code is entitled to either view — Spring's
 * {@code ServletServerHttpRequest.getBody()} in particular RECONSTRUCTS a form body from
 * {@code getParameterMap()} rather than reading the stream. But the container parses its parameters
 * from its OWN stream, which the filter has already drained: without the overrides below every
 * {@code @RequestBody} form handler behind the throttle received an empty parameter map, Spring
 * reconstructed an EMPTY body from it, and the request died as 400 "Required request body is
 * missing" — measured on {@code POST /api/billing/webhook/paytr}, where a 400 is a failed PayTR
 * notification, i.e. unsettled money. The overrides answer from the cached buffer (query-string
 * parameters first, body parameters appended — the servlet-spec order) so both views agree with
 * what was actually sent; non-form requests fall through to the container untouched.
 */
final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    private final byte[] body;

    /** Lazily merged; deliberately not volatile — a servlet request is single-threaded. */
    private Map<String, String[]> parameters;

    CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
        return new ReplayServletInputStream(new ByteArrayInputStream(body));
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
    }

    @Override
    public String getParameter(String name) {
        String[] values = parameterMap().get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = parameterMap().get(name);
        return values == null ? null : values.clone();
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameterMap().keySet());
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return Collections.unmodifiableMap(parameterMap());
    }

    private Map<String, String[]> parameterMap() {
        if (parameters == null) {
            parameters = mergeParameters();
        }
        return parameters;
    }

    /**
     * Container parameters first (with the body already drained those are exactly the query-string
     * parameters), then the cached form body's — the servlet-spec precedence. When the container DID
     * manage to parse the body (an earlier filter triggered parsing before the limiter read the
     * stream), the cached buffer is empty and this degrades to the container's own answer — the two
     * sources can never double-count, because the same bytes are only ever readable once upstream.
     */
    private Map<String, String[]> mergeParameters() {
        Map<String, String[]> merged = new LinkedHashMap<>(super.getParameterMap());
        if (!isFormPost() || body.length == 0) {
            return merged;
        }
        for (Map.Entry<String, List<String>> entry : parseFormBody().entrySet()) {
            String[] appended = entry.getValue().toArray(String[]::new);
            merged.merge(entry.getKey(), appended, (existing, added) -> {
                String[] combined = Arrays.copyOf(existing, existing.length + added.length);
                System.arraycopy(added, 0, combined, existing.length, added.length);
                return combined;
            });
        }
        return merged;
    }

    private Map<String, List<String>> parseFormBody() {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        Charset charset = charset();
        for (String pair : new String(body, charset).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int split = pair.indexOf('=');
            String name = split < 0 ? pair : pair.substring(0, split);
            String value = split < 0 ? "" : pair.substring(split + 1);
            try {
                fields.computeIfAbsent(URLDecoder.decode(name, charset), key -> new ArrayList<>())
                        .add(URLDecoder.decode(value, charset));
            } catch (IllegalArgumentException ex) {
                // A pair that does not URL-decode is dropped, matching the container's tolerance:
                // malformed form data is the handler's to reject, not this wrapper's to explode on.
            }
        }
        return fields;
    }

    private boolean isFormPost() {
        String contentType = getContentType();
        return "POST".equalsIgnoreCase(getMethod())
                && contentType != null
                && contentType.regionMatches(true, 0, FORM_CONTENT_TYPE, 0, FORM_CONTENT_TYPE.length());
    }

    private Charset charset() {
        String encoding = getCharacterEncoding();
        return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
    }

    private static final class ReplayServletInputStream extends ServletInputStream {

        private final InputStream delegate;
        private boolean finished;

        private ReplayServletInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("async reads are not supported on a replayed body");
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value == -1) {
                finished = true;
            }
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            int read = delegate.read(target, offset, length);
            if (read == -1) {
                finished = true;
            }
            return read;
        }
    }
}
