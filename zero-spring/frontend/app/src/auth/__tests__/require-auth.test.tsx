import type { ReactNode } from 'react';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RequireAuth } from '@/auth/require-auth';
import { renderWithProviders, screen } from '@/test/utils';

/**
 * Route-guard behaviour tests (FRONTEND-ARCHITECTURE.md §5).
 *
 * `useAuth` is mocked so each case feeds an explicit session/permission state;
 * the guard is exercised inside a real `<Routes>` tree (via `renderWithProviders`
 * + memory router) so the `<Navigate to="/login">` redirect, the 403 fallback
 * and the happy-path children render are all asserted against actual routing.
 */

const { useAuthMock } = vi.hoisted(() => ({ useAuthMock: vi.fn() }));

vi.mock('@/providers/auth-provider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: useAuthMock,
}));

interface AuthState {
  user: unknown;
  permissions: string[];
  loading?: boolean;
}

function setAuth({ user, permissions, loading = false }: AuthState): void {
  useAuthMock.mockReturnValue({
    user,
    permissions,
    roles: [],
    loading,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  });
}

function renderGuarded(permission?: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<div>login screen</div>} />
      <Route
        path="/secret"
        element={
          <RequireAuth permission={permission}>
            <div>secret content</div>
          </RequireAuth>
        }
      />
    </Routes>,
    { route: '/secret' },
  );
}

beforeEach(() => {
  useAuthMock.mockReset();
  localStorage.clear();
});

describe('RequireAuth', () => {
  it('redirects an unauthenticated user to /login', async () => {
    setAuth({ user: null, permissions: [] });

    renderGuarded('users.read');

    expect(await screen.findByText('login screen')).toBeInTheDocument();
    expect(screen.queryByText('secret content')).not.toBeInTheDocument();
  });

  it('renders the Forbidden page when the required permission is missing', async () => {
    setAuth({ user: { id: '1', username: 'tester' }, permissions: ['users.read'] });

    renderGuarded('users.delete');

    // Forbidden copy resolves from the global message catalogue.
    expect(await screen.findByText('Access denied')).toBeInTheDocument();
    expect(screen.queryByText('secret content')).not.toBeInTheDocument();
  });

  it('renders the guarded children when the user holds the permission', async () => {
    setAuth({ user: { id: '1', username: 'tester' }, permissions: ['users.read'] });

    renderGuarded('users.read');

    expect(await screen.findByText('secret content')).toBeInTheDocument();
  });

  it('renders children for any authenticated user when no permission is required', async () => {
    setAuth({ user: { id: '1', username: 'tester' }, permissions: [] });

    renderGuarded(undefined);

    expect(await screen.findByText('secret content')).toBeInTheDocument();
  });
});
