import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TenantFeaturesPanel } from '@/features/subscriptions/components/tenant-features-panel';
import type { TenantFeatureDto } from '@/features/subscriptions/types';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Tenant feature-override panel tests (CONTRACT-phase5.md §A.4 "tenant-features
 * (override kaydetme)").
 *
 * The panel edits ONLY the override column while showing what an empty field
 * inherits (edition → default), and saves dirty rows only.
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

const tenantFeatures: TenantFeatureDto[] = [
  {
    name: 'app.maxUserCount',
    type: 'NUMBER',
    // Resolution chain: no override yet, so the edition value wins.
    value: '25',
    editionValue: '25',
    defaultValue: '0',
  },
  {
    name: 'app.auditLog',
    type: 'BOOLEAN',
    value: 'true',
    editionValue: 'true',
    defaultValue: 'true',
  },
];

beforeEach(() => {
  apiFetchMock.mockReset();
  useAuthMock.mockReset();
  localStorage.clear();
  grant(['subscriptions.read', 'tenantfeatures.manage']);

  apiFetchMock.mockImplementation((path: string) => {
    if (path.startsWith('/api/tenant-features')) {
      return Promise.resolve(tenantFeatures);
    }
    return Promise.resolve(undefined);
  });
});

describe('TenantFeaturesPanel', () => {
  it('seeds from the override column and shows the inherited fallback', async () => {
    renderWithProviders(
      <TenantFeaturesPanel
        open
        onOpenChange={vi.fn()}
        tenantId={42}
        tenantName="acme"
      />,
    );

    const maxUsers = await screen.findByLabelText('app.maxUserCount');
    // No override set → the field is empty and the edition value is the hint.
    expect(maxUsers).toHaveValue(null);
    expect(maxUsers).toHaveAttribute('placeholder', '25');
    expect(screen.getByText('Inherited: 25')).toBeInTheDocument();

    // BOOLEAN definitions still render as switches here.
    expect(screen.getByLabelText('app.auditLog')).toHaveAttribute(
      'role',
      'switch',
    );

    expect(apiFetchMock).toHaveBeenCalledWith('/api/tenant-features/42');
  });

  it('saves only the overridden rows as a batch PUT', async () => {
    const user = userEvent.setup();

    renderWithProviders(
      <TenantFeaturesPanel
        open
        onOpenChange={vi.fn()}
        tenantId={42}
        tenantName="acme"
      />,
    );

    // The footer button mounts immediately (outside the loading gate), so wait
    // for the rows themselves before touching either.
    const maxUsers = await screen.findByLabelText('app.maxUserCount');
    const save = screen.getByRole('button', { name: 'Save overrides' });
    expect(save).toBeDisabled();

    await user.type(maxUsers, '100');

    await waitFor(() => expect(save).toBeEnabled());
    await user.click(save);

    await waitFor(() => {
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/tenant-features/42',
        expect.objectContaining({ method: 'PUT' }),
      );
    });

    const call = apiFetchMock.mock.calls.find(
      ([path, init]) => path === '/api/tenant-features/42' && init !== undefined,
    );
    expect(JSON.parse(call?.[1]?.body as string)).toEqual([
      { name: 'app.maxUserCount', value: '100' },
    ]);
  });

  it('hides the save button without tenantfeatures.manage', async () => {
    grant(['subscriptions.read']);

    renderWithProviders(
      <TenantFeaturesPanel open onOpenChange={vi.fn()} tenantId={42} />,
    );

    expect(await screen.findByLabelText('app.maxUserCount')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Save overrides' }),
    ).not.toBeInTheDocument();
  });
});
