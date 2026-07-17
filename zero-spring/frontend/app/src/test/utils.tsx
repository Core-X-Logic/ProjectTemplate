import { ReactElement, ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, RenderOptions, RenderResult } from '@testing-library/react';
import { HelmetProvider } from 'react-helmet-async';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '@/providers/auth-provider';
import { I18nProvider } from '@/providers/i18n-provider';
import { TenantProvider } from '@/providers/tenant-provider';

/**
 * Shared test harness (FRONTEND-ARCHITECTURE.md §9).
 *
 * Wraps a component under test in the same provider chain as `App.tsx`
 * (Helmet → Query → Intl → Auth → Tenant → Router) so behaviour tests exercise
 * i18n, RBAC and routing exactly as production does. Individual tests may still
 * `vi.mock` any provider (e.g. a fake `useAuth`) to isolate a single unit.
 */

interface ProviderOptions extends Omit<RenderOptions, 'wrapper'> {
  /** Initial history entry for the in-memory router. Defaults to `/`. */
  route?: string;
  /** Inject a preconfigured client; a retry-disabled one is created otherwise. */
  queryClient?: QueryClient;
}

function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
    },
  });
}

export function renderWithProviders(
  ui: ReactElement,
  { route = '/', queryClient, ...options }: ProviderOptions = {},
): RenderResult {
  const client = queryClient ?? createTestQueryClient();

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <HelmetProvider>
        <QueryClientProvider client={client}>
          <I18nProvider>
            <AuthProvider>
              <TenantProvider>
                <MemoryRouter initialEntries={[route]}>
                  {children}
                </MemoryRouter>
              </TenantProvider>
            </AuthProvider>
          </I18nProvider>
        </QueryClientProvider>
      </HelmetProvider>
    );
  }

  return render(ui, { wrapper: Wrapper, ...options });
}

// Re-export the testing-library surface so specs import from a single module.
export * from '@testing-library/react';
