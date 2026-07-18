import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SettingsPage } from '@/features/settings/pages/settings';
import type { SettingDto } from '@/features/settings/types';
import { renderWithProviders, screen } from '@/test/utils';

/**
 * Settings page behaviour tests (FRONTEND-ARCHITECTURE.md §9).
 *
 * The data layer is mocked at the hooks boundary (`vi.mock` on the feature's
 * hooks module) so the test asserts rendering + RBAC wiring without touching
 * the network. Permissions flow through a mocked `useAuth`, exactly like the
 * production `<Can>`/`<RequireAuth>` consume them. Copy is resolved through the
 * feature's `defaultMessage` fallback, so no i18n catalogue mock is required.
 */

const { grantedPermissions, tenantSettings, hostSettings, mutate } = vi.hoisted(
  () => {
    const tenantSettings: SettingDto[] = [
      { name: 'App.Name', value: 'Acme', defaultValue: 'ZeroSpring' },
      { name: 'App.Timezone', value: 'Europe/Istanbul' },
    ];
    const hostSettings: SettingDto[] = [
      { name: 'Host.Smtp.Host', value: 'smtp.acme.io' },
    ];

    return {
      // Mutable holder: each test seeds the permission set before rendering.
      grantedPermissions: { current: ['settings.tenant.manage'] as string[] },
      tenantSettings,
      hostSettings,
      mutate: { tenant: vi.fn(), host: vi.fn() },
    };
  },
);

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn(), message: vi.fn() },
}));

// Auth boundary: `AuthProvider` becomes a passthrough (used by the render
// helper); `useAuth` reads the per-test permission holder.
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

// Data boundary: every hook the settings page imports.
vi.mock('@/features/settings/hooks', () => ({
  useTenantSettings: () => ({
    data: tenantSettings,
    isLoading: false,
    isError: false,
  }),
  useHostSettings: () => ({
    data: hostSettings,
    isLoading: false,
    isError: false,
  }),
  useUpdateTenantSettings: () => ({ mutate: mutate.tenant, isPending: false }),
  useUpdateHostSettings: () => ({ mutate: mutate.host, isPending: false }),
}));

beforeEach(() => {
  grantedPermissions.current = ['settings.tenant.manage'];
  mutate.tenant.mockReset();
  mutate.host.mockReset();
  localStorage.clear();
});

describe('SettingsPage', () => {
  it('renders a row per tenant setting with its label, value and default hint', async () => {
    renderWithProviders(<SettingsPage />);

    // Labels fall back to the setting name; values seed the inputs.
    expect(await screen.findByText('App.Name')).toBeInTheDocument();
    expect(screen.getByText('App.Timezone')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Acme')).toBeInTheDocument();
    expect(screen.getByDisplayValue('Europe/Istanbul')).toBeInTheDocument();
    // Default hint is only shown when the setting carries a defaultValue.
    expect(screen.getByText('Default: ZeroSpring')).toBeInTheDocument();
  });

  it('hides the Host tab when settings.host.manage is NOT granted (RBAC)', async () => {
    grantedPermissions.current = ['settings.tenant.manage'];
    renderWithProviders(<SettingsPage />);

    await screen.findByText('App.Name');
    expect(screen.getByRole('tab', { name: 'Tenant' })).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Host' })).not.toBeInTheDocument();
  });

  it('shows the Host tab when settings.host.manage IS granted (RBAC)', async () => {
    grantedPermissions.current = [
      'settings.tenant.manage',
      'settings.host.manage',
    ];
    renderWithProviders(<SettingsPage />);

    expect(
      await screen.findByRole('tab', { name: 'Host' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Tenant' })).toBeInTheDocument();
  });

  it('defaults a host-only operator to the accessible Host tab', async () => {
    // Only host.manage: the Tenant tab is hidden and Host is the active scope.
    grantedPermissions.current = ['settings.host.manage'];
    renderWithProviders(<SettingsPage />);

    expect(
      await screen.findByRole('tab', { name: 'Host' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('tab', { name: 'Tenant' }),
    ).not.toBeInTheDocument();
    // The Host panel is the default-active content, so its setting is visible.
    expect(screen.getByText('Host.Smtp.Host')).toBeInTheDocument();
  });

  it('saves only the changed field via updateTenantSettings', async () => {
    const user = userEvent.setup();
    grantedPermissions.current = ['settings.tenant.manage'];
    renderWithProviders(<SettingsPage />);

    const input = await screen.findByDisplayValue('Acme');
    await user.clear(input);
    await user.type(input, 'NewName');

    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(mutate.tenant).toHaveBeenCalledTimes(1);
    expect(mutate.tenant).toHaveBeenCalledWith([
      { name: 'App.Name', value: 'NewName' },
    ]);
    // The untouched Timezone field is excluded from the batch.
    expect(mutate.tenant).not.toHaveBeenCalledWith(
      expect.arrayContaining([
        expect.objectContaining({ name: 'App.Timezone' }),
      ]),
    );
  });
});
