import type { components } from '@/api/schema';

/**
 * Roles feature types (FRONTEND-ARCHITECTURE.md §7).
 *
 * DTO shapes are aliased from the generated OpenAPI schema (`npm run gen:api`)
 * so the feature stays in lock-step with the backend contract.
 */

export type RoleDto = components['schemas']['RoleDto'];
export type RoleDetailDto = components['schemas']['RoleDetailDto'];
export type PageRoleDto = components['schemas']['PageRoleDto'];
export type CreateRoleRequest = components['schemas']['CreateRoleRequest'];
export type UpdateRoleRequest = components['schemas']['UpdateRoleRequest'];
export type PermissionNodeDto = components['schemas']['PermissionNodeDto'];

/** Spring `Pageable` request, flattened to the query-string wire format. */
export interface RoleListParams {
  /** Zero-based page index. */
  page?: number;
  /** Page size. */
  size?: number;
  /** Spring sort expression, e.g. `name,asc`. */
  sort?: string;
}
