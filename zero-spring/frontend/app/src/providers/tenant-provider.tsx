import {
  createContext,
  ReactNode,
  useCallback,
  useContext,
  useMemo,
  useState,
} from 'react';
import { tenantStore } from '@/api/client';

interface TenantContextValue {
  /** Active tenant identifier, or `null` for the default/host tenant. */
  tenant: string | null;
  /** Set (or clear, with `null`) the active tenant; persisted to localStorage. */
  setTenant: (tenant: string | null) => void;
}

const TenantContext = createContext<TenantContextValue | undefined>(undefined);

/**
 * Holds the active tenant used as the `X-Tenant` header source
 * (FRONTEND-ARCHITECTURE.md §4/§5). Backed by the shared `tenantStore` so that
 * `apiFetch` and this React state never diverge.
 */
export function TenantProvider({ children }: { children: ReactNode }) {
  const [tenant, setTenantState] = useState<string | null>(() =>
    tenantStore.get(),
  );

  const setTenant = useCallback((next: string | null) => {
    tenantStore.set(next);
    setTenantState(next);
  }, []);

  const value = useMemo<TenantContextValue>(
    () => ({ tenant, setTenant }),
    [tenant, setTenant],
  );

  return (
    <TenantContext.Provider value={value}>{children}</TenantContext.Provider>
  );
}

export function useTenant(): TenantContextValue {
  const context = useContext(TenantContext);
  if (!context) {
    throw new Error('useTenant must be used within a TenantProvider');
  }
  return context;
}
