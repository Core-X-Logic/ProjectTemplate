package com.mycompanyname.zero.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a request body without letting the sender choose how much memory that costs.
 *
 * <p>Shared by {@link RateLimitFilter} (16 KB on the anonymous paths, B2) and
 * {@link RequestSizeLimitFilter} (the global bound, F1). It lives here rather than as a private
 * method on each because the two filters enforce <em>different limits with the same mechanism</em>,
 * and a copy in each is how one of them ends up fixed and the other does not — the shape of every
 * bug in this package's history (B2, C1, D1 were each a second place that had not been updated).
 *
 * <p>The method reads one byte <em>past</em> the bound, which is what lets a caller distinguish
 * "exactly at the bound" (allowed) from "over it" (refused) while never holding more than the bound
 * plus a byte.
 *
 * <p><b>Why the ceiling is a {@code long}.</b> Both callers used to compute {@code limit + 1}
 * themselves and pass the result in. At a configured bound of {@code Integer.MAX_VALUE} that
 * addition overflows to a negative number, the loop below then exits before reading anything, and
 * the caller receives an <em>empty body</em> which passes the size check and is forwarded to the
 * application in place of the real one. A misconfiguration that silently blanks every chunked
 * request body is a far worse outcome than the one the bound exists to prevent, and the arithmetic
 * that produced it does not belong in two places. Callers now pass the bound itself.
 */
final class BoundedBodyReader {

    private static final int CHUNK_SIZE = 1024;

    private BoundedBodyReader() {
    }

    /**
     * Reads up to {@code limit + 1} bytes; returns fewer only when the stream ends first. A result
     * longer than {@code limit} is the caller's signal that the bound was exceeded.
     */
    static byte[] readOneByteBeyond(InputStream source, int limit) throws IOException {
        long ceiling = (long) limit + 1;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(limit, CHUNK_SIZE));
        byte[] chunk = new byte[CHUNK_SIZE];
        while (buffer.size() < ceiling) {
            int wanted = (int) Math.min(chunk.length, ceiling - buffer.size());
            int read = source.read(chunk, 0, wanted);
            if (read == -1) {
                break;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
