import { apiFetch } from '@/api/client';
import type {
  ProviderStatusDto,
  UpdateProviderCredentialsRequest,
  UpdateProviderOrderRequest,
} from './types';

/**
 * Billing provider endpoint wrappers (paths + shapes verified against the
 * generated `src/api/schema.d.ts` — the authoritative contract).
 *
 * Every endpoint is `Side.HOST` + `billing.credentials.manage` on the backend;
 * the UI mirrors that with the route guard + `<Can>` (quadruple lock together
 * with the menu filter). Credential values are WRITE-ONLY: the GET returns
 * status + masked hint only, and nothing here ever receives a raw secret back.
 */

const PROVIDERS_URL = '/api/billing/providers';

/** `GET /api/billing/providers` — status per provider (mask, never values). */
export function listBillingProviders(): Promise<ProviderStatusDto[]> {
  return apiFetch<ProviderStatusDto[]>(PROVIDERS_URL);
}

/**
 * `PUT /api/billing/providers/{providerId}/credentials` — save credentials.
 * Fields left empty by the operator are OMITTED from the `credentials` map
 * ("do not change"), never sent as `""`. Returns the updated status row.
 */
export function saveBillingCredentials(
  providerId: string,
  body: UpdateProviderCredentialsRequest,
): Promise<ProviderStatusDto> {
  return apiFetch<ProviderStatusDto>(
    `${PROVIDERS_URL}/${providerId}/credentials`,
    {
      method: 'PUT',
      body: JSON.stringify(body),
    },
  );
}

/**
 * `DELETE /api/billing/providers/{providerId}/credentials` — clear; env/yml
 * applies again.
 */
export function clearBillingCredentials(providerId: string): Promise<void> {
  return apiFetch<void>(`${PROVIDERS_URL}/${providerId}/credentials`, {
    method: 'DELETE',
  });
}

/**
 * `PUT /api/billing/providers/order` — full failover order wrapped in
 * `{ order: [...] }`. Returns the FULL updated status list, which callers can
 * use to refresh the cache without waiting for a refetch.
 */
export function saveProviderOrder(
  order: string[],
): Promise<ProviderStatusDto[]> {
  const body: UpdateProviderOrderRequest = { order };
  return apiFetch<ProviderStatusDto[]>(`${PROVIDERS_URL}/order`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}
