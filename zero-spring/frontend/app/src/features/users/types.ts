import type { components } from '@/api/schema';

/**
 * Users feature types (FRONTEND-ARCHITECTURE.md §7).
 *
 * All shapes are derived from the generated OpenAPI schema (`src/api/schema.d.ts`)
 * so the feature stays in lock-step with the backend contract. Never re-declare
 * these by hand — re-run `npm run gen:api` instead.
 */

export type UserDto = components['schemas']['UserDto'];
export type PageUserDto = components['schemas']['PageUserDto'];
export type CreateUserRequest = components['schemas']['CreateUserRequest'];
export type UpdateUserRequest = components['schemas']['UpdateUserRequest'];
export type AssignRolesRequest = components['schemas']['AssignRolesRequest'];
export type AssignOrganizationUnitsRequest =
  components['schemas']['AssignOuRequest'];
export type Pageable = components['schemas']['Pageable'];

/** Role summary used by the role multi-select (source: `GET /api/roles`). */
export type RoleDto = components['schemas']['RoleDto'];
export type PageRoleDto = components['schemas']['PageRoleDto'];

/** Organization unit node (source: `GET /api/organization-units`). */
export type OrganizationUnitDto = components['schemas']['OuDto'];

/** Server-side list parameters (Spring `Pageable` binding + free-text search). */
export interface UserListParams {
  /** Zero-based page index. */
  page?: number;
  /** Page size. */
  size?: number;
  /** Spring sort expressions, e.g. `['username,asc']`. */
  sort?: string[];
  /** Free-text filter (forwarded as `search`; ignored by older backends). */
  search?: string;
}
