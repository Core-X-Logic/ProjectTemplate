package com.mycompanyname.zero.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Tunables for the two-factor login flow ({@code zero.auth.two-factor.*}). Lives in {@code config}
 * (an OPEN module) beside {@link JwtProperties} and {@code RateLimitProperties}, so {@code identity}
 * can read it without a new module edge. Every field has a safe default and an env override.
 */
@Component
@ConfigurationProperties(prefix = "zero.auth.two-factor")
@Getter
@Setter
public class TwoFactorProperties {

    /** How long a pre-login challenge stays redeemable after the password step. */
    private Duration challengeTtl = Duration.ofMinutes(5);

    /** Wrong-code attempts a single challenge tolerates before it is invalidated. */
    private int maxAttempts = 5;

    /** How many single-use recovery codes are minted when 2FA is enabled (and on regeneration). */
    private int recoveryCodeCount = 10;

    /** TOTP time step, in seconds. RFC 6238 / Google Authenticator default is 30. */
    private int totpTimeStepSeconds = 30;

    /**
     * TOTP verification tolerance, in whole time steps on either side of "now". 1 accepts the previous
     * and next 30-second window as well as the current one, absorbing clock skew and the code a user
     * typed just as it rolled over. Larger values widen the guessing surface — keep it small.
     */
    private int totpWindow = 1;
}
