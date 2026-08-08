/**
 * Billing provider credentials feature types (managed billing credentials +
 * failover slice).
 *
 * Wire types are ALIASED from the generated OpenAPI schema (`npm run gen:api`
 * → `src/api/schema.d.ts`). The backend contract landed, so the earlier
 * hand-typed DTOs were converted to `components['schemas'][...]` aliases
 * (typed-client-drift lesson — the generated schema is the only durable
 * source of truth). Only frontend-only structures (field descriptors, brand
 * labels, badge status union) remain hand-written below.
 *
 * Contract (generated, authoritative):
 *  - `GET    /api/billing/providers` → `ProviderStatusDto[]`
 *  - `PUT    /api/billing/providers/{providerId}/credentials`
 *    → `UpdateProviderCredentialsRequest` → `ProviderStatusDto`
 *  - `DELETE /api/billing/providers/{providerId}/credentials` → no content
 *  - `PUT    /api/billing/providers/order`
 *    → `UpdateProviderOrderRequest` → `ProviderStatusDto[]` (full list)
 * Raw credential values NEVER travel back to the client — only `configured`,
 * `maskedHint`, `configuredFields`, `source` and flags.
 */
import type { components } from '@/api/schema';

/** One row of `GET /api/billing/providers` (also returned by both PUTs). */
export type ProviderStatusDto = components['schemas']['ProviderStatusDto'];

/** Write-only body of `PUT /api/billing/providers/{providerId}/credentials`. */
export type UpdateProviderCredentialsRequest =
  components['schemas']['UpdateProviderCredentialsRequest'];

/** Body of `PUT /api/billing/providers/order` — full failover order. */
export type UpdateProviderOrderRequest =
  components['schemas']['UpdateProviderOrderRequest'];

/**
 * Credential field descriptors per known provider. The dialog renders one
 * write-only password input per field; unknown providers returned by the
 * backend get a status card but no edit dialog. Frontend-only structure —
 * deliberately NOT part of the generated schema.
 */
export interface ProviderFieldConfig {
  /** Wire name (`credentials` map key) — must match the backend request DTO. */
  name: string;
  /** i18n id for the field label. */
  labelId: string;
}

export const PROVIDER_FIELDS: Record<string, readonly ProviderFieldConfig[]> = {
  paytr: [
    { name: 'merchantId', labelId: 'billingProviders.field.merchantId' },
    { name: 'merchantKey', labelId: 'billingProviders.field.merchantKey' },
    { name: 'merchantSalt', labelId: 'billingProviders.field.merchantSalt' },
  ],
  iyzico: [
    { name: 'apiKey', labelId: 'billingProviders.field.apiKey' },
    { name: 'secretKey', labelId: 'billingProviders.field.secretKey' },
  ],
};

/** Human display names — brand names, deliberately not translated. */
export const PROVIDER_LABELS: Record<string, string> = {
  paytr: 'PayTR',
  iyzico: 'iyzico',
  stripe: 'Stripe',
};

export function providerLabel(provider: string): string {
  return PROVIDER_LABELS[provider] ?? provider;
}

/**
 * Card status derived from the backend `source` discriminator:
 *  - `'db'`  → `stored`       (credentials saved through the portal)
 *  - `'env'` → `env`          (configured from environment/yml only)
 *  - `'none'`/other → `unconfigured` (no usable credentials anywhere)
 */
export type ProviderCredentialStatus = 'stored' | 'env' | 'unconfigured';

export function credentialStatus(
  dto: ProviderStatusDto,
): ProviderCredentialStatus {
  if (dto.source === 'db') {
    return 'stored';
  }
  return dto.source === 'env' ? 'env' : 'unconfigured';
}
