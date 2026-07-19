package com.mycompanyname.zero.identity.auth;

import com.mycompanyname.zero.config.JwtProperties;
import com.mycompanyname.zero.identity.auth.ImpersonationTokenStore.Ticket;
import com.mycompanyname.zero.identity.auth.web.dto.ImpersonateAuthRequest;
import com.mycompanyname.zero.identity.auth.web.dto.ImpersonateRequest;
import com.mycompanyname.zero.identity.auth.web.dto.ImpersonationTokenDto;
import com.mycompanyname.zero.identity.domain.RefreshToken;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.RefreshTokenRepository;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.identity.web.dto.TokenPairDto;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * User impersonation: an operator acts as another user without knowing their credentials.
 *
 * <p>Flow: an authorized actor requests a single-use hand-off token
 * ({@link #start(ImpersonateRequest)}), then exchanges it for a target-user token pair
 * ({@link #authenticate(ImpersonateAuthRequest)}). The impersonated access token carries the
 * {@code act}/{@code actTenant} claims identifying the real actor, and
 * {@link #backToImpersonator()} uses them to return to the original session. Nested (cascade)
 * impersonation is rejected. HTTP audit of the impersonation login is handled by the audit module's
 * request interceptor (identity does not depend on the audit module).
 */
@Service
@RequiredArgsConstructor
public class ImpersonationService {

    private static final Duration TICKET_TTL = Duration.ofSeconds(30);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final ImpersonationTokenStore tokenStore;

    /** Issues a single-use impersonation hand-off token for the target user. */
    @Transactional(readOnly = true)
    public ImpersonationTokenDto start(ImpersonateRequest request) {
        Jwt actor = currentJwt();
        if (actor == null || actor.getSubject() == null) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        if (actor.getClaim("act") != null) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cascade impersonation is not allowed");
        }
        Long actorUserId = Long.valueOf(actor.getSubject());
        Long actorTenantId = claimAsLong(actor, "tenant");

        User target = userRepository.findById(request.targetUserId())
                .orElseThrow(() -> new DomainException(
                        ErrorCode.NOT_FOUND, "Target user not found: " + request.targetUserId()));
        if (Objects.equals(actorUserId, target.getId())) {
            throw new DomainException(ErrorCode.VALIDATION, "Cannot impersonate yourself");
        }
        // Host actor (no tenant) may impersonate into any tenant; a tenant actor is confined to its own.
        if (actorTenantId != null && !Objects.equals(actorTenantId, target.getTenantId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot impersonate a user in another tenant");
        }
        if (request.targetTenantId() != null
                && !Objects.equals(request.targetTenantId(), target.getTenantId())) {
            throw new DomainException(ErrorCode.VALIDATION, "Target tenant does not match target user");
        }
        if (!target.isActive()) {
            throw new DomainException(ErrorCode.VALIDATION, "Target user is not active");
        }

        String token = tokenStore.issue(actorUserId, actorTenantId, target.getId(), TICKET_TTL);
        return new ImpersonationTokenDto(token);
    }

    /** Exchanges a single-use token for the target user's access/refresh pair with {@code act} claims. */
    @Transactional
    public TokenPairDto authenticate(ImpersonateAuthRequest request) {
        Ticket ticket = tokenStore.consume(request.impersonationToken())
                .orElseThrow(() -> new DomainException(
                        ErrorCode.UNAUTHORIZED, "Invalid or expired impersonation token"));
        User target = userRepository.findById(ticket.targetUserId())
                .filter(User::isActive)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.UNAUTHORIZED, "Target user is not available"));
        String accessToken = jwtService.issueAccessToken(
                target, authoritiesOf(target), ticket.actorUserId(), ticket.actorTenantId());
        String refreshToken = createRefreshToken(target.getId());
        return new TokenPairDto(accessToken, refreshToken, jwtProperties.getAccessTokenTtl().toSeconds());
    }

    /** Returns from an impersonated session to the original actor, using the token's {@code act} claim. */
    @Transactional
    public TokenPairDto backToImpersonator() {
        Jwt jwt = currentJwt();
        if (jwt == null) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        Long actorUserId = claimAsLong(jwt, "act");
        if (actorUserId == null) {
            throw new DomainException(ErrorCode.VALIDATION, "Not in an impersonation session");
        }
        User actor = userRepository.findById(actorUserId)
                .filter(User::isActive)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.UNAUTHORIZED, "Impersonator is not available"));
        String accessToken = jwtService.issueAccessToken(actor, authoritiesOf(actor));
        String refreshToken = createRefreshToken(actor.getId());
        return new TokenPairDto(accessToken, refreshToken, jwtProperties.getAccessTokenTtl().toSeconds());
    }

    private String createRefreshToken(Long userId) {
        byte[] randomBytes = new byte[REFRESH_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(randomBytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(sha256Hex(raw));
        token.setExpiresAt(Instant.now().plus(jwtProperties.getRefreshTokenTtl()));
        token.setRevoked(false);
        refreshTokenRepository.save(token);
        return raw;
    }

    private Set<String> authoritiesOf(User user) {
        Set<String> authorities = new HashSet<>();
        for (Role role : user.getRoles()) {
            authorities.addAll(role.getPermissions());
            authorities.add("ROLE_" + role.getName());
        }
        return authorities;
    }

    private static Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        return authentication.getPrincipal() instanceof Jwt jwt ? jwt : null;
    }

    private static Long claimAsLong(Jwt jwt, String claimName) {
        Object value = jwt.getClaim(claimName);
        return value == null ? null : ((Number) value).longValue();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
