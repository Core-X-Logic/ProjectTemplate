package com.mycompanyname.zero.tenancy;

/**
 * Published after a tenant has been created through {@link TenantService}. Downstream modules
 * (e.g. {@code saas}, which provisions the default subscription, and {@code identity}, which
 * bootstraps the tenant's Admin role and admin user — Issue #1) react via {@code @EventListener}
 * so the dependency direction stays one-way: consumers depend on {@code tenancy}, never the other
 * way round. {@code tenancy} therefore remains a leaf module — see ARCHITECTURE-RULES.md —
 * "Modül bağımlılıkları döngü kurmaz".
 *
 * <p>Listeners run synchronously inside the creating transaction, so tenant + subscription +
 * bootstrap admin are committed atomically.
 *
 * <p><b>{@code adminPassword} is a live credential.</b> It rides on the event because the identity
 * listener is the only component allowed to write identity rows, and the event is dispatched
 * in-memory only (no event publication registry is on the classpath, so it is never serialized or
 * persisted). {@link #toString()} redacts it so no accidental log statement — ours or a
 * framework's — can leak it; do not add the field to any log line or externalized representation.
 *
 * @param adminPasswordGenerated whether {@code adminPassword} was generated server-side (a caller
 *        did not choose it, so the password-policy check is skipped — the generated value is
 *        strong by construction, while a policy raised above its length must not make every
 *        password-less tenant creation fail opaquely)
 */
public record TenantCreatedEvent(Long tenantId, String tenantName, String adminEmail,
                                 String adminPassword, boolean adminPasswordGenerated) {

    @Override
    public String toString() {
        return "TenantCreatedEvent[tenantId=" + tenantId
                + ", tenantName=" + tenantName
                + ", adminEmail=" + adminEmail
                + ", adminPassword=***, adminPasswordGenerated=" + adminPasswordGenerated + "]";
    }
}
