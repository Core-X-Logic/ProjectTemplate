import { apiFetch } from '@/api/client';
import type {
  AssignOrganizationUnitsRequest,
  AssignRolesRequest,
  CreateUserRequest,
  OrganizationUnitDto,
  PageRoleDto,
  PageUserDto,
  UpdateUserRequest,
  UserDto,
  UserListParams,
} from '@/features/users/types';

/**
 * Typed endpoint wrappers for the users feature (FRONTEND-ARCHITECTURE.md §7).
 *
 * Every request/response shape mirrors `src/api/schema.d.ts` operations
 * (`list`, `getById`, `create`, `update`, `delete`, `unlock`, `activate_1`,
 * `deactivate_1`, `assignRoles`, `assignOrganizationUnits`, `export`).
 * Transport (auth header, tenant header, 401 refresh, ProblemDetail parsing)
 * is handled by `apiFetch`.
 */

const USERS_BASE = '/api/users';

function buildListQuery(params: UserListParams): string {
  const query = new URLSearchParams();
  if (params.page !== undefined) {
    query.set('page', String(params.page));
  }
  if (params.size !== undefined) {
    query.set('size', String(params.size));
  }
  for (const sort of params.sort ?? []) {
    query.append('sort', sort);
  }
  if (params.search?.trim()) {
    query.set('search', params.search.trim());
  }
  if (params.tenantId !== undefined) {
    query.set('tenantId', String(params.tenantId));
  }
  const encoded = query.toString();
  return encoded ? `?${encoded}` : '';
}

/** `GET /api/users` — server-side pagination (`users.read`). */
export function listUsers(params: UserListParams = {}): Promise<PageUserDto> {
  return apiFetch<PageUserDto>(`${USERS_BASE}${buildListQuery(params)}`);
}

/** `GET /api/users/{id}` (`users.read`). */
export function getUserById(id: number): Promise<UserDto> {
  return apiFetch<UserDto>(`${USERS_BASE}/${id}`);
}

/** `POST /api/users` (`users.create`). */
export function createUser(body: CreateUserRequest): Promise<UserDto> {
  return apiFetch<UserDto>(USERS_BASE, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/** `PUT /api/users/{id}` (`users.update`). */
export function updateUser(
  id: number,
  body: UpdateUserRequest,
): Promise<UserDto> {
  return apiFetch<UserDto>(`${USERS_BASE}/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

/** `DELETE /api/users/{id}` — soft delete (`users.delete`). */
export function removeUser(id: number): Promise<void> {
  return apiFetch<void>(`${USERS_BASE}/${id}`, { method: 'DELETE' });
}

/** `POST /api/users/{id}/unlock` (`users.unlock`). */
export function unlockUser(id: number): Promise<UserDto> {
  return apiFetch<UserDto>(`${USERS_BASE}/${id}/unlock`, { method: 'POST' });
}

/** `POST /api/users/{id}/activate` (`users.update`). */
export function activateUser(id: number): Promise<UserDto> {
  return apiFetch<UserDto>(`${USERS_BASE}/${id}/activate`, { method: 'POST' });
}

/** `POST /api/users/{id}/deactivate` (`users.update`). */
export function deactivateUser(id: number): Promise<UserDto> {
  return apiFetch<UserDto>(`${USERS_BASE}/${id}/deactivate`, {
    method: 'POST',
  });
}

/** `PUT /api/users/{id}/roles` (`users.update`). */
export function assignRoles(
  id: number,
  body: AssignRolesRequest,
): Promise<UserDto> {
  return apiFetch<UserDto>(`${USERS_BASE}/${id}/roles`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

/** `PUT /api/users/{id}/organization-units` (`users.update`). */
export function assignOrganizationUnits(
  id: number,
  body: AssignOrganizationUnitsRequest,
): Promise<UserDto> {
  return apiFetch<UserDto>(`${USERS_BASE}/${id}/organization-units`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

/**
 * `GET /api/users/export` — XLSX download (`users.read`).
 *
 * Uses `apiFetch`'s `responseType: 'blob'` mode so the binary export reuses the
 * shared transport (Authorization / X-Tenant / Accept-Language headers, the
 * single-flight 401 refresh and ProblemDetail → `ApiError` mapping) exactly
 * like every other call, instead of a hand-rolled `fetch`.
 */
export function exportUsersExcel(): Promise<Blob> {
  return apiFetch<Blob>(
    `${USERS_BASE}/export`,
    {
      headers: {
        Accept:
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      },
    },
    { responseType: 'blob' },
  );
}

/* -------------------------------------------------------------------------- */
/* Lookup sources for the form selects (read-only, other features own writes)  */
/* -------------------------------------------------------------------------- */

/** `GET /api/roles` — first page large enough for a lookup list (`roles.read`). */
export async function listRoleOptions(): Promise<PageRoleDto> {
  return apiFetch<PageRoleDto>('/api/roles?page=0&size=200&sort=name,asc');
}

/** `GET /api/organization-units` — flat tree nodes (`organizationunits.manage`). */
export function listOrganizationUnits(): Promise<OrganizationUnitDto[]> {
  return apiFetch<OrganizationUnitDto[]>('/api/organization-units');
}
