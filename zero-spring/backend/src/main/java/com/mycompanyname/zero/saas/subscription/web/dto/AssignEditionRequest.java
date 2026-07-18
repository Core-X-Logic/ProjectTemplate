package com.mycompanyname.zero.saas.subscription.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Package assignment payload.
 *
 * @param editionId     the edition to sell; must exist
 * @param billingPeriod MONTHLY or ANNUAL; required for a paid edition, ignored for a free one
 * @param trial         start the subscription as a trial; rejected for a free edition and for an
 *                      edition whose {@code trialDayCount} is 0
 */
public record AssignEditionRequest(
        @NotNull Long editionId,
        String billingPeriod,
        boolean trial) {
}
