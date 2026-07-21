package com.mycompanyname.zero.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The stateful gate between a correct password and a minted session when 2FA is enabled. Mirrors
 * {@link RefreshToken}: an opaque secret handed to the caller once, stored only as its SHA-256 hash,
 * looked up by that unique hash, single-use ({@link #consumedAt}) and time-bounded ({@link #expiresAt}).
 *
 * <p>No {@code tenant_id} column, deliberately — see {@code V10__two_factor.sql} and the RISK-REGISTER.
 * The row is always resolved through {@link #userId}, and the tenant is taken from the user when the
 * token is minted; adding a column would force a Hibernate {@code @Filter} (Rule 2) that the anonymous,
 * header-optional verify path cannot satisfy without failing closed on a legitimate tenant login.
 *
 * <p>Not {@code @TrackChanges}: like refresh tokens, its churn is not audit history. {@code token_hash}
 * ends in {@code hash} so any incidental audit of it would mask anyway.
 */
@Entity
@Table(name = "two_factor_challenges")
@Getter
@Setter
public class TwoFactorChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempts_remaining", nullable = false)
    private int attemptsRemaining;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
