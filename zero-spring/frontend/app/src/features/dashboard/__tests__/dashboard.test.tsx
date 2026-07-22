import type { ReactNode } from 'react';
import { QueryClient } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { DashboardPage } from '@/features/dashboard/pages/dashboard';
import type { PageAuditLogDto } from '@/features/audit/types';
import type { PageUserDto } from '@/features/users/types';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Dashboard widget-system behaviour tests (FRONTEND-ARCHITECTURE.md §9).
 *
 * The owning features' endpoint modules are mocked (the dashboard has no
 * endpoints of its own), so the REAL hooks + react-query run against fake
 * wire responses. Assertions cover what the user sees:
 *  - KPI values for granted permissions,
 *  - NEGATIVE permission → widget absent AND its query never sent,
 *  - per-widget error + retry (a failing widget never takes the page down),
 *  - per-widget empty states,
 *  - host/tenant context split (subscription vs tenants KPI),
 *  - the permission-filtered quick-actions grid.
 */

const {
  grantedPermissions,
  tenantIdState,
  listUsersMock,
  listRolesMock,
  listTenantsMock,
  getUnreadCountMock,
  getMySubscriptionMock,
  listAuditLogsMock,
  userCountPage,
  recentUsersPage,
  auditPage,
} = vi.hoisted(() => {
  const now = new Date().toISOString();

  const userCountPage: PageUserDto = {
    content: [],
    totalElements: 42,
    totalPages: 42,
    number: 0,
    size: 1,
  };

  const recentUsersPage: PageUserDto = {
    content: [
      { id: 1, username: 'alice.recent', email: 'alice@acme.io', active: true },
      { id: 2, username: 'bob.recent', email: 'bob@acme.io', active: false },
    ],
    totalElements: 42,
    totalPages: 9,
    number: 0,
    size: 5,
  };

  const auditPage: PageAuditLogDto = {
    content: [
      {
        id: 1,
        username: 'audit.alice',
        httpMethod: 'POST',
        methodName: 'createUser',
        httpStatusCode: 200,
        executionDurationMs: 42,
        executionTime: now,
      },
      {
        id: 2,
        username: 'audit.bob',
        httpMethod: 'DELETE',
        methodName: 'deleteRole',
        httpStatusCode: 500,
        executionDurationMs: 130,
        executionTime: now,
      },
    ],
    totalElements: 2,
    totalPages: 1,
    number: 0,
    size: 8,
  };

  return {
    grantedPermissions: { current: [] as string[] },
    tenantIdState: { current: null as number | null },
    listUsersMock: vi.fn(),
    listRolesMock: vi.fn(),
    listTenantsMock: vi.fn(),
    getUnreadCountMock: vi.fn(),
    getMySubscriptionMock: vi.fn(),
    listAuditLogsMock: vi.fn(),
    userCountPage,
    recentUsersPage,
    auditPage,
  };
});

vi.mock('@/providers/auth-provider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: () => ({
    user: {
      id: '1',
      username: 'tester',
      email: 'tester@acme.io',
      tenantId: tenantIdState.current,
      roles: ['admin'],
      permissions: grantedPermissions.current,
    },
    permissions: grantedPermissions.current,
    roles: ['admin'],
    loading: false,
    isImpersonating: false,
    login: vi.fn(),
    verifyTwoFactor: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
    impersonate: vi.fn(),
    backToImpersonator: vi.fn(),
  }),
}));

vi.mock('@/features/users/api', () => ({
  listUsers: listUsersMock,
}));

vi.mock('@/features/roles/api', () => ({
  listRoles: listRolesMock,
}));

vi.mock('@/features/tenants/api', () => ({
  listTenants: listTenantsMock,
}));

vi.mock('@/features/notifications/api', () => ({
  getUnreadCount: getUnreadCountMock,
}));

vi.mock('@/features/subscriptions/api', () => ({
  getMySubscription: getMySubscriptionMock,
}));

vi.mock('@/features/audit/api', () => ({
  listAuditLogs: listAuditLogsMock,
}));

beforeEach(() => {
  grantedPermissions.current = [];
  tenantIdState.current = null;
  listUsersMock.mockReset();
  listRolesMock.mockReset();
  listTenantsMock.mockReset();
  getUnreadCountMock.mockReset();
  getMySubscriptionMock.mockReset();
  listAuditLogsMock.mockReset();

  // size=1 → the KPI count probe; anything else → the recent-users page.
  listUsersMock.mockImplementation((params: { size?: number } = {}) =>
    Promise.resolve(params.size === 1 ? userCountPage : recentUsersPage),
  );
  listRolesMock.mockResolvedValue({ content: [], totalElements: 7 });
  listTenantsMock.mockResolvedValue([
    { id: 1, name: 't1' },
    { id: 2, name: 't2' },
    { id: 3, name: 't3' },
  ]);
  getUnreadCountMock.mockResolvedValue({ count: 12 });
  getMySubscriptionMock.mockResolvedValue({
    id: 1,
    tenantId: 5,
    editionDisplayName: 'Pro Plan',
    status: 'ACTIVE',
    currentPeriodEndAt: '2026-08-01T00:00:00Z',
  });
  listAuditLogsMock.mockResolvedValue(auditPage);
  localStorage.clear();
});

describe('DashboardPage — KPI band', () => {
  it('renders KPI values for the permissions the user holds', async () => {
    tenantIdState.current = 5;
    grantedPermissions.current = ['users.read', 'roles.read'];

    renderWithProviders(<DashboardPage />);

    // totalElements from the size=1 probes → the KPI numbers.
    expect(await screen.findByText('42')).toBeInTheDocument();
    expect(await screen.findByText('7')).toBeInTheDocument();
    // Unread notifications: auth-only, always present.
    expect(await screen.findByText('12')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Users' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Roles' })).toBeInTheDocument();
  });

  it('NEGATIVE: without users.read the users KPI and recent-users widget are absent and the users query is never sent', async () => {
    tenantIdState.current = 5;
    grantedPermissions.current = ['auditlogs.read'];

    renderWithProviders(<DashboardPage />);

    // Wait for the page to settle on a widget that IS visible.
    expect(await screen.findByText('audit.alice')).toBeInTheDocument();

    expect(
      screen.queryByRole('heading', { name: 'Users' }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: 'Recent users' }),
    ).not.toBeInTheDocument();
    // The permission gate also gates the QUERY: no call, no 403 spam.
    expect(listUsersMock).not.toHaveBeenCalled();
  });
});

describe('DashboardPage — widget states', () => {
  it('shows a per-widget error with Retry when the audit source fails, and Retry refetches', async () => {
    tenantIdState.current = 5;
    grantedPermissions.current = ['auditlogs.read'];
    listAuditLogsMock.mockRejectedValue(new Error('boom'));

    // NOTE: this client's DEFAULTS include `retry: false`, but the widget hooks
    // set their own production retry policy PER QUERY, which overrides the
    // default — so the hooks under test still retry transient failures once.
    // The part that matters here is `retryDelay: 0`: without it the retried
    // attempt (and thus the error state) lands after the exponential backoff,
    // beyond the test timeout. Don't copy this client to a test whose query has
    // NO per-hook retry, or you'd silently disable retry there.
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, retryDelay: 0, gcTime: 0, staleTime: 0 },
        mutations: { retry: false },
      },
    });

    renderWithProviders(<DashboardPage />, { queryClient });

    // Both audit-backed widgets fail INDEPENDENTLY — the page itself survives
    // (the unread KPI still renders).
    expect(
      await screen.findByText('The activity trend could not be loaded.'),
    ).toBeInTheDocument();
    expect(
      await screen.findByText('Recent activity could not be loaded.'),
    ).toBeInTheDocument();
    expect(await screen.findByText('12')).toBeInTheDocument();

    const callsBeforeRetry = listAuditLogsMock.mock.calls.length;
    const user = userEvent.setup();
    await user.click(screen.getAllByRole('button', { name: 'Retry' })[0]);

    await waitFor(() =>
      expect(listAuditLogsMock.mock.calls.length).toBeGreaterThan(
        callsBeforeRetry,
      ),
    );
  });

  it('shows the empty states when the audit window has no rows', async () => {
    tenantIdState.current = 5;
    grantedPermissions.current = ['auditlogs.read'];
    listAuditLogsMock.mockResolvedValue({ content: [], totalElements: 0 });

    renderWithProviders(<DashboardPage />);

    expect(
      await screen.findByText('No activity in the last 14 days.'),
    ).toBeInTheDocument();
    expect(await screen.findByText('No recent activity.')).toBeInTheDocument();
  });

  it('discloses a partial trend sample when the window holds more rows than one page', async () => {
    tenantIdState.current = 5;
    grantedPermissions.current = ['auditlogs.read'];
    // The server clamps `size` (max-page-size: 100): the window reports 250
    // rows but only one page comes back → the widget must SAY it sampled.
    listAuditLogsMock.mockResolvedValue({
      ...auditPage,
      totalElements: 250,
    });

    renderWithProviders(<DashboardPage />);

    expect(
      await screen.findByText(
        'Based on the latest 2 of 250 entries in this window.',
      ),
    ).toBeInTheDocument();
  });

  it('shows no sample disclosure when the page covers the whole window', async () => {
    tenantIdState.current = 5;
    grantedPermissions.current = ['auditlogs.read'];
    listAuditLogsMock.mockResolvedValue(auditPage); // totalElements == rows

    renderWithProviders(<DashboardPage />);

    await screen.findByText('audit.alice');
    expect(screen.queryByText(/Based on the latest/)).not.toBeInTheDocument();
  });
});

describe('DashboardPage — host/tenant context', () => {
  it('host: shows the tenants KPI, hides the subscription widget and never calls /subscriptions/me', async () => {
    tenantIdState.current = null; // host session — no tenant claim
    grantedPermissions.current = ['tenants.manage'];

    renderWithProviders(<DashboardPage />);

    expect(
      await screen.findByRole('heading', { name: 'Tenants' }),
    ).toBeInTheDocument();
    // Plain-array contract: the count is the array length.
    expect(await screen.findByText('3')).toBeInTheDocument();

    expect(
      screen.queryByRole('heading', { name: 'Subscription' }),
    ).not.toBeInTheDocument();
    expect(getMySubscriptionMock).not.toHaveBeenCalled();
  });

  it('tenant: shows the subscription widget, hides the tenants KPI and never calls /api/tenants', async () => {
    tenantIdState.current = 5;
    // Even WITH the (host-side) permission, tenant context hides the KPI.
    grantedPermissions.current = ['tenants.manage'];

    renderWithProviders(<DashboardPage />);

    expect(await screen.findByText('Pro Plan')).toBeInTheDocument();
    expect(await screen.findByText('Active')).toBeInTheDocument();

    expect(
      screen.queryByRole('heading', { name: 'Tenants' }),
    ).not.toBeInTheDocument();
    expect(listTenantsMock).not.toHaveBeenCalled();
  });

  it('tenant without a subscription (404) renders the empty state, not an error', async () => {
    tenantIdState.current = 5;
    grantedPermissions.current = [];
    getMySubscriptionMock.mockRejectedValue(
      new ApiError(404, {
        title: 'Not Found',
        status: 404,
        detail: 'No subscription for tenant: 5',
      }),
    );

    renderWithProviders(<DashboardPage />);

    expect(
      await screen.findByText('No subscription yet.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('DashboardPage — quick actions', () => {
  it('renders only the permission-filtered destination cards', async () => {
    tenantIdState.current = 5;
    grantedPermissions.current = ['users.read'];

    renderWithProviders(<DashboardPage />);

    expect(
      await screen.findByRole('link', { name: /Invite people/ }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: /Define roles/ }),
    ).not.toBeInTheDocument();
  });
});
