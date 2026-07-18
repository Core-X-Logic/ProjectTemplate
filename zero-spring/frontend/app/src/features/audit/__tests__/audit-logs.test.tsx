import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuditLogsPage } from '@/features/audit/pages/audit-logs';
import type { PageAuditLogDto } from '@/features/audit/types';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Audit-log list behaviour tests (FRONTEND-ARCHITECTURE.md §9).
 *
 * The feature's endpoint module is mocked so the real hooks + react-query run
 * against a fake `listAuditLogs`, letting the test assert rendering, the status
 * badge colour mapping, the debounced filter → query-param wiring and the
 * `<Can>`-gated Export action without touching the network. Permissions flow
 * through a mocked `useAuth`, exactly like the production `<Can>` consumes them.
 */

const { grantedPermissions, listAuditLogsMock, exportAuditLogsMock, auditPage } =
  vi.hoisted(() => {
    const auditPage: PageAuditLogDto = {
      content: [
        {
          id: 1,
          username: 'alice',
          serviceName: 'UserService',
          methodName: 'createUser',
          httpMethod: 'POST',
          httpStatusCode: 200,
          executionDurationMs: 42,
          executionTime: '2026-07-01T10:00:00Z',
        },
        {
          id: 2,
          username: 'bob',
          serviceName: 'RoleService',
          methodName: 'deleteRole',
          httpMethod: 'DELETE',
          httpStatusCode: 500,
          executionDurationMs: 130,
          executionTime: '2026-07-01T11:00:00Z',
        },
      ],
      totalElements: 2,
      totalPages: 1,
      number: 0,
      size: 25,
    };

    return {
      grantedPermissions: { current: ['auditlogs.read'] as string[] },
      listAuditLogsMock: vi.fn(),
      exportAuditLogsMock: vi.fn(),
      auditPage,
    };
  });

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn(), message: vi.fn() },
}));

vi.mock('@/providers/auth-provider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: () => ({
    user: {
      id: '1',
      username: 'tester',
      email: 'tester@acme.io',
      tenantId: '1',
      roles: ['admin'],
      permissions: grantedPermissions.current,
    },
    permissions: grantedPermissions.current,
    roles: ['admin'],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  }),
}));

vi.mock('@/features/audit/api', () => ({
  listAuditLogs: listAuditLogsMock,
  exportAuditLogs: exportAuditLogsMock,
  listEntityChanges: vi.fn(),
}));

// The feature catalogue is merged into the root catalogue by the integration
// step; the test mirrors that merge so assertions use real English copy.
vi.mock('@/i18n/messages/en', async () => {
  const actual =
    await vi.importActual<typeof import('@/i18n/messages/en')>(
      '@/i18n/messages/en',
    );
  const { auditMessagesEn } = await vi.importActual<
    typeof import('@/features/audit/messages')
  >('@/features/audit/messages');
  return { default: { ...actual.default, ...auditMessagesEn } };
});

beforeEach(() => {
  grantedPermissions.current = ['auditlogs.read'];
  listAuditLogsMock.mockReset();
  exportAuditLogsMock.mockReset();
  listAuditLogsMock.mockResolvedValue(auditPage);
  exportAuditLogsMock.mockResolvedValue(new Blob(['xlsx']));
  // jsdom has no object-URL support and treats an <a> click as navigation;
  // stub both so the export download is inert.
  URL.createObjectURL = vi.fn(() => 'blob:mock');
  URL.revokeObjectURL = vi.fn();
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  localStorage.clear();
});

describe('AuditLogsPage', () => {
  it('renders a row per audit log with the core columns', async () => {
    renderWithProviders(<AuditLogsPage />);

    expect(await screen.findByText('alice')).toBeInTheDocument();
    expect(screen.getByText('bob')).toBeInTheDocument();
    expect(screen.getByText('UserService')).toBeInTheDocument();
    expect(screen.getByText('RoleService')).toBeInTheDocument();
    expect(screen.getByText('createUser')).toBeInTheDocument();
    // Duration is localized through the `audit.duration.ms` message.
    expect(screen.getByText('42 ms')).toBeInTheDocument();
  });

  it('renders the HTTP status as a colour-coded badge (2xx green / 5xx red)', async () => {
    renderWithProviders(<AuditLogsPage />);

    const ok = await screen.findByText('200');
    const serverError = screen.getByText('500');

    // Both status codes render inside a badge…
    const okBadge = ok.closest('[data-slot="badge"]');
    const errorBadge = serverError.closest('[data-slot="badge"]');
    expect(okBadge).not.toBeNull();
    expect(errorBadge).not.toBeNull();

    // …and the variant colour differs: success maps to green, 5xx to red.
    expect(okBadge?.className).toContain('green');
    expect(errorBadge?.className).toContain('red');
  });

  it('forwards the debounced userName filter as a query param', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AuditLogsPage />);

    await screen.findByText('alice');
    await user.type(
      screen.getByPlaceholderText('Filter by user…'),
      'alice',
    );

    await waitFor(() =>
      expect(listAuditLogsMock).toHaveBeenCalledWith(
        expect.objectContaining({ userName: 'alice' }),
      ),
    );
  });

  it('shows the Export action when auditlogs.read is granted (RBAC)', async () => {
    grantedPermissions.current = ['auditlogs.read'];
    renderWithProviders(<AuditLogsPage />);

    await screen.findByText('alice');
    expect(
      screen.getByRole('button', { name: 'Export' }),
    ).toBeInTheDocument();
  });

  it('hides the Export action when auditlogs.read is absent (RBAC)', async () => {
    grantedPermissions.current = ['someother.read'];
    renderWithProviders(<AuditLogsPage />);

    await screen.findByText('alice');
    expect(
      screen.queryByRole('button', { name: 'Export' }),
    ).not.toBeInTheDocument();
  });

  it('triggers the export mutation with the active filters on click', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AuditLogsPage />);

    await screen.findByText('alice');
    await user.click(screen.getByRole('button', { name: 'Export' }));

    await waitFor(() => expect(exportAuditLogsMock).toHaveBeenCalledTimes(1));
  });
});
