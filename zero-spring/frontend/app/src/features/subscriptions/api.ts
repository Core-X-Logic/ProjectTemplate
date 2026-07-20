import { apiFetch } from '@/api/client';
import type {
  AssignEditionRequest,
  CheckoutSessionDto,
  FeatureValueDto,
  PageSubscriptionDto,
  StartCheckoutRequest,
  SubscriptionDetailDto,
  SubscriptionDto,
  SubscriptionListParams,
  TenantFeatureDto,
} from './types';

/**
 * Subscriptions + tenant-features endpoint wrappers (CONTRACT-phase5.md §A.1).
 *
 * All write endpoints are `Side.HOST`: a tenant cannot change its own edition
 * or feature values. `GET /api/subscriptions/me` is the only tenant-facing,
 * read-only endpoint here.
 */

const SUBSCRIPTIONS_URL = '/api/subscriptions';
const TENANT_FEATURES_URL = '/api/tenant-features';
const CHECKOUT_URL = '/api/billing/checkout';

/** `GET /api/subscriptions` — paged tenant/edition/status list. */
export function listSubscriptions(
  params: SubscriptionListParams = {},
): Promise<PageSubscriptionDto> {
  const query = new URLSearchParams();
  if (params.page !== undefined) {
    query.set('page', String(params.page));
  }
  if (params.size !== undefined) {
    query.set('size', String(params.size));
  }
  if (params.sort) {
    query.set('sort', params.sort);
  }
  const qs = query.toString();
  return apiFetch<PageSubscriptionDto>(
    qs ? `${SUBSCRIPTIONS_URL}?${qs}` : SUBSCRIPTIONS_URL,
  );
}

/** `GET /api/subscriptions/{tenantId}` — subscription plus its event log. */
export function getSubscription(
  tenantId: number,
): Promise<SubscriptionDetailDto> {
  return apiFetch<SubscriptionDetailDto>(`${SUBSCRIPTIONS_URL}/${tenantId}`);
}

/**
 * `PUT /api/subscriptions/{tenantId}/edition` — package assignment. The price
 * is snapshotted onto the subscription server-side, so nothing about the money
 * is computed here.
 */
export function assignEdition(
  tenantId: number,
  body: AssignEditionRequest,
): Promise<SubscriptionDetailDto> {
  return apiFetch<SubscriptionDetailDto>(
    `${SUBSCRIPTIONS_URL}/${tenantId}/edition`,
    { method: 'PUT', body: JSON.stringify(body) },
  );
}

/** `POST /api/subscriptions/{tenantId}/activate` — invalid transitions → 400. */
export function activateSubscription(
  tenantId: number,
): Promise<SubscriptionDetailDto> {
  return apiFetch<SubscriptionDetailDto>(
    `${SUBSCRIPTIONS_URL}/${tenantId}/activate`,
    { method: 'POST' },
  );
}

/** `POST /api/subscriptions/{tenantId}/cancel` — invalid transitions → 400. */
export function cancelSubscription(
  tenantId: number,
): Promise<SubscriptionDetailDto> {
  return apiFetch<SubscriptionDetailDto>(
    `${SUBSCRIPTIONS_URL}/${tenantId}/cancel`,
    { method: 'POST' },
  );
}

/** `GET /api/subscriptions/me` — the caller's own subscription (read-only). */
export function getMySubscription(): Promise<SubscriptionDto> {
  return apiFetch<SubscriptionDto>(`${SUBSCRIPTIONS_URL}/me`);
}

/**
 * `GET /api/tenant-features/{tenantId}` — resolved feature values for a tenant.
 * Each row carries the whole resolution chain (`overrideValue` → `editionValue`
 * → `defaultValue`) so the override editor can show what a cleared field would
 * fall back to.
 */
export function getTenantFeatures(
  tenantId: number,
): Promise<TenantFeatureDto[]> {
  return apiFetch<TenantFeatureDto[]>(`${TENANT_FEATURES_URL}/${tenantId}`);
}

/**
 * `POST /api/billing/checkout` — starts a hosted payment session
 * (CONTRACT-payments-tr P2'-C). Returns the provider's payment page `url`
 * (PayTR iframe URL or iyzico `paymentPageUrl`); the caller opens it in a NEW
 * TAB — never an iframe (CSP + external iframeResizer script). Activation is
 * strictly server-side (webhook/reconciliation): nothing about the redirect
 * back to `successUrl` proves payment. Host-only (`subscriptions.manage`); a
 * disabled/unknown `provider` yields a 400 ProblemDetail naming enabled ids.
 */
export function startCheckout(
  body: StartCheckoutRequest,
): Promise<CheckoutSessionDto> {
  return apiFetch<CheckoutSessionDto>(CHECKOUT_URL, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/** `PUT /api/tenant-features/{tenantId}` — batch override assignment. */
export function updateTenantFeatures(
  tenantId: number,
  values: FeatureValueDto[],
): Promise<TenantFeatureDto[]> {
  return apiFetch<TenantFeatureDto[]>(`${TENANT_FEATURES_URL}/${tenantId}`, {
    method: 'PUT',
    body: JSON.stringify(values),
  });
}
