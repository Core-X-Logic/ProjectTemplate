package com.mycompanyname.zero.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-origin policy for the API (PROD-R3).
 *
 * <p>The SPA is served from its own origin, so without a CORS configuration the browser blocks
 * every call and the platform looks broken on day one — the exact pressure that produces a
 * "temporary" wildcard. The list therefore defaults to <em>empty</em>: cross-origin requests fail
 * closed until a deployment names its origins.
 *
 * <p><b>There is no wildcard form, and now the code says so (B8).</b> The class javadoc used to
 * claim a wildcard "cannot be expressed", but nothing enforced it: {@code CORS_ALLOWED_ORIGINS=*}
 * bound straight through to {@code CorsConfiguration.setAllowedOrigins}, and Spring happily echoed
 * {@code Access-Control-Allow-Origin: *} for every site on the internet. A comment is not a control.
 * {@link #validate()} rejects it at startup instead, along with every other value that is not a
 * concrete origin — under pressure the wildcard is the thing someone reaches for, so it has to fail
 * loudly and immediately rather than at the first security review.
 *
 * <p>Rejection is deliberately a startup failure. Silently dropping a bad entry would leave an
 * operator staring at a working service that refuses their SPA, with no indication why.
 */
@Component
@ConfigurationProperties(prefix = "zero.cors")
@Getter
@Setter
public class CorsProperties {

    /** Exact origins (scheme + host + port), e.g. {@code https://app.example.com}. */
    private List<String> allowedOrigins = new ArrayList<>();

    /**
     * An empty list stays legal — that is the safe default, meaning "refuse every cross-origin
     * request". What is refused is a list that claims to name origins but does not.
     */
    @PostConstruct
    void validate() {
        for (String origin : allowedOrigins) {
            reject(origin);
        }
    }

    private static void reject(String origin) {
        if (origin == null || origin.isBlank()) {
            throw new IllegalStateException(
                    "zero.cors.allowed-origins contains a blank entry. List exact origins such as "
                            + "https://app.example.com, or leave the property empty to refuse all "
                            + "cross-origin requests.");
        }
        String trimmed = origin.trim();
        if (trimmed.contains("*")) {
            throw new IllegalStateException(
                    "zero.cors.allowed-origins does not accept wildcards, and '" + trimmed
                            + "' contains one. A wildcard origin lets any website on the internet drive "
                            + "this API with a victim's Authorization header. Name each SPA origin "
                            + "explicitly (https://app.example.com), or leave the property empty to "
                            + "refuse all cross-origin requests.");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(
                    "zero.cors.allowed-origins contains '" + trimmed + "', which is not a valid origin. "
                            + "Expected scheme://host[:port], e.g. https://app.example.com.", ex);
        }
        boolean wellFormed = uri.getScheme() != null
                && uri.getHost() != null
                && (uri.getPath() == null || uri.getPath().isEmpty())
                && uri.getQuery() == null
                && uri.getFragment() == null
                && uri.getUserInfo() == null;
        if (!wellFormed) {
            throw new IllegalStateException(
                    "zero.cors.allowed-origins contains '" + trimmed + "'. An origin is scheme, host and "
                            + "optional port only — no path, query, fragment or credentials. A value with "
                            + "extra parts never matches the Origin header a browser sends, so it would "
                            + "silently allow nothing.");
        }
    }
}
