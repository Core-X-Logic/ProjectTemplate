import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EditionsListPage } from '@/features/editions/pages/editions-list';
import type { PageEditionDto } from '@/features/editions/types';
import { renderWithProviders, screen } from '@/test/utils';

/**
 * Editions list behaviour tests (CONTRACT-phase5.md §A.4).
 *
 * `apiFetch` is mocked at the client boundary so the real feature hooks and
 * react-query run end to end; `useAuth` is mocked to feed explicit permission
 * lists so the `<Can permission="editions.manage">` guards are asserted
 * directly (frontend half of the double lock).
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

const editionsPage: PageEditionDto = {
  content: [
    {
      id: 1,
      // Display name deliberately differs from the "Free" badge label so the
      // badge assertion below cannot match the name cell by accident.
      name: 'free',
      displayName: 'Free tier',
      monthlyPrice: 0,
      annualPrice: 0,
      currency: 'USD',
      trialDayCount: 0,
      graceDayCount: 0,
      active: true,
      sortOrder: 0,
      free: true,
    },
    {
      id: 2,
      name: 'standard',
      displayName: 'Standard',
      monthlyPrice: 49,
      annualPrice: 490,
      currency: 'USD',
      trialDayCount: 14,
      graceDayCount: 7,
      active: false,
      sortOrder: 1,
      free: false,
    },
  ],
  totalElements: 2,
  totalPages: 1,
  number: 0,
  size: 10,
  numberOfElements: 2,
  first: true,
  last: true,
  empty: false,
};

beforeEach(() => {
  apiFetchMock.mockReset();
  useAuthMock.mockReset();
  localStorage.clear();

  apiFetchMock.mockImplementation((path: string) => {
    if (path.startsWith('/api/editions')) {
      return Promise.resolve(editionsPage);
    }
    return Promise.resolve(undefined);
  });
});

describe('EditionsListPage', () => {
  it('renders the fetched editions with prices, trial/grace and status badges', async () => {
    grant(['editions.read']);

    renderWithProviders(<EditionsListPage />, { route: '/editions' });

    expect(await screen.findByText('Standard')).toBeInTheDocument();
    expect(screen.getByText('Free tier')).toBeInTheDocument();
    expect(screen.getByText('standard')).toBeInTheDocument();
    // Free editions carry a dedicated badge next to the technical name.
    expect(screen.getByText('Free')).toBeInTheDocument();
    // active → Active badge, otherwise Inactive.
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByText('Inactive')).toBeInTheDocument();
    // Trial / grace day counts are rendered per row.
    expect(screen.getByText('14')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
    // Prices go through the currency formatter.
    expect(screen.getByText('$49.00')).toBeInTheDocument();
    expect(screen.getByText('$490.00')).toBeInTheDocument();
    // The list endpoint was hit with pageable query params.
    expect(apiFetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/editions?'),
    );
  });

  it('shows an error state instead of the grid when the list request fails', async () => {
    grant(['editions.read']);
    apiFetchMock.mockRejectedValue(new Error('boom'));

    renderWithProviders(<EditionsListPage />, { route: '/editions' });

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Editions could not be loaded.',
    );
  });

  it('shows the create button when the user holds editions.manage', async () => {
    grant(['editions.read', 'editions.manage']);

    renderWithProviders(<EditionsListPage />, { route: '/editions' });

    expect(await screen.findByText('Standard')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'New edition' }),
    ).toBeInTheDocument();
  });

  it('hides the create button when editions.manage is missing', async () => {
    grant(['editions.read']);

    renderWithProviders(<EditionsListPage />, { route: '/editions' });

    expect(await screen.findByText('Standard')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'New edition' }),
    ).not.toBeInTheDocument();
  });
});

/**
 * Row actions (Edit / Delete) are `<Can permission="editions.manage">`-guarded
 * and only mount as radix `menuitem`s once the row's action menu is opened.
 */
describe('EditionsListPage row actions (RBAC)', () => {
  async function openRowMenu(rowIndex: number): Promise<void> {
    const user = userEvent.setup();
    const triggers = await screen.findAllByRole('button', {
      name: 'Open edition actions',
    });
    await user.click(triggers[rowIndex]);
  }

  it('shows Edit and Delete when editions.manage is granted', async () => {
    grant(['editions.read', 'editions.manage']);

    renderWithProviders(<EditionsListPage />, { route: '/editions' });
    await screen.findByText('Standard');
    await openRowMenu(1);

    expect(
      await screen.findByRole('menuitem', { name: 'Edit' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('menuitem', { name: 'Delete' }),
    ).toBeInTheDocument();
  });

  it('hides Edit and Delete when editions.manage is absent', async () => {
    grant(['editions.read']);

    renderWithProviders(<EditionsListPage />, { route: '/editions' });
    await screen.findByText('Standard');
    await openRowMenu(1);

    expect(
      screen.queryByRole('menuitem', { name: 'Edit' }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('menuitem', { name: 'Delete' }),
    ).not.toBeInTheDocument();
  });
});
