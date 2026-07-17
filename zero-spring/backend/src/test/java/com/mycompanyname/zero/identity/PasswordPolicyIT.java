package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.mycompanyname.zero.AbstractIntegrationIT;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 parity proof for the password policy and account recovery (CONTRACT-phase2 §4.4, §8).
 *
 * <p>A real SMTP endpoint is provided by GreenMail (port 3025); the mail host is pointed at it via
 * {@link DynamicPropertySource} so the SMTP e-mail sender (active only when {@code spring.mail.host}
 * is non-empty) is used for this context only.
 *
 * <p>Scenarios asserted: the password policy rejects a weak password on the reset flow (400), a
 * password change cannot reuse the current password (400), and the forgot-password / reset-password
 * flow works end to end with the reset code captured from the delivered mail.
 */
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
