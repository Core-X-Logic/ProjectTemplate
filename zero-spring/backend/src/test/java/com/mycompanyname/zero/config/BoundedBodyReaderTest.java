package com.mycompanyname.zero.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read that both edge filters bound their bodies with — {@code RateLimitFilter} at 16 KB (B2)
 * and {@code RequestSizeLimitFilter} at 1 MB (F1).
 *
 * <p>Worth its own test rather than being covered incidentally through the two filters, because the
 * boundary it implements is the one both of them make their decision on: a body of exactly the
 * bound is legitimate and a body one byte over is not, and the difference is a single comparison
 * against this method's return length.
 */
class BoundedBodyReaderTest {

    @Test
    void aBodyShorterThanTheBoundIsReturnedWhole() throws IOException {
        assertThat(read("hello", 1024)).isEqualTo("hello");
    }

    @Test
    void anEmptyBodyReadsAsEmptyRatherThanFailing() throws IOException {
        assertThat(read("", 1024)).isEmpty();
    }

    /** Exactly at the bound is inside it; the callers forward this. */
    @Test
    void aBodyExactlyAtTheBoundIsReturnedWhole() throws IOException {
        String body = "A".repeat(64);

        assertThat(read(body, 64))
                .as("the callers compare length > limit, so returning 65 here would refuse a body "
                        + "that is precisely the configured maximum")
                .hasSize(64)
                .isEqualTo(body);
    }

    /**
     * One byte past the bound, and the reason the method reads {@code limit + 1} rather than
     * {@code limit}: stopping at the bound makes an oversized body indistinguishable from an exactly
     * sized one, and both would then be forwarded.
     */
    @Test
    void aBodyOverTheBoundIsReadOneByteBeyondItAndNoFurther() throws IOException {
        String body = "A".repeat(5000);

        String read = read(body, 64);

        assertThat(read)
                .as("the caller can only detect the overflow by the length exceeding the limit")
                .hasSize(65);
    }

    /**
     * REGRESSION. The callers used to compute {@code limit + 1} themselves and pass it in. At a
     * configured bound of {@code Integer.MAX_VALUE} that addition overflows to a negative number,
     * the read loop exits immediately, and the caller gets an <b>empty body</b> — which passes the
     * size check and is then forwarded to the application <em>in place of the real request</em>.
     *
     * <p>A bound set absurdly high is an operator saying "do not restrict me", and silently blanking
     * every chunked request body is the one answer to that which is worse than not bounding at all.
     * The arithmetic is done in {@code long} inside the reader now, and done once.
     */
    @Test
    void anAbsurdlyHighBoundStillReturnsTheBodyRatherThanNothing() throws IOException {
        assertThat(read("hello", Integer.MAX_VALUE))
                .as("limit + 1 overflowed to Integer.MIN_VALUE, the loop never ran, and the request "
                        + "reached the application with its body replaced by zero bytes")
                .isEqualTo("hello");
    }

    /** A stream that dribbles one byte at a time still has to be drained to the bound. */
    @Test
    void aStreamThatReturnsOneByteAtATimeIsFullyRead() throws IOException {
        byte[] source = "abcdefghij".getBytes(StandardCharsets.UTF_8);

        byte[] read = BoundedBodyReader.readOneByteBeyond(new OneByteAtATimeStream(source), 1024);

        assertThat(new String(read, StandardCharsets.UTF_8))
                .as("a short read is not the end of the stream; treating it as one truncates bodies "
                        + "that arrive in small TCP segments")
                .isEqualTo("abcdefghij");
    }

    private static String read(String body, int limit) throws IOException {
        byte[] bytes = BoundedBodyReader.readOneByteBeyond(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), limit);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Models a socket handing back less than was asked for, which is normal and not EOF. */
    private static final class OneByteAtATimeStream extends InputStream {

        private final byte[] source;
        private int position;

        private OneByteAtATimeStream(byte[] source) {
            this.source = source;
        }

        @Override
        public int read() {
            return position < source.length ? source[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (position >= source.length) {
                return -1;
            }
            target[offset] = source[position++];
            return 1;
        }
    }
}
