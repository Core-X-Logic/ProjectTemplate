import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { ResetPasswordPage } from '@/features/account/pages/reset-password';
import { renderWithProviders, screen } from '@/test/utils';

/**
 * Reset-password behaviour tests (U-01, flow 1).
 *
 * The `?code=` parameter is not incidental: `EmailTemplateService` mails
 * `{baseUrl}/account/reset-password?code=…`, so "the code from the link is
 * picked up" is a contract with the backend, not a convenience.
 */

const { apiFetchMock } = vi.hoisted(() => ({ apiFetchMock: vi.fn() }));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), message: vi.fn() },
}));

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>();
  return { ...actual, apiFetch: apiFetchMock };
});

beforeEach(() => {
  apiFetchMock.mockReset();
  localStorage.clear();
  apiFetchMock.mockResolvedValue(undefined);
});

describe('ResetPasswordPage', () => {
  it('picks the code up from the emailed link and submits it with the new password', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ResetPasswordPage />, {
      route: '/account/reset-password?code=emailed-code-123',
    });

    // The code arrived via the link, so the user only picks a password.
    expect(screen.getByLabelText('Reset code')).toHaveValue(
      'emailed-code-123',
    );
    expect(screen.getByText('The code was taken from your link.')).toBeInTheDocument();

    await user.type(screen.getByLabelText('New password'), 'correct-horse');
    await user.type(
      screen.getByLabelText('Confirm new password'),
      'correct-horse',
    );
    await user.click(
      screen.getByRole('button', { name: 'Set new password' }),
    );

    expect(await screen.findByText('Password updated')).toBeInTheDocument();
    expect(apiFetchMock).toHaveBeenCalledWith(
      '/api/account/reset-password',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          resetCode: 'emailed-code-123',
          newPassword: 'correct-horse',
        }),
      }),
    );
  });

  it('lets the user paste a code when the link carried none', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ResetPasswordPage />, {
      route: '/account/reset-password',
    });

    const codeField = screen.getByLabelText('Reset code');
    expect(codeField).toHaveValue('');
    expect(
      screen.queryByText('The code was taken from your link.'),
    ).not.toBeInTheDocument();

    await user.type(codeField, 'pasted-code');
    await user.type(screen.getByLabelText('New password'), 'correct-horse');
    await user.type(
      screen.getByLabelText('Confirm new password'),
      'correct-horse',
    );
    await user.click(
      screen.getByRole('button', { name: 'Set new password' }),
    );

    expect(await screen.findByText('Password updated')).toBeInTheDocument();
  });

  it('blocks the request when the two passwords differ', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ResetPasswordPage />, {
      route: '/account/reset-password?code=abc',
    });

    await user.type(screen.getByLabelText('New password'), 'correct-horse');
    await user.type(
      screen.getByLabelText('Confirm new password'),
      'correct-mouse',
    );
    await user.click(
      screen.getByRole('button', { name: 'Set new password' }),
    );

    expect(
      await screen.findByText('The passwords do not match.'),
    ).toBeInTheDocument();
    expect(apiFetchMock).not.toHaveBeenCalled();
  });

  it('rejects a password below the endpoint minimum without a round trip', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ResetPasswordPage />, {
      route: '/account/reset-password?code=abc',
    });

    await user.type(screen.getByLabelText('New password'), 'abc');
    await user.type(screen.getByLabelText('Confirm new password'), 'abc');
    await user.click(
      screen.getByRole('button', { name: 'Set new password' }),
    );

    expect(
      await screen.findByText('Use at least 6 characters.'),
    ).toBeInTheDocument();
    expect(apiFetchMock).not.toHaveBeenCalled();
  });

  it('shows the backend reason when the code is invalid or already used', async () => {
    const user = userEvent.setup();
    apiFetchMock.mockRejectedValue(
      new ApiError(400, { detail: 'Invalid or expired reset code' }),
    );

    renderWithProviders(<ResetPasswordPage />, {
      route: '/account/reset-password?code=stale',
    });

    await user.type(screen.getByLabelText('New password'), 'correct-horse');
    await user.type(
      screen.getByLabelText('Confirm new password'),
      'correct-horse',
    );
    await user.click(
      screen.getByRole('button', { name: 'Set new password' }),
    );

    // The server's ProblemDetail reaches the user verbatim — a stale code and a
    // policy violation are both 400s and only `detail` tells them apart.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Invalid or expired reset code',
    );
    expect(screen.queryByText('Password updated')).not.toBeInTheDocument();
  });
});
