import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EntityHistoryPage } from '@/features/audit/pages/entity-history';
import type { PageEntityChangeDto } from '@/features/audit/types';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Entity-history behaviour tests (FRONTEND-ARCHITECTURE.md §9).
 *
 * The endpoint module is mocked so the real hooks run against a fake
 * `listEntityChanges`. The tests assert the change-type badge, the debounced
 * filter → query-param wiring and the expandable property-diff detail
 * (original → new) without touching the network.
 */

const { listEntityChangesMock, entityPage } = vi.hoisted(() => {
  const entityPage: PageEntityChangeDto = {
    content: [
      {
        id: 10,
        entityTypeName: 'User',
        entityId: '42',
        changeType: 'UPDATED',
        changeTime: '2026-07-01T10:00:00Z',
        userId: 1,
        propertyChanges: [
          {
            propertyName: 'email',
            originalValue: 'old@acme.io',
            newValue: 'new@acme.io',
          },
        ],
      },
    ],
    totalElements: 1,
    totalPages: 1,
    number: 0,
    size: 25,
  };

  return { listEntityChangesMock: vi.fn(), entityPage };
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
      permissions: ['auditlogs.read'],
    },
    permissions: ['auditlogs.read'],
    roles: ['admin'],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  }),
}));

vi.mock('@/features/audit/api', () => ({
  listAuditLogs: vi.fn(),
  exportAuditLogs: vi.fn(),
  listEntityChanges: listEntityChangesMock,
}));

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
  listEntityChangesMock.mockReset();
  listEntityChangesMock.mockResolvedValue(entityPage);
  localStorage.clear();
});

describe('EntityHistoryPage', () => {
  it('renders an entity change row with a localized change-type badge', async () => {
    renderWithProviders(<EntityHistoryPage />);

    // Anchor on the change-type badge — a data-only value that only appears once
    // the row has loaded (the "User" entity type collides with a column header).
    expect(await screen.findByText('Updated')).toBeInTheDocument();
    expect(screen.getByText('User')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  it('expands a row to reveal the property changes (original → new)', async () => {
    const user = userEvent.setup();
    renderWithProviders(<EntityHistoryPage />);

    await screen.findByText('Updated');

    // The property diff is not rendered until the row is expanded.
    expect(screen.queryByText('old@acme.io')).not.toBeInTheDocument();

    await user.click(
      screen.getByRole('button', { name: 'Toggle change detail' }),
    );

    expect(await screen.findByText('email')).toBeInTheDocument();
    expect(screen.getByText('old@acme.io')).toBeInTheDocument();
    expect(screen.getByText('new@acme.io')).toBeInTheDocument();
  });

  it('forwards the debounced entityTypeName filter as a query param', async () => {
    const user = userEvent.setup();
    renderWithProviders(<EntityHistoryPage />);

    await screen.findByText('Updated');
    await user.type(screen.getByPlaceholderText('e.g. User'), 'Role');

    await waitFor(() =>
      expect(listEntityChangesMock).toHaveBeenCalledWith(
        expect.objectContaining({ entityTypeName: 'Role' }),
      ),
    );
  });
});
