package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.mycompanyname.zero.AbstractIntegrationIT;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the invitation flow: invite → e-mail token → anonymous accept →
 * active account → login. The mail leg runs against a real SMTP endpoint (GreenMail), and the token
 * asserted with is only ever the one CAPTURED FROM THE DELIVERED MAIL — the API never returns it.
 *
 * <p>Negative space covered: missing permission (403), unknown/expired/revoked token (one non-oracle
 * 400), double accept (no-op, no second account), the seat limit re-checked at accept time, the
 * duplicate-pending 409, and cross-tenant invisibility measured both at the API and — through the
 * {@code inTenantDatabase}/{@code asHostDatabase} helpers — at the RLS floor of {@code V15}.
 */
class InvitationFlowIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";
    private static final String MAX_USER_COUNT = "app.maxUserCount";
    private static final String ACCEPT_PASSWORD = "Invited123!";
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
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------------------------------

    @Test
    void inviteEmailsATokenAcceptCreatesAnActiveConfirmedAccountAndLoginWorks() throws Exception {
        HttpHeaders admin = tenantAdmin();
        String username = unique("invitee");
        String email = username + "@example.com";

        greenMail.purgeEmailFromAllMailboxes();
        ResponseEntity<JsonNode> invited = invite(admin, username, email);
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(invited.getBody()).isNotNull();
        assertThat(invited.getBody().path("status").asText()).isEqualTo("PENDING");
        // The token must never appear in the API response — the mail is its only carrier.
        assertThat(invited.getBody().has("token")).isFalse();
        assertThat(invited.getBody().has("tokenHash")).isFalse();
        long invitationId = invited.getBody().path("id").asLong();

        String token = tokenFromDeliveredMail(email);

        // The anonymous info endpoint shows the admin-fixed username to the token holder.
        ResponseEntity<JsonNode> info = restTemplate.getForEntity(
                "/api/account/invitation?token=" + token, JsonNode.class);
        assertThat(info.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(info.getBody()).isNotNull();
        assertThat(info.getBody().path("username").asText()).isEqualTo(username);
        assertThat(info.getBody().path("status").asText()).isEqualTo("PENDING");

        assertThat(accept(token, ACCEPT_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The invited account signs in with the password it just chose.
        assertThat(login(DEFAULT_TENANT, username, ACCEPT_PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Active + emailConfirmed (the token round-trip IS the confirmation), with the invited role.
        JsonNode user = findUser(admin, username);
        assertThat(user.path("active").asBoolean()).isTrue();
        assertThat(user.path("emailConfirmed").asBoolean()).isTrue();
        assertThat(user.path("roles").toString()).contains("Admin");

        // The admin list reflects the consumption.
        assertThat(invitationStatus(admin, invitationId)).isEqualTo("ACCEPTED");
    }

    // ---------------------------------------------------------------------------------------
    // Authorization
    // ---------------------------------------------------------------------------------------

    @Test
    void aUserWithoutUsersCreateCannotInviteOrTouchInvitations() {
        HttpHeaders admin = tenantAdmin();
        String powerless = unique("powerless");
        createUser(admin, powerless, powerless + "@example.com", "Password123!", Set.of());
        HttpHeaders headers = bearerHeaders(
                accessToken(DEFAULT_TENANT, powerless, "Password123!"), DEFAULT_TENANT);

        assertThat(invite(headers, unique("nope"), unique("nope") + "@example.com").getStatusCode())
                .as("invite without users.create must be 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange("/api/invitations", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class).getStatusCode())
                .as("listing invitations without users.create must be 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------------------------
    // Token lifecycle
    // ---------------------------------------------------------------------------------------

    @Test
    void anUnknownTokenIsRefusedWithoutAnOracle() {
        ResponseEntity<JsonNode> response = acceptJson("definitely-not-a-token", ACCEPT_PASSWORD);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void anExpiredInvitationIsRefusedAndResendReissuesAWorkingToken() throws Exception {
        HttpHeaders admin = tenantAdmin();
        String username = unique("expired");
        String email = username + "@example.com";

        greenMail.purgeEmailFromAllMailboxes();
        ResponseEntity<JsonNode> invited = invite(admin, username, email);
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long invitationId = invited.getBody().path("id").asLong();
        String staleToken = tokenFromDeliveredMail(email);

        // Push expiry into the past at the floor. The write is made under the host database
        // context: the test thread is not a @Service, so without it the V15 policy answers 0 rows.
        asHostDatabase(() -> {
            int updated = jdbcTemplate.update(
                    "update user_invitations set expires_at = now() - interval '1 hour' where id = ?",
                    invitationId);
            assertThat(updated).isEqualTo(1);
        });

        assertThat(acceptJson(staleToken, ACCEPT_PASSWORD).getStatusCode())
                .as("an expired invitation must be refused")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // The admin re-sends: fresh token, fresh validity, same invitation row.
        greenMail.purgeEmailFromAllMailboxes();
        ResponseEntity<JsonNode> resent = restTemplate.exchange(
                "/api/invitations/" + invitationId + "/resend", HttpMethod.POST,
                new HttpEntity<>(admin), JsonNode.class);
        assertThat(resent.getStatusCode()).isEqualTo(HttpStatus.OK);
        String freshToken = tokenFromDeliveredMail(email);

        assertThat(acceptJson(staleToken, ACCEPT_PASSWORD).getStatusCode())
                .as("the re-send must invalidate the previous token (hash overwritten)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(accept(freshToken, ACCEPT_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(login(DEFAULT_TENANT, username, ACCEPT_PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void acceptingTwiceIsANoOpAndCreatesNoSecondAccount() throws Exception {
        HttpHeaders admin = tenantAdmin();
        String username = unique("twice");
        String email = username + "@example.com";

        greenMail.purgeEmailFromAllMailboxes();
        assertThat(invite(admin, username, email).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = tokenFromDeliveredMail(email);

        assertThat(accept(token, ACCEPT_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // Second accept, different password: no-op 204 (the screen just points at sign-in) — it
        // must neither create a duplicate nor rebind the password.
        assertThat(accept(token, "Different456!").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(login(DEFAULT_TENANT, username, ACCEPT_PASSWORD).getStatusCode())
                .as("the first accept's password must survive the replay")
                .isEqualTo(HttpStatus.OK);
        assertThat(login(DEFAULT_TENANT, username, "Different456!").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(countUsers(admin, username))
                .as("a replayed accept must not create a second account")
                .isEqualTo(1);
    }

    @Test
    void aRevokedInvitationCannotBeAccepted() throws Exception {
        HttpHeaders admin = tenantAdmin();
        String username = unique("revoked");
        String email = username + "@example.com";

        greenMail.purgeEmailFromAllMailboxes();
        ResponseEntity<JsonNode> invited = invite(admin, username, email);
        long invitationId = invited.getBody().path("id").asLong();
        String token = tokenFromDeliveredMail(email);

        ResponseEntity<JsonNode> revoked = restTemplate.exchange(
                "/api/invitations/" + invitationId + "/revoke", HttpMethod.POST,
                new HttpEntity<>(admin), JsonNode.class);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoked.getBody().path("status").asText()).isEqualTo("REVOKED");

        assertThat(acceptJson(token, ACCEPT_PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aSecondPendingInvitationForTheSameEmailIsRefused() throws Exception {
        HttpHeaders admin = tenantAdmin();
        String username = unique("dup");
        String email = username + "@example.com";

        greenMail.purgeEmailFromAllMailboxes();
        assertThat(invite(admin, username, email).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(invite(admin, unique("dupother"), email).getStatusCode())
                .as("a second PENDING invitation for the same email must be a conflict")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(invite(admin, username, unique("dupmail") + "@example.com").getStatusCode())
                .as("a second PENDING invitation for the same username must be a conflict")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ---------------------------------------------------------------------------------------
    // Seats — re-checked at ACCEPT time (a pending invitation holds no seat)
    // ---------------------------------------------------------------------------------------

    @Test
    void theSeatLimitIsEnforcedWhenTheInvitationIsAcceptedNotWhenItIsSent() throws Exception {
        HttpHeaders admin = tenantAdmin();
        String username = unique("seat");
        String email = username + "@example.com";
        try {
            greenMail.purgeEmailFromAllMailboxes();
            assertThat(invite(admin, username, email).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String token = tokenFromDeliveredMail(email);

            setMaxUserCount(String.valueOf(liveUserCount(admin)));
            ResponseEntity<JsonNode> refused = acceptJson(token, ACCEPT_PASSWORD);
            assertThat(refused.getStatusCode())
                    .as("at the limit the accept must be refused, got %s: %s",
                            refused.getStatusCode(), refused.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getBody().path("code").asText()).isEqualTo("VALIDATION");

            // The refusal rolled back with the claim, so the SAME token succeeds once a seat opens.
            setMaxUserCount(String.valueOf(liveUserCount(admin) + 5));
            assertThat(accept(token, ACCEPT_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        } finally {
            setMaxUserCount(null);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Tenant isolation — API surface and the V15 RLS floor
    // ---------------------------------------------------------------------------------------

    @Test
    void anotherTenantCannotSeeResendOrRevokeAForeignInvitation() throws Exception {
        HttpHeaders admin = tenantAdmin();
        String username = unique("isolated");
        String email = username + "@example.com";

        greenMail.purgeEmailFromAllMailboxes();
        ResponseEntity<JsonNode> invited = invite(admin, username, email);
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long invitationId = invited.getBody().path("id").asLong();

        // A second tenant with a bootstrapped admin of known password (TenantBootstrapIT pattern).
        // Tenant names follow TenantBootstrapIT's shape (no underscores — the create validates).
        String otherTenant = "invb-" + Long.toString(System.nanoTime(), 36);
        HttpHeaders host = bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
        Map<String, Object> tenantBody = new LinkedHashMap<>();
        tenantBody.put("name", otherTenant);
        tenantBody.put("displayName", "Tenant " + otherTenant);
        tenantBody.put("adminEmail", "admin@" + otherTenant + ".test");
        tenantBody.put("adminPassword", "BootAdmin123!");
        ResponseEntity<JsonNode> createdTenant = restTemplate.exchange(
                "/api/tenants", HttpMethod.POST, new HttpEntity<>(tenantBody, host), JsonNode.class);
        assertThat(createdTenant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long otherTenantId = createdTenant.getBody().path("id").asLong();
        assertThat(otherTenantId)
                .as("the tenant create response must expose the id this test scopes the DB probe to")
                .isPositive();
        HttpHeaders otherAdmin = bearerHeaders(
                accessToken(otherTenant, "admin", "BootAdmin123!"), otherTenant);

        // API surface: invisible in the list, unmanageable by id — 404, never 200.
        ResponseEntity<JsonNode> foreignList = restTemplate.exchange(
                "/api/invitations?page=0&size=200", HttpMethod.GET,
                new HttpEntity<>(otherAdmin), JsonNode.class);
        assertThat(foreignList.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode row : pageContent(foreignList.getBody())) {
            assertThat(row.path("id").asLong()).isNotEqualTo(invitationId);
        }
        assertThat(restTemplate.exchange("/api/invitations/" + invitationId + "/resend",
                HttpMethod.POST, new HttpEntity<>(otherAdmin), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange("/api/invitations/" + invitationId + "/revoke",
                HttpMethod.POST, new HttpEntity<>(otherAdmin), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // RLS floor: the row does not exist for the other tenant's database context, and does for
        // host. This measures V15 itself, independent of any application-layer predicate.
        Integer seenByOtherTenant = inTenantDatabase(otherTenantId, () -> jdbcTemplate.queryForObject(
                "select count(*) from user_invitations where id = ?", Integer.class, invitationId));
        assertThat(seenByOtherTenant)
                .as("the V15 policy must hide a foreign tenant's invitation at the database floor")
                .isZero();
        Integer seenByHost = asHostDatabase(() -> jdbcTemplate.queryForObject(
                "select count(*) from user_invitations where id = ?", Integer.class, invitationId));
        assertThat(seenByHost).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private String unique(String prefix) {
        return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
    }

    private HttpHeaders tenantAdmin() {
        return bearerHeaders(accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
    }

    private ResponseEntity<JsonNode> invite(HttpHeaders headers, String username, String email) {
        Map<String, Object> body = Map.of(
                "username", username,
                "email", email,
                "roleNames", Set.of("Admin"));
        return restTemplate.exchange("/api/invitations", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    /** Success-path accept: asserts 204 and, on failure, surfaces the ProblemDetail body. */
    private ResponseEntity<JsonNode> accept(String token, String password) {
        ResponseEntity<JsonNode> response = acceptJson(token, password);
        assertThat(response.getStatusCode())
                .as("accept must answer 204, got %s: %s", response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        return response;
    }

    private ResponseEntity<JsonNode> acceptJson(String token, String password) {
        return restTemplate.exchange("/api/account/accept-invitation", HttpMethod.POST,
                new HttpEntity<>(Map.of("token", token, "password", password), jsonHeaders()), JsonNode.class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private void createUser(HttpHeaders adminHeaders, String username, String email, String password,
                            Set<String> roleNames) {
        Map<String, Object> body = Map.of(
                "username", username,
                "email", email,
                "password", password,
                "roleNames", roleNames);
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(body, adminHeaders), JsonNode.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("create user must succeed, got %s", created.getStatusCode())
                .isTrue();
    }

    private JsonNode findUser(HttpHeaders adminHeaders, String username) {
        ResponseEntity<JsonNode> list = restTemplate.exchange(
                "/api/users?page=0&size=50&search=" + username, HttpMethod.GET,
                new HttpEntity<>(adminHeaders), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode user : pageContent(list.getBody())) {
            if (username.equalsIgnoreCase(user.path("username").asText())) {
                return user;
            }
        }
        throw new AssertionError("user not found: " + username);
    }

    private long countUsers(HttpHeaders adminHeaders, String username) {
        ResponseEntity<JsonNode> list = restTemplate.exchange(
                "/api/users?page=0&size=50&search=" + username, HttpMethod.GET,
                new HttpEntity<>(adminHeaders), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        long count = 0;
        for (JsonNode user : pageContent(list.getBody())) {
            if (username.equalsIgnoreCase(user.path("username").asText())) {
                count++;
            }
        }
        return count;
    }

    private String invitationStatus(HttpHeaders adminHeaders, long invitationId) {
        ResponseEntity<JsonNode> list = restTemplate.exchange(
                "/api/invitations?page=0&size=200", HttpMethod.GET,
                new HttpEntity<>(adminHeaders), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode row : pageContent(list.getBody())) {
            if (row.path("id").asLong() == invitationId) {
                return row.path("status").asText();
            }
        }
        throw new AssertionError("invitation not found in list: " + invitationId);
    }

    private long liveUserCount(HttpHeaders adminHeaders) {
        ResponseEntity<JsonNode> list = restTemplate.exchange("/api/users?page=0&size=1",
                HttpMethod.GET, new HttpEntity<>(adminHeaders), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        return list.getBody().path("totalElements").asLong();
    }

    /** Host-side write of the tenant's {@code app.maxUserCount}; {@code null} clears the override. */
    private void setMaxUserCount(String value) {
        HttpHeaders host = bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", MAX_USER_COUNT);
        entry.put("value", value);
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/tenant-features/" + defaultTenantId(host), HttpMethod.PUT,
                new HttpEntity<>(List.of(entry), host), JsonNode.class);
        assertThat(response.getStatusCode())
                .as("setting %s to %s must succeed, got %s", MAX_USER_COUNT, value, response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private long defaultTenantId(HttpHeaders host) {
        ResponseEntity<JsonNode> list = restTemplate.exchange(
                "/api/tenants", HttpMethod.GET, new HttpEntity<>(host), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode node : pageContent(list.getBody())) {
            if (DEFAULT_TENANT.equals(node.path("name").asText())) {
                return node.path("id").asLong();
            }
        }
        throw new AssertionError("tenant not found: " + DEFAULT_TENANT);
    }

    /**
     * Waits for the invitation mail addressed to {@code email} and extracts the token — the longest
     * URL-safe base64 run in the body, exactly the trick {@code PasswordPolicyIT} uses (tolerates
     * HTML markup and quoted-printable soft line breaks).
     */
    private String tokenFromDeliveredMail(String email) throws Exception {
        assertThat(greenMail.waitForIncomingEmail(5000, 1))
                .as("an invitation e-mail must be delivered")
                .isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).isNotEmpty();
        MimeMessage message = messages[messages.length - 1];
        assertThat(message.getAllRecipients()[0].toString()).contains(email);
        String token = extractToken(GreenMailUtil.getBody(message));
        assertThat(token).as("the invitation token must be present in the e-mail").isNotBlank();
        return token;
    }

    private String extractToken(String rawBody) {
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
