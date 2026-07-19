import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { ProfilePage } from '@/features/profile/pages/profile';
import type { ProfileDto } from '@/features/profile/types';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Profile behaviour tests (U-01, flow 2).
 *
 * `apiFetch` is mocked at the client boundary so the real hooks and react-query
 * run end to end. `useAuth` is stubbed because the page renders inside the
 * authenticated shell.
 */

const { apiFetchMock, useAuthMock, toastErrorMock, toastSuccessMock } =
  vi.hoisted(() => ({
    apiFetchMock: vi.fn(),
    useAuthMock: vi.fn(),
    toastErrorMock: vi.fn(),
    toastSuccessMock: vi.fn(),
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

vi.mock('@/providers/auth-provider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: useAuthMock,
}));

const profile: ProfileDto = {
  id: 7,
  username: 'jane',
  name: 'Jane',
  surname: 'Doe',
  email: 'jane@example.com',
  phoneNumber: '+90 555 000 0000',
  emailConfirmed: true,
  roles: ['Admin', 'Auditor'],
  tenantId: 3,
};

beforeEach(() => {
  apiFetchMock.mockReset();
  useAuthMock.mockReset();
  toastErrorMock.mockReset();
  toastSuccessMock.mockReset();
  localStorage.clear();

  useAuthMock.mockReturnValue({
    user: { id: '7', username: 'jane' },
    permissions: [],
    roles: [],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  });

  apiFetchMock.mockImplementation((path: string) => {
    if (path === '/api/profile') {
      return Promise.resolve(profile);
    }
    return Promise.resolve(undefined);
  });
});

describe('ProfilePage details', () => {
  it('renders the fetched profile into the form and shows read-only identity', async () => {
    renderWithProviders(<ProfilePage />, { route: '/profile' });

    expect(await screen.findByLabelText('First name')).toHaveValue('Jane');
    expect(screen.getByLabelText('Last name')).toHaveValue('Doe');
    expect(screen.getByLabelText('Email')).toHaveValue('jane@example.com');
    expect(screen.getByLabelText('Phone number')).toHaveValue(
      '+90 555 000 0000',
    );

    // Username is display-only: UpdateProfileRequest carries no username field,
    // so an editable input would be a control that silently does nothing.
    expect(screen.getByText('jane')).toBeInTheDocument();
    expect(screen.queryByLabelText('Username')).not.toBeInTheDocument();

    // Roles and confirmation status come straight off the DTO.
    expect(screen.getByText('Admin')).toBeInTheDocument();
    expect(screen.getByText('Auditor')).toBeInTheDocument();
    expect(screen.getByText('Email confirmed')).toBeInTheDocument();
  });

  it('saves edited details via PUT /api/profile', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ProfilePage />, { route: '/profile' });

    const nameField = await screen.findByLabelText('First name');
    await user.clear(nameField);
    await user.type(nameField, 'Janet');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => {
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/profile',
        expect.objectContaining({ method: 'PUT' }),
      );
    });

    const putCall = apiFetchMock.mock.calls.find(
      (call) => call[1]?.method === 'PUT',
    );
    expect(JSON.parse(putCall?.[1]?.body as string)).toEqual({
      name: 'Janet',
      surname: 'Doe',
      email: 'jane@example.com',
      phoneNumber: '+90 555 000 0000',
    });
  });

  it('rejects a malformed email before the round trip', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ProfilePage />, { route: '/profile' });

    const emailField = await screen.findByLabelText('Email');
    await user.clear(emailField);
    await user.type(emailField, 'not-an-email');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(
      await screen.findByText('Enter a valid email address.'),
    ).toBeInTheDocument();
    expect(
      apiFetchMock.mock.calls.some((call) => call[1]?.method === 'PUT'),
    ).toBe(false);
  });

  it('shows an error state when the profile cannot be loaded', async () => {
    apiFetchMock.mockRejectedValue(new Error('boom'));

    renderWithProviders(<ProfilePage />, { route: '/profile' });

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Your profile could not be loaded.',
    );
  });
});

describe('ProfilePage change password', () => {
  it('posts the current and new password, then clears the fields', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ProfilePage />, { route: '/profile' });

    await screen.findByLabelText('First name');

    await user.type(screen.getByLabelText('Current password'), 'old-password');
    await user.type(screen.getByLabelText('New password'), 'new-password-1');
    await user.type(
      screen.getByLabelText('Confirm new password'),
      'new-password-1',
    );
    await user.click(
      screen.getByRole('button', { name: 'Change password' }),
    );

    await waitFor(() => {
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/profile/change-password',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({
            currentPassword: 'old-password',
            newPassword: 'new-password-1',
          }),
        }),
      );
    });

    // A plaintext password must not survive in form state after success.
    await waitFor(() => {
      expect(screen.getByLabelText('Current password')).toHaveValue('');
    });
    expect(screen.getByLabelText('New password')).toHaveValue('');
  });

  it('blocks the request when the confirmation does not match', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ProfilePage />, { route: '/profile' });

    await screen.findByLabelText('First name');

    await user.type(screen.getByLabelText('Current password'), 'old-password');
    await user.type(screen.getByLabelText('New password'), 'new-password-1');
    await user.type(
      screen.getByLabelText('Confirm new password'),
      'new-password-2',
    );
    await user.click(
      screen.getByRole('button', { name: 'Change password' }),
    );

    expect(
      await screen.findByText('The passwords do not match.'),
    ).toBeInTheDocument();
    expect(
      apiFetchMock.mock.calls.some(
        (call) => call[0] === '/api/profile/change-password',
      ),
    ).toBe(false);
  });

  it('surfaces the backend reason when the current password is wrong', async () => {
    const user = userEvent.setup();
    apiFetchMock.mockImplementation((path: string) => {
      if (path === '/api/profile') {
        return Promise.resolve(profile);
      }
      return Promise.reject(
        new ApiError(400, { detail: 'Current password is incorrect' }),
      );
    });

    renderWithProviders(<ProfilePage />, { route: '/profile' });
    await screen.findByLabelText('First name');

    await user.type(screen.getByLabelText('Current password'), 'wrong');
    await user.type(screen.getByLabelText('New password'), 'new-password-1');
    await user.type(
      screen.getByLabelText('Confirm new password'),
      'new-password-1',
    );
    await user.click(
      screen.getByRole('button', { name: 'Change password' }),
    );

    // Policy, history and wrong-current-password are all 400s; only the
    // ProblemDetail `detail` tells the user which one they hit.
    await waitFor(() => {
      expect(toastErrorMock).toHaveBeenCalledWith(
        'Current password is incorrect',
      );
    });
  });
});
