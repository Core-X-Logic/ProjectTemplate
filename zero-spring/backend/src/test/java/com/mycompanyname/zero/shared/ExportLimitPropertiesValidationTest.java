package com.mycompanyname.zero.shared;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W5-3 follow-up — a non-positive export limit must stop the context, not every request.
 *
 * <p><b>What was wrong.</b> {@code BoundedExport.fetch} checked {@code max-rows &lt; 1} at REQUEST
 * time and threw {@code IllegalStateException}, which {@code GlobalExceptionHandler} logs as
 * {@code "Unhandled exception"} at ERROR and answers with an HTTP 500. So {@code EXPORT_MAX_ROWS=0}
 * booted a perfectly green deployment — health up, readiness up, every probe satisfied — that
 * answered every export with a 500 and wrote an ERROR line for it, indefinitely. That is the
 * pattern {@code ClientErrorLogBudgetIT} exists to prevent on the request path: a predictable,
 * caller-triggerable ERROR line is how a real fault gets buried.
 *
 * <p><b>Why startup.</b> The value cannot change between requests, so checking it per request
 * cannot catch anything a single check at boot would miss — it only moves the discovery from the
 * deploy to the first operator who tries to download a spreadsheet. Same shape as
 * {@code config.CorsPropertiesValidationTest}: {@code @PostConstruct}, {@code
 * IllegalStateException}, and a
 * message that names the property so the failure is actionable without reading the source.
 */
class ExportLimitPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ExportLimitPropertiesConfiguration.class);

    @Test
    void aZeroLimitRefusesToStart() {
        // The realistic typo: "0" read as "unlimited". It is the opposite — it refuses everything,
        // with a 500 rather than a 400.
        contextRunner.withPropertyValues("zero.export.max-rows=0")
                .run(context -> {
                    assertThat(context)
                            .as("max-rows=0 must never be a RUNNING configuration: it turns every "
                                    + "export into a 500 plus an ERROR line, for the life of the "
                                    + "deployment")
                            .hasFailed();
                    assertThat(rootCauseMessage(context.getStartupFailure()))
                            .contains("zero.export.max-rows")
                            .contains("at least 1")
                            .contains("0");
                });
    }

    @Test
    void aNegativeLimitRefusesToStart() {
        contextRunner.withPropertyValues("zero.export.max-rows=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCauseMessage(context.getStartupFailure()))
                            .contains("zero.export.max-rows");
                });
    }

    @Test
    void theFailureMessageNamesTheEnvironmentVariableAnOperatorWouldFix() {
        // A startup failure nobody can act on is only marginally better than the 500 it replaced.
        contextRunner.withPropertyValues("zero.export.max-rows=0")
                .run(context -> assertThat(rootCauseMessage(context.getStartupFailure()))
                        .contains("EXPORT_MAX_ROWS")
                        .contains("10000"));
    }

    @Test
    void aLimitOfOneIsLegalAndStartsFine() {
        // The boundary in the other direction. One row is a pointless export but a coherent
        // configuration, and tightening the rejection must not swallow it.
        contextRunner.withPropertyValues("zero.export.max-rows=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ExportLimitProperties.class).getMaxRows()).isEqualTo(1);
                });
    }

    @Test
    void theDefaultStartsFine() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ExportLimitProperties.class).getMaxRows())
                    .as("the shipped default must survive the validation, or every deployment that "
                            + "does not override it fails to boot")
                    .isEqualTo(10000);
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
    @EnableConfigurationProperties(ExportLimitProperties.class)
    static class ExportLimitPropertiesConfiguration {
    }
}
