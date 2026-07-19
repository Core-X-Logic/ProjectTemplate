package com.mycompanyname.zero.saas.edition;

import com.mycompanyname.zero.shared.domain.AbstractAuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A sellable package. Prices are relational ({@code numeric(19,4)} + currency), never JSON, and stay
 * editable: existing subscribers are protected because the subscription snapshots the price it was
 * sold at (ADR-0012).
 *
 * <p>{@code expiringEditionId} is the free edition a subscription downgrades to once it expires
 * (state S10); the target must itself be free.
 */
@Entity
@Table(name = "editions")
@Getter
@Setter
public class Edition extends AbstractAuditedEntity {

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "monthly_price", precision = 19, scale = 4)
    private BigDecimal monthlyPrice;

    @Column(name = "annual_price", precision = 19, scale = 4)
    private BigDecimal annualPrice;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "trial_day_count", nullable = false)
    private int trialDayCount;

    @Column(name = "grace_day_count", nullable = false)
    private int graceDayCount;

    @Column(name = "expiring_edition_id")
    private Long expiringEditionId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * An edition is free when it carries no price at all. Computed rather than stored so it can never
     * drift from the price columns.
     */
    public boolean isFree() {
        return monthlyPrice == null && annualPrice == null;
    }

    /** The price for {@code period}, or {@code null} for a free edition / unpriced period. */
    public BigDecimal priceFor(String period) {
        if (period == null) {
            return null;
        }
        return "ANNUAL".equals(period) ? annualPrice : monthlyPrice;
    }
}
