package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.identity.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The self-service management half: {@code /api/profile/two-factor/{setup,enable,disable,
 * recovery-codes/regenerate}}, driven through the real HTTP API on host users so the whole enrolment
 * lifecycle is exercised end to end (including a real login+verify against the enrolled account).
 */
class TwoFactorManagementIT extends AbstractTwoFactorIT {

    private static final String PASSWORD = "Manage-2FA-1!";

    @Test
    void setupProvisionsASecretAndUriWithoutEnabling() {
        TwoFactorUser user = createHostUserWithoutTwoFactor(PASSWORD);
        String token = accessToken(null, user.username(), user.password());

        ResponseEntity<JsonNode> response = post("/api/profile/two-factor/setup", token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("secret").asText()).isNotBlank();
        assertThat(response.getBody().path("otpauthUri").asText())
                .as("setup returns the provisioning URI the authenticator imports")
                .startsWith("otpauth://totp/");

        User row = asHostDatabase(() -> userRepository.findById(user.userId()).orElseThrow());
        assertThat(row.isTwoFactorEnabled())
                .as("setup provisions a pending secret but must NOT switch 2FA on")
                .isFalse();
        assertThat(row.getTwoFactorSecret())
                .as("the pending secret is stored encrypted, not in clear")
                .isNotBlank()
                .isNotEqualTo(response.getBody().path("secret").asText());
    }

    @Test
    void enableWithAValidCodeTurnsOnAndReturnsRecoveryCodesThatLetLoginComplete() {
        TwoFactorUser user = createHostUserWithoutTwoFactor(PASSWORD);
        String token = accessToken(null, user.username(), user.password());

        String secret = post("/api/profile/two-factor/setup", token, null).getBody().path("secret").asText();

        ResponseEntity<JsonNode> enabled = post("/api/profile/two-factor/enable", token,
                Map.of("code", currentTotp(secret)));
        assertThat(enabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode codes = enabled.getBody().path("recoveryCodes");
        assertThat(codes.isArray()).isTrue();
        assertThat(codes.size())
                .as("enable issues the configured number of recovery codes, once")
                .isEqualTo(twoFactorProperties.getRecoveryCodeCount());

        assertThat(asHostDatabase(() -> userRepository.findById(user.userId()).orElseThrow())
                .isTwoFactorEnabled()).isTrue();

        // End to end: the account now requires 2FA at login, and the enrolled secret verifies.
        ResponseEntity<JsonNode> loginResponse = login(null, user.username(), user.password());
        assertThat(loginResponse.getBody().path("twoFactorRequired").asBoolean()).isTrue();
        String challenge = loginResponse.getBody().path("twoFactor").path("challengeToken").asText();
        assertThat(verify(null, challenge, currentTotp(secret)).getStatusCode()).isEqualTo(HttpStatus.OK);

        // ...and so does one of the recovery codes it just handed out.
        String recoveryCode = codes.get(0).asText();
        String freshChallenge = login(null, user.username(), user.password())
                .getBody().path("twoFactor").path("challengeToken").asText();
        assertThat(verify(null, freshChallenge, recoveryCode).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void enableWithAWrongCodeIsRejectedAndLeaves2faOff() {
        TwoFactorUser user = createHostUserWithoutTwoFactor(PASSWORD);
        String token = accessToken(null, user.username(), user.password());
        String secret = post("/api/profile/two-factor/setup", token, null).getBody().path("secret").asText();

        ResponseEntity<JsonNode> response = post("/api/profile/two-factor/enable", token,
                Map.of("code", wrongTotp(secret)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(asHostDatabase(() -> userRepository.findById(user.userId()).orElseThrow())
                .isTwoFactorEnabled())
                .as("a rejected confirmation must not enable 2FA")
                .isFalse();
    }

    @Test
    void disableRequiresTheCurrentPasswordAndClearsEverything() {
        TwoFactorUser user = createHostUserWithoutTwoFactor(PASSWORD);
        // The session minted BEFORE enabling 2FA stays valid (a JWT); enabling does not revoke it.
        // Using it here also avoids re-login, which would now return a challenge instead of a token.
        String token = enrollViaApi(user);

        // wrong password is refused, 2FA stays on
        assertThat(post("/api/profile/two-factor/disable", token, Map.of("password", "wrong-password-1!"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(asHostDatabase(() -> userRepository.findById(user.userId()).orElseThrow())
                .isTwoFactorEnabled()).isTrue();

        // correct password disables and wipes the secret + recovery codes
        assertThat(post("/api/profile/two-factor/disable", token, Map.of("password", user.password()))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        User row = asHostDatabase(() -> userRepository.findById(user.userId()).orElseThrow());
        assertThat(row.isTwoFactorEnabled()).isFalse();
        assertThat(row.getTwoFactorSecret()).isNull();
        assertThat(recoveryCodeRepository.findByUserIdAndConsumedAtIsNull(user.userId())).isEmpty();

        // and login is back to the single-factor path
        assertThat(login(null, user.username(), user.password()).getBody().path("accessToken").asText())
                .isNotBlank();
    }

    @Test
    void regenerateReplacesTheRecoveryCodeSetAfterReverifyingThePassword() {
        TwoFactorUser user = createHostUserWithoutTwoFactor(PASSWORD);
        String token = enrollViaApi(user);

        assertThat(post("/api/profile/two-factor/recovery-codes/regenerate", token,
                Map.of("password", "nope-1!")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> regenerated = post("/api/profile/two-factor/recovery-codes/regenerate",
                token, Map.of("password", user.password()));
        assertThat(regenerated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(regenerated.getBody().path("recoveryCodes").size())
                .isEqualTo(twoFactorProperties.getRecoveryCodeCount());
        // exactly the configured number remain (the old set was replaced, not appended to)
        assertThat(recoveryCodeRepository.findByUserIdAndConsumedAtIsNull(user.userId()))
                .hasSize(twoFactorProperties.getRecoveryCodeCount());
    }

    // -------------------------------------------------------------------------------------
    // negative authorization / self-only
    // -------------------------------------------------------------------------------------

    @Test
    void managementEndpointsRequireAuthentication() {
        assertThat(post("/api/profile/two-factor/setup", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/api/profile/two-factor/enable", null, Map.of("code", "123456")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/api/profile/two-factor/disable", null, Map.of("password", "x")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void enrolmentIsSelfOnlyAndNeverTouchesAnotherUser() {
        TwoFactorUser actor = createHostUserWithoutTwoFactor(PASSWORD);
        TwoFactorUser bystander = createHostUserWithoutTwoFactor(PASSWORD);

        String token = accessToken(null, actor.username(), actor.password());
        String secret = post("/api/profile/two-factor/setup", token, null).getBody().path("secret").asText();
        assertThat(post("/api/profile/two-factor/enable", token, Map.of("code", currentTotp(secret)))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(asHostDatabase(() -> userRepository.findById(actor.userId()).orElseThrow())
                .isTwoFactorEnabled()).isTrue();
        User other = asHostDatabase(() -> userRepository.findById(bystander.userId()).orElseThrow());
        assertThat(other.isTwoFactorEnabled())
                .as("one user enrolling in 2FA must not enable it for anyone else")
                .isFalse();
        assertThat(other.getTwoFactorSecret()).isNull();
    }

    // -------------------------------------------------------------------------------------
    // /api/auth/me surfaces the enrolment state (twoFactorEnabled)
    // -------------------------------------------------------------------------------------

    @Test
    void meReportsTwoFactorDisabledForANonEnrolledUser() {
        TwoFactorUser user = createHostUserWithoutTwoFactor(PASSWORD);
        String token = accessToken(null, user.username(), user.password());

        assertThat(me(null, token).getBody().path("twoFactorEnabled").asBoolean())
                .as("a normal, non-enrolled user must see twoFactorEnabled=false on /me")
                .isFalse();
    }

    @Test
    void meReportsTwoFactorEnabledOnceEnrolled() {
        TwoFactorUser user = createHostUserWithoutTwoFactor(PASSWORD);
        // The token minted BEFORE enabling stays valid; /me reflects live user state, not the token,
        // so the same session flips to twoFactorEnabled=true the moment enrolment completes.
        String token = enrollViaApi(user);

        assertThat(me(null, token).getBody().path("twoFactorEnabled").asBoolean())
                .as("once 2FA is enabled, /me must report twoFactorEnabled=true")
                .isTrue();
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Runs setup + enable through the API for {@code user}, returning the still-valid access token. */
    private String enrollViaApi(TwoFactorUser user) {
        String token = accessToken(null, user.username(), user.password());
        String secret = post("/api/profile/two-factor/setup", token, null).getBody().path("secret").asText();
        assertThat(post("/api/profile/two-factor/enable", token, Map.of("code", currentTotp(secret)))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return token;
    }

    private ResponseEntity<JsonNode> post(String path, String bearer, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
