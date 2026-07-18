package com.mycompanyname.zero.seed;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence for B4 — the seed flag's default, asserted against the configuration files themselves.
 *
 * <p>The finding: {@code application.yml} carried {@code zero.seed.enabled: true}. Every other
 * dangerous setting in that file fails closed when {@code SPRING_PROFILES_ACTIVE} is unset or
 * misspelled — {@code JWT_SECRET} has no default, the seed password has no default, the CORS list is
 * empty. This one did the opposite: a profile mishap against a production database provisioned a
 * host admin, a {@code default} tenant and a tenant admin, and logged "Data seeding completed".
 * Live-confirmed with no profile, a valid {@code JWT_SECRET} and an operator-supplied password.
 *
 * <p>These assertions read the YAML rather than a running context on purpose. The defect is
 * <em>which file</em> the value lives in, and any test that boots with a profile active is by
 * construction blind to it — that is precisely how it survived until now.
 */
class SeedProfileDefaultTest {

    private static final String SEED_ENABLED = "zero.seed.enabled";

    @Test
    void theBaseConfigurationDoesNotSeed() throws IOException {
        // The property is written as ${SEED_ENABLED:false}; the placeholder default is what a
        // deployment lands on, so that is what has to be read.
        assertThat(placeholderDefault(value("application.yml", SEED_ENABLED)))
                .as("a lost profile must not provision a host admin against whatever database this "
                        + "instance happens to point at")
                .isEqualTo("false");
    }

    @Test
    void theDevProfileSeeds() throws IOException {
        assertThat(placeholderDefault(value("application-dev.yml", SEED_ENABLED)))
                .as("a laptop still wants a working admin login out of the box — the base default is "
                        + "safe because dev opts in, not because seeding is gone")
                .isEqualTo("true");
    }

    @Test
    void theTestProfileSeeds() throws IOException {
        // Every integration test logs in as the seeded admin, so this is load-bearing: if it were
        // missing, the suite would fail loudly rather than silently, but it would fail.
        assertThat(String.valueOf(value("application-test.yml", SEED_ENABLED)))
                .isEqualTo("true");
    }

    @Test
    void theProdProfileDoesNotSeed() throws IOException {
        assertThat(placeholderDefault(value("application-prod.yml", SEED_ENABLED)))
                .isEqualTo("false");
    }

    @Test
    void theCodeLevelFallbackAgreesWithTheConfiguration() {
        // Fixing the YAML alone would leave a second, quieter copy of the same default: a deployment
        // that removes the property outright binds against the @Value fallback instead.
        assertThat(seedEnabledPlaceholder())
                .as("DataSeeder's own fallback must fail closed too")
                .isEqualTo("${" + SEED_ENABLED + ":false}");
    }

    private static String seedEnabledPlaceholder() {
        for (var constructor : DataSeeder.class.getDeclaredConstructors()) {
            for (var annotations : constructor.getParameterAnnotations()) {
                for (var annotation : annotations) {
                    if (annotation instanceof org.springframework.beans.factory.annotation.Value value
                            && value.value().contains(SEED_ENABLED)) {
                        return value.value();
                    }
                }
            }
        }
        throw new AssertionError("DataSeeder no longer reads " + SEED_ENABLED
                + "; this test must be updated to follow it rather than deleted");
    }

    // --- helpers ---------------------------------------------------------

    /** {@code ${SEED_ENABLED:false}} -> {@code false}; a plain literal is returned unchanged. */
    private static String placeholderDefault(Object raw) {
        String text = String.valueOf(raw);
        if (!text.startsWith("${")) {
            return text;
        }
        int colon = text.indexOf(':');
        assertThat(colon)
                .as("%s supplies no default, so an environment without the variable fails to start "
                        + "rather than falling back to a safe value", text)
                .isGreaterThan(0);
        return text.substring(colon + 1, text.length() - 1);
    }

    private static Object value(String fileName, String property) throws IOException {
        Resource resource = new ClassPathResource(fileName);
        assertThat(resource.exists()).as("%s must be on the classpath", fileName).isTrue();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(fileName, resource);
        for (PropertySource<?> source : sources) {
            if (source.containsProperty(property)) {
                return source.getProperty(property);
            }
        }
        throw new AssertionError(fileName + " does not set " + property
                + " — it must state its position explicitly rather than inheriting it");
    }
}
