package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.mycompanyname.zero.AbstractIntegrationIT;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 parity proof for transactional email dispatch (CONTRACT-phase2 §8).
 *
 * <p>A real SMTP endpoint is provided by GreenMail (port 3025) so the {@code SmtpEmailSender} (active
 * only when {@code spring.mail.host} is set) is exercised. Verifies that creating a user delivers a
 * welcome message, and that changing the profile email delivers a confirmation message to the new
 * address.
 */
class EmailDispatchIT extends AbstractIntegrationIT {

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
        HttpHeaders headers = bearerHeaders(accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
        headers.set("Accept-Language", "en"); // deterministic subjects
        return headers;
    }

    @Test
    void creatingAUserDeliversAWelcomeEmail() throws Exception {
        HttpHeaders admin = tenantAdmin();
        greenMail.purgeEmailFromAllMailboxes();

        String username = unique("welcome");
        String email = username + "@example.com";
        Map<String, Object> body = Map.of(
                "username", username,
                "email", email,
                "password", "Password123!",
                "roleNames", Set.of("Admin"));
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(body, admin), JsonNode.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("create user must succeed, got %s", created.getStatusCode())
                .isTrue();

        assertThat(greenMail.waitForIncomingEmail(5000, 1))
                .as("a welcome e-mail must be delivered on user creation")
                .isTrue();
        MimeMessage message = latestTo(email);
        assertThat(message).as("a welcome e-mail addressed to the new user must exist").isNotNull();
        assertThat(message.getSubject()).isEqualTo("Welcome to Zero Platform");
    }

    @Test
    void changingTheProfileEmailDeliversAConfirmationEmail() throws Exception {
        HttpHeaders admin = tenantAdmin();
        String username = unique("confirm");
        Map<String, Object> body = Map.of(
                "username", username,
                "email", username + "@example.com",
                "password", "Password123!",
                "roleNames", Set.of("Admin"));
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(body, admin), JsonNode.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("create user must succeed, got %s", created.getStatusCode())
                .isTrue();

        // discard the welcome mail; only the confirmation should remain
        greenMail.purgeEmailFromAllMailboxes();

        HttpHeaders userHeaders = bearerHeaders(accessToken(DEFAULT_TENANT, username, "Password123!"), DEFAULT_TENANT);
        String newEmail = unique("changed") + "@example.com";
        ResponseEntity<JsonNode> updated = restTemplate.exchange(
                "/api/profile", HttpMethod.PUT,
                new HttpEntity<>(Map.of("email", newEmail), userHeaders), JsonNode.class);
        assertThat(updated.getStatusCode().is2xxSuccessful())
                .as("profile update must succeed, got %s", updated.getStatusCode())
                .isTrue();
        assertThat(updated.getBody().path("emailConfirmed").asBoolean())
                .as("changing the email must reset emailConfirmed to false")
                .isFalse();

        assertThat(greenMail.waitForIncomingEmail(5000, 1))
                .as("a confirmation e-mail must be delivered on email change")
                .isTrue();
        MimeMessage message = latestTo(newEmail);
        assertThat(message).as("a confirmation e-mail addressed to the new address must exist").isNotNull();
        assertThat(message.getSubject()).isEqualTo("Confirm your email address");
    }

    private MimeMessage latestTo(String recipient) throws Exception {
        MimeMessage[] messages = greenMail.getReceivedMessages();
        MimeMessage match = null;
        for (MimeMessage message : messages) {
            if (message.getAllRecipients() != null) {
                for (jakarta.mail.Address address : message.getAllRecipients()) {
                    if (address.toString().contains(recipient)) {
                        match = message;
                    }
                }
            }
        }
        return match;
    }
}
