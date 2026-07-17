import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { OuTreePage } from '@/features/organization-units/pages/ou-tree';
import type { OrganizationUnit } from '@/features/organization-units/types';
import { renderWithProviders, screen, within } from '@/test/utils';

/**
 * Behaviour tests for the OU tree screen (FRONTEND-ARCHITECTURE.md §9).
 *
 * The feature API module is mocked so the query resolves a deterministic flat
 * list; the auth module is mocked at the `useAuth` boundary so each test
 * controls the permission set (`<Can organizationunits.manage>` guard).
 */

const authState = vi.hoisted(() => ({ permissions: [] as string[] }));

const { listMock } = vi.hoisted(() => ({
  listMock: vi.fn<() => Promise<OrganizationUnit[]>>(),
}));

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn(), message: vi.fn() },
}));

vi.mock('@/providers/auth-provider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: () => ({
    user: {
      id: '1',
      username: 'admin',
      email: 'admin@example.com',
      tenantId: '1',
      roles: [],
      permissions: authState.permissions,
    },
    permissions: authState.permissions,
    roles: [],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  }),
}));

vi.mock('@/features/organization-units/api', () => ({
  listOrganizationUnits: listMock,
  createOrganizationUnit: vi.fn(),
  updateOrganizationUnit: vi.fn(),
  moveOrganizationUnit: vi.fn(),
  removeOrganizationUnit: vi.fn(),
}));

/** Flat backend shape: hierarchy encoded via `parentId` + dotted `code`. */
const UNITS: OrganizationUnit[] = [
  { id: 1, code: '00001', displayName: 'Headquarters', memberCount: 5 },
  {
    id: 2,
    parentId: 1,
    code: '00001.00001',
    displayName: 'Engineering',
    memberCount: 3,
  },
  {
    id: 3,
    parentId: 1,
    code: '00001.00002',
    displayName: 'Human Resources',
    memberCount: 2,
  },
  { id: 4, code: '00002', displayName: 'Field Office', memberCount: 0 },
];

beforeEach(() => {
  listMock.mockReset();
  listMock.mockResolvedValue(UNITS);
  authState.permissions = [];
  localStorage.clear();
});

describe('OuTreePage', () => {
  it('renders the units as a tree nested according to their code hierarchy', async () => {
    renderWithProviders(<OuTreePage />);

    // Roots render as top-level tree items.
    const headquarters = (await screen.findByText('Headquarters')).closest(
      'li',
    ) as HTMLElement;
    expect(screen.getByText('Field Office')).toBeInTheDocument();

    // Children of 00001 render inside the Headquarters node's group…
    const group = within(headquarters).getByRole('group');
    expect(within(group).getByText('Engineering')).toBeInTheDocument();
    expect(within(group).getByText('Human Resources')).toBeInTheDocument();

    // …ordered by code (00001.00001 before 00001.00002).
    const children = within(group).getAllByRole('treeitem');
    expect(children[0]).toHaveTextContent('Engineering');
    expect(children[1]).toHaveTextContent('Human Resources');

    // A leaf root has no nested group.
    const fieldOffice = screen.getByText('Field Office').closest(
      'li',
    ) as HTMLElement;
    expect(within(fieldOffice).queryByRole('group')).toBeNull();
  });

  it('hides every manage action when organizationunits.manage is absent', async () => {
    authState.permissions = ['users.read'];
    renderWithProviders(<OuTreePage />);

    await screen.findByText('Headquarters');

    // No per-node action menus and no root-level create button.
    expect(screen.queryByRole('button', { name: 'Actions' })).toBeNull();
    expect(
      screen.queryByRole('button', { name: /New root unit/i }),
    ).toBeNull();
  });

  it('shows the action menus for holders of organizationunits.manage', async () => {
    authState.permissions = ['organizationunits.manage'];
    renderWithProviders(<OuTreePage />);

    await screen.findByText('Headquarters');

    // One action menu per node (4 units) + the root-level create button.
    expect(screen.getAllByRole('button', { name: 'Actions' })).toHaveLength(
      UNITS.length,
    );
    expect(
      screen.getByRole('button', { name: /New root unit/i }),
    ).toBeInTheDocument();
  });
});
