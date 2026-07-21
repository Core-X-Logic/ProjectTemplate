package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.identity.domain.TwoFactorRecoveryCode;
import com.mycompanyname.zero.identity.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The login half of the 2FA slice: the gate between a correct password and a minted session, and the
 * verify endpoint that redeems the challenge.
 *
 * <p><b>The load-bearing test is {@link #twoFactorEnabledLoginYieldsAChallengeAndNoTokens()}.</b> It is
 * the bypass proof: with the 2FA gate present, a correct password for an enrolled user returns a
 * challenge and NO access/refresh token, and the challenge cannot authenticate anything. Remove the
 * gate in {@code AuthService.login} (mint tokens directly) and it goes red — that mutation was run and
 * recorded.
 */
class TwoFactorLoginIT extends AbstractTwoFactorIT {

    private static final String PASSWORD = "Correct-Horse-1!";

    // -------------------------------------------------------------------------------------
    // THE BYPASS PROOF
    // -------------------------------------------------------------------------------------

    @Test
    void twoFactorEnabledLoginYieldsAChallengeAndNoTokens() {
        TwoFactorUser user = createHostUserWithTwoFactor(PASSWORD, 3);

        ResponseEntity<JsonNode> response = login(null, user.username(), user.password());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("twoFactorRequired").asBoolean())
                .as("an enrolled user's correct password must announce a required second factor")
                .isTrue();
        assertThat(body.path("accessToken").isNull())
                .as("NO access token may be minted at the password step when 2FA is enabled")
                .isTrue();
        assertThat(body.path("refreshToken").isNull())
                .as("NO refresh token may be minted at the password step when 2FA is enabled")
                .isTrue();

        String challengeToken = body.path("twoFactor").path("challengeToken").asText();
        assertThat(challengeToken)
                .as("the caller must receive a challenge to redeem")
                .isNotBlank();
        assertThat(body.path("twoFactor").path("expiresInSeconds").asLong()).isPositive();

        // The challenge is not a session: it cannot be presented as a bearer token.
        assertThat(me(null, challengeToken).getStatusCode())
                .as("the challenge token is not a JWT and must not authenticate /api/auth/me")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -------------------------------------------------------------------------------------
    // happy path: TOTP and recovery code
    // -------------------------------------------------------------------------------------

    @Test
    void correctTotpMintsRealTokensAndMeWorks() {
        TwoFactorUser user = createHostUserWithTwoFactor(PASSWORD, 3);
        String challenge = loginForChallenge(user);

        ResponseEntity<JsonNode> verified = verify(null, challenge, currentTotp(user.secret()));
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode tokens = verified.getBody();
        assertThat(tokens).isNotNull();
        String accessToken = tokens.path("accessToken").asText();
        assertThat(accessToken).as("a correct TOTP mints a real access token").isNotBlank();
        assertThat(tokens.path("refreshToken").asText()).isNotBlank();
        assertThat(tokens.path("expiresInSeconds").asLong()).isPositive();

        ResponseEntity<JsonNode> meResponse = me(null, accessToken);
        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meResponse.getBody().path("username").asText()).isEqualTo(user.username());
    }

    @Test
    void validRecoveryCodeMintsTokensThenIsSingleUseWhileSiblingsSurvive() {
        TwoFactorUser user = createHostUserWithTwoFactor(PASSWORD, 3);
        String firstCode = user.recoveryCodes().get(0);
        String siblingCode = user.recoveryCodes().get(1);

        // (1) a valid, unconsumed recovery code mints tokens
        ResponseEntity<JsonNode> ok = verify(null, loginForChallenge(user), firstCode);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().path("accessToken").asText()).isNotBlank();

        // (2) the SAME code again (fresh challenge) is refused — replay blocked
        ResponseEntity<JsonNode> replay = verify(null, loginForChallenge(user), firstCode);
        assertThat(replay.getStatusCode())
                .as("a consumed recovery code must never authenticate again")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // (3) a sibling code is unaffected and still works
        ResponseEntity<JsonNode> sibling = verify(null, loginForChallenge(user), siblingCode);
        assertThat(sibling.getStatusCode())
                .as("consuming one recovery code must not invalidate the others")
                .isEqualTo(HttpStatus.OK);
        assertThat(sibling.getBody().path("accessToken").asText()).isNotBlank();
    }

    // -------------------------------------------------------------------------------------
    // negative paths
    // -------------------------------------------------------------------------------------

    @Test
    void wrongTotpIsRejectedWithGeneric401AndNoToken() {
        TwoFactorUser user = createHostUserWithTwoFactor(PASSWORD, 3);

        ResponseEntity<JsonNode> response = verify(null, loginForChallenge(user), wrongTotp(user.secret()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("accessToken").isMissingNode() || body.path("accessToken").isNull())
                .as("a wrong TOTP must mint no token")
                .isTrue();
        assertThat(body.path("status").asInt()).isEqualTo(401);
    }

    @Test
    void aConsumedChallengeCannotBeReused() {
        TwoFactorUser user = createHostUserWithTwoFactor(PASSWORD, 3);
        String challenge = loginForChallenge(user);

        assertThat(verify(null, challenge, currentTotp(user.secret())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // A second, otherwise-valid code against the already-consumed challenge is refused.
        assertThat(verify(null, challenge, currentTotp(user.secret())).getStatusCode())
                .as("a challenge is single-use, even with a valid code")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnknownChallengeTokenIsRejected() {
        assertThat(verify(null, "not-a-real-challenge-token", "000000").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void repeatedWrongCodesExhaustTheChallengeAndCountTowardLockout() {
        TwoFactorUser user = createHostUserWithTwoFactor(PASSWORD, 3);
        String challenge = loginForChallenge(user);
        int maxAttempts = twoFactorProperties.getMaxAttempts();

        for (int i = 0; i < maxAttempts; i++) {
            assertThat(verify(null, challenge, wrongTotp(user.secret())).getStatusCode())
                    .as("each wrong code is a 401")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // The challenge is now exhausted: even a correct code cannot revive it.
        assertThat(verify(null, challenge, currentTotp(user.secret())).getStatusCode())
                .as("once attempts are exhausted the challenge is invalidated")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // The failures counted toward the per-account lockout: a fresh login is now locked out.
        ResponseEntity<JsonNode> lockedLogin = login(null, user.username(), user.password());
        assertThat(lockedLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(lockedLogin.getBody().path("code").asText())
                .as("2FA failures must count toward the same lockout the password step uses")
                .isEqualTo("ACCOUNT_LOCKED");
    }

    /**
     * SECURITY (stack-review Finding 1). A correct password must NOT reset the lockout counter when
     * 2FA is enabled — otherwise an attacker holding a leaked password but no authenticator resets the
     * counter on every re-login and brute-forces the 6-digit code without bound. Here each login is
     * followed by only FOUR wrong codes — one below the default lockout threshold of five — so no
     * single challenge can ever be the thing that locks; only the CUMULATIVE per-account counter,
     * surviving across re-logins, can. If first-factor success cleared it, the account would never
     * lock and this loop would run to exhaustion.
     */
    @Test
    void firstFactorSuccessDoesNotResetTheLockoutCounterAcrossRelogins() {
        TwoFactorUser user = createHostUserWithTwoFactor(PASSWORD, 1);
        final int wrongPerLogin = 4; // one below the default App.Auth.LockoutMaxFailedAttempts (5)

        boolean locked = false;
        for (int reLogin = 0; reLogin < 10 && !locked; reLogin++) {
            ResponseEntity<JsonNode> loginResponse = login(null, user.username(), user.password());
            if (loginResponse.getStatusCode() == HttpStatus.UNAUTHORIZED
                    && "ACCOUNT_LOCKED".equals(loginResponse.getBody().path("code").asText())) {
                locked = true;
                break;
            }
            assertThat(loginResponse.getStatusCode())
                    .as("until it locks, a correct password keeps returning a 2FA challenge")
                    .isEqualTo(HttpStatus.OK);
            String challenge = loginResponse.getBody().path("twoFactor").path("challengeToken").asText();
            for (int i = 0; i < wrongPerLogin; i++) {
                verify(null, challenge, wrongTotp(user.secret()));
            }
        }

        assertThat(locked)
                .as("interleaving re-logins with sub-threshold wrong codes must STILL reach "
                        + "ACCOUNT_LOCKED. If it never locks, first-factor success is resetting the "
                        + "counter and online TOTP brute-force is unbounded (only the IP limiter remains)")
                .isTrue();
    }

    // -------------------------------------------------------------------------------------
    // fail-closed
    // -------------------------------------------------------------------------------------

    @Test
    void aCorruptStoredSecretFailsClosedWithNoToken() {
        TwoFactorUser user = createHostUserWithTwoFactor(PASSWORD, 3);
        // Inject a fault: overwrite the stored ciphertext with something that cannot be decrypted.
        User row = userRepository.findById(user.userId()).orElseThrow();
        row.setTwoFactorSecret("not-valid-ciphertext");
        userRepository.saveAndFlush(row);

        ResponseEntity<JsonNode> response = verify(null, loginForChallenge(user), currentTotp(user.secret()));
        assertThat(response.getStatusCode())
                .as("a decrypt failure on the verify path must yield NO token, only a generic 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().path("status").asInt()).isEqualTo(401);
    }

    // -------------------------------------------------------------------------------------
    // at rest
    // -------------------------------------------------------------------------------------

    @Test
    void theSecretIsStoredAsCiphertextAndRecoveryCodesAsHashes() {
        TwoFactorUser user = createHostUserWithTwoFactor(PASSWORD, 3);

        User row = userRepository.findById(user.userId()).orElseThrow();
        assertThat(row.getTwoFactorSecret())
                .as("the TOTP secret must never sit in the database in clear text")
                .isNotEqualTo(user.secret());
        assertThat(fieldEncryptionService.decrypt(row.getTwoFactorSecret()))
                .as("the stored ciphertext must decrypt back to the original secret")
                .isEqualTo(user.secret());

        List<TwoFactorRecoveryCode> stored = recoveryCodeRepository.findByUserIdAndConsumedAtIsNull(user.userId());
        assertThat(stored).isNotEmpty();
        for (TwoFactorRecoveryCode code : stored) {
            assertThat(user.recoveryCodes())
                    .as("a recovery code hash must not equal any plaintext code")
                    .doesNotContain(code.getCodeHash());
        }
    }

    // -------------------------------------------------------------------------------------
    // regression: non-2FA login unchanged
    // -------------------------------------------------------------------------------------

    @Test
    void aUserWithoutTwoFactorLogsInExactlyAsBefore() {
        TwoFactorUser user = createHostUserWithoutTwoFactor(PASSWORD);

        ResponseEntity<JsonNode> response = login(null, user.username(), user.password());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("twoFactorRequired").asBoolean()).isFalse();
        assertThat(body.path("accessToken").asText())
                .as("a non-2FA login still returns the token fields at the top level, as before 2FA existed")
                .isNotBlank();
        assertThat(body.path("refreshToken").asText()).isNotBlank();
        assertThat(body.path("expiresInSeconds").asLong()).isPositive();

        // and the token actually works
        assertThat(me(null, body.path("accessToken").asText()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- helpers ---------------------------------------------------------------------------

    private String loginForChallenge(TwoFactorUser user) {
        ResponseEntity<JsonNode> response = login(null, user.username(), user.password());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = response.getBody().path("twoFactor").path("challengeToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }
}
