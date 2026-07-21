package com.mycompanyname.zero.identity.web;

import com.mycompanyname.zero.identity.auth.AuthService;
import com.mycompanyname.zero.identity.web.dto.LoginRequest;
import com.mycompanyname.zero.identity.web.dto.LoginResultDto;
import com.mycompanyname.zero.identity.web.dto.MeDto;
import com.mycompanyname.zero.identity.web.dto.RefreshRequest;
import com.mycompanyname.zero.identity.web.dto.TokenPairDto;
import com.mycompanyname.zero.identity.web.dto.TwoFactorVerifyRequest;
import com.mycompanyname.zero.shared.web.EndpointPolicy;
import com.mycompanyname.zero.shared.web.EndpointPolicy.Exposure;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * {@code AUDIT_EXEMPT} is what {@code AuditLogInterceptor} now reads instead of hardcoding this
     * controller's URL: the request parameters are credentials and must never be persisted.
     */
    @PostMapping("/login")
    @EndpointPolicy({Exposure.ANONYMOUS, Exposure.AUDIT_EXEMPT, Exposure.SUBSCRIPTION_EXEMPT})
    public LoginResultDto login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * Second factor of login. Takes the challenge minted by {@link #login} plus a TOTP or recovery
     * code and returns real session tokens only on success. Same policy as {@code /login}: anonymous
     * (the caller has no session yet), audit-exempt (the body carries a credential), and
     * subscription-exempt. Throttled via {@code zero.ratelimit.paths} and backed by a {@code permitAll}
     * matcher in {@code SecurityConfig}. Every failure is a generic 401 with no oracle.
     */
    @PostMapping("/two-factor/verify")
    @EndpointPolicy({Exposure.ANONYMOUS, Exposure.AUDIT_EXEMPT, Exposure.SUBSCRIPTION_EXEMPT})
    public TokenPairDto verifyTwoFactor(@Valid @RequestBody TwoFactorVerifyRequest request) {
        return authService.verifyTwoFactor(request);
    }

    @PostMapping("/refresh")
    @EndpointPolicy({Exposure.ANONYMOUS, Exposure.AUDIT_EXEMPT, Exposure.SUBSCRIPTION_EXEMPT})
    public TokenPairDto refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    /**
     * Revokes the caller's own refresh token. No permission: the endpoint returns no data (204), and
     * {@code AuthService.logout} refuses a token that does not belong to the calling principal, so
     * authentication is the whole of the authorization decision.
     */
    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    @EndpointPolicy(Exposure.SUBSCRIPTION_EXEMPT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    /**
     * The caller's own identity, roles and granted permissions. Deliberately permission-free:
     * gating "which permissions do I have?" behind a permission is circular — the client would need
     * the answer in order to be allowed to ask the question. {@code AuthService.me()} takes its
     * identity solely from {@code CurrentUser.userId()} and accepts no caller-supplied identifier,
     * so the response is entirely principal-derived.
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @EndpointPolicy(Exposure.SUBSCRIPTION_EXEMPT)
    public MeDto me() {
        return authService.me();
    }
}
