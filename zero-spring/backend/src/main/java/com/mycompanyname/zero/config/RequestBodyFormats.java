package com.mycompanyname.zero.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Answers two questions about a request body's media type, and keeps them apart on purpose (D1):
 *
 * <ol>
 *   <li><b>Will this application deserialize a body in this format?</b> Asked of the application
 *       itself — of the very {@link HttpMessageConverter} list the {@code @RequestBody} argument
 *       resolver consults — rather than of a list somebody maintained by hand.</li>
 *   <li><b>Can {@link RateLimitFilter} derive an identity from a body in this format?</b> That is a
 *       property of the filter's own parser, and it is narrower.</li>
 * </ol>
 *
 * <p><b>Why the two must be asked separately.</b> The limiter used to ask only the second question
 * and treat a "no" as "then this request is none of my business". Every format in the gap between
 * the two answers was therefore an unthrottled path to bcrypt. The gap was not hypothetical and not
 * small: {@code springdoc-openapi-starter-webmvc-ui} brings {@code jackson-dataformat-yaml} onto the
 * classpath, Boot auto-registers {@code MappingJackson2YamlHttpMessageConverter} for it, and YAML
 * 1.2 is a superset of JSON — so the byte-for-byte identical login body, relabelled
 * {@code application/yaml}, bound to the same DTO and reached the credential check while the filter
 * skipped both the size bound and the username bucket. Setting
 * {@code springdoc.api-docs.enabled=false} in prod does not unregister the converter.
 *
 * <p>Three rounds of that same fault (B2, then C1, then D1) were each closed by widening a
 * hand-written allowlist of spellings. This class exists so there is no allowlist to widen: the
 * inventory is computed from the running application, so a converter that lands on the classpath
 * tomorrow is accounted for the moment it is registered — and, since it will land in the gap rather
 * than in the accountable set, it is refused rather than waved through.
 *
 * <p><b>Resolved lazily</b> via {@link ObjectProvider}: the filter is built by the security
 * configuration, and forcing {@code RequestMappingHandlerAdapter} into existence that early would
 * couple two unrelated corners of the context startup order for no benefit. The answer cannot change
 * after startup, so it is computed once and memoised.
 *
 * <p>Resolution is triggered by {@link RateLimitFilter#reportBodyFormatInventory()} on
 * {@code ApplicationReadyEvent} — late enough for the adapter to exist, early enough to precede
 * traffic. It used to be triggered by whichever request first needed it, which made the inventory
 * below a claim rather than a fact: measured, the gap line appeared about two minutes after boot, on
 * the first refused content type, and on a deployment that never received a malformed request it
 * would never have appeared at all.
 */
@Slf4j
final class RequestBodyFormats {

    /**
     * Stand-in for the DTOs the throttled endpoints bind, because {@link HttpMessageConverter#canRead}
     * is answered per target type, not per media type alone. Every throttled endpoint takes a record
     * of short strings ({@code LoginRequest}, {@code ForgotPasswordRequest}, {@code RefreshRequest},
     * {@code ResetPasswordRequest}, {@code ConfirmEmailRequest}), so this is that shape.
     *
     * <p>Asking about a concrete DTO shape rather than {@code Object} is what keeps
     * {@code StringHttpMessageConverter}, {@code ByteArrayHttpMessageConverter},
     * {@code ResourceHttpMessageConverter} and the form converters out of the answer: they read
     * {@code String}, {@code byte[]}, {@code Resource} and {@code MultiValueMap} respectively, and
     * none of them can bind a body to a record. Their media types are consequently reported as
     * unreadable, which matches what the endpoints actually do with them (415).
     *
     * <p>Declared here rather than importing a real DTO so this class stays inside its own module.
     */
    record RepresentativeBody(String usernameOrEmail, String password) {
    }

    private final ObjectProvider<RequestMappingHandlerAdapter> handlerAdapters;

    /** Written once, under {@code this}; read without locking afterwards. */
    private volatile Set<MediaType> readable;

    RequestBodyFormats(ObjectProvider<RequestMappingHandlerAdapter> handlerAdapters) {
        this.handlerAdapters = handlerAdapters;
    }

    /**
     * Whether {@link RateLimitFilter} can read an identity out of a body in this format — which is
     * the only ground on which it may let one through.
     *
     * <p>JSON and the {@code +json} structured suffix RFC 6839 defines, plus (P2'-A)
     * {@code application/x-www-form-urlencoded} — because that is precisely what
     * {@code extractUsername} parses, and no more. The form entry exists for the PayTR notification
     * webhook, whose transport is form-encoded by the provider's contract; it is not a per-path
     * special case, so the filter had to LEARN the format rather than allowlist the path — widening
     * this without also teaching {@code extractUsername} the new format would reopen D1 exactly as
     * it was, which is why the two changed in the same commit and
     * {@code RateLimitFormBodyAccountingTest} pins both halves.
     */
    static boolean isAccountable(MediaType mediaType) {
        return MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)
                || mediaType.getSubtype().endsWith("+json")
                || MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(mediaType);
    }

    /**
     * Whether any converter in this application would deserialize a request DTO from this media
     * type — i.e. whether a body labelled this way would actually reach a handler.
     *
     * <p>Used to tell two refusals apart in the log: a format the application would have read is a
     * live bypass attempt (or a classpath change nobody noticed) and is worth a {@code WARN}; a
     * format nothing can read would have been a 415 anyway and is not.
     */
    boolean isReadableByApplication(MediaType mediaType) {
        return readable().stream().anyMatch(supported -> supported.includes(mediaType));
    }

    /**
     * The gap, in full: formats this application will deserialize but the limiter cannot account
     * for. Every one of them is refused on a throttled path.
     *
     * <p>Called once at startup by {@link RateLimitFilter#reportBodyFormatInventory()} — partly for
     * the answer, but mostly because resolving is what writes the inventory to the log. That is what
     * makes the gap a visible operational fact rather than something an adversary finds first.
     */
    Set<MediaType> unaccountableReadableFormats() {
        Set<MediaType> gap = new LinkedHashSet<>(readable());
        gap.removeIf(RequestBodyFormats::isAccountable);
        return gap;
    }

    private Set<MediaType> readable() {
        Set<MediaType> resolved = this.readable;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (this.readable == null) {
                this.readable = resolve();
            }
            return this.readable;
        }
    }

    private Set<MediaType> resolve() {
        RequestMappingHandlerAdapter adapter = handlerAdapters.getIfAvailable();
        if (adapter == null) {
            // Nothing to ask. Only the log level of a refusal depends on this answer, never the
            // refusal itself, so an empty inventory is safe: it cannot widen what gets through.
            log.warn("No RequestMappingHandlerAdapter available; the rate limiter cannot report which "
                    + "body formats this application reads. Refusals still apply.");
            return Set.of();
        }

        Set<MediaType> supported = new LinkedHashSet<>();
        for (HttpMessageConverter<?> converter : adapter.getMessageConverters()) {
            for (MediaType mediaType : converter.getSupportedMediaTypes()) {
                if (converter.canRead(RepresentativeBody.class, mediaType)) {
                    supported.add(mediaType);
                }
            }
        }

        List<MediaType> gap = supported.stream().filter(mediaType -> !isAccountable(mediaType)).toList();
        if (gap.isEmpty()) {
            log.info("Rate limiter: every request body format this application reads is one it can "
                    + "account for ({})", supported);
        } else {
            // The D1 warning. If this line ever names a format that is meant to be usable on an
            // unauthenticated endpoint, the limiter needs to learn to parse it — refusing it is the
            // safe answer, not necessarily the wanted one.
            log.warn("Rate limiter: this application deserializes request bodies from {} but can only "
                    + "account for {}. Bodies in the remaining formats are refused with 415 on "
                    + "throttled paths, because an identity that cannot be read cannot be counted.",
                    supported, supported.stream().filter(RequestBodyFormats::isAccountable).toList());
        }
        return supported;
    }
}
