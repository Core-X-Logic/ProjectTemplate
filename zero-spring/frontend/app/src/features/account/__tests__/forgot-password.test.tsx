import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { ForgotPasswordPage } from '@/features/account/pages/forgot-password';
import { renderWithProviders, screen } from '@/test/utils';

/**
 * Forgot-password behaviour tests (U-01, flow 1).
 *
 * `apiFetch` is mocked at the client boundary so the real hook and react-query
 * run end to end. These assertions are about what the USER sees, not about how
 * the request is shaped — with one exception: the enumeration-safety test,
 * where the exact wording IS the behaviour under test.
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

describe('ForgotPasswordPage', () => {
  it('sends the request and confirms without revealing whether the account exists', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ForgotPasswordPage />, {
      route: '/account/forgot-password',
    });

    await user.type(
      screen.getByLabelText('Username or email'),
      'someone@example.com',
    );
    await user.click(screen.getByRole('button', { name: 'Send reset code' }));

    // The confirmation panel replaces the form.
    expect(await screen.findByText('Check your inbox')).toBeInTheDocument();

    // Enumeration safety is the point of this flow: the backend answers 204
    // either way, so the UI must stay conditional ("if an account matches")
    // and must never echo the address back as a confirmed recipient.
    const confirmation = screen.getByText(/If an account matches/i);
    expect(confirmation).toBeInTheDocument();
    expect(confirmation).not.toHaveTextContent('someone@example.com');
    expect(screen.queryByText(/we sent/i)).not.toBeInTheDocument();
  });

  it('posts the trimmed identifier and omits an empty tenant', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ForgotPasswordPage />, {
      route: '/account/forgot-password',
    });

    await user.type(screen.getByLabelText('Username or email'), '  admin  ');
    await user.click(screen.getByRole('button', { name: 'Send reset code' }));

    await screen.findByText('Check your inbox');
    expect(apiFetchMock).toHaveBeenCalledWith(
      '/api/account/forgot-password',
      expect.objectContaining({
        method: 'POST',
        // An empty tenant must not be sent as '': the backend treats a blank
        // tenant as "host scope" only when the field is absent/blank.
        body: JSON.stringify({ usernameOrEmail: 'admin' }),
      }),
    );
  });

  it('sends the tenant when one is entered', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ForgotPasswordPage />, {
      route: '/account/forgot-password',
    });

    await user.type(screen.getByLabelText('Username or email'), 'admin');
    await user.type(screen.getByLabelText('Tenant'), 'acme');
    await user.click(screen.getByRole('button', { name: 'Send reset code' }));

    await screen.findByText('Check your inbox');
    expect(apiFetchMock).toHaveBeenCalledWith(
      '/api/account/forgot-password',
      expect.objectContaining({
        body: JSON.stringify({ usernameOrEmail: 'admin', tenant: 'acme' }),
      }),
    );
  });

  it('requires an identifier before sending anything', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ForgotPasswordPage />, {
      route: '/account/forgot-password',
    });

    await user.click(screen.getByRole('button', { name: 'Send reset code' }));

    expect(
      await screen.findByText('This field is required.'),
    ).toBeInTheDocument();
    expect(apiFetchMock).not.toHaveBeenCalled();
  });

  it('keeps the form and shows the problem detail when the request fails', async () => {
    const user = userEvent.setup();
    apiFetchMock.mockRejectedValue(
      new ApiError(503, { detail: 'Mail service unavailable' }),
    );

    renderWithProviders(<ForgotPasswordPage />, {
      route: '/account/forgot-password',
    });

    await user.type(screen.getByLabelText('Username or email'), 'admin');
    await user.click(screen.getByRole('button', { name: 'Send reset code' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Mail service unavailable',
    );
    // The success panel must not appear on a failure.
    expect(screen.queryByText('Check your inbox')).not.toBeInTheDocument();
  });
});
