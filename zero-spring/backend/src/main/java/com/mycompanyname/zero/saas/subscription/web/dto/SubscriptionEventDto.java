package com.mycompanyname.zero.saas.subscription.web.dto;

import java.time.Instant;

/** One entry of the subscription's lifecycle trail. {@code fromStatus} is null for provisioning. */
public record SubscriptionEventDto(
        Long id,
        String fromStatus,
        String toStatus,
        String reason,
        Instant occurredAt,
        String actor) {
}
