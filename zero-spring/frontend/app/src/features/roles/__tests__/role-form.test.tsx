import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RoleFormPage } from '@/features/roles/pages/role-form';
import { renderWithProviders, screen } from '@/test/utils';

/**
 * Role form RBAC test (FRONTEND-ARCHITECTURE.md §5).
 *
 * The submit button is `<Can>`-guarded (`roles.create` in create mode,
 * `roles.update` in edit mode), so a viewer without the write permission never
 * sees a save affordance — matching the project convention of guarding actions,
 * not just routes. The feature hooks are mocked so the form renders without the
 * network; `useAuth` feeds the per-test permission set.
 */

const { useAuthMock } = vi.hoisted(() => ({ useAuthMock: vi.fn() }));

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn(), message: vi.fn() },
}));

vi.mock('@/providers/auth-provider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: useAuthMock,
}));

vi.mock('@/features/roles/hooks', () => ({
  useRole: () => ({ data: undefined, isLoading: false, isError: false }),
  usePermissionTree: () => ({ data: [], isLoading: false, isError: false }),
  useCreateRole: () => ({
    mutateAsync: vi.fn().mockResolvedValue(undefined),
    isPending: false,
  }),
  useUpdateRole: () => ({
    mutateAsync: vi.fn().mockResolvedValue(undefined),
    isPending: false,
  }),
}));

function grant(permissions: string[]): void {
  useAuthMock.mockReturnValue({
    user: { id: '1', username: 'tester' },
    permissions,
    roles: [],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  });
}

beforeEach(() => {
  useAuthMock.mockReset();
  localStorage.clear();
});

describe('RoleFormPage submit guard (create mode)', () => {
  it('shows the submit button when roles.create is granted', async () => {
    grant(['roles.read', 'roles.create']);

    renderWithProviders(<RoleFormPage />, { route: '/roles/new' });

    // Cancel is always present; the guarded submit ("Create") appears too.
    expect(
      await screen.findByRole('button', { name: 'Cancel' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create' })).toBeInTheDocument();
  });

  it('hides the submit button when roles.create is absent', async () => {
    grant(['roles.read']);

    renderWithProviders(<RoleFormPage />, { route: '/roles/new' });

    // The form still renders (Cancel present), but the save action is gated out.
    expect(
      await screen.findByRole('button', { name: 'Cancel' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Create' }),
    ).not.toBeInTheDocument();
  });
});
