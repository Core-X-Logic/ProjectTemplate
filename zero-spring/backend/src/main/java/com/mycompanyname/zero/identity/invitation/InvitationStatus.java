package com.mycompanyname.zero.identity.invitation;

/**
 * Lifecycle of a {@link UserInvitation}. There is deliberately no {@code EXPIRED} member: expiry is
 * derived from {@code expiresAt} at read time (a PENDING invitation whose {@code expiresAt} is in
 * the past cannot be accepted, but CAN still be re-sent). Persisting expiry would require a
 * scheduled writer, and a {@code @Component} job writes no GUC — against this policed table it
 * would see 0 rows and "succeed" silently (RISK-REGISTER R-46).
 */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    REVOKED
}
