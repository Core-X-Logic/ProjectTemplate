import { apiFetch } from '@/api/client';
import type { CreateTenantRequest, TenantDto } from './types';

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
 * `POST /api/tenants` — 201 with the created tenant.
 *
 * A duplicate `name` is a 409 whose ProblemDetail `detail` names the clash.
 *
 * KNOWN GAP (Issue #1): this creates the tenant row and provisions a default
 * subscription (via `TenantCreatedEvent`) but does NOT create an admin user for
 * it. The new tenant therefore has nobody who can sign in. The create dialog
 * says so out loud rather than leaving the operator to discover it.
 */
export function createTenant(body: CreateTenantRequest): Promise<TenantDto> {
  return apiFetch<TenantDto>(TENANTS_URL, {
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
