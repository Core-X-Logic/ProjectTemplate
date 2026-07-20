import { apiFetch } from '@/api/client';
import type {
  CreateTenantRequest,
  CreateTenantResponse,
  TenantDto,
} from './types';

/**
 * Tenants endpoint wrappers (`TenantController`).
 *
 * Every method is covered by the controller's class-level
 * `@PreAuthorize("hasAuthority('tenants.manage')")`; the UI mirrors that with a
 * route guard plus `<Can>` (double lock).
 *
 * NOTE — the surface is deliberately small because the backend's is:
 * there is no `PUT /api/tenants/{id}` and no `DELETE`. A tenant's `name` and
 * `displayName` cannot be edited after creation, and tenants cannot be removed;
 * deactivation is the only "off" switch. Do not add wrappers for endpoints that
 * do not exist.
 */

const TENANTS_URL = '/api/tenants';

/**
 * `GET /api/tenants` — every tenant.
 *
 * Returns a plain array, NOT a Spring `Page`: `TenantController.list()` is
 * `List<TenantDto>` with no `Pageable`. The grid therefore paginates client-side
 * rather than sending page/size params the backend would ignore.
 */
export function listTenants(): Promise<TenantDto[]> {
  return apiFetch<TenantDto[]>(TENANTS_URL);
}

/**
 * `POST /api/tenants` — 201 with the created tenant AND its bootstrap admin
 * (Issue #1 closed by backend 20247d5: `adminEmail` is now required, and the
 * tenant is created together with an `admin` user so someone can sign in).
 *
 * A duplicate `name` is a 409 whose ProblemDetail `detail` names the clash.
 *
 * When the request omits `adminPassword`, the response's
 * `generatedAdminPassword` carries the server-generated credential EXACTLY
 * ONCE — the caller shows it and forgets it (never stored, never logged).
 */
export function createTenant(
  body: CreateTenantRequest,
): Promise<CreateTenantResponse> {
  return apiFetch<CreateTenantResponse>(TENANTS_URL, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/** `PUT /api/tenants/{id}/activate` — returns the updated tenant. */
export function activateTenant(id: number): Promise<TenantDto> {
  return apiFetch<TenantDto>(`${TENANTS_URL}/${id}/activate`, {
    method: 'PUT',
  });
}

/** `PUT /api/tenants/{id}/deactivate` — returns the updated tenant. */
export function deactivateTenant(id: number): Promise<TenantDto> {
  return apiFetch<TenantDto>(`${TENANTS_URL}/${id}/deactivate`, {
    method: 'PUT',
  });
}
