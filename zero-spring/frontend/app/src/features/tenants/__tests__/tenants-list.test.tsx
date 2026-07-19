import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { TenantsListPage } from '@/features/tenants/pages/tenants-list';
import type { TenantDto } from '@/features/tenants/types';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Tenants list behaviour tests (U-01, flow 3).
 *
 * `apiFetch` is mocked at the client boundary so the real hooks and react-query
 * run end to end; `useAuth` is mocked to feed explicit permission lists so the
 * `<Can permission="tenants.manage">` guards are asserted directly (the
 * frontend half of the lock — the backend enforces the same key with a
 * class-level `@PreAuthorize` on `TenantController`).
 */

const { apiFetchMock, useAuthMock, toastErrorMock } = vi.hoisted(() => ({
  apiFetchMock: vi.fn(),
  useAuthMock: vi.fn(),
  toastErrorMock: vi.fn(),
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: toastErrorMock, message: vi.fn() },
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
    user: { id: '1', username: 'host-admin' },
    permissions,
    roles: [],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  });
}

const tenants: TenantDto[] = [
  {
    id: 1,
    name: 'acme',
    displayName: 'Acme Inc.',
    active: true,
    createdAt: '2026-01-15T10:00:00Z',
  },
  {
    id: 2,
    name: 'globex',
    displayName: 'Globex Corp.',
    active: false,
    createdAt: '2026-02-20T10:00:00Z',
  },
];

beforeEach(() => {
  apiFetchMock.mockReset();
  useAuthMock.mockReset();
  toastErrorMock.mockReset();
  localStorage.clear();

  apiFetchMock.mockImplementation((path: string) => {
    if (path === '/api/tenants') {
      return Promise.resolve(tenants);
    }
    return Promise.resolve(undefined);
  });
});

describe('TenantsListPage', () => {
  it('renders the fetched tenants with their status badges', async () => {
    grant(['tenants.manage']);

    renderWithProviders(<TenantsListPage />, { route: '/tenants' });

    expect(await screen.findByText('Acme Inc.')).toBeInTheDocument();
    expect(screen.getByText('acme')).toBeInTheDocument();
    expect(screen.getByText('Globex Corp.')).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByText('Inactive')).toBeInTheDocument();
  });

  it('shows an empty state when no tenant exists yet', async () => {
    grant(['tenants.manage']);
    apiFetchMock.mockResolvedValue([]);

    renderWithProviders(<TenantsListPage />, { route: '/tenants' });

    expect(
      await screen.findByText(
        'No tenants yet. Create the first one to get started.',
      ),
    ).toBeInTheDocument();
  });

  it('shows an error state instead of the grid when the list request fails', async () => {
    grant(['tenants.manage']);
    apiFetchMock.mockRejectedValue(new Error('boom'));

    renderWithProviders(<TenantsListPage />, { route: '/tenants' });

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Tenants could not be loaded.',
    );
  });
});

/**
 * Permission-gated visibility. `tenants.manage` is `Side.HOST`, so a tenant
 * operator can never hold it — these tests pin the UX half of that rule.
 */
describe('TenantsListPage RBAC', () => {
  it('shows the create button when the user holds tenants.manage', async () => {
    grant(['tenants.manage']);

    renderWithProviders(<TenantsListPage />, { route: '/tenants' });

    expect(await screen.findByText('Acme Inc.')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'New tenant' }),
    ).toBeInTheDocument();
  });

  it('hides the create button from a user without tenants.manage', async () => {
    // A tenant-side operator with a full tenant-scoped permission set still
    // never gets tenants.manage, so tenant management stays invisible to them.
    grant(['users.read', 'roles.read', 'settings.tenant.manage']);

    renderWithProviders(<TenantsListPage />, { route: '/tenants' });

    expect(await screen.findByText('Acme Inc.')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'New tenant' }),
    ).not.toBeInTheDocument();
  });

  it('hides the activate/deactivate action without tenants.manage', async () => {
    const user = userEvent.setup();
    grant(['users.read']);

    renderWithProviders(<TenantsListPage />, { route: '/tenants' });
    await screen.findByText('Acme Inc.');

    const triggers = await screen.findAllByRole('button', {
      name: 'Open tenant actions',
    });
    await user.click(triggers[0]);

    expect(
      screen.queryByRole('menuitem', { name: 'Deactivate' }),
    ).not.toBeInTheDocument();
  });
});

describe('TenantsListPage row actions', () => {
  it('deactivates an active tenant through the row menu', async () => {
    const user = userEvent.setup();
    grant(['tenants.manage']);

    renderWithProviders(<TenantsListPage />, { route: '/tenants' });
    await screen.findByText('Acme Inc.');

    const triggers = await screen.findAllByRole('button', {
      name: 'Open tenant actions',
    });
    // Row 0 is the ACTIVE tenant, so the menu offers Deactivate.
    await user.click(triggers[0]);
    await user.click(
      await screen.findByRole('menuitem', { name: 'Deactivate' }),
    );

    await waitFor(() => {
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/tenants/1/deactivate',
        expect.objectContaining({ method: 'PUT' }),
      );
    });
  });

  it('activates an inactive tenant through the row menu', async () => {
    const user = userEvent.setup();
    grant(['tenants.manage']);

    renderWithProviders(<TenantsListPage />, { route: '/tenants' });
    await screen.findByText('Globex Corp.');

    const triggers = await screen.findAllByRole('button', {
      name: 'Open tenant actions',
    });
    // Row 1 is INACTIVE, so the same control reads Activate.
    await user.click(triggers[1]);
    await user.click(
      await screen.findByRole('menuitem', { name: 'Activate' }),
    );

    await waitFor(() => {
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/tenants/2/activate',
        expect.objectContaining({ method: 'PUT' }),
      );
    });
  });

  it('tells the operator that renaming is not possible', async () => {
    const user = userEvent.setup();
    grant(['tenants.manage']);

    renderWithProviders(<TenantsListPage />, { route: '/tenants' });
    await screen.findByText('Acme Inc.');

    const triggers = await screen.findAllByRole('button', {
      name: 'Open tenant actions',
    });
    await user.click(triggers[0]);

    // There is no PUT /api/tenants/{id}; the menu says so rather than offering
    // an Edit item that would have nowhere to go.
    expect(
      await screen.findByText(
        'A tenant’s name cannot be changed after creation.',
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('menuitem', { name: 'Edit' }),
    ).not.toBeInTheDocument();
  });
});

describe('CreateTenantDialog', () => {
  async function openDialog(): Promise<void> {
    const user = userEvent.setup();
    await screen.findByText('Acme Inc.');
    await user.click(screen.getByRole('button', { name: 'New tenant' }));
  }

  it('warns that no admin user is created (known gap, Issue #1)', async () => {
    grant(['tenants.manage']);
    renderWithProviders(<TenantsListPage />, { route: '/tenants' });
    await openDialog();

    // The operator must learn this BEFORE creating a tenant nobody can log in
    // to — not by discovering the empty tenant afterwards.
    expect(
      await screen.findByText('No admin user is created'),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/does not create a user for it/i),
    ).toBeInTheDocument();
  });

  it('creates a tenant with the entered name and display name', async () => {
    const user = userEvent.setup();
    grant(['tenants.manage']);
    renderWithProviders(<TenantsListPage />, { route: '/tenants' });
    await openDialog();

    await user.type(await screen.findByLabelText('Name'), 'initech');
    await user.type(screen.getByLabelText('Display name'), 'Initech LLC');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => {
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/tenants',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({
            name: 'initech',
            displayName: 'Initech LLC',
          }),
        }),
      );
    });
  });

  it('rejects a name that violates the backend pattern before sending it', async () => {
    const user = userEvent.setup();
    grant(['tenants.manage']);
    renderWithProviders(<TenantsListPage />, { route: '/tenants' });
    await openDialog();

    // Uppercase and spaces are outside `@Pattern("[a-z0-9-]{2,30}")`.
    await user.type(await screen.findByLabelText('Name'), 'Acme Corp');
    await user.type(screen.getByLabelText('Display name'), 'Acme');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    expect(
      await screen.findByText(
        'Use 2-30 characters: lowercase letters, digits or hyphens.',
      ),
    ).toBeInTheDocument();
    expect(
      apiFetchMock.mock.calls.some((call) => call[1]?.method === 'POST'),
    ).toBe(false);
  });

  it('keeps the dialog open and reports the conflict when the name is taken', async () => {
    const user = userEvent.setup();
    grant(['tenants.manage']);
    apiFetchMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/api/tenants' && init?.method === 'POST') {
        return Promise.reject(
          new ApiError(409, {
            detail: "Tenant with name 'acme' already exists",
          }),
        );
      }
      return Promise.resolve(tenants);
    });

    renderWithProviders(<TenantsListPage />, { route: '/tenants' });
    await openDialog();

    await user.type(await screen.findByLabelText('Name'), 'acme');
    await user.type(screen.getByLabelText('Display name'), 'Acme');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => {
      expect(toastErrorMock).toHaveBeenCalledWith(
        "Tenant with name 'acme' already exists",
      );
    });
    // Still open so the operator can correct the name rather than retype it.
    expect(screen.getByLabelText('Name')).toBeInTheDocument();
  });
});
