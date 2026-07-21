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
 * One single-use 2FA recovery code for a user, stored only as a BCrypt hash (verified by iterating
 * {@code matches()}, exactly like {@code PasswordHistory}). {@link #consumedAt} makes a code single-use:
 * once redeemed it is marked and can never mint a session again (replay blocked), while its siblings
 * stay valid.
 *
 * <p>No {@code tenant_id} column, for the same reason as {@link TwoFactorChallenge}: the row is scoped
 * by {@link #userId}, whose tenant is authoritative. {@code code_hash} ends in {@code hash} so audit
 * masking applies.
 */
@Entity
@Table(name = "two_factor_recovery_codes")
@Getter
@Setter
public class TwoFactorRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
