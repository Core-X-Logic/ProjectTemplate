import type { ReactNode } from 'react';
import { ImpersonateAction } from '@/features/impersonation/components/impersonate-action';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { renderWithProviders, screen, waitFor } from '@/test/utils';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Behaviour tests for the row-level impersonate action (FRONTEND-ARCHITECTURE.md §9).
 *
 * `useAuth` is mocked (vi.hoisted) so each case feeds an explicit permission set
 * and `isImpersonating` flag — the same values `<Can>` and the action consume in
 * production. The action is a `DropdownMenuItem` (role=menuitem), so it is
 * rendered inside an open `DropdownMenu` exactly like the users-list row menu.
 * Asserts RBAC visibility and the cascade block (`aria-disabled` while already
 * impersonating).
 */

const { useAuthMock, impersonateMock } = vi.hoisted(() => ({
  useAuthMock: vi.fn(),
  impersonateMock: vi.fn(),
}));

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn(), message: vi.fn() },
}));

vi.mock('@/providers/auth-provider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: useAuthMock,
}));

function mockAuth(permissions: string[], isImpersonating = false): void {
  useAuthMock.mockReturnValue({
    user: {
      id: '1',
      username: 'host',
      email: 'host@acme.io',
      tenantId: '1',
      roles: [],
      permissions,
    },
    permissions,
    roles: [],
    loading: false,
    isImpersonating,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
    impersonate: impersonateMock,
    backToImpersonator: vi.fn(),
  });
}

/** Render the action inside an open dropdown menu (its production context). */
function renderAction(props: { userId: number; username?: string }) {
  return renderWithProviders(
    <DropdownMenu open>
      <DropdownMenuTrigger>menu</DropdownMenuTrigger>
      <DropdownMenuContent>
        <ImpersonateAction {...props} />
      </DropdownMenuContent>
    </DropdownMenu>,
  );
}

beforeEach(() => {
  useAuthMock.mockReset();
  impersonateMock.mockReset();
  impersonateMock.mockResolvedValue(undefined);
  localStorage.clear();
});

describe('ImpersonateAction', () => {
  it('is hidden when users.impersonate is NOT granted (RBAC)', () => {
    mockAuth(['users.read']);
    renderAction({ userId: 5, username: 'bob' });

    expect(
      screen.queryByRole('menuitem', { name: 'Impersonate' }),
    ).not.toBeInTheDocument();
  });

  it('is visible and enabled when users.impersonate IS granted', () => {
    mockAuth(['users.read', 'users.impersonate']);
    renderAction({ userId: 5, username: 'bob' });

    const item = screen.getByRole('menuitem', { name: 'Impersonate' });
    expect(item).toBeInTheDocument();
    expect(item).not.toHaveAttribute('aria-disabled');
  });

  it('calls impersonate with the target user id on select', async () => {
    const user = userEvent.setup();
    mockAuth(['users.impersonate']);
    renderAction({ userId: 5, username: 'bob' });

    await user.click(screen.getByRole('menuitem', { name: 'Impersonate' }));

    await waitFor(() => expect(impersonateMock).toHaveBeenCalledTimes(1));
    expect(impersonateMock).toHaveBeenCalledWith(5, undefined);
  });

  it('is disabled while already impersonating (cascade block)', () => {
    mockAuth(['users.impersonate'], true);
    renderAction({ userId: 5, username: 'bob' });

    expect(
      screen.getByRole('menuitem', { name: 'Impersonate' }),
    ).toHaveAttribute('aria-disabled', 'true');
  });

  it('does not call impersonate when selected while blocked (no-op)', async () => {
    const user = userEvent.setup();
    mockAuth(['users.impersonate'], true);
    renderAction({ userId: 5, username: 'bob' });

    await user.click(screen.getByRole('menuitem', { name: 'Impersonate' }));

    expect(impersonateMock).not.toHaveBeenCalled();
  });
});
