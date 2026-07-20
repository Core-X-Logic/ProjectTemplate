import type { components } from '@/api/schema';

/**
 * Subscriptions feature types (F5 slice A — CONTRACT-phase5.md §A.2).
 *
 * DTO shapes are aliased from the generated OpenAPI schema (`npm run gen:api`).
 */

export type SubscriptionDto = components['schemas']['SubscriptionDto'];
export type SubscriptionDetailDto =
  components['schemas']['SubscriptionDetailDto'];
export type SubscriptionEventDto =
  components['schemas']['SubscriptionEventDto'];
export type PageSubscriptionDto = components['schemas']['PageSubscriptionDto'];
export type AssignEditionRequest =
  components['schemas']['AssignEditionRequest'];
export type TenantFeatureDto = components['schemas']['TenantFeatureDto'];
export type FeatureValueDto = components['schemas']['FeatureValueDto'];
export type StartCheckoutRequest =
  components['schemas']['StartCheckoutRequest'];
export type CheckoutSessionDto = components['schemas']['CheckoutSessionDto'];

/** Spring `Pageable` request, flattened to the query-string wire format. */
export interface SubscriptionListParams {
  /** Zero-based page index. */
  page?: number;
  /** Page size. */
  size?: number;
  /** Spring sort expression, e.g. `tenantName,asc`. */
  sort?: string;
}

/**
 * Subscription lifecycle states (backend `SubscriptionStatus`). The wire type
 * is a plain string, so `subscriptionStatuses` is the UI's closed list and
 * anything outside it renders through the neutral `unknown` styling.
 */
export const SUBSCRIPTION_STATUSES = [
  'TRIALING',
  'ACTIVE',
  'GRACE',
  'EXPIRED',
  'CANCELLED',
  'PENDING_PAYMENT',
] as const;

export type SubscriptionStatus = (typeof SUBSCRIPTION_STATUSES)[number];

/** Narrows the wire status onto the known set, or `undefined` when unknown. */
export function toSubscriptionStatus(
  status?: string,
): SubscriptionStatus | undefined {
  const normalized = (status ?? '').toUpperCase();
  return (SUBSCRIPTION_STATUSES as readonly string[]).includes(normalized)
    ? (normalized as SubscriptionStatus)
    : undefined;
}

/** Billing periods accepted by `AssignEditionRequest.billingPeriod`. */
export const BILLING_PERIODS = ['MONTHLY', 'ANNUAL'] as const;

export type BillingPeriod = (typeof BILLING_PERIODS)[number];

/**
 * Hosted-checkout providers the UI offers (`StartCheckoutRequest.provider` is
 * a plain string on the wire). The backend rejects a disabled or unknown id
 * with a 400 ProblemDetail naming the enabled providers, so this list is a UI
 * convenience, not the source of truth.
 */
export const PAYMENT_PROVIDERS = ['paytr', 'iyzico'] as const;

export type PaymentProvider = (typeof PAYMENT_PROVIDERS)[number];
