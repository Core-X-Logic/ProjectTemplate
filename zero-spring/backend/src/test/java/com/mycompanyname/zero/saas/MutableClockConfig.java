package com.mycompanyname.zero.saas;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application's {@code Clock.systemUTC()} bean with a {@link MutableClock}.
 *
 * <p>Only the tests that actually need to move time import this, because importing it forks the
 * Spring context. They all import exactly this one configuration so they share that single extra
 * context rather than starting one each.
 */
@TestConfiguration
public class MutableClockConfig {

    /** Declared as {@link MutableClock} so a test can autowire it and shift it, not merely read it. */
    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock();
    }
}
