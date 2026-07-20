import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
  type MockInstance,
} from 'vitest';
import { ApiError } from '@/api/client';
import { CheckoutDialog } from '@/features/subscriptions/components/checkout-dialog';
import type { PageEditionDto } from '@/features/editions/types';
import type {
  CheckoutSessionDto,
  SubscriptionDto,
} from '@/features/subscriptions/types';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Checkout dialog behaviour tests (CONTRACT-payments-tr P2'-C, UI half).
 *
 * Asserts the exact `POST /api/billing/checkout` payload (tenant, edition,
 * period, provider, BOTH absolute result URLs), the provider hand-off
 * (window.open + always-rendered started state with fallback link and the
 * server-side-activation warning), and that a 400 ProblemDetail keeps the
 * dialog open with the backend's wording plus a retry button.
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

const editionsPage: PageEditionDto = {
  content: [
    {
      id: 1,
      name: 'free',
      displayName: 'Free',
      active: true,
      free: true,
    },
    {
      id: 2,
      name: 'standard',
      displayName: 'Standard',
      active: true,
      free: false,
    },
  ],
  totalElements: 2,
  totalPages: 1,
  number: 0,
  size: 100,
};

const subscription: SubscriptionDto = {
  id: 10,
  tenantId: 42,
  tenantName: 'acme',
  status: 'EXPIRED',
};

const session: CheckoutSessionDto = {
  paymentId: 77,
  sessionId: 'sess-1',
  url: 'https://www.paytr.com/odeme/guvenli/tok123',
};

let openSpy: MockInstance<typeof window.open>;

beforeEach(() => {
  apiFetchMock.mockReset();
  useAuthMock.mockReset();
  localStorage.clear();
  openSpy = vi.spyOn(window, 'open').mockReturnValue(null);

  useAuthMock.mockReturnValue({
    user: { id: '1', username: 'host-admin' },
    permissions: ['subscriptions.read', 'subscriptions.manage'],
    roles: [],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  });

  apiFetchMock.mockImplementation((path: string) => {
    if (path.startsWith('/api/editions')) {
      return Promise.resolve(editionsPage);
    }
    if (path === '/api/billing/checkout') {
      return Promise.resolve(session);
    }
    return Promise.resolve(undefined);
  });
});

afterEach(() => {
  openSpy.mockRestore();
});

function checkoutCallBody(): Record<string, unknown> | undefined {
  const call = apiFetchMock.mock.calls.find(
    ([path]) => path === '/api/billing/checkout',
  );
  return call ? JSON.parse(call[1]?.body as string) : undefined;
}

describe('CheckoutDialog', () => {
  it('submits tenant, edition, period, provider and both absolute result URLs', async () => {
    const user = userEvent.setup();

    renderWithProviders(
      <CheckoutDialog open onOpenChange={vi.fn()} subscription={subscription} />,
    );

    await user.selectOptions(await screen.findByLabelText('Edition'), '2');
    await user.selectOptions(screen.getByLabelText('Billing period'), 'ANNUAL');

    await user.click(screen.getByRole('button', { name: 'Start payment' }));

    await waitFor(() => {
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/billing/checkout',
        expect.objectContaining({ method: 'POST' }),
      );
    });

    // Every contract field must be present — the backend validates all of
    // them, and the result URLs are what the provider redirects back to.
    expect(checkoutCallBody()).toEqual({
      tenantId: 42,
      editionId: 2,
      billingPeriod: 'ANNUAL',
      provider: 'paytr',
      successUrl: `${window.location.origin}/payment/result/success`,
      cancelUrl: `${window.location.origin}/payment/result/cancel`,
    });
  });

  it('switches the provider field when the iyzico radio is chosen', async () => {
    const user = userEvent.setup();

    renderWithProviders(
      <CheckoutDialog open onOpenChange={vi.fn()} subscription={subscription} />,
    );

    await user.selectOptions(await screen.findByLabelText('Edition'), '2');

    // PayTR is the default; both radios are present and localized.
    expect(screen.getByRole('radio', { name: 'PayTR' })).toBeChecked();
    await user.click(screen.getByRole('radio', { name: 'iyzico' }));

    await user.click(screen.getByRole('button', { name: 'Start payment' }));

    await waitFor(() => {
      expect(checkoutCallBody()).toMatchObject({ provider: 'iyzico' });
    });
  });

  it('opens the provider page in a new tab and always renders the started state', async () => {
    const user = userEvent.setup();

    renderWithProviders(
      <CheckoutDialog open onOpenChange={vi.fn()} subscription={subscription} />,
    );

    await user.selectOptions(await screen.findByLabelText('Edition'), '2');
    await user.click(screen.getByRole('button', { name: 'Start payment' }));

    // Hand-off: new tab, never an iframe.
    await waitFor(() => {
      expect(openSpy).toHaveBeenCalledWith(
        session.url,
        '_blank',
        'noopener,noreferrer',
      );
    });

    // Started state: fallback link for popup blockers…
    const fallbackLink = await screen.findByRole('link', {
      name: /Open the payment page/,
    });
    expect(fallbackLink).toHaveAttribute('href', session.url);

    // …the payment reference…
    expect(screen.getByText('Payment reference: 77')).toBeInTheDocument();

    // …and the load-bearing warning: activation is SERVER-SIDE; closing
    // tabs/dialogs neither cancels nor confirms.
    expect(screen.getByRole('alert')).toHaveTextContent(
      /Activation completes on the server once the provider confirms the payment/,
    );
    expect(screen.getByRole('alert')).toHaveTextContent(
      /neither cancels nor confirms anything/,
    );
  });

  it('keeps the dialog open on a 400 and shows the ProblemDetail with a retry button', async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();
    const detail =
      "Payment provider 'iyzico' is not enabled. Enabled providers: paytr";

    apiFetchMock.mockImplementation((path: string) => {
      if (path.startsWith('/api/editions')) {
        return Promise.resolve(editionsPage);
      }
      if (path === '/api/billing/checkout') {
        return Promise.reject(
          new ApiError(400, {
            status: 400,
            title: 'Bad Request',
            detail,
          }),
        );
      }
      return Promise.resolve(undefined);
    });

    renderWithProviders(
      <CheckoutDialog
        open
        onOpenChange={onOpenChange}
        subscription={subscription}
      />,
    );

    await user.selectOptions(await screen.findByLabelText('Edition'), '2');
    await user.click(screen.getByRole('button', { name: 'Start payment' }));

    // The backend's wording (naming the enabled providers) surfaces inline.
    expect(await screen.findByRole('alert')).toHaveTextContent(detail);

    // The dialog did not close and the form is still there for a retry.
    expect(onOpenChange).not.toHaveBeenCalledWith(false);
    expect(screen.getByLabelText('Edition')).toBeInTheDocument();
    expect(openSpy).not.toHaveBeenCalled();

    // The retry button re-submits the same request.
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => {
      const checkoutCalls = apiFetchMock.mock.calls.filter(
        ([path]) => path === '/api/billing/checkout',
      );
      expect(checkoutCalls).toHaveLength(2);
    });
  });
});
