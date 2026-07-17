import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { UsersListPage } from '@/features/users/pages/users-list';
import type { PageUserDto } from '@/features/users/types';
import { renderWithProviders, screen } from '@/test/utils';

/**
 * Users list behaviour tests (FRONTEND-ARCHITECTURE.md §9).
 *
 * The data layer is mocked at the hooks boundary (`vi.mock` on the feature's
 * hooks module) so the test asserts rendering + RBAC wiring without touching
 * the network. Permissions are fed through a mocked `useAuth`, exactly like the
 * production `<Can>`/`<RequireAuth>` consume them.
 */

const { grantedPermissions, mutationStub, usersPage } = vi.hoisted(() => {
  const usersPage: PageUserDto = {
    content: [
      {
        id: 1,
        username: 'alice',
        email: 'alice@acme.io',
        name: 'Alice',
        surname: 'Smith',
        active: true,
        roles: ['admin'],
      },
      {
        id: 2,
        username: 'bob',
        email: 'bob@acme.io',
        name: 'Bob',
        surname: 'Stone',
        active: false,
        roles: ['user'],
        // Far-future lockout end → `isLocked` is true, so the row exposes the
        // (permission-gated) Unlock action.
        lockoutEndAt: '2099-01-01T00:00:00Z',
      },
    ],
    totalElements: 2,
    totalPages: 1,
    number: 0,
    size: 10,
  };

  return {
    // Mutable holder: each test seeds the permission set before rendering.
    grantedPermissions: { current: ['users.read'] as string[] },
    mutationStub: () => ({
      mutate: vi.fn(),
      mutateAsync: vi.fn().mockResolvedValue(undefined),
      isPending: false,
    }),
    usersPage,
  };
});

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn(), message: vi.fn() },
}));

// Auth boundary: `AuthProvider` becomes a passthrough (used by the render
// helper); `useAuth` reads the per-test permission holder.
vi.mock('@/providers/auth-provider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: () => ({
    user: {
      id: '1',
      username: 'tester',
      email: 'tester@acme.io',
      tenantId: '1',
      roles: ['admin'],
      permissions: grantedPermissions.current,
    },
    permissions: grantedPermissions.current,
    roles: ['admin'],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  }),
}));

// Data boundary: every hook the list page (and its dialog subtree) imports.
vi.mock('@/features/users/hooks', () => ({
  useUsers: () => ({ data: usersPage, isLoading: false }),
  useUser: () => ({ data: undefined, isLoading: false }),
  useCreateUser: mutationStub,
  useUpdateUser: mutationStub,
  useDeleteUser: mutationStub,
  useUnlockUser: mutationStub,
  useActivateUser: mutationStub,
  useDeactivateUser: mutationStub,
  useAssignRoles: mutationStub,
  useAssignOrganizationUnits: mutationStub,
  useExportUsers: mutationStub,
  useRoleOptions: () => ({ data: [], isLoading: false }),
  useOrganizationUnitOptions: () => ({ data: [], isLoading: false }),
}));

// The feature catalogue is merged into the root catalogue by the integration
// step; the test mirrors that merge so assertions use real English copy.
vi.mock('@/i18n/messages/en', async () => {
  const actual =
    await vi.importActual<typeof import('@/i18n/messages/en')>(
      '@/i18n/messages/en',
    );
  const { usersMessagesEn } = await vi.importActual<
    typeof import('@/features/users/messages')
  >('@/features/users/messages');
  return { default: { ...actual.default, ...usersMessagesEn } };
});

beforeEach(() => {
  grantedPermissions.current = ['users.read'];
  localStorage.clear();
});

describe('UsersListPage', () => {
  it('renders a row per user with username, email and role badges', async () => {
    renderWithProviders(<UsersListPage />);

    expect(await screen.findByText('alice')).toBeInTheDocument();
    expect(screen.getByText('bob')).toBeInTheDocument();
    expect(screen.getByText('alice@acme.io')).toBeInTheDocument();
    expect(screen.getByText('bob@acme.io')).toBeInTheDocument();
    expect(screen.getByText('admin')).toBeInTheDocument();
    // Active/inactive status is rendered as localized badges.
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByText('Inactive')).toBeInTheDocument();
  });

  it('hides the Create button when users.create is NOT granted (RBAC)', async () => {
    grantedPermissions.current = ['users.read'];
    renderWithProviders(<UsersListPage />);

    await screen.findByText('alice');
    expect(
      screen.queryByRole('button', { name: 'Create' }),
    ).not.toBeInTheDocument();
    // The read-only Export action stays visible with users.read alone.
    expect(screen.getByRole('button', { name: 'Export' })).toBeInTheDocument();
  });

  it('shows the Create button when users.create IS granted (RBAC)', async () => {
    grantedPermissions.current = ['users.read', 'users.create'];
    renderWithProviders(<UsersListPage />);

    await screen.findByText('alice');
    expect(screen.getByRole('button', { name: 'Create' })).toBeInTheDocument();
  });
});

/**
 * Row-level dropdown actions are individually `<Can>`-guarded. Each action is a
 * radix `menuitem` that only mounts once the row's action menu is opened, so the
 * assertions open the menu and check presence/absence per permission.
 *
 * Row order mirrors the fixture: index 0 = alice (active, unlocked),
 * index 1 = bob (inactive, locked → the Unlock branch is mounted).
 */
describe('UsersListPage row actions (RBAC)', () => {
  async function openRowMenu(rowIndex: number): Promise<void> {
    const user = userEvent.setup();
    const triggers = await screen.findAllByRole('button', { name: 'Actions' });
    await user.click(triggers[rowIndex]);
  }

  it('shows the Edit action when users.update is granted', async () => {
    grantedPermissions.current = ['users.read', 'users.update'];
    renderWithProviders(<UsersListPage />);

    await screen.findByText('alice');
    await openRowMenu(0);

    expect(
      await screen.findByRole('menuitem', { name: 'Edit' }),
    ).toBeInTheDocument();
  });

  it('hides the Edit action when users.update is absent', async () => {
    grantedPermissions.current = ['users.read', 'users.delete'];
    renderWithProviders(<UsersListPage />);

    await screen.findByText('alice');
    await openRowMenu(0);

    // The menu is open (Delete is present), but Edit is gated out.
    expect(
      await screen.findByRole('menuitem', { name: 'Delete' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('menuitem', { name: 'Edit' }),
    ).not.toBeInTheDocument();
  });

  it('shows the Delete action when users.delete is granted', async () => {
    grantedPermissions.current = ['users.read', 'users.delete'];
    renderWithProviders(<UsersListPage />);

    await screen.findByText('alice');
    await openRowMenu(0);

    expect(
      await screen.findByRole('menuitem', { name: 'Delete' }),
    ).toBeInTheDocument();
  });

  it('hides the Delete action when users.delete is absent', async () => {
    grantedPermissions.current = ['users.read', 'users.update'];
    renderWithProviders(<UsersListPage />);

    await screen.findByText('alice');
    await openRowMenu(0);

    // The menu is open (Edit is present), but Delete is gated out.
    expect(
      await screen.findByRole('menuitem', { name: 'Edit' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('menuitem', { name: 'Delete' }),
    ).not.toBeInTheDocument();
  });

  it('shows the Unlock action for a locked user when users.unlock is granted', async () => {
    grantedPermissions.current = ['users.read', 'users.unlock'];
    renderWithProviders(<UsersListPage />);

    await screen.findByText('bob');
    await openRowMenu(1);

    expect(
      await screen.findByRole('menuitem', { name: 'Unlock' }),
    ).toBeInTheDocument();
  });

  it('hides the Unlock action when users.unlock is absent (even if locked)', async () => {
    grantedPermissions.current = ['users.read', 'users.update'];
    renderWithProviders(<UsersListPage />);

    await screen.findByText('bob');
    await openRowMenu(1);

    // The menu is open (Edit is present for the locked row), but Unlock is gated.
    expect(
      await screen.findByRole('menuitem', { name: 'Edit' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('menuitem', { name: 'Unlock' }),
    ).not.toBeInTheDocument();
  });
});
