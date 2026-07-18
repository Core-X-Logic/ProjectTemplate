package com.mycompanyname.zero.config;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Works out which address a rate-limit bucket should be charged to (B3).
 *
 * <p><b>The bug this replaces.</b> {@code server.forward-headers-strategy=framework} makes
 * {@code request.getRemoteAddr()} return the <em>leftmost</em> {@code X-Forwarded-For} entry. That
 * is the wrong end of the list. The universal nginx idiom is
 *
 * <pre>proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;</pre>
 *
 * which <em>appends</em> the peer it actually saw. So the list is
 * {@code [whatever the client made up ..., the real client]} and the only trustworthy entry is on
 * the right. Reading the left one let a single host walk past the limiter by sending a different
 * fake leading entry on every request — live-proven: capacity 2, six requests, six 401s.
 *
 * <p><b>The rule.</b> {@code trustedProxyCount} (default 1) is how many proxies stand between the
 * internet and this application. Each appends exactly one entry, so the client address is the
 * {@code trustedProxyCount}-th entry counted from the right. One proxy → the last entry. A CDN in
 * front of nginx → {@code trusted-proxy-count: 2} → the second from the right. Entries to the left
 * of that are client-supplied fiction and are ignored, however many of them there are.
 *
 * <p>{@code trusted-proxy-count: 0} means "no proxy": the header is ignored entirely and the TCP
 * peer is used. Set that for a directly exposed deployment — otherwise any client can pick its own
 * bucket.
 *
 * <p><b>Reading the raw header.</b> {@code ForwardedHeaderFilter} runs before the security chain and
 * hides the {@code X-Forwarded-*} headers from everything downstream, so this class unwraps back to
 * the container's own request object. That deliberately bypasses the framework's interpretation:
 * the whole point is to apply a different, right-to-left one.
 *
 * <p><b>Trust boundary.</b> Unchanged and still load-bearing — the proxy in front must overwrite,
 * not merely forward, client-supplied {@code X-Forwarded-*}. That is the same assumption HSTS
 * depends on (PROD-R4): {@code request.isSecure()} is derived from {@code X-Forwarded-Proto}, so a
 * deployment that gets this wrong loses the HSTS header as well as the rate limit.
 */
final class ClientAddressResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    /** Long enough for any IPv6 address with a zone id; anything longer is not an address. */
    private static final int MAX_ADDRESS_LENGTH = 64;

    private final int trustedProxyCount;

    ClientAddressResolver(int trustedProxyCount) {
        this.trustedProxyCount = Math.max(0, trustedProxyCount);
    }

    /**
     * The address to charge. Falls back to the TCP peer whenever the header is absent, shorter than
     * the configured proxy chain, or does not look like an address at all — all of which mean the
     * deployment is not shaped the way the configuration claims, and guessing would be worse.
     */
    String resolve(HttpServletRequest request) {
        HttpServletRequest container = unwrap(request);
        String peer = container.getRemoteAddr();
        if (trustedProxyCount == 0) {
            return orUnknown(peer);
        }
        List<String> forwarded = forwardedFor(container);
        int index = forwarded.size() - trustedProxyCount;
        if (index < 0 || index >= forwarded.size()) {
            return orUnknown(peer);
        }
        String candidate = forwarded.get(index);
        return looksLikeAddress(candidate) ? candidate.toLowerCase(Locale.ROOT) : orUnknown(peer);
    }

    /**
     * Every {@code X-Forwarded-For} value, in order, flattened across repeated headers — a proxy may
     * append a whole new header line instead of extending the existing one, and both spellings mean
     * the same list.
     */
    private static List<String> forwardedFor(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(X_FORWARDED_FOR);
        if (headers == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            if (header == null) {
                continue;
            }
            for (String part : header.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    values.add(trimmed);
                }
            }
        }
        return Collections.unmodifiableList(values);
    }

    /**
     * Peels off {@code ForwardedHeaderFilter}'s wrapper (and any other) to reach the container
     * request, whose headers and {@code getRemoteAddr()} are the untouched transport-level truth.
     */
    private static HttpServletRequest unwrap(HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            ServletRequest delegate = wrapper.getRequest();
            if (delegate == null) {
                break;
            }
            current = delegate;
        }
        return current instanceof HttpServletRequest http ? http : request;
    }

    /**
     * Not a full address parser — just enough to reject a header crafted to make bucket keys out of
     * arbitrary attacker-chosen text. Accepts the IPv4, IPv6 and bracketed-with-port spellings that
     * appear in {@code X-Forwarded-For}.
     */
    private static boolean looksLikeAddress(String value) {
        if (value.isEmpty() || value.length() > MAX_ADDRESS_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F')
                    || c == '.' || c == ':' || c == '[' || c == ']' || c == '%';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static String orUnknown(String peer) {
        return peer == null || peer.isBlank() ? "unknown" : peer.toLowerCase(Locale.ROOT);
    }
}
