package com.mycompanyname.zero.shared.tenant;

public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(Long tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static Long getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static boolean isHost() {
        return CURRENT_TENANT.get() == null;
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
