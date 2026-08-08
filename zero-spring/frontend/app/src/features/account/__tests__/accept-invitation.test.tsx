import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { AcceptInvitationPage } from '@/features/account/pages/accept-invitation';
import { renderWithProviders, screen } from '@/test/utils';

/**
 * Accept-invitation behaviour tests.
 *
 * The `?token=` parameter is not incidental: `EmailTemplateService.invitation`
 * mails `{baseUrl}/account/accept-invitation?token=…`, so "the token from the
 * link drives the screen" is a contract with the backend, not a convenience.
 * The username is DISPLAYED (admin-fixed at invite time), never collected.
 */

const { apiFetchMock } = vi.hoisted(() => ({ apiFetchMock: vi.fn() }));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), message: vi.fn() },
}));

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>();
  return { ...actual, apiFetch: apiFetchMock };
});

function mockBackend({
  info = { username: 'invited-user', email: 'invited@acme.io', status: 'PENDING' },
  acceptError,
}: {
  info?: unknown;
  acceptError?: unknown;
} = {}) {
  apiFetchMock.mockImplementation((url: string) => {
    if (url.startsWith('/api/account/invitation')) {
      return info instanceof Error
        ? Promise.reject(info)
        : Promise.resolve(info);
    }
    if (url === '/api/account/accept-invitation') {
      return acceptError ? Promise.reject(acceptError) : Promise.resolve(undefined);
    }
    return Promise.reject(new Error('unexpected call: ' + url));
  });
}

beforeEach(() => {
  apiFetchMock.mockReset();
  localStorage.clear();
});

describe('AcceptInvitationPage', () => {
  it('shows the admin-fixed username from the token and submits token + chosen password', async () => {
    const user = userEvent.setup();
    mockBackend();
    renderWithProviders(<AcceptInvitationPage />, {
      route: '/account/accept-invitation?token=emailed-token-123',
    });

    // The username arrives via the token lookup and is read-only.
    expect(await screen.findByLabelText('Username')).toHaveValue('invited-user');
    expect(screen.getByLabelText('Username')).toBeDisabled();

    await user.type(screen.getByLabelText('Password'), 'correct-horse');
    await user.type(screen.getByLabelText('Confirm password'), 'correct-horse');
    await user.click(screen.getByRole('button', { name: 'Activate account' }));

    expect(await screen.findByText('Account activated')).toBeInTheDocument();
    expect(apiFetchMock).toHaveBeenCalledWith(
      '/api/account/accept-invitation',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          token: 'emailed-token-123',
          password: 'correct-horse',
        }),
      }),
    );
  });

  it('sends an already-accepted invitation straight to sign-in without a form', async () => {
    mockBackend({ info: { status: 'ACCEPTED' } });
    renderWithProviders(<AcceptInvitationPage />, {
      route: '/account/accept-invitation?token=used-token',
    });

    expect(
      await screen.findByText('Invitation already used'),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'Go to sign in' }),
    ).toBeInTheDocument();
  });

  it('shows the invalid panel when the token is unknown or expired', async () => {
    mockBackend({
      info: new ApiError(400, { detail: 'Invalid or expired invitation' }),
    });
    renderWithProviders(<AcceptInvitationPage />, {
      route: '/account/accept-invitation?token=stale-token',
    });

    expect(await screen.findByText('Invitation not valid')).toBeInTheDocument();
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
  });

  it('refuses to render a form when the link carries no token', () => {
    renderWithProviders(<AcceptInvitationPage />, {
      route: '/account/accept-invitation',
    });

    expect(screen.getByText('Invitation not valid')).toBeInTheDocument();
    expect(
      screen.getByText(
        'This link is missing its invitation token. Please open the link from your email again.',
      ),
    ).toBeInTheDocument();
    // Without a token there is nothing to look up, so no request may be fired.
    expect(apiFetchMock).not.toHaveBeenCalled();
  });

  it('blocks the request when the two passwords differ', async () => {
    const user = userEvent.setup();
    mockBackend();
    renderWithProviders(<AcceptInvitationPage />, {
      route: '/account/accept-invitation?token=abc',
    });

    await screen.findByLabelText('Username');
    await user.type(screen.getByLabelText('Password'), 'correct-horse');
    await user.type(screen.getByLabelText('Confirm password'), 'correct-mouse');
    await user.click(screen.getByRole('button', { name: 'Activate account' }));

    expect(
      await screen.findByText('The passwords do not match.'),
    ).toBeInTheDocument();
    expect(apiFetchMock).not.toHaveBeenCalledWith(
      '/api/account/accept-invitation',
      expect.anything(),
    );
  });

  it('surfaces the backend reason when the accept is refused', async () => {
    const user = userEvent.setup();
    mockBackend({
      acceptError: new ApiError(400, {
        detail: 'The tenant has reached the maximum number of users allowed by its package (5)',
      }),
    });
    renderWithProviders(<AcceptInvitationPage />, {
      route: '/account/accept-invitation?token=abc',
    });

    await screen.findByLabelText('Username');
    await user.type(screen.getByLabelText('Password'), 'correct-horse');
    await user.type(screen.getByLabelText('Confirm password'), 'correct-horse');
    await user.click(screen.getByRole('button', { name: 'Activate account' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The tenant has reached the maximum number of users allowed by its package (5)',
    );
    expect(screen.queryByText('Account activated')).not.toBeInTheDocument();
  });
});
