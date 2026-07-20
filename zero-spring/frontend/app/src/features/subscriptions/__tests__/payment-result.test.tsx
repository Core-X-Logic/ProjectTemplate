import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  PaymentResultCancelPage,
  PaymentResultSuccessPage,
} from '@/features/subscriptions/pages/payment-result';
import { renderWithProviders, screen } from '@/test/utils';

/**
 * Payment result landing pages (CONTRACT-payments-tr P2'-C).
 *
 * The load-bearing assertion: the SUCCESS page must never claim activation —
 * the provider redirect proves nothing (activation is webhook/reconciliation,
 * server-side). Both pages route the user back to the subscriptions list.
 */

const { useAuthMock } = vi.hoisted(() => ({
  useAuthMock: vi.fn(),
}));

vi.mock('@/providers/auth-provider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: useAuthMock,
}));

beforeEach(() => {
  useAuthMock.mockReset();
  localStorage.clear();

  // Any authenticated session — deliberately WITHOUT subscription permissions:
  // the provider redirect may land on a buyer session that has none.
  useAuthMock.mockReturnValue({
    user: { id: '2', username: 'tenant-buyer' },
    permissions: [],
    roles: [],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  });
});

describe('PaymentResultSuccessPage', () => {
  it('says the provider received the payment and warns that activation is server-side', () => {
    renderWithProviders(<PaymentResultSuccessPage />, {
      route: '/payment/result/success',
    });

    expect(
      screen.getByText('Payment received by the provider'),
    ).toBeInTheDocument();

    // Never "activated": the redirect is not a confirmation.
    expect(screen.getByRole('status')).toHaveTextContent(
      /Activation completes server-side once the payment is confirmed/,
    );
    expect(screen.getByRole('status')).toHaveTextContent(
      /does not mean the subscription is active/,
    );
    expect(screen.queryByText(/activated/i)).not.toBeInTheDocument();

    expect(
      screen.getByRole('link', { name: 'Go to subscriptions' }),
    ).toHaveAttribute('href', '/subscriptions');
  });
});

describe('PaymentResultCancelPage', () => {
  it('says the payment was not completed and points back at subscriptions for a retry', () => {
    renderWithProviders(<PaymentResultCancelPage />, {
      route: '/payment/result/cancel',
    });

    expect(screen.getByText('Payment not completed')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(
      /You can start a new payment from the subscriptions page/,
    );

    expect(
      screen.getByRole('link', { name: 'Go to subscriptions' }),
    ).toHaveAttribute('href', '/subscriptions');
  });
});
