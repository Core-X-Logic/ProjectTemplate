import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SubscriptionsListPage } from '@/features/subscriptions/pages/subscriptions-list';
import type { PageSubscriptionDto } from '@/features/subscriptions/types';
import { renderWithProviders, screen } from '@/test/utils';

/**
 * Subscriptions list behaviour tests (CONTRACT-phase5.md §A.4).
 *
 * Covers the status-badge rendering across the lifecycle states and the RBAC
 * wiring of the row actions (`subscriptions.manage` for assign/activate/cancel,
 * `tenantfeatures.manage` for the override panel).
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

function grant(permissions: string[]): void {
  useAuthMock.mockReturnValue({
    user: { id: '1', username: 'host-admin' },
    permissions,
    roles: [],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  });
}

const subscriptionsPage: PageSubscriptionDto = {
  content: [
    {
      id: 10,
      tenantId: 1,
      tenantName: 'acme',
      editionId: 2,
      editionName: 'standard',
      editionDisplayName: 'Standard',
      status: 'ACTIVE',
      billingPeriod: 'MONTHLY',
      currentPeriodEndAt: '2026-09-01T00:00:00Z',
    },
    {
      id: 11,
      tenantId: 2,
      tenantName: 'globex',
      editionId: 2,
      editionName: 'standard',
      editionDisplayName: 'Standard',
      status: 'TRIALING',
      billingPeriod: 'ANNUAL',
      currentPeriodEndAt: '2026-08-15T00:00:00Z',
    },
    {
      id: 12,
      tenantId: 3,
      tenantName: 'initech',
      editionId: 1,
      editionName: 'free',
      editionDisplayName: 'Free',
      status: 'EXPIRED',
    },
    {
      id: 13,
      tenantId: 4,
      tenantName: 'umbrella',
      status: 'PENDING_PAYMENT',
    },
  ],
  totalElements: 4,
  totalPages: 1,
  number: 0,
  size: 10,
  numberOfElements: 4,
  first: true,
  last: true,
  empty: false,
};

beforeEach(() => {
  apiFetchMock.mockReset();
  useAuthMock.mockReset();
  localStorage.clear();

  apiFetchMock.mockImplementation((path: string) => {
    if (path.startsWith('/api/subscriptions')) {
      return Promise.resolve(subscriptionsPage);
    }
    return Promise.resolve(undefined);
  });
});

describe('SubscriptionsListPage', () => {
  it('renders one row per tenant with a localized status badge', async () => {
    grant(['subscriptions.read']);

    renderWithProviders(<SubscriptionsListPage />, { route: '/subscriptions' });

    expect(await screen.findByText('acme')).toBeInTheDocument();
    expect(screen.getByText('globex')).toBeInTheDocument();
    expect(screen.getByText('initech')).toBeInTheDocument();
    expect(screen.getByText('umbrella')).toBeInTheDocument();

    // Every lifecycle state maps onto its own localized badge label.
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByText('Trial')).toBeInTheDocument();
    expect(screen.getByText('Expired')).toBeInTheDocument();
    expect(screen.getByText('Pending payment')).toBeInTheDocument();

    // Billing period is localized too; the tenant without one renders a dash.
    expect(screen.getByText('Monthly')).toBeInTheDocument();
    expect(screen.getByText('Annual')).toBeInTheDocument();

    expect(apiFetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/subscriptions?'),
    );
  });

  it('shows an error state instead of the grid when the request fails', async () => {
    grant(['subscriptions.read']);
    apiFetchMock.mockRejectedValue(new Error('boom'));

    renderWithProviders(<SubscriptionsListPage />, { route: '/subscriptions' });

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Subscriptions could not be loaded.',
    );
  });
});

describe('SubscriptionsListPage row actions (RBAC)', () => {
  async function openRowMenu(rowIndex: number): Promise<void> {
    const user = userEvent.setup();
    const triggers = await screen.findAllByRole('button', {
      name: 'Open subscription actions',
    });
    await user.click(triggers[rowIndex]);
  }

  it('shows assign / activate / cancel when subscriptions.manage is granted', async () => {
    grant(['subscriptions.read', 'subscriptions.manage']);

    renderWithProviders(<SubscriptionsListPage />, { route: '/subscriptions' });
    await screen.findByText('acme');
    await openRowMenu(0);

    expect(
      await screen.findByRole('menuitem', { name: 'Assign edition' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('menuitem', { name: 'Activate' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('menuitem', { name: 'Cancel subscription' }),
    ).toBeInTheDocument();
    // The override panel is a separate permission and stays hidden.
    expect(
      screen.queryByRole('menuitem', { name: 'Feature overrides' }),
    ).not.toBeInTheDocument();
  });

  it('hides every write action when only subscriptions.read is held', async () => {
    grant(['subscriptions.read']);

    renderWithProviders(<SubscriptionsListPage />, { route: '/subscriptions' });
    await screen.findByText('acme');
    await openRowMenu(0);

    expect(
      screen.queryByRole('menuitem', { name: 'Assign edition' }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('menuitem', { name: 'Activate' }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('menuitem', { name: 'Cancel subscription' }),
    ).not.toBeInTheDocument();
  });

  it('shows the feature-override action only with tenantfeatures.manage', async () => {
    grant(['subscriptions.read', 'tenantfeatures.manage']);

    renderWithProviders(<SubscriptionsListPage />, { route: '/subscriptions' });
    await screen.findByText('acme');
    await openRowMenu(0);

    expect(
      await screen.findByRole('menuitem', { name: 'Feature overrides' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('menuitem', { name: 'Assign edition' }),
    ).not.toBeInTheDocument();
  });
});
