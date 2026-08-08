package com.mycompanyname.zero.identity.invitation.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Invitation request ({@code users.create}). The admin fixes the username and the roles; the
 * invitee only ever chooses a password — which is why the accept screen displays the username
 * read-only instead of collecting one.
 */
public record InviteUserRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Email @Size(max = 256) String email,
        Set<String> roleNames) {
}
