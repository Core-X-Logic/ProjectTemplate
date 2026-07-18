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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

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
 */
final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

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
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
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
