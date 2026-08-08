package com.mycompanyname.zero.identity.invitation.web.dto;

import java.time.Instant;
import java.util.Set;

/**
 * Admin-side view of an invitation. Deliberately WITHOUT the token or its hash: the token exists
 * only in the invitation e-mail, and re-exposing it to the admin surface would turn every
 * {@code users.create} holder into a bearer of every pending invitee's account.
 */
public record InvitationDto(
        Long id,
        String username,
        String email,
        Set<String> roleNames,
        String status,
        Instant expiresAt,
        Instant createdAt) {
}
