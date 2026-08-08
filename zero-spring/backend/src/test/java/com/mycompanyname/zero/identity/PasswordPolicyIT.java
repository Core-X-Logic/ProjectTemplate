package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.identity.auth.AccountRecoveryCodes;
import com.mycompanyname.zero.saas.MutableClock;
import com.mycompanyname.zero.saas.MutableClockConfig;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the password policy and account recovery.
 *
 * <p>A real SMTP endpoint is provided by GreenMail (port 3025); the mail host is pointed at it via
 * {@link DynamicPropertySource} so the SMTP e-mail sender (active only when {@code spring.mail.host}
 * is non-empty) is used for this context only.
 *
 * <p>Scenarios asserted: the password policy rejects a weak password on the reset flow (400), a
 * password change cannot reuse the current password (400), the forgot-password / reset-password
 * flow works end to end with the reset code captured from the delivered mail, and the negative
 * space around it: an unknown account gets the same 204 with no mail (no enumeration oracle) and a
 * fabricated reset code is rejected even when the password itself is compliant.
 *
 * <p><b>R-44 (V14).</b> The mailed codes are stored hashed and expiring, and both claims are
 * measured here rather than believed: the database floor holds the SHA-256 of the mailed code
 * (never the code, and the legacy plaintext column stays NULL), and "expired" is a REAL condition
 * — the {@link MutableClock} moves time past each validity window and watches the code die. Before
 * V14 the "Invalid or expired reset code" message advertised a condition no code path could ever
 * reach.
 */
@Import(MutableClockConfig.class)
class PasswordPolicyIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> 3025);
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
    }

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The clock bean is shared by the whole context — a shifted clock must never leak to a sibling test. */
    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private String unique(String prefix) {
        return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
    }

    private HttpHeaders tenantAdmin() {
        return bearerHeaders(accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
    }

    private void createUser(HttpHeaders adminHeaders, String username, String email, String password) {
        Map<String, Object> body = Map.of(
                "username", username,
                "email", email,
                "password", password,
                "roleNames", Set.of("Admin"));
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(body, adminHeaders), JsonNode.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("create user must succeed, got %s", created.getStatusCode())
                .isTrue();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<JsonNode> changePassword(HttpHeaders userHeaders, String current, String next) {
        return restTemplate.exchange(
                "/api/profile/change-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("currentPassword", current, "newPassword", next), userHeaders),
                JsonNode.class);
    }

    @Test
    void changePasswordCannotReuseTheCurrentPassword() {
        HttpHeaders admin = tenantAdmin();
        String username = unique("reuse");
        createUser(admin, username, username + "@example.com", "Password123!");
        HttpHeaders userHeaders = bearerHeaders(accessToken(DEFAULT_TENANT, username, "Password123!"), DEFAULT_TENANT);

        ResponseEntity<JsonNode> response = changePassword(userHeaders, "Password123!", "Password123!");

        assertThat(response.getStatusCode())
                .as("reusing the current password must be rejected with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void changePasswordRejectsAPasswordViolatingThePolicy() {
        HttpHeaders admin = tenantAdmin();
        String username = unique("weakchange");
        createUser(admin, username, username + "@example.com", "Password123!");
        HttpHeaders userHeaders = bearerHeaders(accessToken(DEFAULT_TENANT, username, "Password123!"), DEFAULT_TENANT);

        // 'aaaaaaaa' satisfies the length rule but has no digit and no uppercase letter -> rejected.
        ResponseEntity<JsonNode> response = changePassword(userHeaders, "Password123!", "aaaaaaaa");

        assertThat(response.getStatusCode())
                .as("a weak password must be rejected by the policy with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void changePasswordCannotReuseAPasswordFromHistory() {
        HttpHeaders admin = tenantAdmin();
        String username = unique("histreuse");
        createUser(admin, username, username + "@example.com", "Password123!");

        // change X -> Y (X is retired into the password history)
        HttpHeaders withX = bearerHeaders(accessToken(DEFAULT_TENANT, username, "Password123!"), DEFAULT_TENANT);
        ResponseEntity<JsonNode> toY = changePassword(withX, "Password123!", "Password456!");
        assertThat(toY.getStatusCode())
                .as("the first change to a compliant, unused password must succeed, got %s", toY.getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // re-authenticate with Y, then attempt Y -> X (X is a recently-used password) -> rejected
        HttpHeaders withY = bearerHeaders(accessToken(DEFAULT_TENANT, username, "Password456!"), DEFAULT_TENANT);
        ResponseEntity<JsonNode> backToX = changePassword(withY, "Password456!", "Password123!");

        assertThat(backToX.getStatusCode())
                .as("reusing a password from history must be rejected with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(backToX.getBody()).isNotNull();
        assertThat(backToX.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void forgotPasswordEmailsCodeThenPolicyGuardsResetAndResetSucceeds() throws Exception {
        HttpHeaders admin = tenantAdmin();
        String username = unique("resetme");
        String email = username + "@example.com";
        createUser(admin, username, email, "Password123!");

        greenMail.purgeEmailFromAllMailboxes();
        HttpHeaders json = jsonHeaders();

        // forgot-password always returns 204 (no user enumeration)
        ResponseEntity<Void> forgot = restTemplate.exchange(
                "/api/account/forgot-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("usernameOrEmail", email, "tenant", DEFAULT_TENANT), json), Void.class);
        assertThat(forgot.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(greenMail.waitForIncomingEmail(5000, 1))
                .as("a password reset e-mail must be delivered")
                .isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).isNotEmpty();
        MimeMessage message = messages[messages.length - 1];
        assertThat(message.getAllRecipients()[0].toString()).contains(email);

        String resetCode = extractResetCode(GreenMailUtil.getBody(message));
        assertThat(resetCode).as("reset code must be present in the e-mail").isNotBlank();

        // a weak new password is rejected by the policy; the code is NOT consumed on failure
        ResponseEntity<JsonNode> weak = restTemplate.exchange(
                "/api/account/reset-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("resetCode", resetCode, "newPassword", "alllowercase"), json), JsonNode.class);
        assertThat(weak.getStatusCode())
                .as("a password violating the policy must be rejected with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(weak.getBody()).isNotNull();
        assertThat(weak.getBody().path("code").asText()).isEqualTo("VALIDATION");

        // a compliant new password succeeds
        ResponseEntity<Void> reset = restTemplate.exchange(
                "/api/account/reset-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("resetCode", resetCode, "newPassword", "Reset789!"), json), Void.class);
        assertThat(reset.getStatusCode().is2xxSuccessful())
                .as("reset-password must succeed, got %s", reset.getStatusCode())
                .isTrue();

        // the new password authenticates, the old one no longer does
        assertThat(login(DEFAULT_TENANT, username, "Reset789!").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(DEFAULT_TENANT, username, "Password123!").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void forgotPasswordAnswersTheSame204ForAnUnknownAccountAndAFabricatedCodeIsRejected() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
        HttpHeaders json = jsonHeaders();

        // The happy-path test proves 204 for a KNOWN account; enumeration safety is only proven
        // when the UNKNOWN account gets the indistinguishable half: same 204, and the one channel
        // that could leak existence — the mailbox — stays empty.
        ResponseEntity<Void> forgot = restTemplate.exchange(
                "/api/account/forgot-password", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "usernameOrEmail", unique("ghost") + "@example.com",
                        "tenant", DEFAULT_TENANT), json),
                Void.class);
        assertThat(forgot.getStatusCode())
                .as("an unknown account must get the same 204 as a known one (no enumeration oracle)")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(greenMail.waitForIncomingEmail(1500, 1))
                .as("no mail may be delivered for an unknown account")
                .isFalse();

        // A fabricated code is rejected even with a policy-compliant password. The detail string is
        // load-bearing: the reset screen distinguishes a code rejection from a password rejection
        // by prefix ("Invalid or expired…" vs "Password…") — see reset-password.tsx.
        ResponseEntity<JsonNode> invalid = restTemplate.exchange(
                "/api/account/reset-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("resetCode", unique("bogus"), "newPassword", "Reset789!"), json),
                JsonNode.class);
        assertThat(invalid.getStatusCode())
                .as("a fabricated reset code must be rejected with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).isNotNull();
        assertThat(invalid.getBody().path("code").asText()).isEqualTo("VALIDATION");
        assertThat(invalid.getBody().path("detail").asText()).isEqualTo("Invalid or expired reset code");
    }

    // ---------------------------------------------------------------------------------------
    // R-44 (V14): the codes are stored hashed and they really expire
    // ---------------------------------------------------------------------------------------

    @Test
    void theDatabaseStoresOnlyTheSha256OfTheMailedResetCodeAndTheLegacyColumnStaysNull() throws Exception {
        HttpHeaders admin = tenantAdmin();
        String username = unique("hashed");
        String email = username + "@example.com";
        createUser(admin, username, email, "Password123!");

        greenMail.purgeEmailFromAllMailboxes();
        ResponseEntity<Void> forgot = restTemplate.exchange(
                "/api/account/forgot-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("usernameOrEmail", email, "tenant", DEFAULT_TENANT), jsonHeaders()),
                Void.class);
        assertThat(forgot.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String mailedCode = resetCodeFromDeliveredMail(email);

        // The database floor. Read under the host database context: `users` is policed (V12) and
        // the test thread is not a @Service, so without it the policy answers 0 rows.
        Map<String, Object> row = asHostDatabase(() -> jdbcTemplate.queryForMap(
                "select password_reset_code_hash, password_reset_code_expires_at, password_reset_code "
                        + "from users where lower(username) = lower(?)",
                username));
        assertThat(row.get("password_reset_code_hash"))
                .as("the stored value must be the SHA-256 hex of the mailed code, never the code")
                .isEqualTo(AccountRecoveryCodes.sha256(mailedCode))
                .isNotEqualTo(mailedCode);
        assertThat(row.get("password_reset_code_expires_at"))
                .as("V14 must give the code an expiry")
                .isNotNull();
        assertThat(row.get("password_reset_code"))
                .as("the legacy plaintext column must stay untouched (unmapped, dropped later)")
                .isNull();

        // The mailed (raw) code still works — proving the hash comparison, not equality, is in play.
        ResponseEntity<Void> reset = restTemplate.exchange(
                "/api/account/reset-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("resetCode", mailedCode, "newPassword", "Reset789!"), jsonHeaders()),
                Void.class);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(login(DEFAULT_TENANT, username, "Reset789!").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anExpiredResetCodeIsRefusedAndThePasswordStaysUnchanged() throws Exception {
        HttpHeaders admin = tenantAdmin();
        String username = unique("expired");
        String email = username + "@example.com";
        createUser(admin, username, email, "Password123!");

        greenMail.purgeEmailFromAllMailboxes();
        ResponseEntity<Void> forgot = restTemplate.exchange(
                "/api/account/forgot-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("usernameOrEmail", email, "tenant", DEFAULT_TENANT), jsonHeaders()),
                Void.class);
        assertThat(forgot.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String mailedCode = resetCodeFromDeliveredMail(email);

        // Move time past the 1h validity through the SAME Clock the service reads — this measures
        // the expiry condition itself, not a hand-planted timestamp.
        clock.advance(AccountRecoveryCodes.RESET_CODE_VALIDITY.plus(Duration.ofMinutes(1)));

        ResponseEntity<JsonNode> stale = restTemplate.exchange(
                "/api/account/reset-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("resetCode", mailedCode, "newPassword", "Reset789!"), jsonHeaders()),
                JsonNode.class);
        assertThat(stale.getStatusCode())
                .as("an expired reset code must be refused even with a compliant password")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stale.getBody()).isNotNull();
        assertThat(stale.getBody().path("code").asText()).isEqualTo("VALIDATION");
        // The same non-oracle message as an unknown code: expired-vs-unknown must not be tellable apart.
        assertThat(stale.getBody().path("detail").asText()).isEqualTo("Invalid or expired reset code");

        // The refused reset changed nothing.
        assertThat(login(DEFAULT_TENANT, username, "Password123!").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(DEFAULT_TENANT, username, "Reset789!").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Recovery is a fresh request, exactly what the reset screen's "request a new code" link
        // offers — and the new code works within its own window.
        greenMail.purgeEmailFromAllMailboxes();
        ResponseEntity<Void> again = restTemplate.exchange(
                "/api/account/forgot-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("usernameOrEmail", email, "tenant", DEFAULT_TENANT), jsonHeaders()),
                Void.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String freshCode = resetCodeFromDeliveredMail(email);
        ResponseEntity<Void> reset = restTemplate.exchange(
                "/api/account/reset-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("resetCode", freshCode, "newPassword", "Reset789!"), jsonHeaders()),
                Void.class);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(login(DEFAULT_TENANT, username, "Reset789!").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anExpiredConfirmationCodeIsRefusedAndAFreshOneStillConfirms() throws Exception {
        HttpHeaders admin = tenantAdmin();
        String username = unique("confirm");
        String email = username + "@example.com";
        createUser(admin, username, email, "Password123!");
        HttpHeaders userHeaders = bearerHeaders(
                accessToken(DEFAULT_TENANT, username, "Password123!"), DEFAULT_TENANT);

        // Changing the email issues a confirmation code to the NEW address (ProfileService).
        greenMail.purgeEmailFromAllMailboxes();
        String changedEmail = unique("changed") + "@example.com";
        ResponseEntity<JsonNode> updated = restTemplate.exchange(
                "/api/profile", HttpMethod.PUT,
                new HttpEntity<>(Map.of("email", changedEmail), userHeaders), JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String mailedCode = resetCodeFromDeliveredMail(changedEmail);

        // Past the 72h validity the code is dead — before V14 this condition did not exist at all.
        clock.advance(AccountRecoveryCodes.CONFIRMATION_CODE_VALIDITY.plus(Duration.ofMinutes(1)));
        ResponseEntity<JsonNode> stale = restTemplate.exchange(
                "/api/account/confirm-email", HttpMethod.POST,
                new HttpEntity<>(Map.of("code", mailedCode), jsonHeaders()), JsonNode.class);
        assertThat(stale.getStatusCode())
                .as("an expired confirmation code must be refused")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stale.getBody()).isNotNull();
        assertThat(stale.getBody().path("code").asText()).isEqualTo("VALIDATION");

        // A fresh code (re-issued by another email change) confirms within its own window.
        greenMail.purgeEmailFromAllMailboxes();
        String finalEmail = unique("final") + "@example.com";
        ResponseEntity<JsonNode> changedAgain = restTemplate.exchange(
                "/api/profile", HttpMethod.PUT,
                new HttpEntity<>(Map.of("email", finalEmail), userHeaders), JsonNode.class);
        assertThat(changedAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
        String freshCode = resetCodeFromDeliveredMail(finalEmail);
        ResponseEntity<Void> confirmed = restTemplate.exchange(
                "/api/account/confirm-email", HttpMethod.POST,
                new HttpEntity<>(Map.of("code", freshCode), jsonHeaders()), Void.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> profile = restTemplate.exchange(
                "/api/profile", HttpMethod.GET, new HttpEntity<>(userHeaders), JsonNode.class);
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile.getBody().path("emailConfirmed").asBoolean())
                .as("the fresh code must actually confirm the address")
                .isTrue();
    }

    /** Waits for the code-bearing mail addressed to {@code email} and extracts the code from its body. */
    private String resetCodeFromDeliveredMail(String email) throws Exception {
        assertThat(greenMail.waitForIncomingEmail(5000, 1))
                .as("a code-bearing e-mail must be delivered")
                .isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).isNotEmpty();
        MimeMessage message = messages[messages.length - 1];
        assertThat(message.getAllRecipients()[0].toString()).contains(email);
        String code = extractResetCode(GreenMailUtil.getBody(message));
        assertThat(code).as("the code must be present in the e-mail").isNotBlank();
        return code;
    }

    /**
     * Extracts the reset code from the mail body. The code is an opaque URL-safe base64 token (~43
     * chars), embedded in the reset link; the longest {@code [A-Za-z0-9_-]} run is that token.
     * Tolerates HTML markup and quoted-printable soft line breaks.
     */
    private String extractResetCode(String rawBody) {
        String unfolded = rawBody.replace("=\r\n", "").replace("=\n", "");
        String text = unfolded.replaceAll("<[^>]+>", " ");
        Matcher tokens = Pattern.compile("[A-Za-z0-9_-]{16,}").matcher(text);
        String longest = null;
        while (tokens.find()) {
            String candidate = tokens.group();
            if (longest == null || candidate.length() > longest.length()) {
                longest = candidate;
            }
        }
        return longest;
    }
}
