import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RequireAuth } from '@/auth/require-auth';
import { filterMenuByPermission, MENU_SIDEBAR } from '@/config/menu.config';
import { BillingProvidersPage } from '@/features/billing-providers/pages/billing-providers';
import type { ProviderStatusDto } from '@/features/billing-providers/types';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Payment providers screen behaviour tests (managed billing credentials +
 * failover — GOREV B). Paths and DTO shapes follow the generated schema:
 * `GET/PUT /api/billing/providers[...]` with `ProviderStatusDto`.
 *
 * `apiFetch` is mocked at the client boundary so the real hooks and
 * react-query run end to end; `useAuth` is mocked to feed explicit permission
 * lists so all four locks are asserted where the SPA owns them: menu filter,
 * route guard, and `<Can permission="billing.credentials.manage">`.
 *
 * The non-negotiable assertion of this suite: a RAW CREDENTIAL VALUE NEVER
 * APPEARS in the DOM — inputs stay empty, only the backend masked hint shows.
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

/**
 * The three `source` states: db (stored, masked hint), env (no DB row) and
 * none (unconfigured). Stripe carries no `displayOrder` — the unordered case.
 */
function initialRows(): ProviderStatusDto[] {
  return [
    {
      provider: 'paytr',
      configured: true,
      source: 'db',
      maskedHint: '…1234',
      configuredFields: ['merchantId', 'merchantKey', 'merchantSalt'],
      enabled: true,
      displayOrder: 0,
    },
    {
      provider: 'iyzico',
      configured: true,
      source: 'env',
      configuredFields: [],
      enabled: true,
      displayOrder: 1,
    },
    {
      provider: 'stripe',
      configured: false,
      source: 'none',
      configuredFields: [],
      enabled: false,
    },
  ];
}

/** Mutable GET payload so mutation → invalidation → refetch is observable. */
let providerRows: ProviderStatusDto[];

beforeEach(() => {
  apiFetchMock.mockReset();
  useAuthMock.mockReset();
  localStorage.clear();
  providerRows = initialRows();

  apiFetchMock.mockImplementation((path: string, options?: RequestInit) => {
    if (path === '/api/billing/providers' && !options?.method) {
      return Promise.resolve(providerRows);
    }
    return Promise.resolve(undefined);
  });
});

describe('BillingProvidersPage — card states', () => {
  it('renders one card per provider with status badge, mask and failover position', async () => {
    grant(['billing.credentials.manage']);

    renderWithProviders(<BillingProvidersPage />, {
      route: '/billing-providers',
    });

    // Brand names (not translated).
    expect(await screen.findByText('PayTR')).toBeInTheDocument();
    expect(screen.getByText('iyzico')).toBeInTheDocument();
    expect(screen.getByText('Stripe')).toBeInTheDocument();

    // Status badges from `source`: db / env / none.
    expect(screen.getByText('Configured in portal')).toBeInTheDocument();
    expect(screen.getByText('From environment')).toBeInTheDocument();
    expect(screen.getByText('Not configured')).toBeInTheDocument();

    // Enabled flags: paytr + iyzico enabled, stripe disabled.
    expect(screen.getAllByText('Enabled')).toHaveLength(2);
    expect(screen.getByText('Disabled')).toBeInTheDocument();

    // The stored provider shows the backend masked hint — and ONLY the hint.
    expect(screen.getByText('Stored credential: …1234')).toBeInTheDocument();

    // Failover positions follow `displayOrder`; a row without one sinks to
    // the end and shows the "unordered" badge instead of a position.
    expect(screen.getByText('Failover position 1')).toBeInTheDocument();
    expect(screen.getByText('Failover position 2')).toBeInTheDocument();
    expect(screen.queryByText('Failover position 3')).not.toBeInTheDocument();
    expect(screen.getByText('Unordered')).toBeInTheDocument();

    expect(apiFetchMock).toHaveBeenCalledWith('/api/billing/providers');
  });

  it('shows an error state with retry when the list request fails', async () => {
    grant(['billing.credentials.manage']);
    apiFetchMock.mockRejectedValue(new Error('boom'));

    renderWithProviders(<BillingProvidersPage />, {
      route: '/billing-providers',
    });

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Payment providers could not be loaded.',
    );
  });
});

describe('BillingProvidersPage — RBAC (quadruple lock, SPA half)', () => {
  it('menu: the sidebar entry disappears without billing.credentials.manage', () => {
    const withPermission = filterMenuByPermission(MENU_SIDEBAR, (p) =>
      ['billing.credentials.manage'].includes(p),
    );
    const withoutPermission = filterMenuByPermission(MENU_SIDEBAR, () => false);

    const flatten = (items: typeof MENU_SIDEBAR): string[] =>
      items.flatMap((item) => [
        item.title ?? '',
        ...(item.children ? flatten(item.children) : []),
      ]);

    expect(flatten(withPermission)).toContain('nav.billingProviders');
    expect(flatten(withoutPermission)).not.toContain('nav.billingProviders');
  });

  it('route: a user without the permission gets the 403 page, and no providers request is made', async () => {
    grant(['subscriptions.read']);

    renderWithProviders(
      <RequireAuth permission="billing.credentials.manage">
        <BillingProvidersPage />
      </RequireAuth>,
      { route: '/billing-providers' },
    );

    expect(await screen.findByText('Access denied')).toBeInTheDocument();
    expect(screen.queryByText('Payment providers')).not.toBeInTheDocument();
    expect(apiFetchMock).not.toHaveBeenCalledWith('/api/billing/providers');
  });

  it('<Can>: write actions are hidden when the permission is missing', async () => {
    // Direct render without the route guard: even then, no write surface.
    grant([]);

    renderWithProviders(<BillingProvidersPage />, {
      route: '/billing-providers',
    });

    expect(await screen.findByText('PayTR')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Update credentials' }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Enter credentials' }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Clear credentials' }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Move PayTR up' }),
    ).not.toBeInTheDocument();
  });
});

describe('BillingProvidersPage — save flow (write-only)', () => {
  it('first save requires every field, sends them write-only, and the card mask updates', async () => {
    grant(['billing.credentials.manage']);
    const user = userEvent.setup();

    renderWithProviders(<BillingProvidersPage />, {
      route: '/billing-providers',
    });

    // iyzico is env-configured (no stored row) → "Enter credentials".
    await screen.findByText('iyzico');
    await user.click(screen.getByRole('button', { name: 'Enter credentials' }));

    const apiKeyInput = await screen.findByLabelText('API key');
    const secretKeyInput = screen.getByLabelText('Secret key');

    // Submitting with a missing field is rejected client-side (first save).
    await user.type(apiKeyInput, 'iyz-api-key-9876');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(
      await screen.findByText('All fields are required for the first save.'),
    ).toBeInTheDocument();
    expect(apiFetchMock).not.toHaveBeenCalledWith(
      '/api/billing/providers/iyzico/credentials',
      expect.anything(),
    );

    // Complete the form; the refetch after saving returns a stored row.
    await user.type(secretKeyInput, 'iyz-secret-key-9876');
    providerRows = initialRows().map((row) =>
      row.provider === 'iyzico'
        ? {
            ...row,
            source: 'db',
            maskedHint: '…9876',
            configuredFields: ['apiKey', 'secretKey'],
          }
        : row,
    );
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/billing/providers/iyzico/credentials',
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({
            credentials: {
              apiKey: 'iyz-api-key-9876',
              secretKey: 'iyz-secret-key-9876',
            },
          }),
        }),
      ),
    );

    // Dialog closes and the card now shows the NEW hint (never the raw value).
    expect(
      await screen.findByText('Stored credential: …9876'),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('API key')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('iyz-secret-key-9876');
  });

  it('never round-trips a stored value: inputs are empty password fields with the hint as placeholder', async () => {
    grant(['billing.credentials.manage']);
    const user = userEvent.setup();

    renderWithProviders(<BillingProvidersPage />, {
      route: '/billing-providers',
    });

    // paytr has stored credentials → "Update credentials".
    await screen.findByText('PayTR');
    await user.click(
      screen.getByRole('button', { name: 'Update credentials' }),
    );

    const merchantId = await screen.findByLabelText('Merchant ID');
    const merchantKey = screen.getByLabelText('Merchant key');
    const merchantSalt = screen.getByLabelText('Merchant salt');

    for (const input of [merchantId, merchantKey, merchantSalt]) {
      // Write-only: empty value, masked type, hint only via placeholder.
      expect(input).toHaveValue('');
      expect(input).toHaveAttribute('type', 'password');
      expect(input).toHaveAttribute('placeholder', '…1234');
    }

    // All paytr fields are in `configuredFields` → per-field "Set" badges.
    expect(screen.getAllByText('Set')).toHaveLength(3);

    // Submitting all-empty means "change nothing" → rejected, no request.
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(
      await screen.findByText(
        'Enter at least one field to change, or cancel.',
      ),
    ).toBeInTheDocument();
    expect(apiFetchMock).not.toHaveBeenCalledWith(
      '/api/billing/providers/paytr/credentials',
      expect.anything(),
    );

    // A partial update sends ONLY the typed field ("empty = keep current").
    await user.type(merchantSalt, 'new-salt-value');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/billing/providers/paytr/credentials',
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({
            credentials: { merchantSalt: 'new-salt-value' },
          }),
        }),
      ),
    );
  });
});

describe('BillingProvidersPage — clear and reorder', () => {
  it('clears credentials only after AlertDialog confirmation', async () => {
    grant(['billing.credentials.manage']);
    const user = userEvent.setup();

    renderWithProviders(<BillingProvidersPage />, {
      route: '/billing-providers',
    });

    await screen.findByText('PayTR');
    await user.click(screen.getByRole('button', { name: 'Clear credentials' }));

    // Confirmation dialog first — nothing sent yet.
    expect(
      await screen.findByText(
        'The stored PayTR credentials will be deleted. The provider falls back to the server environment configuration, if any. Payments already in flight are not affected.',
      ),
    ).toBeInTheDocument();
    expect(apiFetchMock).not.toHaveBeenCalledWith(
      '/api/billing/providers/paytr/credentials',
      expect.anything(),
    );

    // After the clear, paytr falls back to env: no DB row, no masked hint.
    providerRows = initialRows().map((row) =>
      row.provider === 'paytr'
        ? {
            ...row,
            source: 'env',
            maskedHint: undefined,
            configuredFields: [],
          }
        : row,
    );
    await user.click(screen.getByRole('button', { name: 'Clear' }));

    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/billing/providers/paytr/credentials',
        expect.objectContaining({ method: 'DELETE' }),
      ),
    );
    // The hint is gone after the refetch (falls back to env → hint text).
    await waitFor(() =>
      expect(
        screen.queryByText('Stored credential: …1234'),
      ).not.toBeInTheDocument(),
    );
  });

  it('moving a provider up sends the FULL new order', async () => {
    grant(['billing.credentials.manage']);
    const user = userEvent.setup();

    renderWithProviders(<BillingProvidersPage />, {
      route: '/billing-providers',
    });

    await screen.findByText('iyzico');
    await user.click(screen.getByRole('button', { name: 'Move iyzico up' }));

    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/billing/providers/order',
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({ order: ['iyzico', 'paytr', 'stripe'] }),
        }),
      ),
    );
  });

  it('disables the impossible arrows: first cannot move up, last cannot move down', async () => {
    grant(['billing.credentials.manage']);

    renderWithProviders(<BillingProvidersPage />, {
      route: '/billing-providers',
    });

    await screen.findByText('PayTR');
    expect(screen.getByRole('button', { name: 'Move PayTR up' })).toBeDisabled();
    expect(
      screen.getByRole('button', { name: 'Move Stripe down' }),
    ).toBeDisabled();
    expect(
      screen.getByRole('button', { name: 'Move iyzico up' }),
    ).toBeEnabled();
  });
});
