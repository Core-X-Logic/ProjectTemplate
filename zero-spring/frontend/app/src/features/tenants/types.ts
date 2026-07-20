import type { components } from '@/api/schema';

/**
 * Tenants feature types (U-01, flow 3).
 *
 * DTO shapes are aliased from the generated OpenAPI schema (`npm run gen:api`).
 */

export type TenantDto = components['schemas']['TenantDto'];
export type CreateTenantRequest =
  components['schemas']['CreateTenantRequest'];

/**
 * Response of `POST /api/tenants`. `generatedAdminPassword` is a ONE-TIME
 * disclosure: non-null only when the request omitted `adminPassword` (the
 * server then generated the bootstrap admin's credential). It can never be
 * retrieved again — only its hash is persisted — so the dialog must show it
 * once and let it go: no storage, no logging.
 */
export type CreateTenantResponse =
  components['schemas']['CreateTenantResponse'];

/**
 * `CreateTenantRequest.name` is `@Pattern(regexp = "[a-z0-9-]{2,30}")` —
 * lowercase letters, digits and hyphens, 2-30 characters. Mirrored here so the
 * dialog can explain the rule instead of bouncing the user off a 400.
 *
 * Anchored on this side because Java's `@Pattern` matches the WHOLE string
 * while JavaScript's `RegExp.test` matches a substring — without `^…$` the
 * client would accept names the backend rejects.
 */
export const TENANT_NAME_PATTERN = /^[a-z0-9-]{2,30}$/;

/**
 * The single permission guarding every tenant endpoint.
 *
 * `TenantController` carries a CLASS-level
 * `@PreAuthorize("hasAuthority('tenants.manage')")`, so list, create, activate
 * and deactivate all sit behind this one key. It is declared `Side.HOST` in
 * `PermissionDefinitions`, which is what makes tenant management host-only: a
 * tenant-side role can never hold it.
 */
export const TENANTS_MANAGE = 'tenants.manage';
