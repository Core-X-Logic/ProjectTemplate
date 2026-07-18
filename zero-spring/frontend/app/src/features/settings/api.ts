import { apiFetch } from '@/api/client';
import type { ClientSettings, SettingDto, SettingUpdate } from './types';

/**
 * Typed endpoint wrappers for `/api/settings/*` (FRONTEND-ARCHITECTURE.md §7).
 *
 * Read/write are scope-symmetric: `tenant` and `host` expose a `GET` (current
 * effective settings) and a batch `PUT` (only changed entries). `client` is
 * read-only — it feeds the frontend bootstrap and never edits state; visibility
 * to the client is decided by the backend, not this screen. Transport (auth /
 * tenant headers, 401 refresh, ProblemDetail parsing) is handled by `apiFetch`.
 */

const TENANT_BASE = '/api/settings/tenant';
const HOST_BASE = '/api/settings/host';
const CLIENT_BASE = '/api/settings/client';

/** `GET /api/settings/tenant` (`settings.tenant.manage`). */
export function getTenantSettings(): Promise<SettingDto[]> {
  return apiFetch<SettingDto[]>(TENANT_BASE);
}

/** `PUT /api/settings/tenant` — batch update (`settings.tenant.manage`). */
export function updateTenantSettings(
  items: SettingUpdate[],
): Promise<SettingDto[]> {
  return apiFetch<SettingDto[]>(TENANT_BASE, {
    method: 'PUT',
    body: JSON.stringify(items),
  });
}

/** `GET /api/settings/host` (`settings.host.manage`). */
export function getHostSettings(): Promise<SettingDto[]> {
  return apiFetch<SettingDto[]>(HOST_BASE);
}

/** `PUT /api/settings/host` — batch update (`settings.host.manage`). */
export function updateHostSettings(
  items: SettingUpdate[],
): Promise<SettingDto[]> {
  return apiFetch<SettingDto[]>(HOST_BASE, {
    method: 'PUT',
    body: JSON.stringify(items),
  });
}

/** `GET /api/settings/client` — read-only bootstrap map (any session). */
export function getClientSettings(): Promise<ClientSettings> {
  return apiFetch<ClientSettings>(CLIENT_BASE);
}
