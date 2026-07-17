import { apiFetch } from '@/api/client';
import type {
  CreateRoleRequest,
  PageRoleDto,
  PermissionNodeDto,
  RoleDetailDto,
  RoleListParams,
  UpdateRoleRequest,
} from './types';

/**
 * Roles endpoint wrappers (FRONTEND-ARCHITECTURE.md §7).
 *
 * Thin, typed functions over `apiFetch` — auth/tenant/locale headers, the 401
 * refresh dance and ProblemDetail → `ApiError` mapping all live in the client.
 */

const ROLES_URL = '/api/roles';

/** `GET /api/roles` — paged role list (Spring `Pageable` as query params). */
export function listRoles(params: RoleListParams = {}): Promise<PageRoleDto> {
  const query = new URLSearchParams();
  if (params.page !== undefined) {
    query.set('page', String(params.page));
  }
  if (params.size !== undefined) {
    query.set('size', String(params.size));
  }
  if (params.sort) {
    query.set('sort', params.sort);
  }
  const qs = query.toString();
  return apiFetch<PageRoleDto>(qs ? `${ROLES_URL}?${qs}` : ROLES_URL);
}

/** `GET /api/roles/{id}` — role detail including its permission names. */
export function getRoleById(id: number): Promise<RoleDetailDto> {
  return apiFetch<RoleDetailDto>(`${ROLES_URL}/${id}`);
}

/** `POST /api/roles` — create a role with an initial permission set. */
export function createRole(body: CreateRoleRequest): Promise<RoleDetailDto> {
  return apiFetch<RoleDetailDto>(ROLES_URL, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/** `PUT /api/roles/{id}` — update display name / default flag / permissions. */
export function updateRole(
  id: number,
  body: UpdateRoleRequest,
): Promise<RoleDetailDto> {
  return apiFetch<RoleDetailDto>(`${ROLES_URL}/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

/** `DELETE /api/roles/{id}` — static roles are rejected by the backend. */
export function removeRole(id: number): Promise<void> {
  return apiFetch<void>(`${ROLES_URL}/${id}`, { method: 'DELETE' });
}

/** `POST /api/roles/{id}/clone` — copies the role together with its permissions. */
export function cloneRole(id: number): Promise<RoleDetailDto> {
  return apiFetch<RoleDetailDto>(`${ROLES_URL}/${id}/clone`, {
    method: 'POST',
  });
}

/**
 * `GET /api/permissions/tree` — the full permission catalogue as a tree.
 * Host-only permissions are already filtered out server-side for tenant users,
 * so the frontend renders whatever it receives without extra filtering.
 */
export function getPermissionTree(): Promise<PermissionNodeDto[]> {
  return apiFetch<PermissionNodeDto[]>('/api/permissions/tree');
}
