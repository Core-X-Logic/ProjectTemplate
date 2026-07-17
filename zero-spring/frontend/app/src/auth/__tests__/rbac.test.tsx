import { render, renderHook, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Can, hasAnyPermission, usePermission } from '@/auth/rbac';

/**
 * RBAC behaviour tests (FRONTEND-ARCHITECTURE.md §5).
 *
 * `useAuth` is mocked so each case feeds an explicit permission list; the units
 * under test are `usePermission` (reactive boolean) and `<Can>` (render guard).
 */

const { useAuthMock } = vi.hoisted(() => ({ useAuthMock: vi.fn() }));

vi.mock('@/providers/auth-provider', () => ({
  useAuth: useAuthMock,
}));

function grant(permissions: string[]): void {
  useAuthMock.mockReturnValue({
    user: null,
    permissions,
    roles: [],
    loading: false,
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn(),
  });
}

beforeEach(() => {
  useAuthMock.mockReset();
});

describe('usePermission', () => {
  it('returns true when the permission list contains the requested key', () => {
    grant(['users.read', 'users.write']);

    const { result } = renderHook(() => usePermission('users.read'));

    expect(result.current).toBe(true);
  });

  it('returns false when the permission is absent from the list', () => {
    grant(['users.read']);

    const { result } = renderHook(() => usePermission('users.delete'));

    expect(result.current).toBe(false);
  });
});

describe('hasAnyPermission', () => {
  it('is true when at least one required permission is owned', () => {
    expect(hasAnyPermission(['users.read'], ['users.read', 'users.write'])).toBe(
      true,
    );
  });

  it('is false when none of the required permissions are owned', () => {
    expect(hasAnyPermission(['roles.read'], ['users.read'])).toBe(false);
  });
});

describe('<Can>', () => {
  it('renders its children when the permission is granted', () => {
    grant(['users.read']);

    render(
      <Can permission="users.read">
        <span>secret panel</span>
      </Can>,
    );

    expect(screen.getByText('secret panel')).toBeInTheDocument();
  });

  it('renders nothing when the permission is missing', () => {
    grant(['users.read']);

    render(
      <Can permission="users.delete">
        <span>secret panel</span>
      </Can>,
    );

    expect(screen.queryByText('secret panel')).not.toBeInTheDocument();
  });

  it('renders the fallback when the permission is missing', () => {
    grant(['users.read']);

    render(
      <Can permission="users.delete" fallback={<span>no access</span>}>
        <span>secret panel</span>
      </Can>,
    );

    expect(screen.queryByText('secret panel')).not.toBeInTheDocument();
    expect(screen.getByText('no access')).toBeInTheDocument();
  });
});
