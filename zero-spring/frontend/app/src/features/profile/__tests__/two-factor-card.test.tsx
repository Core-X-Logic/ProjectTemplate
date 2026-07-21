import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TwoFactorCard } from '@/features/profile/components/two-factor-card';
import { renderWithProviders, screen, waitFor, within } from '@/test/utils';

/**
 * Behaviour tests for the self-service 2FA card.
 *
 * `apiFetch` is mocked at the client boundary so the real feature hooks and
 * react-query run end to end. The card owns no server-readable "enabled" state
 * (no DTO exposes `twoFactorEnabled`), so the tests drive the same local flow a
 * user would: enable → confirm → recovery codes, and manage → disable /
 * regenerate behind a password prompt.
 */

const { apiFetchMock, toastSuccessMock, toastErrorMock, writeTextMock } =
  vi.hoisted(() => ({
    apiFetchMock: vi.fn(),
    toastSuccessMock: vi.fn(),
    toastErrorMock: vi.fn(),
    writeTextMock: vi.fn(),
  }));

vi.mock('sonner', () => ({
  toast: {
    success: toastSuccessMock,
    error: toastErrorMock,
    message: vi.fn(),
  },
}));

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>();
  return { ...actual, apiFetch: apiFetchMock };
});

const SETUP = {
  secret: 'JBSWY3DPEHPK3PXP',
  otpauthUri: 'otpauth://totp/Zero:jane?secret=JBSWY3DPEHPK3PXP&issuer=Zero',
};
const RECOVERY = {
  recoveryCodes: ['AAAA-1111', 'BBBB-2222', 'CCCC-3333', 'DDDD-4444'],
};

beforeEach(() => {
  apiFetchMock.mockReset();
  toastSuccessMock.mockReset();
  toastErrorMock.mockReset();
  writeTextMock.mockReset();
  writeTextMock.mockResolvedValue(undefined);
  localStorage.clear();
});

afterEach(async () => {
  // `input-otp` schedules selection-sync timers (≤50ms) that it never clears on
  // unmount; flush them while jsdom is still alive so none fire after the
  // environment is torn down (which would throw "window is not defined").
  await new Promise((resolve) => setTimeout(resolve, 60));
});

describe('TwoFactorCard enrollment', () => {
  it('runs setup → confirm → enable and shows the secret, QR and recovery codes once', async () => {
    const user = userEvent.setup();
    apiFetchMock.mockImplementation((path: string) => {
      if (path === '/api/profile/two-factor/setup') {
        return Promise.resolve(SETUP);
      }
      if (path === '/api/profile/two-factor/enable') {
        return Promise.resolve(RECOVERY);
      }
      return Promise.resolve(undefined);
    });

    renderWithProviders(<TwoFactorCard />);

    await user.click(
      screen.getByRole('button', { name: 'Enable two-factor authentication' }),
    );

    // Setup was requested and its one-time secret + QR are shown.
    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/profile/two-factor/setup',
        expect.objectContaining({ method: 'POST' }),
      ),
    );
    expect(await screen.findByTestId('two-factor-secret')).toHaveTextContent(
      'JBSWY3DPEHPK3PXP',
    );
    expect(
      screen.getByLabelText('Two-factor setup QR code'),
    ).toBeInTheDocument();

    // Confirm with a 6-digit code from the OTP entry.
    await user.type(
      screen.getByLabelText('Enter the 6-digit code to confirm'),
      '654321',
    );
    await user.click(
      screen.getByRole('button', { name: 'Confirm and enable' }),
    );

    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/profile/two-factor/enable',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ code: '654321' }),
        }),
      ),
    );

    // Recovery codes render once, with the save-now warning.
    const codes = await screen.findByTestId('recovery-codes');
    expect(within(codes).getByText('AAAA-1111')).toBeInTheDocument();
    expect(within(codes).getByText('DDDD-4444')).toBeInTheDocument();
    expect(
      screen.getByText('Save your recovery codes'),
    ).toBeInTheDocument();

    // Copy-all pushes every code to the clipboard. `userEvent.setup()` installs
    // its own clipboard stub, so override it here (after setup) to capture the
    // exact payload our hook writes.
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: writeTextMock },
      configurable: true,
    });
    await user.click(screen.getByRole('button', { name: 'Copy all' }));
    expect(writeTextMock).toHaveBeenCalledWith(
      'AAAA-1111\nBBBB-2222\nCCCC-3333\nDDDD-4444',
    );
  });
});

describe('TwoFactorCard management', () => {
  it('requires the current password before disabling', async () => {
    const user = userEvent.setup();
    apiFetchMock.mockResolvedValue(undefined);

    renderWithProviders(<TwoFactorCard />);

    // Bridge into the manage view (no readable enabled-state on load).
    await user.click(
      screen.getByRole('button', { name: 'Already set up? Manage it' }),
    );
    await user.click(
      screen.getByRole('button', { name: 'Disable two-factor' }),
    );

    // Dialog is up; the confirm button is inert until a password is entered,
    // and nothing has hit the disable endpoint yet.
    const confirm = await screen.findByRole('button', { name: 'Disable' });
    expect(confirm).toBeDisabled();
    expect(
      apiFetchMock.mock.calls.some(
        (c) => c[0] === '/api/profile/two-factor/disable',
      ),
    ).toBe(false);

    await user.type(screen.getByLabelText('Current password'), 'secret-pw');
    await user.click(screen.getByRole('button', { name: 'Disable' }));

    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/profile/two-factor/disable',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ password: 'secret-pw' }),
        }),
      ),
    );

    // Back to the idle view after a successful disable.
    expect(
      await screen.findByRole('button', {
        name: 'Enable two-factor authentication',
      }),
    ).toBeInTheDocument();
  });

  it('regenerates recovery codes behind a password prompt and shows them once', async () => {
    const user = userEvent.setup();
    apiFetchMock.mockImplementation((path: string) => {
      if (path === '/api/profile/two-factor/recovery-codes/regenerate') {
        return Promise.resolve(RECOVERY);
      }
      return Promise.resolve(undefined);
    });

    renderWithProviders(<TwoFactorCard />);

    await user.click(
      screen.getByRole('button', { name: 'Already set up? Manage it' }),
    );
    await user.click(
      screen.getByRole('button', { name: 'Regenerate recovery codes' }),
    );

    await user.type(screen.getByLabelText('Current password'), 'secret-pw');
    await user.click(screen.getByRole('button', { name: 'Regenerate' }));

    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/profile/two-factor/recovery-codes/regenerate',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ password: 'secret-pw' }),
        }),
      ),
    );

    const codes = await screen.findByTestId('recovery-codes');
    expect(within(codes).getByText('AAAA-1111')).toBeInTheDocument();
  });
});
