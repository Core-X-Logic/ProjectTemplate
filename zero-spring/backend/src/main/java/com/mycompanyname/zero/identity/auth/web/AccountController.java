package com.mycompanyname.zero.identity.auth.web;

import com.mycompanyname.zero.identity.auth.AccountService;
import com.mycompanyname.zero.identity.auth.web.dto.ConfirmEmailRequest;
import com.mycompanyname.zero.identity.auth.web.dto.ForgotPasswordRequest;
import com.mycompanyname.zero.identity.auth.web.dto.ResetPasswordRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated account self-service endpoints (permitAll in {@code SecurityConfig}). Separate from
 * {@code AuthController} which handles login/refresh/logout.
 */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        accountService.forgotPassword(request.usernameOrEmail(), request.tenant());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        accountService.resetPassword(request.resetCode(), request.newPassword());
    }

    @PostMapping("/confirm-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmEmail(@Valid @RequestBody ConfirmEmailRequest request) {
        accountService.confirmEmail(request.code());
    }
}
