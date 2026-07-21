import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { ApiError, tokenStore } from '@/api/client';
import { LoginPage } from '@/auth/pages/login';
import { TwoFactorPage } from '@/auth/pages/two-factor';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Behaviour tests for the 2FA login second step.
 *
 * `apiFetch` is mocked at the client boundary so the REAL `AuthProvider`,
 * `LoginPage` and `TwoFactorPage` run end to end through the router — the test
 * exercises the whole login → challenge → verify flow, not a stubbed context.
 * `tokenStore` is the real in-memory store, so "did we store tokens?" is a fact
 * we can assert directly.
 */

const { apiFetchMock } = vi.hoisted(() => ({ apiFetchMock: vi.fn() }));

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn(), message: vi.fn() },
}));

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>();
  return { ...actual, apiFetch: apiFetchMock };
});

const CHALLENGE = { challengeToken: 'chal-123', expiresInSeconds: 300 };
const TOKENS = {
  accessToken: 'access-xyz',
  refreshToken: 'refresh-xyz',
  expiresInSeconds: 3600,
};
const ME = { id: 1, username: 'jane', roles: [], permissions: [] };

/** The two public auth routes plus a home marker to prove a successful redirect. */
const appRoutes = (
  <Routes>
    <Route path="/login" element={<LoginPage />} />
    <Route path="/login/two-factor" element={<TwoFactorPage />} />
    <Route path="/" element={<div>HOME PAGE</div>} />
  </Routes>
);

beforeEach(() => {
  apiFetchMock.mockReset();
  tokenStore.clear();
  localStorage.clear();
});

afterEach(async () => {
  // `input-otp` schedules selection-sync timers (≤50ms) that it never clears on
  // unmount; flush them while jsdom is still alive so none fire after the
  // environment is torn down (which would throw "window is not defined").
  await new Promise((resolve) => setTimeout(resolve, 60));
});

/** Drives a login whose account has 2FA on, landing on the second-step screen. */
async function reachSecondStep(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Username or email'), 'jane');
  await user.type(screen.getByLabelText('Password'), 'Passw0rd!');
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
  await screen.findByText('Two-step verification');
}

describe('Two-factor login second step', () => {
  it('routes to the second step WITHOUT storing tokens, then verify stores tokens and proceeds', async () => {
    const user = userEvent.setup();
    apiFetchMock.mockImplementation((path: string) => {
      if (path === '/api/auth/login') {
        return Promise.resolve({ twoFactorRequired: true, twoFactor: CHALLENGE });
      }
      if (path === '/api/auth/two-factor/verify') {
        return Promise.resolve(TOKENS);
      }
      if (path === '/api/auth/me') {
        return Promise.resolve(ME);
      }
      return Promise.resolve(undefined);
    });

    renderWithProviders(appRoutes, { route: '/login' });
    await reachSecondStep(user);

    // The challenge screen is up but NO session was minted: the token store is
    // still empty and /api/auth/me has not been called.
    expect(tokenStore.getAccess()).toBeNull();
    expect(
      apiFetchMock.mock.calls.some((c) => c[0] === '/api/auth/me'),
    ).toBe(false);

    // Redeem with a recovery code (deterministic text input).
    await user.click(
      screen.getByRole('button', { name: 'Use a recovery code instead' }),
    );
    await user.type(screen.getByLabelText('Recovery code'), 'RECOVER-1');
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/auth/two-factor/verify',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({
            challengeToken: 'chal-123',
            code: 'RECOVER-1',
          }),
        }),
      ),
    );

    // Success path: tokens stored and we land on the home marker.
    expect(await screen.findByText('HOME PAGE')).toBeInTheDocument();
    expect(tokenStore.getAccess()).toBe('access-xyz');
  });

  it('shows one neutral error and stays on the step when verify fails with 401', async () => {
    const user = userEvent.setup();
    apiFetchMock.mockImplementation((path: string) => {
      if (path === '/api/auth/login') {
        return Promise.resolve({ twoFactorRequired: true, twoFactor: CHALLENGE });
      }
      if (path === '/api/auth/two-factor/verify') {
        // Generic 401 — the backend never says whether it was a bad code or an
        // expired challenge.
        return Promise.reject(new ApiError(401, { detail: 'Unauthorized' }));
      }
      return Promise.resolve(undefined);
    });

    renderWithProviders(appRoutes, { route: '/login' });
    await reachSecondStep(user);

    await user.click(
      screen.getByRole('button', { name: 'Use a recovery code instead' }),
    );
    await user.type(screen.getByLabelText('Recovery code'), 'WRONG-CODE');
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    // Neutral message (no oracle), and still on the second step (not HOME).
    expect(
      await screen.findByText(
        'That code is invalid or has expired. Please try again.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText('HOME PAGE')).not.toBeInTheDocument();
    expect(tokenStore.getAccess()).toBeNull();
  });

  it('accepts a 6-digit code typed into the OTP entry by default', async () => {
    const user = userEvent.setup();
    apiFetchMock.mockImplementation((path: string) => {
      if (path === '/api/auth/login') {
        return Promise.resolve({ twoFactorRequired: true, twoFactor: CHALLENGE });
      }
      if (path === '/api/auth/two-factor/verify') {
        return Promise.resolve(TOKENS);
      }
      if (path === '/api/auth/me') {
        return Promise.resolve(ME);
      }
      return Promise.resolve(undefined);
    });

    renderWithProviders(appRoutes, { route: '/login' });
    await reachSecondStep(user);

    // Default (TOTP) mode: the OTP input is present and accepts digits.
    await user.type(screen.getByLabelText('Authentication code'), '123456');
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/auth/two-factor/verify',
        expect.objectContaining({
          body: JSON.stringify({
            challengeToken: 'chal-123',
            code: '123456',
          }),
        }),
      ),
    );
  });

  it('redirects to /login when the challenge is missing (refresh / direct hit)', async () => {
    renderWithProviders(appRoutes, { route: '/login/two-factor' });

    // No router state → the page bounces straight back to the login form
    // (its unique subtitle proves we are on /login, not the second step).
    expect(
      await screen.findByText('Welcome back. Please enter your details.'),
    ).toBeInTheDocument();
    expect(
      screen.queryByText('Two-step verification'),
    ).not.toBeInTheDocument();
  });
});
