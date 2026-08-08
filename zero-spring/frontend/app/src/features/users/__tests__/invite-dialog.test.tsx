import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { InviteUserDialog } from '@/features/users/components/invite-dialog';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Invite dialog behaviour tests.
 *
 * The invitation API module is mocked (deterministic network); the real
 * `invitation-hooks` mutation flow runs on top of it. Permissions are fed
 * through a mocked `useAuth`, exactly like the production `<Can>` consumes
 * them — the submit button must vanish without `users.create`.
 */

const { grantedPermissions, inviteUserMock } = vi.hoisted(() => ({
  grantedPermissions: { current: ['users.read', 'users.create'] as string[] },
  inviteUserMock: vi.fn(),
}));

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn(), message: vi.fn() },
}));

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

// Network boundary of the dialog itself.
vi.mock('@/features/users/invitation-api', () => ({
  inviteUser: inviteUserMock,
  listInvitations: vi.fn().mockResolvedValue({ content: [] }),
  resendInvitation: vi.fn(),
  revokeInvitation: vi.fn(),
}));

// The role multi-select's lookup (owned by hooks.ts, used via RoleSelect).
vi.mock('@/features/users/hooks', () => ({
  useRoleOptions: () => ({
    data: [
      { id: 1, name: 'Admin', displayName: 'Admin' },
      { id: 2, name: 'Member', displayName: 'Member' },
    ],
    isLoading: false,
  }),
}));

beforeEach(() => {
  grantedPermissions.current = ['users.read', 'users.create'];
  inviteUserMock.mockReset();
  inviteUserMock.mockResolvedValue({ id: 7, status: 'PENDING' });
  localStorage.clear();
});

describe('InviteUserDialog', () => {
  it('submits username + email and closes on success', async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();
    renderWithProviders(
      <InviteUserDialog open onOpenChange={onOpenChange} />,
    );

    await user.type(screen.getByLabelText('Username'), 'newcolleague');
    await user.type(screen.getByLabelText('Email'), 'new@acme.io');
    await user.click(screen.getByRole('button', { name: 'Send invitation' }));

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
    expect(inviteUserMock).toHaveBeenCalledWith({
      username: 'newcolleague',
      email: 'new@acme.io',
      roleNames: [],
    });
  });

  it('hides the submit action without users.create', () => {
    grantedPermissions.current = ['users.read'];
    renderWithProviders(<InviteUserDialog open onOpenChange={vi.fn()} />);

    expect(
      screen.queryByRole('button', { name: 'Send invitation' }),
    ).not.toBeInTheDocument();
    // The form itself still renders — only the permission-gated action is gone.
    expect(screen.getByLabelText('Username')).toBeInTheDocument();
  });

  it('rejects an invalid email locally without a round trip', async () => {
    const user = userEvent.setup();
    renderWithProviders(<InviteUserDialog open onOpenChange={vi.fn()} />);

    await user.type(screen.getByLabelText('Username'), 'x');
    await user.type(screen.getByLabelText('Email'), 'not-an-email');
    await user.click(screen.getByRole('button', { name: 'Send invitation' }));

    expect(
      await screen.findByText('Enter a valid email address.'),
    ).toBeInTheDocument();
    expect(inviteUserMock).not.toHaveBeenCalled();
  });

  it('keeps the dialog open and shows the backend reason on a duplicate invitation', async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();
    inviteUserMock.mockRejectedValue(
      new ApiError(409, {
        detail: 'A pending invitation already exists for this username or email',
      }),
    );
    renderWithProviders(<InviteUserDialog open onOpenChange={onOpenChange} />);

    await user.type(screen.getByLabelText('Username'), 'dupe');
    await user.type(screen.getByLabelText('Email'), 'dupe@acme.io');
    await user.click(screen.getByRole('button', { name: 'Send invitation' }));

    expect(
      await screen.findByText(
        'A pending invitation already exists for this username or email',
      ),
    ).toBeInTheDocument();
    expect(onOpenChange).not.toHaveBeenCalledWith(false);
  });
});
