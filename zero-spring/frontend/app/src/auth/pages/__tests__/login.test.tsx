import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LoginPage } from '@/auth/pages/login';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Behaviour tests for the login form (FRONTEND-ARCHITECTURE.md §9).
 *
 * The `login` action is mocked at the `useAuth` boundary so the test asserts the
 * form → context contract (validation, i18n labels, submit wiring) without
 * touching the network. `sonner` is stubbed so error toasts are inert.
 */

const { loginMock } = vi.hoisted(() => ({ loginMock: vi.fn() }));

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn(), message: vi.fn() },
}));

// Replace the auth module: `AuthProvider` becomes a passthrough (used by the
// render helper) and `useAuth` returns a controllable `login` spy.
vi.mock('@/providers/auth-provider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: () => ({
    user: null,
    permissions: [],
    roles: [],
    loading: false,
    login: loginMock,
    logout: vi.fn(),
    refreshMe: vi.fn(),
  }),
}));

beforeEach(() => {
  loginMock.mockReset();
  loginMock.mockResolvedValue(undefined);
  localStorage.clear();
});

describe('LoginPage', () => {
  it('renders the username, password and submit controls with i18n labels', () => {
    renderWithProviders(<LoginPage />);

    expect(screen.getByLabelText('Username or email')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Sign in' }),
    ).toBeInTheDocument();
    // Subtitle proves the IntlProvider resolved the catalogue, not raw keys.
    expect(
      screen.getByText('Welcome back. Please enter your details.'),
    ).toBeInTheDocument();
  });

  it('shows zod validation errors and does not call login on empty submit', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LoginPage />);

    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    // Both required fields surface the localized validation message.
    const errors = await screen.findAllByText('This field is required.');
    expect(errors.length).toBeGreaterThanOrEqual(2);
    expect(loginMock).not.toHaveBeenCalled();
  });

  it('calls login with the entered credentials on a valid submit', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LoginPage />);

    await user.type(screen.getByLabelText('Username or email'), 'admin');
    await user.type(screen.getByLabelText('Password'), 'Passw0rd!');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(loginMock).toHaveBeenCalledTimes(1));
    // Empty tenant field collapses to `undefined` (default/host tenant).
    expect(loginMock).toHaveBeenCalledWith('admin', 'Passw0rd!', undefined);
  });
});
