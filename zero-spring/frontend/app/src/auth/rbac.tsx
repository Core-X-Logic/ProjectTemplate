import { ReactNode } from 'react';
import { useAuth } from '@/providers/auth-provider';

/**
 * RBAC helpers (FRONTEND-ARCHITECTURE.md §5).
 *
 * Frontend guards are UX only — the backend enforces the same permissions via
 * `@PreAuthorize` (double lock). Never rely on these for security.
 */

/** Reactive check: does the current user hold `permission`? */
export function usePermission(permission: string): boolean {
  const { permissions } = useAuth();
  return permissions.includes(permission);
}

/**
 * Pure helper: does `owned` contain at least one of the `required` permissions?
 * Kept side-effect free so it can be used outside React (e.g. menu filtering).
 */
export function hasAnyPermission(
  owned: readonly string[],
  required: readonly string[],
): boolean {
  return required.some((permission) => owned.includes(permission));
}

interface CanProps {
  permission: string;
  children: ReactNode;
  /** Rendered when the permission is absent. Defaults to nothing. */
  fallback?: ReactNode;
}

/** Render-guard: shows `children` only when the user holds `permission`. */
export function Can({ permission, children, fallback = null }: CanProps) {
  const allowed = usePermission(permission);
  return <>{allowed ? children : fallback}</>;
}
