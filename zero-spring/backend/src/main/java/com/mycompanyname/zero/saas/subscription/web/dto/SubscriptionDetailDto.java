package com.mycompanyname.zero.saas.subscription.web.dto;

import java.util.List;

/**
 * Subscription detail for the host admin: the current state plus its lifecycle trail, so the
 * {@code subscription_events} record is observable without a dedicated endpoint.
 */
public record SubscriptionDetailDto(
        SubscriptionDto subscription,
        List<SubscriptionEventDto> events) {
}
