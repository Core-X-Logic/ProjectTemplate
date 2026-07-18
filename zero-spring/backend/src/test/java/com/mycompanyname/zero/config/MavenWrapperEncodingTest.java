package com.mycompanyname.zero.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence for B7.
 *
 * <p>{@code maven-wrapper.properties} was committed with a UTF-8 BOM. The {@code mvnw} shell script
 * parses it with a plain read loop, so the BOM became part of the first key: the script looked for
 * {@code distributionUrl}, found {@code ﻿distributionUrl}, and died with "cannot read
 * distributionUrl property" before running anything. Every Linux CI job failed at the first command,
 * on a file nobody edits and no test covered — {@code mvnw.cmd} on Windows tolerates the BOM, so the
 * failure was invisible to whoever committed it.
 *
 * <p>Three bytes of invisible whitespace can stop the entire pipeline, and nothing else in the build
 * will notice. This test is the thing that notices.
 */
class MavenWrapperEncodingTest {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** Surefire runs with the module directory as the working directory. */
    private static final Path WRAPPER_PROPERTIES =
            Path.of(".mvn", "wrapper", "maven-wrapper.properties");

    @Test
    void theWrapperPropertiesFileHasNoByteOrderMark() throws IOException {
        byte[] content = Files.readAllBytes(WRAPPER_PROPERTIES);

        assertThat(content).hasSizeGreaterThan(UTF8_BOM.length);
        assertThat(new byte[]{content[0], content[1], content[2]})
                .as("a BOM here makes ./mvnw fail with 'cannot read distributionUrl property', "
                        + "which takes CI down before a single test runs")
                .isNotEqualTo(UTF8_BOM);
    }

    @Test
    void theDistributionUrlIsReadableTheWayTheShellScriptReadsIt() {
        // The shell script matches on a line beginning exactly with the key. Asserting the absence of
        // a BOM is not quite the same claim as asserting the key is findable; this asserts the claim.
        assertThat(lines())
                .as("mvnw looks for a line starting with 'distributionUrl='")
                .anyMatch(line -> line.startsWith("distributionUrl="));
    }

    @Test
    void theFileIsPlainAsciiWithNoInvisiblePrefix() {
        // Guards the whole class of "it looks identical in the editor" regressions, not just the BOM.
        for (String line : lines()) {
            assertThat(line)
                    .as("unexpected non-ASCII content in the wrapper properties: %s", line)
                    .matches("[\\x20-\\x7E]*");
        }
    }

    private static java.util.List<String> lines() {
        try {
            return Files.readAllLines(WRAPPER_PROPERTIES, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AssertionError("could not read " + WRAPPER_PROPERTIES.toAbsolutePath(), ex);
        }
    }
}
