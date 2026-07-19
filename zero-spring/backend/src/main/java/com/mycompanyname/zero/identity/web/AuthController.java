package com.mycompanyname.zero.identity.web;

import com.mycompanyname.zero.identity.auth.AuthService;
import com.mycompanyname.zero.identity.web.dto.LoginRequest;
import com.mycompanyname.zero.identity.web.dto.MeDto;
import com.mycompanyname.zero.identity.web.dto.RefreshRequest;
import com.mycompanyname.zero.identity.web.dto.TokenPairDto;
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

    @PostMapping("/login")
    public TokenPairDto login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
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
    public MeDto me() {
        return authService.me();
    }
}
