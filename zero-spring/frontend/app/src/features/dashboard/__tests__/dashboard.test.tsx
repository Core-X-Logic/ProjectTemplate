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
 * Dashboard tab-center behaviour tests (FRONTEND-ARCHITECTURE.md §9).
 *
 * The owning features' endpoint modules are mocked (the dashboard has no
 * endpoints of its own), so the REAL hooks + react-query run against fake
 * wire responses. Assertions cover what the user sees:
 *  - tab visibility follows permissions/context (a useless tab is not offered),
 *  - a tab's queries fire on FIRST activation only (lazy per-tab data),
 *  - KPI values for granted permissions,
 *  - NEGATIVE permission → widget absent AND its query never sent,
 *  - per-widget error + retry (a failing widget never takes a tab down),
 *  - per-widget empty states,
 *  - host/tenant context split (subscription vs tenants KPI vs host finance).
 */

const {
  grantedPermissions,
  tenantIdState,
  listUsersMock,
  listRolesMock,
  listTenantsMock,
  getUnreadCountMock,
  getMySubscriptionMock,
  listSubscriptionsMock,
  listNotificationsMock,
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
    listSubscriptionsMock: vi.fn(),
    listNotificationsMock: vi.fn(),
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
  listNotifications: listNotificationsMock,
}));

vi.mock('@/features/subscriptions/api', () => ({
  getMySubscription: getMySubscriptionMock,
  listSubscriptions: listSubscriptionsMock,
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
  listSubscriptionsMock.mockReset();
  listNotificationsMock.mockReset();
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
  listSubscriptionsMock.mockResolvedValue({
    content: [
      {
        id: 11,
        tenantName: 'acme',
        editionDisplayName: 'Pro Plan',
        status: 'ACTIVE',
      },
      {
        id: 12,
        tenantName: 'globex',
        editionDisplayName: 'Starter',
        status: 'CANCELLED',
      },
    ],
    totalElements: 8,
  });
  listNotificationsMock.mockResolvedValue({
    content: [
      {
        id: 21,
        title: 'Deploy finished',
        body: 'v1.0.0-rc.1 is live',
        level: 'SUCCESS',
        isRead: false,
        createdAt: new Date().toISOString(),
      },
    ],
    totalElements: 1,
  });
  listAuditLogsMock.mockResolvedValue(auditPage);
  localStorage.clear();
});

/** Clicks a dashboard tab by its accessible name. */
async function switchTab(name: string) {
  const user = userEvent.setup();
  await user.click(screen.getByRole('tab', { name }));
}

describe('DashboardPage — tab visibility', () => {
  it('offers only the tabs the user can use', async () => {
    tenantIdState.current = null; // host
    grantedPermissions.current = []; // no audit, no subscriptions, no admin

    renderWithProviders(<DashboardPage />);

    expect(await screen.findByRole('tab', { name: 'Overview' })).toBeVisible();
    expect(screen.getByRole('tab', { name: 'Operations' })).toBeVisible();
    // No auditlogs.read → no Activity tab; host without subscriptions.read →
    // no Finance tab; no admin permission → no Management tab.
    expect(screen.queryByRole('tab', { name: 'Activity' })).toBeNull();
    expect(screen.queryByRole('tab', { name: 'Finance' })).toBeNull();
    expect(screen.queryByRole('tab', { name: 'Management' })).toBeNull();
  });

  it('offers Activity/Finance/Management when permissions and context allow', async () => {
    tenantIdState.current = 5; // tenant → Finance is always offered
    grantedPermissions.current = ['auditlogs.read', 'users.read'];

    renderWithProviders(<DashboardPage />);

    expect(await screen.findByRole('tab', { name: 'Activity' })).toBeVisible();
    expect(screen.getByRole('tab', { name: 'Finance' })).toBeVisible();
    expect(screen.getByRole('tab', { name: 'Management' })).toBeVisible();
  });
});

describe('DashboardPage — lazy per-tab data', () => {
  it('does not fetch a tab’s data until the tab is first opened', async () => {
    tenantIdState.current = 5;
    grantedPermissions.current = [];

    renderWithProviders(<DashboardPage />);
    expect(await screen.findByText('12')).toBeInTheDocument(); // overview settled

    // Operations content (inbox) is unmounted → its query has NOT fired.
    expect(listNotificationsMock).not.toHaveBeenCalled();

    await switchTab('Operations');
    expect(await screen.findByText('Deploy finished')).toBeInTheDocument();
    expect(listNotificationsMock).toHaveBeenCalledTimes(1);
  });
});

describe('DashboardPage — KPI band (Overview)', () => {
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

    // Wait for the page to settle on widgets that ARE visible.
    expect(await screen.findByText('12')).toBeInTheDocument();
    expect(
      await screen.findByRole('heading', { name: 'Activity trend' }),
    ).toBeInTheDocument();

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
  it('shows per-widget errors with Retry when the audit source fails, and Retry refetches', async () => {
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

    // Overview: the trend fails but the page survives (unread KPI renders).
    expect(
      await screen.findByText('The activity trend could not be loaded.'),
    ).toBeInTheDocument();
    expect(await screen.findByText('12')).toBeInTheDocument();

    // Activity tab: the timeline fails independently.
    await switchTab('Activity');
    expect(
      await screen.findByText('Recent activity could not be loaded.'),
    ).toBeInTheDocument();

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

    await switchTab('Activity');
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

    await screen.findByRole('heading', { name: 'Activity trend' });
    await waitFor(() => expect(listAuditLogsMock).toHaveBeenCalled());
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

  it('tenant: Finance shows the own subscription, the tenants KPI stays hidden and /api/tenants is never called', async () => {
    tenantIdState.current = 5;
    // Even WITH the (host-side) permission, tenant context hides the KPI.
    grantedPermissions.current = ['tenants.manage'];

    renderWithProviders(<DashboardPage />);
    expect(await screen.findByText('12')).toBeInTheDocument();

    await switchTab('Finance');
    expect(await screen.findByText('Pro Plan')).toBeInTheDocument();
    expect(await screen.findByText('Active')).toBeInTheDocument();

    expect(
      screen.queryByRole('heading', { name: 'Tenants' }),
    ).not.toBeInTheDocument();
    expect(listTenantsMock).not.toHaveBeenCalled();
  });

  it('host with subscriptions.read: Finance shows the subscriptions overview', async () => {
    tenantIdState.current = null;
    grantedPermissions.current = ['subscriptions.read'];

    renderWithProviders(<DashboardPage />);
    await switchTab('Finance');

    expect(await screen.findByText('acme')).toBeInTheDocument();
    expect(await screen.findByText('globex')).toBeInTheDocument();
    // The own-subscription endpoint is tenant-only and must stay untouched.
    expect(getMySubscriptionMock).not.toHaveBeenCalled();
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
    await switchTab('Finance');

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
