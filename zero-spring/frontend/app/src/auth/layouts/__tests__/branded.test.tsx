import type { ReactNode } from 'react';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { BrandedLayout } from '@/auth/layouts/branded';
import { LoginPage } from '@/auth/pages/login';
import { renderWithProviders, screen } from '@/test/utils';

/**
 * Smoke test for the branded auth layout (stack-review finding).
 *
 * Rationale: no other test renders `BrandedLayout` — a broken import or render
 * error inside `branded.tsx` would take down `/login` AND `/login/two-factor`
 * in production while every gate stayed green, because the page tests mount
 * `LoginPage`/`TwoFactorPage` directly, bypassing the route-level layout.
 *
 * This spec mounts the same route shape as `routes.tsx` (layout route wrapping
 * the page) and asserts BOTH columns: the form card arriving through the
 * `<Outlet />` and the right-hand brand panel copy.
 */

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn(), message: vi.fn() },
}));

// Same boundary mock as login.test.tsx: passthrough provider + anonymous user,
// so the real page renders without touching the network.
vi.mock('@/providers/auth-provider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: () => ({
    user: null,
    permissions: [],
    roles: [],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  }),
}));

describe('BrandedLayout', () => {
  it('renders the login form card and the brand panel together on /login', () => {
    renderWithProviders(
      <Routes>
        <Route element={<BrandedLayout />}>
          <Route path="/login" element={<LoginPage />} />
        </Route>
      </Routes>,
      { route: '/login' },
    );

    // Left column: the real login form came through the layout's <Outlet />.
    expect(screen.getByLabelText('Username or email')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Sign in' }),
    ).toBeInTheDocument();

    // Right column: brand panel resolved its i18n copy (not raw keys).
    expect(
      screen.getByRole('heading', { name: 'Secure Dashboard Access' }),
    ).toBeInTheDocument();
  });
});
