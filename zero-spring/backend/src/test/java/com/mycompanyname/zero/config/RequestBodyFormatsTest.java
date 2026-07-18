package com.mycompanyname.zero.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.yaml.MappingJackson2YamlHttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level evidence for D1, alongside the end-to-end proof in
 * {@code RateLimitMediaTypeFailClosedIT}.
 *
 * <p>The integration test proves an unaccountable body cannot reach the credential check. This one
 * pins down the two questions the fix rests on and, more importantly, keeps them from quietly
 * collapsing into one:
 *
 * <ul>
 *   <li>{@link RequestBodyFormats#isReadableByApplication} must say <em>yes</em> to YAML. If it ever
 *       said no, the D1 refusal would still happen but would be logged at {@code DEBUG} as an
 *       ordinary client mistake — the operator would lose the one signal that says a format on the
 *       classpath is reachable and uncounted.</li>
 *   <li>{@link RequestBodyFormats#isAccountable} must say <em>no</em> to YAML. If it ever said yes,
 *       the filter would forward a body it cannot parse and D1 would be reopened exactly as it
 *       was.</li>
 * </ul>
 *
 * <p>Built on the real converter classes rather than mocks. The whole point of the design is that
 * the answer comes from the converters the application actually has, so a test that stubbed them
 * would be testing the stub — which is how the hand-written matrix in
 * {@code RateLimitContentTypeBypassIT} managed to stay green through the D1 bypass (C7, D5).
 */
class RequestBodyFormatsTest {

    /** The converter set Boot registers here: Jackson JSON plus the YAML one springdoc drags in. */
    private final RequestBodyFormats formats = formatsFor(
            new ByteArrayHttpMessageConverter(),
            new StringHttpMessageConverter(),
            new ResourceHttpMessageConverter(),
            new FormHttpMessageConverter(),
            new MappingJackson2HttpMessageConverter(),
            new MappingJackson2YamlHttpMessageConverter());

    // --- what the application can read ------------------------------------

    @Test
    void jsonIsReadable() {
        assertThat(formats.isReadableByApplication(MediaType.APPLICATION_JSON)).isTrue();
    }

    /**
     * The D1 premise. {@code jackson-dataformat-yaml} arrives transitively through
     * springdoc-openapi, Boot registers a converter for it, and YAML 1.2 parses any JSON document —
     * so this converter binds the identical login body to the identical DTO.
     */
    @Test
    void yamlIsReadableBecauseSpringdocPutTheConverterOnTheClasspath() {
        assertThat(formats.isReadableByApplication(MediaType.APPLICATION_YAML)).isTrue();
    }

    /** A vendor suffix reaches the Jackson converter through {@code application/*+json}. */
    @Test
    void aStructuredJsonSuffixIsReadable() {
        assertThat(formats.isReadableByApplication(MediaType.parseMediaType("application/vnd.acme+json")))
                .isTrue();
    }

    /**
     * The converters that read {@code String}, {@code byte[]}, {@code Resource} and
     * {@code MultiValueMap} must not widen the answer: none of them can bind a body to a DTO, which
     * is why these media types are a 415 at the handler rather than a bypass.
     */
    @Test
    void formatsNoConverterCanBindToADtoAreNotReadable() {
        assertThat(formats.isReadableByApplication(MediaType.TEXT_PLAIN)).isFalse();
        assertThat(formats.isReadableByApplication(MediaType.APPLICATION_FORM_URLENCODED)).isFalse();
        assertThat(formats.isReadableByApplication(MediaType.APPLICATION_OCTET_STREAM)).isFalse();
        assertThat(formats.isReadableByApplication(MediaType.parseMediaType("application/x-foo"))).isFalse();
    }

    // --- what the limiter can account for ---------------------------------

    @Test
    void jsonAndItsStructuredSuffixesAreAccountable() {
        assertThat(RequestBodyFormats.isAccountable(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(RequestBodyFormats.isAccountable(
                MediaType.parseMediaType("application/vnd.acme+json"))).isTrue();
        assertThat(RequestBodyFormats.isAccountable(
                MediaType.parseMediaType("application/json;charset=UTF-8"))).isTrue();
    }

    /**
     * The load-bearing assertion of the whole fix. {@code extractUsername} parses JSON and only
     * JSON; the moment this returns true for a format the filter cannot parse, the filter starts
     * forwarding bodies whose username bucket it never charges — which is D1, verbatim.
     */
    @Test
    void yamlIsNotAccountableBecauseTheLimiterCannotParseIt() {
        assertThat(RequestBodyFormats.isAccountable(MediaType.APPLICATION_YAML)).isFalse();
    }

    // --- the gap between the two ------------------------------------------

    /**
     * The gap is what gets refused, and it is reported at startup so it is an operational fact
     * rather than something an adversary finds first.
     */
    @Test
    void theGapIsExactlyTheReadableFormatsTheLimiterCannotParse() {
        assertThat(formats.unaccountableReadableFormats())
                .as("YAML is readable and unparseable, so it is refused on throttled paths")
                .contains(MediaType.APPLICATION_YAML);
        assertThat(formats.unaccountableReadableFormats())
                .as("JSON is readable and parseable, so it must never appear in the refused set — "
                        + "that would be an outage on the platform's login endpoint")
                .doesNotContain(MediaType.APPLICATION_JSON);
    }

    /**
     * A deployment whose converters the limiter can all parse has no gap and says so. This is the
     * shape the codebase would have without springdoc's transitive YAML dependency.
     */
    @Test
    void aJsonOnlyDeploymentHasNoGap() {
        RequestBodyFormats jsonOnly = formatsFor(
                new StringHttpMessageConverter(), new MappingJackson2HttpMessageConverter());

        assertThat(jsonOnly.unaccountableReadableFormats()).isEmpty();
    }

    /**
     * Only the log level of a refusal depends on the inventory, never the refusal itself — so a
     * context that cannot supply the adapter must degrade to "I do not know", not to "allow".
     */
    @Test
    void anUnavailableHandlerAdapterReportsNothingReadable() {
        RequestBodyFormats unknown = new RequestBodyFormats(new StubProvider(null));

        assertThat(unknown.isReadableByApplication(MediaType.APPLICATION_JSON)).isFalse();
        assertThat(unknown.unaccountableReadableFormats()).isEmpty();
    }

    // --- helpers ----------------------------------------------------------

    private static RequestBodyFormats formatsFor(HttpMessageConverter<?>... converters) {
        RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
        adapter.setMessageConverters(List.of(converters));
        return new RequestBodyFormats(new StubProvider(adapter));
    }

    /** Minimal {@link ObjectProvider}; the production code only ever calls {@code getIfAvailable}. */
    private record StubProvider(RequestMappingHandlerAdapter adapter)
            implements ObjectProvider<RequestMappingHandlerAdapter> {

        @Override
        public RequestMappingHandlerAdapter getObject() throws BeansException {
            return adapter;
        }

        @Override
        public RequestMappingHandlerAdapter getObject(Object... args) throws BeansException {
            return adapter;
        }

        @Override
        public RequestMappingHandlerAdapter getIfAvailable() throws BeansException {
            return adapter;
        }

        @Override
        public RequestMappingHandlerAdapter getIfUnique() throws BeansException {
            return adapter;
        }
    }
}
