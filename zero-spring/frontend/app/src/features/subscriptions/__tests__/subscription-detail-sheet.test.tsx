import { vi, beforeEach, describe, expect, it } from 'vitest';
import { SubscriptionDetailSheet } from '@/features/subscriptions/components/subscription-detail-sheet';
import type { SubscriptionDto } from '@/features/subscriptions/types';
import { renderWithProviders, screen } from '@/test/utils';

/**
 * Subscription detail sheet (ASP.NET Zero parity: the subscription-management
 * detail view — state snapshot + the full lifecycle event trail).
 */

const { apiFetchMock } = vi.hoisted(() => ({ apiFetchMock: vi.fn() }));

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>();
  return { ...actual, apiFetch: apiFetchMock };
});

const subscription: SubscriptionDto = {
  id: 1,
  tenantId: 5,
  tenantName: 'acme',
  editionDisplayName: 'Pro Plan',
  status: 'ACTIVE',
};

const detail = {
  subscription: {
    ...subscription,
    editionName: 'pro',
    billingPeriod: 'MONTHLY',
    priceAmount: 25,
    priceCurrency: 'USD',
    currentPeriodEndAt: '2026-08-15T00:00:00Z',
  },
  events: [
    {
      id: 10,
      fromStatus: null,
      toStatus: 'PENDING_PAYMENT',
      reason: 'PROVISIONED',
      occurredAt: '2026-07-01T10:00:00Z',
      actor: 'system',
    },
    {
      id: 11,
      fromStatus: 'PENDING_PAYMENT',
      toStatus: 'ACTIVE',
      reason: 'ACTIVATED',
      occurredAt: '2026-07-02T09:00:00Z',
      actor: 'admin',
    },
    {
      id: 12,
      fromStatus: 'ACTIVE',
      toStatus: 'ACTIVE',
      reason: 'EXPIRY_NOTICE',
      occurredAt: '2026-08-08T09:00:00Z',
      actor: 'system',
    },
  ],
};

beforeEach(() => {
  apiFetchMock.mockReset();
});

describe('SubscriptionDetailSheet', () => {
  it('renders the state snapshot and the lifecycle history from the detail endpoint', async () => {
    apiFetchMock.mockResolvedValue(detail);

    renderWithProviders(
      <SubscriptionDetailSheet subscription={subscription} onOpenChange={vi.fn()} />,
    );

    // Snapshot fields.
    expect(await screen.findByText('Pro Plan')).toBeInTheDocument();
    expect(screen.getByText('25 USD')).toBeInTheDocument();

    // The event trail, newest first, with localized reason labels.
    expect(screen.getByText('Provisioned')).toBeInTheDocument();
    expect(screen.getByText('Activated')).toBeInTheDocument();
    expect(screen.getByText('Expiry warning sent')).toBeInTheDocument();
    // The transition arrow renders both endpoint statuses.
    expect(screen.getAllByText('Pending payment').length).toBeGreaterThan(0);

    // The detail endpoint was actually asked for THIS tenant.
    expect(String(apiFetchMock.mock.calls[0]?.[0])).toContain(
      '/api/subscriptions/5',
    );
  });

  it('shows the empty-history state when no events exist yet', async () => {
    apiFetchMock.mockResolvedValue({
      subscription: detail.subscription,
      events: [],
    });

    renderWithProviders(
      <SubscriptionDetailSheet subscription={subscription} onOpenChange={vi.fn()} />,
    );

    expect(
      await screen.findByText('No lifecycle events yet.'),
    ).toBeInTheDocument();
  });

  it('shows an error with retry when the detail endpoint fails', async () => {
    apiFetchMock.mockRejectedValue(new Error('boom'));

    renderWithProviders(
      <SubscriptionDetailSheet subscription={subscription} onOpenChange={vi.fn()} />,
    );

    expect(
      await screen.findByText('The subscription detail could not be loaded.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
