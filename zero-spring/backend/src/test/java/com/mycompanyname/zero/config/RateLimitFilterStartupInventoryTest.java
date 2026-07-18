package com.mycompanyname.zero.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.yaml.MappingJackson2YamlHttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inventory {@link RequestBodyFormats} reports has to be reported at startup, which is what its
 * javadoc always said and what the code did not do.
 *
 * <p>Resolution was lazy and nothing forced it, so the report was written by whichever request first
 * needed the answer. Measured on dev: the gap line arrived about two minutes after boot, on the first
 * refused content type. The consequence is the part worth a test — on a deployment that never
 * receives a malformed request, the line is never written at all, so the one signal telling an
 * operator "this application deserializes a format the limiter cannot count" appears only where
 * somebody is already probing for it. An operational warning that requires an attack to trigger it
 * is not an operational warning.
 *
 * <p>Unit-level on purpose. Asserting this through a Spring context would mean capturing logs across
 * a boot the suite deliberately caches and reuses, which is both fragile and slow; what needs proving
 * is that the {@code ApplicationReadyEvent} hook forces resolution, and that is visible right here.
 * The wiring itself ({@code @EventListener} on a registered bean) is Spring's to honour.
 */
class RateLimitFilterStartupInventoryTest {

    private Logger formatsLogger;
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void captureLog() {
        formatsLogger = (Logger) LoggerFactory.getLogger(RequestBodyFormats.class);
        captured = new ListAppender<>();
        captured.start();
        formatsLogger.addAppender(captured);
    }

    @AfterEach
    void releaseLog() {
        formatsLogger.detachAppender(captured);
        captured.stop();
    }

    /**
     * The claim, made good: the gap is on the log before any request has been served. The YAML
     * converter is in the fixture because it is the real D1 gap — springdoc drags it onto the
     * classpath, and it is exactly what an operator needs told.
     */
    @Test
    void theFormatGapIsReportedWhenTheApplicationBecomesReadyRatherThanOnFirstUse() {
        RateLimitFilter filter = filterWith(true,
                new StringHttpMessageConverter(),
                new MappingJackson2HttpMessageConverter(),
                new MappingJackson2YamlHttpMessageConverter());

        assertThat(captured.list)
                .as("nothing should be resolved merely by constructing the filter — the security "
                        + "chain is built before the MVC adapter exists, which is why this is lazy")
                .isEmpty();

        filter.reportBodyFormatInventory();

        assertThat(captured.list)
                .as("the D1 gap has to reach the log at startup, naming the format, at WARN — that "
                        + "is the whole operator-facing value of the inventory")
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("yaml"));
    }

    /**
     * A deployment with no gap still has to say so. "No warning" is ambiguous between "nothing to
     * report" and "never looked", and the difference is the entire point of this change.
     */
    @Test
    void aDeploymentWithNoGapReportsThatItHasNone() {
        RateLimitFilter filter = filterWith(true,
                new StringHttpMessageConverter(), new MappingJackson2HttpMessageConverter());

        filter.reportBodyFormatInventory();

        assertThat(captured.list)
                .anyMatch(event -> event.getLevel() == Level.INFO);
        assertThat(captured.list)
                .as("there is no gap here, so nothing should be warned about")
                .noneMatch(event -> event.getLevel() == Level.WARN);
    }

    /**
     * With the limiter switched off nothing is refused, so a report describing refusals would be
     * false. Reporting anyway would be the mirror image of the bug being fixed: a log line that does
     * not correspond to what the system actually does.
     */
    @Test
    void aDisabledLimiterReportsNoInventory() {
        RateLimitFilter filter = filterWith(false,
                new MappingJackson2HttpMessageConverter(), new MappingJackson2YamlHttpMessageConverter());

        filter.reportBodyFormatInventory();

        assertThat(captured.list)
                .as("no refusals happen, so there is no gap to describe")
                .isEmpty();
    }

    private static RateLimitFilter filterWith(boolean enabled, HttpMessageConverter<?>... converters) {
        RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
        adapter.setMessageConverters(List.of(converters));

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(enabled);

        return new RateLimitFilter(properties, new ObjectMapper(),
                new RequestBodyFormats(new StubProvider(adapter)));
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
