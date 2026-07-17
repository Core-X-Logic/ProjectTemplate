import { QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import { ThemeProvider } from 'next-themes';
import { HelmetProvider } from 'react-helmet-async';
import { BrowserRouter } from 'react-router-dom';
import { LoadingBarContainer } from 'react-top-loading-bar';
import { queryClient } from '@/api/query-client';
import { AuthProvider } from '@/providers/auth-provider';
import { I18nProvider } from '@/providers/i18n-provider';
import { TenantProvider } from '@/providers/tenant-provider';
import { Toaster } from '@/components/ui/sonner';
import { AppRouting } from '@/routing/app-routing';

const { BASE_URL } = import.meta.env;

/**
 * Provider chain (FRONTEND-ARCHITECTURE.md §3):
 *
 *   ThemeProvider → HelmetProvider → QueryClientProvider → I18nProvider
 *     → AuthProvider → TenantProvider → BrowserRouter → Toaster + AppRoutes
 */
export function App() {
  return (
    <ThemeProvider
      attribute="class"
      defaultTheme="system"
      storageKey="vite-theme"
      enableSystem
      enableColorScheme
      disableTransitionOnChange
    >
      <HelmetProvider>
        <QueryClientProvider client={queryClient}>
          {import.meta.env.DEV && (
            <ReactQueryDevtools initialIsOpen={false} buttonPosition="bottom-left" />
          )}
          <I18nProvider>
            <AuthProvider>
              <TenantProvider>
                <BrowserRouter basename={BASE_URL}>
                  <LoadingBarContainer>
                    <Toaster position="top-center" />
                    <AppRouting />
                  </LoadingBarContainer>
                </BrowserRouter>
              </TenantProvider>
            </AuthProvider>
          </I18nProvider>
        </QueryClientProvider>
      </HelmetProvider>
    </ThemeProvider>
  );
}
