package com.mycompanyname.zero.tenancy;

/**
 * Published after a tenant has been created through {@link TenantService}. Downstream modules
 * (e.g. {@code saas}, which provisions the default subscription) react via {@code @EventListener}
 * so the dependency direction stays one-way: consumers depend on {@code tenancy}, never the other
 * way round. {@code tenancy} therefore remains a leaf module — see ARCHITECTURE-RULES.md —
 * "Modül bağımlılıkları döngü kurmaz".
 *
 * <p>Listeners run synchronously inside the creating transaction, so tenant + subscription are
 * committed atomically.
 */
public record TenantCreatedEvent(Long tenantId, String tenantName) {
}
