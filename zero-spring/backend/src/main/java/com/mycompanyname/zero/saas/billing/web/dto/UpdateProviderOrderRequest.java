package com.mycompanyname.zero.saas.billing.web.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * The operator's checkout failover order (ADR-0020): provider ids, first-tried first. Every named
 * provider gets its stored {@code display_order} set to its position; providers not named keep
 * their current order (environment-only providers without a row sort last).
 *
 * @param order provider ids in the desired failover order; duplicates are a 400
 */
public record UpdateProviderOrderRequest(@NotEmpty List<String> order) {
}
