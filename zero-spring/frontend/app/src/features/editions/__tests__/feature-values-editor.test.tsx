import type { ReactNode } from 'react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FeatureValuesEditor } from '@/features/editions/components/feature-values-editor';
import type {
  FeatureDefinitionDto,
  FeatureValueDto,
} from '@/features/editions/types';
import { renderWithProviders, screen, waitFor } from '@/test/utils';

/**
 * Feature-value editor behaviour tests (CONTRACT-phase5.md §A.4).
 *
 * Asserts the two things the contract calls out for this component:
 *  1. the input kind follows the DEFINITION TYPE (boolean → switch, number →
 *     numeric input, string → text input);
 *  2. saving sends a DIRTY-ONLY batch to `PUT /api/editions/{id}/features`.
 *
 * `apiFetch` is mocked at the client boundary, so the real hooks + react-query
 * pipeline runs and the assertion is made against the actual request payload.
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

const definitions: FeatureDefinitionDto[] = [
  {
    name: 'app.maxUserCount',
    type: 'NUMBER',
    defaultValue: '0',
    visibleOnPricingTable: true,
  },
  {
    name: 'app.auditLog',
    type: 'BOOLEAN',
    defaultValue: 'true',
    visibleOnPricingTable: true,
  },
  {
    name: 'app.supportTier',
    type: 'STRING',
    defaultValue: 'basic',
    visibleOnPricingTable: false,
  },
];

/** Values already assigned to the edition under edit. */
const assigned: FeatureValueDto[] = [{ name: 'app.maxUserCount', value: '25' }];

beforeEach(() => {
  apiFetchMock.mockReset();
  useAuthMock.mockReset();
  localStorage.clear();
  grant(['editions.read', 'editions.manage']);

  apiFetchMock.mockImplementation((path: string) => {
    if (path.startsWith('/api/features/definitions')) {
      return Promise.resolve(definitions);
    }
    return Promise.resolve({ edition: { id: 7 }, features: [] });
  });
});

describe('FeatureValuesEditor', () => {
  it('renders the input matching each definition type', async () => {
    renderWithProviders(
      <FeatureValuesEditor editionId={7} values={assigned} />,
    );

    // NUMBER → numeric input, seeded from the edition's assigned value.
    const maxUsers = await screen.findByLabelText('app.maxUserCount');
    expect(maxUsers).toHaveAttribute('type', 'number');
    expect(maxUsers).toHaveValue(25);

    // BOOLEAN → radix switch (role="switch"), reflecting the default `true`.
    const auditLog = screen.getByLabelText('app.auditLog');
    expect(auditLog).toHaveAttribute('role', 'switch');
    expect(auditLog).toBeChecked();

    // STRING → text input; unassigned, so the default shows as a placeholder.
    const supportTier = screen.getByLabelText('app.supportTier');
    expect(supportTier).toHaveAttribute('type', 'text');
    expect(supportTier).toHaveAttribute('placeholder', 'basic');
    expect(supportTier).toHaveValue('');

    // Defaults are also surfaced as hints.
    expect(screen.getByText('Default: basic')).toBeInTheDocument();
  });

  it('sends only the changed values as a batch to PUT /api/editions/{id}/features', async () => {
    const user = userEvent.setup();

    renderWithProviders(
      <FeatureValuesEditor editionId={7} values={assigned} />,
    );

    const save = await screen.findByRole('button', { name: 'Save features' });
    // Nothing touched yet → save stays disabled (dirty-only).
    expect(save).toBeDisabled();

    const maxUsers = screen.getByLabelText('app.maxUserCount');
    await user.clear(maxUsers);
    await user.type(maxUsers, '50');

    await waitFor(() => expect(save).toBeEnabled());
    await user.click(save);

    await waitFor(() => {
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/api/editions/7/features',
        expect.objectContaining({ method: 'PUT' }),
      );
    });

    const call = apiFetchMock.mock.calls.find(
      ([path]) => path === '/api/editions/7/features',
    );
    // The batch carries the edited row ONLY — the untouched boolean/string
    // definitions must not be forced onto the edition.
    expect(JSON.parse(call?.[1]?.body as string)).toEqual([
      { name: 'app.maxUserCount', value: '50' },
    ]);
  });

  it('hides the save button when editions.manage is missing', async () => {
    grant(['editions.read']);

    renderWithProviders(
      <FeatureValuesEditor editionId={7} values={assigned} />,
    );

    expect(await screen.findByLabelText('app.maxUserCount')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Save features' }),
    ).not.toBeInTheDocument();
  });

  it('shows an error state when the definitions request fails', async () => {
    apiFetchMock.mockRejectedValue(new Error('boom'));

    renderWithProviders(
      <FeatureValuesEditor editionId={7} values={assigned} />,
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Feature definitions could not be loaded.',
    );
  });
});
