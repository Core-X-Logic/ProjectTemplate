import { ReactNode } from 'react';
import { LoaderCircle } from 'lucide-react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '@/providers/auth-provider';
import { hasAnyPermission } from '@/auth/rbac';
import { ForbiddenPage } from '@/auth/pages/forbidden';

function FullscreenLoader() {
  return (
    <div className="flex grow items-center justify-center min-h-[60vh] w-full">
      <LoaderCircle className="size-6 animate-spin text-muted-foreground" />
    </div>
  );
}

interface RequireAuthProps {
  /** When set, the user must additionally hold this permission. */
  permission?: string;
  /**
   * When set (non-empty), the user must hold AT LEAST ONE of these permissions
   * (any-of). Combines with `permission` as an independent gate: if both are
   * supplied the single `permission` must hold AND one of `anyPermission`.
   */
  anyPermission?: string[];
  /**
   * Optional guarded subtree. When omitted an `<Outlet />` is rendered, so the
   * component works both as a wrapper and as a layout route element.
   */
  children?: ReactNode;
}

/**
 * Route guard (FRONTEND-ARCHITECTURE.md §5):
 *  - while the session bootstraps -> spinner
 *  - no authenticated user -> redirect to `/login`
 *  - authenticated but missing `permission` -> 403 page
 *  - authenticated but holding none of `anyPermission` -> 403 page
 */
export function RequireAuth({
  permission,
  anyPermission,
  children,
}: RequireAuthProps) {
  const { user, loading, permissions } = useAuth();
  const location = useLocation();

  if (loading) {
    return <FullscreenLoader />;
  }

  if (!user) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  if (permission && !permissions.includes(permission)) {
    return <ForbiddenPage />;
  }

  if (
    anyPermission &&
    anyPermission.length > 0 &&
    !hasAnyPermission(permissions, anyPermission)
  ) {
    return <ForbiddenPage />;
  }

  return <>{children ?? <Outlet />}</>;
}
