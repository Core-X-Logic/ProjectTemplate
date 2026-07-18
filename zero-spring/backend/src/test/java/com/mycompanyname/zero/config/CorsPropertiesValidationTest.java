package com.mycompanyname.zero.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence for B8.
 *
 * <p>{@link CorsProperties} documented that a wildcard "cannot be expressed" and then accepted one:
 * {@code CORS_ALLOWED_ORIGINS=*} bound straight through to {@code CorsConfiguration}, which echoed
 * {@code Access-Control-Allow-Origin: *} to every site on the internet. The javadoc was the only
 * thing standing between the deployment and that, and javadoc does not run.
 *
 * <p>The wildcard is the value someone reaches for at 2am when the SPA cannot talk to the API, so
 * the failure has to be immediate and legible rather than deferred to a security review. Each test
 * asserts the context <em>fails to start</em>, and that the message names the property — a startup
 * failure nobody can act on is only marginally better than the wildcard.
 */
class CorsPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CorsPropertiesConfiguration.class);

    @Test
    void aWildcardOriginRefusesToStart() {
        contextRunner.withPropertyValues("zero.cors.allowed-origins=*")
                .run(context -> {
                    assertThat(context)
                            .as("a wildcard lets any website drive this API with a victim's "
                                    + "Authorization header; it must never be a running configuration")
                            .hasFailed();
                    assertThat(rootCauseMessage(context.getStartupFailure()))
                            .contains("zero.cors.allowed-origins")
                            .contains("wildcard");
                });
    }

    @Test
    void aWildcardHiddenInsideAnOriginRefusesToStart() {
        // The subdomain form is the one that looks reasonable in a config file and is not supported
        // by the CORS spec at all — it would silently match nothing, or, worse, be "fixed" later by
        // someone who assumes it should work.
        contextRunner.withPropertyValues("zero.cors.allowed-origins=https://*.example.com")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCauseMessage(context.getStartupFailure())).contains("wildcard");
                });
    }

    @Test
    void aWildcardAlongsideValidOriginsStillRefusesToStart() {
        // The realistic shape of the mistake: a working list with one entry appended in a hurry.
        contextRunner
                .withPropertyValues("zero.cors.allowed-origins=https://app.example.com,*")
                .run(context -> assertThat(context)
                        .as("one bad entry poisons the list — it must not be quietly tolerated "
                                + "because its neighbours are fine")
                        .hasFailed());
    }

    @Test
    void aBlankEntryRefusesToStart() {
        // Indexed form, because the comma-separated form lets the binder discard the empty element
        // before this validation ever sees it — and the point here is what happens when it does not.
        contextRunner
                .withPropertyValues(
                        "zero.cors.allowed-origins[0]=https://app.example.com",
                        "zero.cors.allowed-origins[1]=   ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anOriginWithAPathRefusesToStart() {
        // Browsers send scheme+host+port as the Origin header and nothing else, so this entry can
        // never match. Accepting it means an operator watching a list that does nothing.
        contextRunner
                .withPropertyValues("zero.cors.allowed-origins=https://app.example.com/spa")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCauseMessage(context.getStartupFailure())).contains("no path");
                });
    }

    @Test
    void anEmptyListIsTheSafeDefaultAndStartsFine() {
        // Empty means "refuse every cross-origin request". That is the intended default and must not
        // be collateral damage from tightening the validation.
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CorsProperties.class).getAllowedOrigins()).isEmpty();
        });
    }

    @Test
    void concreteOriginsStartFine() {
        contextRunner
                .withPropertyValues("zero.cors.allowed-origins="
                        + "https://app.example.com,http://localhost:5173,http://127.0.0.1:5173")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CorsProperties.class).getAllowedOrigins())
                            .containsExactly("https://app.example.com",
                                    "http://localhost:5173", "http://127.0.0.1:5173");
                });
    }

    private static String rootCauseMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CorsProperties.class)
    static class CorsPropertiesConfiguration {
    }
}
