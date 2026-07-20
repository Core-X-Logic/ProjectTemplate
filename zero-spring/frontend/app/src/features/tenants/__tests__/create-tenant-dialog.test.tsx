import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CreateTenantDialog } from '@/features/tenants/components/create-tenant-dialog';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Create-tenant dialog behaviour tests for the admin-bootstrap contract
 * (backend 20247d5, closing Issue #1).
 *
 * `POST /api/tenants` now REQUIRES `adminEmail`, optionally accepts
 * `adminPassword`, and answers with `CreateTenantResponse` whose
 * `generatedAdminPassword` is a ONE-TIME disclosure (present only when the
 * server generated the credential). These tests pin:
 *
 *  - no request leaves without a valid admin email;
 *  - the request body carries `adminEmail` and omits a blank `adminPassword`;
 *  - the generated password is revealed exactly once, is copyable, and is gone
 *    after the dialog closes.
 */

const { apiFetchMock, useAuthMock } = vi.hoisted(() => ({
  apiFetchMock: vi.fn(),
  useAuthMock: vi.fn(),
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), message: vi.fn() },
}));

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>();
  return { ...actual, apiFetch: apiFetchMock };
});

vi.mock('@/providers/auth-provider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: useAuthMock,
}));

beforeEach(() => {
  apiFetchMock.mockReset();
  localStorage.clear();
  useAuthMock.mockReturnValue({
    user: { id: '1', username: 'host-admin' },
    permissions: ['tenants.manage'],
    roles: [],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  });
});

/** Parses the JSON body of the (single) POST /api/tenants call. */
function postedBody(): Record<string, unknown> {
  const call = apiFetchMock.mock.calls.find(
    (c) => c[0] === '/api/tenants' && c[1]?.method === 'POST',
  );
  expect(call).toBeDefined();
  return JSON.parse(call![1].body as string);
}

async function fillBaseFields(
  user: ReturnType<typeof userEvent.setup>,
): Promise<void> {
  await user.type(await screen.findByLabelText('Name'), 'initech');
  await user.type(screen.getByLabelText('Display name'), 'Initech LLC');
}

describe('CreateTenantDialog admin bootstrap', () => {
  it('requires an admin email: shows a localized error and sends no request', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateTenantDialog open onOpenChange={vi.fn()} />,
    );

    await fillBaseFields(user);
    // Admin email left empty on purpose.
    await user.click(screen.getByRole('button', { name: 'Create' }));

    expect(
      await screen.findByText('This field is required.'),
    ).toBeInTheDocument();
    expect(
      apiFetchMock.mock.calls.some((call) => call[1]?.method === 'POST'),
    ).toBe(false);
  });

  it('rejects a malformed admin email before sending it', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateTenantDialog open onOpenChange={vi.fn()} />,
    );

    await fillBaseFields(user);
    await user.type(screen.getByLabelText('Admin email'), 'not-an-email');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    expect(
      await screen.findByText('Enter a valid email address.'),
    ).toBeInTheDocument();
    expect(
      apiFetchMock.mock.calls.some((call) => call[1]?.method === 'POST'),
    ).toBe(false);
  });

  it('sends adminEmail and omits a blank adminPassword (server generates one)', async () => {
    const user = userEvent.setup();
    apiFetchMock.mockResolvedValue({
      id: 7,
      name: 'initech',
      displayName: 'Initech LLC',
      active: true,
      createdAt: '2026-07-20T10:00:00Z',
      generatedAdminPassword: 'Xk9-generated-2!',
    });

    renderWithProviders(
      <CreateTenantDialog open onOpenChange={vi.fn()} />,
    );

    await fillBaseFields(user);
    await user.type(
      screen.getByLabelText('Admin email'),
      'admin@initech.com',
    );
    await user.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => {
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/tenants',
        expect.objectContaining({ method: 'POST' }),
      );
    });
    const body = postedBody();
    expect(body).toEqual({
      name: 'initech',
      displayName: 'Initech LLC',
      adminEmail: 'admin@initech.com',
    });
    // Blank password must be OMITTED, not sent as "" — "" would be rejected by
    // the server-side password policy instead of triggering generation.
    expect(body).not.toHaveProperty('adminPassword');
  });

  it('sends an operator-supplied adminPassword and closes without a reveal', async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();
    apiFetchMock.mockResolvedValue({
      id: 7,
      name: 'initech',
      displayName: 'Initech LLC',
      active: true,
      createdAt: '2026-07-20T10:00:00Z',
      // Operator chose the password, so the server discloses nothing.
    });

    renderWithProviders(
      <CreateTenantDialog open onOpenChange={onOpenChange} />,
    );

    await fillBaseFields(user);
    await user.type(
      screen.getByLabelText('Admin email'),
      'admin@initech.com',
    );
    await user.type(
      screen.getByLabelText('Admin password'),
      'Chosen-By-0perator!',
    );
    await user.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => {
      expect(onOpenChange).toHaveBeenCalledWith(false);
    });
    expect(postedBody()).toMatchObject({
      adminEmail: 'admin@initech.com',
      adminPassword: 'Chosen-By-0perator!',
    });
    // Nothing to reveal: the one-time screen must not appear.
    expect(screen.queryByText('Shown only once')).not.toBeInTheDocument();
  });

  it('reveals a generated password once, monospace, with a working copy control', async () => {
    const user = userEvent.setup();
    apiFetchMock.mockResolvedValue({
      id: 7,
      name: 'initech',
      displayName: 'Initech LLC',
      active: true,
      createdAt: '2026-07-20T10:00:00Z',
      generatedAdminPassword: 'Xk9-generated-2!',
    });

    renderWithProviders(
      <CreateTenantDialog open onOpenChange={vi.fn()} />,
    );

    await fillBaseFields(user);
    await user.type(
      screen.getByLabelText('Admin email'),
      'admin@initech.com',
    );
    await user.click(screen.getByRole('button', { name: 'Create' }));

    // Result state: the password itself, rendered monospace…
    const password = await screen.findByText('Xk9-generated-2!');
    expect(password).toHaveClass('font-mono');
    // …with the one-time warning…
    expect(screen.getByText('Shown only once')).toBeInTheDocument();
    expect(
      screen.getByText(/will not be shown again/i),
    ).toBeInTheDocument();
    // …and the form gone: this screen replaces it.
    expect(screen.queryByLabelText('Name')).not.toBeInTheDocument();

    // The copy control puts the password on the clipboard (userEvent stubs
    // navigator.clipboard, so the spy observes the real call path).
    const writeText = vi.spyOn(navigator.clipboard, 'writeText');
    await user.click(
      screen.getByRole('button', { name: 'Copy password' }),
    );
    expect(writeText).toHaveBeenCalledWith('Xk9-generated-2!');
  });

  it('does not show the password again after the dialog closes and reopens', async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();
    apiFetchMock.mockResolvedValue({
      id: 7,
      name: 'initech',
      displayName: 'Initech LLC',
      active: true,
      createdAt: '2026-07-20T10:00:00Z',
      generatedAdminPassword: 'Xk9-generated-2!',
    });

    const { rerender } = renderWithProviders(
      <CreateTenantDialog open onOpenChange={onOpenChange} />,
    );

    await fillBaseFields(user);
    await user.type(
      screen.getByLabelText('Admin email'),
      'admin@initech.com',
    );
    await user.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByText('Xk9-generated-2!');

    // Operator closes the reveal via the footer button. (DialogContent's
    // built-in X is also accessibly named "Close" — data-slot="dialog-close" —
    // so the footer control is the one WITHOUT that slot marker.)
    const footerClose = screen
      .getAllByRole('button', { name: 'Close' })
      .find((b) => b.getAttribute('data-slot') !== 'dialog-close');
    expect(footerClose).toBeDefined();
    await user.click(footerClose!);
    expect(onOpenChange).toHaveBeenCalledWith(false);

    // …and a later reopen starts from a blank form: the one-time credential is
    // never rendered again (it lives nowhere but the closed dialog's state).
    rerender(<CreateTenantDialog open={false} onOpenChange={onOpenChange} />);
    rerender(<CreateTenantDialog open onOpenChange={onOpenChange} />);

    expect(await screen.findByLabelText('Name')).toHaveValue('');
    expect(screen.queryByText('Xk9-generated-2!')).not.toBeInTheDocument();
  });
});
