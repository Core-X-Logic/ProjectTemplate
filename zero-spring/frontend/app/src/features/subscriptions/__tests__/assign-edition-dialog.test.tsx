import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AssignEditionDialog } from '@/features/subscriptions/components/assign-edition-dialog';
import type { PageEditionDto } from '@/features/editions/types';
import type { SubscriptionDto } from '@/features/subscriptions/types';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Package assignment dialog behaviour tests (CONTRACT-phase5.md §A.4).
 *
 * Drives the real selectors and asserts the exact `PUT
 * /api/subscriptions/{tenantId}/edition` payload — edition id, billing period
 * and trial flag — since that request is what snapshots the price server-side.
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

const editionsPage: PageEditionDto = {
  content: [
    {
      id: 1,
      name: 'free',
      displayName: 'Free',
      active: true,
      free: true,
      trialDayCount: 0,
    },
    {
      id: 2,
      name: 'standard',
      displayName: 'Standard',
      active: true,
      free: false,
      trialDayCount: 14,
    },
    {
      id: 3,
      name: 'legacy',
      displayName: 'Legacy',
      // Inactive editions must not be offered for assignment.
      active: false,
      free: false,
    },
  ],
  totalElements: 3,
  totalPages: 1,
  number: 0,
  size: 100,
};

const subscription: SubscriptionDto = {
  id: 10,
  tenantId: 42,
  tenantName: 'acme',
  status: 'EXPIRED',
};

beforeEach(() => {
  apiFetchMock.mockReset();
  useAuthMock.mockReset();
  localStorage.clear();

  useAuthMock.mockReturnValue({
    user: { id: '1', username: 'host-admin' },
    permissions: ['subscriptions.read', 'subscriptions.manage'],
    roles: [],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  });

  apiFetchMock.mockImplementation((path: string) => {
    if (path.startsWith('/api/editions')) {
      return Promise.resolve(editionsPage);
    }
    return Promise.resolve({ subscription, events: [] });
  });
});

describe('AssignEditionDialog', () => {
  it('offers only active editions', async () => {
    renderWithProviders(
      <AssignEditionDialog
        open
        onOpenChange={vi.fn()}
        subscription={subscription}
      />,
    );

    const select = await screen.findByLabelText('Edition');
    expect(
      screen.getByRole('option', { name: 'Standard' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Free' })).toBeInTheDocument();
    expect(
      screen.queryByRole('option', { name: 'Legacy' }),
    ).not.toBeInTheDocument();
    expect(select).toHaveValue('');
  });

  it('submits the selected edition, period and trial flag', async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();

    renderWithProviders(
      <AssignEditionDialog
        open
        onOpenChange={onOpenChange}
        subscription={subscription}
      />,
    );

    await user.selectOptions(await screen.findByLabelText('Edition'), '2');
    await user.selectOptions(screen.getByLabelText('Billing period'), 'ANNUAL');
    await user.click(screen.getByLabelText('Start as trial'));

    await user.click(screen.getByRole('button', { name: 'Assign' }));

    await waitFor(() => {
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/subscriptions/42/edition',
        expect.objectContaining({ method: 'PUT' }),
      );
    });

    const call = apiFetchMock.mock.calls.find(
      ([path]) => path === '/api/subscriptions/42/edition',
    );
    expect(JSON.parse(call?.[1]?.body as string)).toEqual({
      editionId: 2,
      billingPeriod: 'ANNUAL',
      trial: true,
    });

    // A successful assignment closes the dialog.
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
  });

  it('does not allow a trial on a free edition (backend rejects it with 400)', async () => {
    const user = userEvent.setup();

    renderWithProviders(
      <AssignEditionDialog
        open
        onOpenChange={vi.fn()}
        subscription={subscription}
      />,
    );

    await user.selectOptions(await screen.findByLabelText('Edition'), '1');

    expect(screen.getByLabelText('Start as trial')).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Assign' }));

    await waitFor(() => {
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/subscriptions/42/edition',
        expect.objectContaining({ method: 'PUT' }),
      );
    });

    const call = apiFetchMock.mock.calls.find(
      ([path]) => path === '/api/subscriptions/42/edition',
    );
    expect(JSON.parse(call?.[1]?.body as string)).toEqual({
      editionId: 1,
      billingPeriod: 'MONTHLY',
      trial: false,
    });
  });

  it('refuses to submit without an edition and surfaces a validation hint', async () => {
    const user = userEvent.setup();

    renderWithProviders(
      <AssignEditionDialog
        open
        onOpenChange={vi.fn()}
        subscription={subscription}
      />,
    );

    await screen.findByLabelText('Edition');
    await user.click(screen.getByRole('button', { name: 'Assign' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Select an edition to continue.',
    );
    expect(apiFetchMock).not.toHaveBeenCalledWith(
      '/api/subscriptions/42/edition',
      expect.anything(),
    );
  });
});
