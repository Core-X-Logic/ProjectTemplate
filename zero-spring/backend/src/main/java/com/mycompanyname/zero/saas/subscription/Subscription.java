package com.mycompanyname.zero.saas.subscription;

import com.mycompanyname.zero.shared.domain.AbstractAuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Aggregate root of a tenant's commercial relationship: exactly one row per tenant (unique
 * {@code tenant_id}).
 *
 * <p>{@code priceAmount}/{@code priceCurrency}/{@code billingPeriod} are a <em>snapshot</em> taken
 * when the package was assigned, so later edits to the edition's price never change what an existing
 * subscriber pays (ADR-0012).
 *
 * <p>{@code legacy*} columns exist for F6/ETL traceability and stay empty in Slice A;
 * {@code externalRef}/{@code provider} are filled by the billing provider in Slice C.
 */
@Entity
@Table(name = "subscriptions")
@Getter
@Setter
public class Subscription extends AbstractAuditedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "edition_id", nullable = false)
    private Long editionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private SubscriptionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", length = 16)
    private BillingPeriod billingPeriod;

    @Column(name = "price_amount", precision = 19, scale = 4)
    private BigDecimal priceAmount;

    @Column(name = "price_currency", length = 3)
    private String priceCurrency;

    @Column(name = "trial_end_at")
    private Instant trialEndAt;

    @Column(name = "current_period_end_at")
    private Instant currentPeriodEndAt;

    @Column(name = "grace_end_at")
    private Instant graceEndAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "external_ref", length = 128)
    private String externalRef;

    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "legacy_edition_id")
    private Integer legacyEditionId;

    @Column(name = "legacy_tenant_payment_ref", length = 128)
    private String legacyTenantPaymentRef;
}
