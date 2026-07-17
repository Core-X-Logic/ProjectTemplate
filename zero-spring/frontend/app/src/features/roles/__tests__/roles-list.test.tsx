import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { RolesListPage } from '@/features/roles/pages/roles-list';
import type { PageRoleDto } from '@/features/roles/types';
import { renderWithProviders, screen } from '@/test/utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Roles list behaviour tests (FRONTEND-ARCHITECTURE.md §9).
 *
 * `apiFetch` is mocked at the client boundary so the real feature hooks and
 * react-query run; `useAuth` is mocked to feed explicit permission lists so the
 * RBAC guards (`<Can permission="roles.create">`) are asserted directly.
 */

const { apiFetchMock, useAuthMock } = vi.hoisted(() => ({
  apiFetchMock: vi.fn(),
  useAuthMock: vi.fn(),
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), message: vi.fn() },
}));

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>();
  return { ...actual, apiFetch: apiFetchMock };
});

vi.mock('@/providers/auth-provider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: useAuthMock,
}));

function grant(permissions: string[]): void {
  useAuthMock.mockReturnValue({
    user: { id: '1', username: 'admin' },
    permissions,
    roles: [],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  });
}

const rolesPage: PageRoleDto = {
  content: [
    {
      id: 1,
      name: 'admin',
      displayName: 'Administrator',
      isStatic: true,
      isDefault: false,
      memberCount: 3,
    },
    {
      id: 2,
      name: 'editor',
      displayName: 'Content Editor',
      isStatic: false,
      isDefault: true,
      memberCount: 12,
    },
  ],
  totalElements: 2,
  totalPages: 1,
  number: 0,
  size: 10,
  numberOfElements: 2,
  first: true,
  last: true,
  empty: false,
};

beforeEach(() => {
  apiFetchMock.mockReset();
  useAuthMock.mockReset();
  localStorage.clear();

  apiFetchMock.mockImplementation((path: string) => {
    if (path.startsWith('/api/roles')) {
      return Promise.resolve(rolesPage);
    }
    if (path.startsWith('/api/permissions/tree')) {
      return Promise.resolve([]);
    }
    return Promise.resolve(undefined);
  });
});

describe('RolesListPage', () => {
  it('renders the fetched roles with static/custom and default badges', async () => {
    grant(['roles.read']);

    renderWithProviders(<RolesListPage />, { route: '/roles' });

    expect(await screen.findByText('Administrator')).toBeInTheDocument();
    expect(screen.getByText('Content Editor')).toBeInTheDocument();
    // isStatic → Static badge, otherwise Custom.
    expect(screen.getByText('Static')).toBeInTheDocument();
    expect(screen.getByText('Custom')).toBeInTheDocument();
    // The list endpoint was hit with pageable query params.
    expect(apiFetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/roles?'),
    );
  });

  it('shows the create button when the user holds roles.create', async () => {
    grant(['roles.read', 'roles.create']);

    renderWithProviders(<RolesListPage />, { route: '/roles' });

    expect(await screen.findByText('Administrator')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'New role' }),
    ).toBeInTheDocument();
  });

  it('hides the create button when roles.create is missing', async () => {
    grant(['roles.read']);

    renderWithProviders(<RolesListPage />, { route: '/roles' });

    expect(await screen.findByText('Administrator')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'New role' }),
    ).not.toBeInTheDocument();
  });
});

/**
 * Each row action (Edit / Clone / Delete) is individually `<Can>`-guarded and
 * only mounts as a radix `menuitem` once the row's action menu is opened. Row
 * order mirrors the fixture: index 0 = admin (static), index 1 = editor
 * (custom, so its Delete item is enabled).
 */
describe('RolesListPage row actions (RBAC)', () => {
  async function openRowMenu(rowIndex: number): Promise<void> {
    const user = userEvent.setup();
    const triggers = await screen.findAllByRole('button', {
      name: 'Open role actions',
    });
    await user.click(triggers[rowIndex]);
  }

  it('shows Edit, Clone and Delete when the matching permissions are granted', async () => {
    grant(['roles.read', 'roles.update', 'roles.create', 'roles.delete']);

    renderWithProviders(<RolesListPage />, { route: '/roles' });
    await screen.findByText('Content Editor');
    await openRowMenu(1);

    expect(
      await screen.findByRole('menuitem', { name: 'Edit' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Clone' })).toBeInTheDocument();
    expect(
      screen.getByRole('menuitem', { name: 'Delete' }),
    ).toBeInTheDocument();
  });

  it('hides the Edit action when roles.update is absent', async () => {
    grant(['roles.read', 'roles.create']);

    renderWithProviders(<RolesListPage />, { route: '/roles' });
    await screen.findByText('Content Editor');
    await openRowMenu(1);

    // The menu is open (Clone is present), but Edit is gated out.
    expect(
      await screen.findByRole('menuitem', { name: 'Clone' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('menuitem', { name: 'Edit' }),
    ).not.toBeInTheDocument();
  });

  it('hides the Clone action when roles.create is absent', async () => {
    grant(['roles.read', 'roles.update']);

    renderWithProviders(<RolesListPage />, { route: '/roles' });
    await screen.findByText('Content Editor');
    await openRowMenu(1);

    expect(
      await screen.findByRole('menuitem', { name: 'Edit' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('menuitem', { name: 'Clone' }),
    ).not.toBeInTheDocument();
  });

  it('hides the Delete action when roles.delete is absent', async () => {
    grant(['roles.read', 'roles.update']);

    renderWithProviders(<RolesListPage />, { route: '/roles' });
    await screen.findByText('Content Editor');
    await openRowMenu(1);

    expect(
      await screen.findByRole('menuitem', { name: 'Edit' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('menuitem', { name: 'Delete' }),
    ).not.toBeInTheDocument();
  });
});
