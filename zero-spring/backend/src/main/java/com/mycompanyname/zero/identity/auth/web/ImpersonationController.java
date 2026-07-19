package com.mycompanyname.zero.identity.auth.web;

import com.mycompanyname.zero.identity.auth.ImpersonationService;
import com.mycompanyname.zero.identity.auth.web.dto.ImpersonateAuthRequest;
import com.mycompanyname.zero.identity.auth.web.dto.ImpersonateRequest;
import com.mycompanyname.zero.identity.auth.web.dto.ImpersonationTokenDto;
import com.mycompanyname.zero.identity.domain.AppPermissions;
import com.mycompanyname.zero.identity.web.dto.TokenPairDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Impersonation endpoints, kept separate from {@code AuthController}.
 * All three require an authenticated caller (enforced by the security filter chain); {@code /impersonate}
 * additionally requires the {@code users.impersonate} permission.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class ImpersonationController {

    private final ImpersonationService impersonationService;

    @PostMapping("/impersonate")
    @PreAuthorize("hasAuthority('" + AppPermissions.USERS_IMPERSONATE + "')")
    public ImpersonationTokenDto impersonate(@Valid @RequestBody ImpersonateRequest request) {
        return impersonationService.start(request);
    }

    @PostMapping("/impersonate/authenticate")
    public TokenPairDto authenticate(@Valid @RequestBody ImpersonateAuthRequest request) {
        return impersonationService.authenticate(request);
    }

    @PostMapping("/back-to-impersonator")
    public TokenPairDto backToImpersonator() {
        return impersonationService.backToImpersonator();
    }
}
