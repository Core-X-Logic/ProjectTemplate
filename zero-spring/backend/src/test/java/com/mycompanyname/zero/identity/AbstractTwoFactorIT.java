package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.config.FieldEncryptionService;
import com.mycompanyname.zero.config.TwoFactorProperties;
import com.mycompanyname.zero.identity.domain.TwoFactorRecoveryCode;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.TwoFactorChallengeRepository;
import com.mycompanyname.zero.identity.repo.TwoFactorRecoveryCodeRepository;
import com.mycompanyname.zero.identity.repo.UserRepository;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared fixtures for the 2FA integration tests. Test users are created directly through the
 * repository (host users, {@code tenant_id = null}, unique usernames) and their 2FA state is written
 * the same way — a known base32 secret encrypted with the real {@link FieldEncryptionService}, and
 * known recovery codes stored as BCrypt hashes. Two reasons this bypasses the API:
 * <ul>
 *   <li>the flow under test must start from a user that is ALREADY enrolled with a secret whose
 *       plaintext the test knows, so it can compute valid TOTP codes; and</li>
 *   <li>the shared Spring context seeds a single {@code admin} used by every other IT — flipping that
 *       account to 2FA would turn its {@code accessToken()} helper into a challenge and break the whole
 *       suite. Dedicated, uniquely-named users keep 2FA off the shared admin.</li>
 * </ul>
 *
 * <p>Host users also sidestep the subscription gate (which only applies to a tenant), so the
 * authenticated management endpoints are reachable without provisioning a subscription.
 */
abstract class AbstractTwoFactorIT extends AbstractIntegrationIT {

    protected static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected FieldEncryptionService fieldEncryptionService;

    @Autowired
    protected TwoFactorRecoveryCodeRepository recoveryCodeRepository;

    @Autowired
    protected TwoFactorChallengeRepository challengeRepository;

    @Autowired
    protected TwoFactorProperties twoFactorProperties;

    /** A test user plus everything needed to authenticate as it, including its 2FA secrets in clear. */
    protected record TwoFactorUser(long userId, String username, String password, String secret,
                                   List<String> recoveryCodes) {
    }

    protected String uniqueUsername(String prefix) {
        return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
    }

    /** A plain host user with NO second factor (for the regression path). */
    protected TwoFactorUser createHostUserWithoutTwoFactor(String password) {
        String username = uniqueUsername("2fa_off");
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@host.local");
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setActive(true);
        long id = userRepository.saveAndFlush(user).getId();
        return new TwoFactorUser(id, username, password, null, List.of());
    }

    /** A host user with 2FA ON: a fresh secret and {@code recoveryCodeCount} known recovery codes. */
    protected TwoFactorUser createHostUserWithTwoFactor(String password, int recoveryCodeCount) {
        return createUserWithTwoFactor(null, password, recoveryCodeCount);
    }

    /**
     * A user in {@code tenantId} ({@code null} = host) with 2FA ON: a fresh secret and
     * {@code recoveryCodeCount} known recovery codes. Written through the repository so it bypasses the
     * subscription gate (the API management endpoints are not subscription-exempt), which lets the
     * tenant-isolation test enrol a tenant user without provisioning a subscription for it.
     */
    protected TwoFactorUser createUserWithTwoFactor(Long tenantId, String password, int recoveryCodeCount) {
        String secret = new DefaultSecretGenerator().generate();
        String username = uniqueUsername("2fa_on");
        User user = new User();
        user.setTenantId(tenantId);
        user.setUsername(username);
        user.setEmail(username + "@host.local");
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setActive(true);
        user.setTwoFactorEnabled(true);
        user.setTwoFactorSecret(fieldEncryptionService.encrypt(secret));
        long id = userRepository.saveAndFlush(user).getId();

        List<String> codes = new ArrayList<>();
        for (int i = 0; i < recoveryCodeCount; i++) {
            String code = "RECOVERY-" + username + "-" + i;
            codes.add(code);
            TwoFactorRecoveryCode row = new TwoFactorRecoveryCode();
            row.setUserId(id);
            row.setCodeHash(passwordEncoder.encode(code));
            recoveryCodeRepository.saveAndFlush(row);
        }
        return new TwoFactorUser(id, username, password, secret, codes);
    }

    // --- TOTP helpers ----------------------------------------------------------------------

    private int step() {
        return twoFactorProperties.getTotpTimeStepSeconds();
    }

    private long currentBucket() {
        return Math.floorDiv(new SystemTimeProvider().getTime(), step());
    }

    private String codeForBucket(String secret, long bucket) {
        try {
            return new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6).generate(secret, bucket);
        } catch (CodeGenerationException e) {
            throw new IllegalStateException("could not generate a TOTP code", e);
        }
    }

    /** The TOTP the authenticator would show right now — accepted within the ±window tolerance. */
    protected String currentTotp(String secret) {
        return codeForBucket(secret, currentBucket());
    }

    /**
     * A syntactically-valid but DEFINITELY-wrong TOTP: a code from a bucket outside the verification
     * window, checked not to collide with any of the three codes the verifier would currently accept.
     */
    protected String wrongTotp(String secret) {
        long now = currentBucket();
        int window = twoFactorProperties.getTotpWindow();
        Set<String> accepted = new java.util.HashSet<>();
        for (long b = now - window; b <= now + window; b++) {
            accepted.add(codeForBucket(secret, b));
        }
        for (int back = window + 1; back < 5000; back++) {
            String candidate = codeForBucket(secret, now - back);
            if (!accepted.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not derive a wrong TOTP outside the window");
    }

    // --- HTTP helpers ----------------------------------------------------------------------

    /** POST /api/auth/two-factor/verify with the given tenant header (null for a host user). */
    protected ResponseEntity<JsonNode> verify(String tenant, String challengeToken, String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenant != null) {
            headers.set(TENANT_HEADER, tenant);
        }
        Map<String, String> body = Map.of("challengeToken", challengeToken, "code", code);
        return restTemplate.exchange("/api/auth/two-factor/verify", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    /** The stored form of a challenge/refresh token: SHA-256 hex, mirroring AuthService.sha256Hex. */
    protected String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * GET /api/auth/me presenting {@code bearer}. {@code tenant} must match the token's tenant claim
     * (null for a host user) — an authenticated request whose X-Tenant does not match the claim is a
     * 403 "Tenant mismatch" (AuthenticatedTenantFilter), so a tenant token needs its tenant header.
     */
    protected ResponseEntity<JsonNode> me(String tenant, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        if (tenant != null) {
            headers.set(TENANT_HEADER, tenant);
        }
        return restTemplate.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
    }
}
