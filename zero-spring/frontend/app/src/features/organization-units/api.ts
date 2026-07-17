import { apiFetch } from '@/api/client';
import type {
  CreateOuRequest,
  MoveOuRequest,
  OrganizationUnit,
  UpdateOuRequest,
} from './types';

/**
 * Organization Units endpoint wrappers (typed via `src/api/schema.d.ts`).
 *
 * The backend guards every operation with `organizationunits.manage`
 * (`@PreAuthorize` double lock, FRONTEND-ARCHITECTURE.md §5).
 */

const BASE_PATH = '/api/organization-units';

/** `GET /api/organization-units` — flat list; the tree is built client-side. */
export function listOrganizationUnits(): Promise<OrganizationUnit[]> {
  return apiFetch<OrganizationUnit[]>(BASE_PATH);
}

/** `POST /api/organization-units` — create a root (no `parentId`) or child unit. */
export function createOrganizationUnit(
  body: CreateOuRequest,
): Promise<OrganizationUnit> {
  return apiFetch<OrganizationUnit>(BASE_PATH, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/** `PUT /api/organization-units/{id}` — rename (only `displayName` is mutable). */
export function updateOrganizationUnit(
  id: number,
  body: UpdateOuRequest,
): Promise<OrganizationUnit> {
  return apiFetch<OrganizationUnit>(`${BASE_PATH}/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

/** `PUT /api/organization-units/{id}/move` — re-parent; omit `newParentId` for root. */
export function moveOrganizationUnit(
  id: number,
  body: MoveOuRequest,
): Promise<OrganizationUnit> {
  return apiFetch<OrganizationUnit>(`${BASE_PATH}/${id}/move`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

/** `DELETE /api/organization-units/{id}` — 204 on success. */
export function removeOrganizationUnit(id: number): Promise<void> {
  return apiFetch<void>(`${BASE_PATH}/${id}`, { method: 'DELETE' });
}
