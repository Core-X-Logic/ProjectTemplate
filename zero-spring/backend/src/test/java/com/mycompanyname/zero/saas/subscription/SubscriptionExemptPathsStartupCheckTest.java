package com.mycompanyname.zero.saas.subscription;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mycompanyname.zero.shared.web.EndpointPolicy;
import com.mycompanyname.zero.shared.web.EndpointPolicy.Exposure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The only mechanism in this design that addresses the RUNTIME-overridable half of the defect.
 *
 * <p>{@code zero.saas.subscription-gate.exempt-paths} is a {@code @Value}, so no build-time rule can
 * know the effective set. Worse, {@code SubscriptionAccessCheck.parse} REPLACES the built-in list
 * wholesale on any non-blank value — so an operator who sets the property intending to add one
 * exemption removes {@code /api/auth/**} at the same time, and a tenant whose subscription lapsed
 * can no longer reach the login it needs in order to renew. A typo does the same thing.
 *
 * <p>Unit-level for the reason {@code RateLimitFilterStartupInventoryTest} is: what needs proving is
 * the behaviour of the {@code ApplicationReadyEvent} hook, and asserting it through a Spring context
 * would mean capturing logs across a boot the suite deliberately caches.
 */
class SubscriptionExemptPathsStartupCheckTest {

    private Logger checkLogger;
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void captureLog() {
        checkLogger = (Logger) LoggerFactory.getLogger(SubscriptionExemptPathsStartupCheck.class);
        captured = new ListAppender<>();
        captured.start();
        checkLogger.addAppender(captured);
    }

    @AfterEach
    void releaseLog() {
        checkLogger.detachAppender(captured);
        captured.stop();
    }

    @Test
    @DisplayName("an exemption matching no live route refuses the boot")
    void anUnresolvableExemptionFailsStartup() {
        // The realistic accident: a wholesale override with one path misspelled. Every other entry
        // resolves, so nothing but this check would notice.
        SubscriptionExemptPathsStartupCheck check = checkWith("/api/auth/**,/api/lokalization/**");

        assertThatThrownBy(check::validateExemptPathsAgainstLiveMappings)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/api/lokalization/**")
                .hasMessageContaining("locks every tenant");
    }

    @Test
    @DisplayName("a widening override is named at WARN together with the routes it opens")
    void aWideningOverrideIsWarnedAbout() {
        SubscriptionExemptPathsStartupCheck check = checkWith("/api/auth/**,/api/localization/**,"
                + "/api/account/**,/api/subscriptions/me,/actuator/**,/v3/api-docs/**,"
                + "/swagger-ui/**,/error,/api/users/**");

        check.validateExemptPathsAgainstLiveMappings();

        assertThat(captured.list)
                .describedAs("an entry outside the built-in set has to be named on the log; an "
                        + "operator widening the gate is a legitimate decision, but never a silent one")
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("/api/users/**")
                        && event.getFormattedMessage().contains("/api/users"));
    }

    @Test
    @DisplayName("the built-in list produces an INFO and no WARN")
    void theDefaultListIsReportedAsClean() {
        SubscriptionExemptPathsStartupCheck check = checkWith("");

        assertThatCode(check::validateExemptPathsAgainstLiveMappings).doesNotThrowAnyException();

        assertThat(captured.list)
                .describedAs("\"no warning\" must be distinguishable from \"never looked\" — the "
                        + "whole reason this runs at startup rather than on first use")
                .anyMatch(event -> event.getLevel() == Level.INFO);
        assertThat(captured.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    @DisplayName("an empty mapping table refuses the boot rather than certifying nothing")
    void anEmptyMappingTableFailsStartup() {
        SubscriptionExemptPathsStartupCheck check = new SubscriptionExemptPathsStartupCheck(
                new SubscriptionAccessCheck(tenantId -> true, ""),
                new SingletonProvider(new StubMapping(new LinkedHashMap<>())));

        assertThatThrownBy(check::validateExemptPathsAgainstLiveMappings)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must fail, not pass");
    }

    // --- fixture ----------------------------------------------------------

    private static SubscriptionExemptPathsStartupCheck checkWith(String configuredExemptPaths) {
        return new SubscriptionExemptPathsStartupCheck(
                new SubscriptionAccessCheck(tenantId -> true, configuredExemptPaths),
                new SingletonProvider(new StubMapping(fixtureHandlers())));
    }

    /** A miniature of the real route table: two claimed routes, one that claims nothing. */
    private static Map<RequestMappingInfo, HandlerMethod> fixtureHandlers() {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        FixtureController bean = new FixtureController();
        register(handlers, bean, "login", "/api/auth/login");
        register(handlers, bean, "dictionary", "/api/localization/{culture}");
        register(handlers, bean, "forgotPassword", "/api/account/forgot-password");
        register(handlers, bean, "me", "/api/subscriptions/me");
        register(handlers, bean, "listUsers", "/api/users");
        return handlers;
    }

    private static void register(Map<RequestMappingInfo, HandlerMethod> handlers,
                                 Object bean, String methodName, String path) {
        RequestMappingInfo.BuilderConfiguration options = new RequestMappingInfo.BuilderConfiguration();
        options.setPatternParser(new PathPatternParser());
        Method method;
        try {
            method = bean.getClass().getMethod(methodName);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("fixture method " + methodName + " is missing", e);
        }
        handlers.put(RequestMappingInfo.paths(path).options(options).build(),
                new HandlerMethod(bean, method));
    }

    /**
     * Deliberately NOT annotated {@code @RestController} and carrying no mapping annotations: Boot's
     * component scan reaches {@code target/test-classes} under the same base package, and a nested
     * controller fixture would be registered as a real bean in every cached integration context. The
     * mapping is supplied by {@link #register} instead; only {@code @EndpointPolicy} needs to be
     * readable off the method here.
     */
    public static class FixtureController {

        @EndpointPolicy({Exposure.ANONYMOUS, Exposure.SUBSCRIPTION_EXEMPT})
        public void login() {
        }

        @EndpointPolicy({Exposure.ANONYMOUS, Exposure.SUBSCRIPTION_EXEMPT})
        public void dictionary() {
        }

        @EndpointPolicy({Exposure.ANONYMOUS, Exposure.SUBSCRIPTION_EXEMPT})
        public void forgotPassword() {
        }

        @EndpointPolicy(Exposure.SUBSCRIPTION_EXEMPT)
        public void me() {
        }

        /** Claims nothing: the route a widening override would open without consent. */
        public void listUsers() {
        }
    }

    private static final class StubMapping extends RequestMappingHandlerMapping {

        private final Map<RequestMappingInfo, HandlerMethod> handlers;

        private StubMapping(Map<RequestMappingInfo, HandlerMethod> handlers) {
            this.handlers = handlers;
        }

        @Override
        public Map<RequestMappingInfo, HandlerMethod> getHandlerMethods() {
            return handlers;
        }
    }

    /** Minimal {@link ObjectProvider}; the production code only calls {@code orderedStream}. */
    private record SingletonProvider(RequestMappingHandlerMapping mapping)
            implements ObjectProvider<RequestMappingHandlerMapping> {

        @Override
        public RequestMappingHandlerMapping getObject() throws BeansException {
            return mapping;
        }

        @Override
        public RequestMappingHandlerMapping getObject(Object... args) throws BeansException {
            return mapping;
        }

        @Override
        public RequestMappingHandlerMapping getIfAvailable() throws BeansException {
            return mapping;
        }

        @Override
        public RequestMappingHandlerMapping getIfUnique() throws BeansException {
            return mapping;
        }

        @Override
        public Stream<RequestMappingHandlerMapping> orderedStream() {
            return Stream.of(mapping);
        }
    }
}
