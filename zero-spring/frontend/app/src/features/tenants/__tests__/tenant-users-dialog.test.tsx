import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TenantUsersDialog } from '@/features/tenants/components/tenant-users-dialog';
import type { TenantDto } from '@/features/tenants/types';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Host-side tenant users dialog (the UI bridge over the backend's existing
 * cross-tenant listing and impersonation).
 *
 * `apiFetch` is mocked at the client boundary so the real react-query flow
 * runs; `useAuth` is mocked to feed permission lists, asserting the
 * `<Can permission="users.impersonate">` guard in BOTH directions — the button
 * must exist for a holder and must NOT exist for a non-holder (the frontend
 * half of the triple lock; the backend enforces the same key on
 * `POST /api/auth/impersonate`).
 */

const { apiFetchMock, useAuthMock, impersonateMock } = vi.hoisted(() => ({
  apiFetchMock: vi.fn(),
  useAuthMock: vi.fn(),
  impersonateMock: vi.fn(),
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

function grant(permissions: string[], isImpersonating = false): void {
  useAuthMock.mockReturnValue({
    user: { id: '1', username: 'host-admin' },
    permissions,
    roles: [],
    loading: false,
    isImpersonating,
    impersonate: impersonateMock,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  });
}

const tenant: TenantDto = {
  id: 7,
  name: 'acme',
  displayName: 'Acme Inc.',
  active: true,
  createdAt: '2026-01-15T10:00:00Z',
};

const usersPage = {
  content: [
    {
      id: 42,
      username: 'jane',
      email: 'jane@acme.test',
      active: true,
      tenantId: 7,
      roles: ['Admin'],
    },
    {
      id: 43,
      username: 'inactive-joe',
      email: 'joe@acme.test',
      active: false,
      tenantId: 7,
      roles: [],
    },
  ],
  totalElements: 2,
  totalPages: 1,
  number: 0,
  size: 20,
};

function renderDialog() {
  return renderWithProviders(
    <TenantUsersDialog tenant={tenant} open onOpenChange={vi.fn()} />,
  );
}

describe('TenantUsersDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiFetchMock.mockResolvedValue(usersPage);
  });

  it('lists the tenant users via the host-only tenantId parameter', async () => {
    grant(['users.read', 'users.impersonate']);
    renderDialog();

    expect(await screen.findByText('jane')).toBeInTheDocument();
    expect(screen.getByText('inactive-joe')).toBeInTheDocument();

    // The listing must be the CROSS-TENANT form: without tenantId the backend
    // would silently answer with the host's own users, which renders the same
    // grid shape — only the query string distinguishes right from wrong.
    const url = apiFetchMock.mock.calls[0][0] as string;
    expect(url).toContain('/api/users?');
    expect(url).toContain('tenantId=7');
  });

  it('impersonates a listed user in the tenant context', async () => {
    grant(['users.read', 'users.impersonate']);
    impersonateMock.mockResolvedValue(undefined);
    renderDialog();

    const buttons = await screen.findAllByRole('button', {
      name: 'Impersonate',
    });
    await userEvent.click(buttons[0]);

    await waitFor(() => {
      // tenantId must ride along — without it the backend refuses a host actor
      // targeting a tenant user only when ids collide by luck, and the request
      // is semantically "impersonate in MY scope".
      expect(impersonateMock).toHaveBeenCalledWith(42, 7);
    });
  });

  it('disables impersonation for an inactive user', async () => {
    grant(['users.read', 'users.impersonate']);
    renderDialog();

    const buttons = await screen.findAllByRole('button', {
      name: 'Impersonate',
    });
    // Row order follows the mocked page: jane (active), inactive-joe.
    expect(buttons[0]).toBeEnabled();
    expect(buttons[1]).toBeDisabled();
  });

  it('disables impersonation while already impersonating (cascade rule)', async () => {
    grant(['users.read', 'users.impersonate'], true);
    renderDialog();

    const buttons = await screen.findAllByRole('button', {
      name: 'Impersonate',
    });
    for (const button of buttons) {
      expect(button).toBeDisabled();
    }
  });

  it('hides the impersonate action without users.impersonate', async () => {
    grant(['users.read']);
    renderDialog();

    expect(await screen.findByText('jane')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Impersonate' }),
    ).not.toBeInTheDocument();
  });
});
