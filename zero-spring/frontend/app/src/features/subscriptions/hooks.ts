import { ApiError } from '@/api/client';
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { toast } from 'sonner';
import {
  activateSubscription,
  assignEdition,
  cancelSubscription,
  getMySubscription,
  getSubscription,
  getTenantFeatures,
  listSubscriptions,
  updateTenantFeatures,
} from './api';
import type {
  AssignEditionRequest,
  FeatureValueDto,
  SubscriptionListParams,
} from './types';

/**
 * React-query bindings for the subscriptions feature (CONTRACT-phase5.md §A.2).
 *
 * Every mutation invalidates the `['subscriptions']` family (and, for feature
 * writes, the tenant's feature key) and raises a sonner toast. Failure toasts
 * prefer the ProblemDetail `detail`, so an invalid state transition (400) or a
 * `Side.HOST` violation (403) reaches the operator with the backend's wording.
 */

export const subscriptionKeys = {
  all: ['subscriptions'] as const,
  lists: () => [...subscriptionKeys.all, 'list'] as const,
  list: (params: SubscriptionListParams) =>
    [...subscriptionKeys.lists(), params] as const,
  detail: (tenantId: number) =>
    [...subscriptionKeys.all, 'detail', tenantId] as const,
  me: () => [...subscriptionKeys.all, 'me'] as const,
};

export const tenantFeatureKeys = {
  all: ['tenant-features'] as const,
  byTenant: (tenantId: number) =>
    [...tenantFeatureKeys.all, tenantId] as const,
};

/** Paged subscription list; previous page stays on screen while loading. */
export function useSubscriptions(params: SubscriptionListParams = {}) {
  return useQuery({
    queryKey: subscriptionKeys.list(params),
    queryFn: () => listSubscriptions(params),
    placeholderData: keepPreviousData,
  });
}

/** Subscription detail (+ event log). Disabled until a tenant id arrives. */
export function useSubscription(tenantId?: number) {
  return useQuery({
    queryKey: subscriptionKeys.detail(tenantId ?? -1),
    queryFn: () => getSubscription(tenantId as number),
    enabled: tenantId !== undefined,
  });
}

/** The caller's own subscription (read-only, any authenticated user). */
export function useMySubscription() {
  return useQuery({
    queryKey: subscriptionKeys.me(),
    queryFn: getMySubscription,
  });
}

/** Resolved feature values for one tenant. Disabled until an id arrives. */
export function useTenantFeatures(tenantId?: number) {
  return useQuery({
    queryKey: tenantFeatureKeys.byTenant(tenantId ?? -1),
    queryFn: () => getTenantFeatures(tenantId as number),
    enabled: tenantId !== undefined,
  });
}

/** Shared success/error handlers: toast + `['subscriptions']` invalidation. */
function useSubscriptionMutationHandlers(successMessageId: string) {
  const intl = useIntl();
  const queryClient = useQueryClient();

  return {
    onSuccess: async () => {
      toast.success(intl.formatMessage({ id: successMessageId }));
      await queryClient.invalidateQueries({ queryKey: subscriptionKeys.all });
    },
    onError: (error: unknown) => {
      const fallback = intl.formatMessage({ id: 'subscriptions.toast.error' });
      toast.error(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    },
  };
}

/** `PUT .../edition` — package assignment (edition + period + trial). */
export function useAssignEdition() {
  const handlers = useSubscriptionMutationHandlers(
    'subscriptions.toast.assigned',
  );
  return useMutation({
    mutationFn: ({
      tenantId,
      body,
    }: {
      tenantId: number;
      body: AssignEditionRequest;
    }) => assignEdition(tenantId, body),
    ...handlers,
  });
}

export function useActivateSubscription() {
  const handlers = useSubscriptionMutationHandlers(
    'subscriptions.toast.activated',
  );
  return useMutation({
    mutationFn: (tenantId: number) => activateSubscription(tenantId),
    ...handlers,
  });
}

export function useCancelSubscription() {
  const handlers = useSubscriptionMutationHandlers(
    'subscriptions.toast.cancelled',
  );
  return useMutation({
    mutationFn: (tenantId: number) => cancelSubscription(tenantId),
    ...handlers,
  });
}

/**
 * `PUT /api/tenant-features/{tenantId}` — batch override save. Invalidates the
 * tenant's own feature key (the list itself is unaffected by feature edits).
 */
export function useUpdateTenantFeatures() {
  const intl = useIntl();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({
      tenantId,
      values,
    }: {
      tenantId: number;
      values: FeatureValueDto[];
    }) => updateTenantFeatures(tenantId, values),
    onSuccess: async (_data, variables) => {
      toast.success(
        intl.formatMessage({ id: 'subscriptions.toast.featuresSaved' }),
      );
      await queryClient.invalidateQueries({
        queryKey: tenantFeatureKeys.byTenant(variables.tenantId),
      });
    },
    onError: (error: unknown) => {
      const fallback = intl.formatMessage({ id: 'subscriptions.toast.error' });
      toast.error(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    },
  });
}
