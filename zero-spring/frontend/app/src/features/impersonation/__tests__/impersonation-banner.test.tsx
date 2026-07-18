import type { ReactNode } from 'react';
import { ImpersonationBanner } from '@/features/impersonation/components/impersonation-banner';
import { renderWithProviders, screen, waitFor } from '@/test/utils';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Behaviour tests for the impersonation banner (FRONTEND-ARCHITECTURE.md §9).
 *
 * `useAuth` is mocked (vi.hoisted) so each case feeds an explicit
 * `isImpersonating` flag + user identity; `sonner` is stubbed so toasts are
 * inert. The banner reads `auth.isImpersonating` to decide visibility and calls
 * `auth.backToImpersonator()` from the "Back to my account" action.
 */

const { useAuthMock, backToImpersonatorMock } = vi.hoisted(() => ({
  useAuthMock: vi.fn(),
  backToImpersonatorMock: vi.fn(),
}));

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn(), message: vi.fn() },
}));

vi.mock('@/providers/auth-provider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: useAuthMock,
}));

function mockAuth(isImpersonating: boolean): void {
  useAuthMock.mockReturnValue({
    user: {
      id: '1',
      username: 'alice',
      email: 'alice@acme.io',
      tenantId: '1',
      roles: [],
      permissions: [],
    },
    permissions: [],
    roles: [],
    loading: false,
    isImpersonating,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
    impersonate: vi.fn(),
    backToImpersonator: backToImpersonatorMock,
  });
}

beforeEach(() => {
  useAuthMock.mockReset();
  backToImpersonatorMock.mockReset();
  backToImpersonatorMock.mockResolvedValue(undefined);
  localStorage.clear();
});

describe('ImpersonationBanner', () => {
  it('renders the banner with the impersonated username and a back action when impersonating', () => {
    mockAuth(true);
    renderWithProviders(<ImpersonationBanner />);

    expect(screen.getByText('Impersonating: alice')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Back to my account' }),
    ).toBeInTheDocument();
  });

  it('calls backToImpersonator when the back action is clicked', async () => {
    const user = userEvent.setup();
    mockAuth(true);
    renderWithProviders(<ImpersonationBanner />);

    await user.click(
      screen.getByRole('button', { name: 'Back to my account' }),
    );

    await waitFor(() =>
      expect(backToImpersonatorMock).toHaveBeenCalledTimes(1),
    );
  });

  it('renders nothing when the session is not an impersonation', () => {
    mockAuth(false);
    const { container } = renderWithProviders(<ImpersonationBanner />);

    expect(
      screen.queryByRole('button', { name: 'Back to my account' }),
    ).not.toBeInTheDocument();
    expect(container).toBeEmptyDOMElement();
  });
});
