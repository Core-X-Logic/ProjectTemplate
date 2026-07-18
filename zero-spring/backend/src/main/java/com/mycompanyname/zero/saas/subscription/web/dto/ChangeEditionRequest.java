package com.mycompanyname.zero.saas.subscription.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Upgrade/downgrade payload (state-table row S13).
 *
 * @param editionId     the edition to move to; must exist and differ from the current one
 * @param billingPeriod MONTHLY or ANNUAL; optional — when omitted the subscription keeps its current
 *                      period. Required when the subscription has no period yet (it was free) and
 *                      the target edition is priced. Ignored for a free target.
 */
public record ChangeEditionRequest(
        @NotNull Long editionId,
        String billingPeriod) {
}
