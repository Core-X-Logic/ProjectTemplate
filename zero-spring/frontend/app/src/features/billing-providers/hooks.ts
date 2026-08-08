import { ApiError } from '@/api/client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { toast } from 'sonner';
import {
  clearBillingCredentials,
  listBillingProviders,
  saveBillingCredentials,
  saveProviderOrder,
} from './api';
import type {
  ProviderStatusDto,
  UpdateProviderCredentialsRequest,
} from './types';

/**
 * React-query bindings for the billing provider credentials feature.
 *
 * Every mutation invalidates the `['billing-providers']` key family so the
 * card statuses (and the masked hint returned by the backend) refresh after a
 * save or clear. Failure toasts prefer the ProblemDetail `detail` so backend
 * rules reach the operator verbatim.
 */

export const billingProviderKeys = {
  all: ['billing-providers'] as const,
};

/** Provider statuses (masked hint + flags — never raw values). */
export function useBillingProviders() {
  return useQuery({
    queryKey: billingProviderKeys.all,
    queryFn: listBillingProviders,
  });
}

/** Shared success/error handlers: toast + `['billing-providers']` invalidation. */
function useCredentialMutationHandlers(successMessageId: string) {
  const intl = useIntl();
  const queryClient = useQueryClient();

  return {
    onSuccess: async () => {
      toast.success(intl.formatMessage({ id: successMessageId }));
      await queryClient.invalidateQueries({
        queryKey: billingProviderKeys.all,
      });
    },
    onError: (error: unknown) => {
      const fallback = intl.formatMessage({
        id: 'billingProviders.toast.error',
      });
      toast.error(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    },
  };
}

/** Save (write-only) credentials for one provider. */
export function useSaveBillingCredentials() {
  const handlers = useCredentialMutationHandlers(
    'billingProviders.toast.saved',
  );
  return useMutation({
    mutationFn: ({
      provider,
      body,
    }: {
      provider: string;
      body: UpdateProviderCredentialsRequest;
    }) => saveBillingCredentials(provider, body),
    ...handlers,
  });
}

/** Clear stored credentials — the provider falls back to env/yml config. */
export function useClearBillingCredentials() {
  const handlers = useCredentialMutationHandlers(
    'billingProviders.toast.cleared',
  );
  return useMutation({
    mutationFn: (provider: string) => clearBillingCredentials(provider),
    ...handlers,
  });
}

/** Persist the full failover order. */
export function useSaveProviderOrder() {
  const queryClient = useQueryClient();
  const handlers = useCredentialMutationHandlers(
    'billingProviders.toast.orderSaved',
  );
  return useMutation({
    mutationFn: (order: string[]) => saveProviderOrder(order),
    ...handlers,
    onSuccess: async (data: ProviderStatusDto[] | undefined) => {
      // The order endpoint returns the FULL updated list — seed the cache so
      // the new order paints immediately; the shared invalidation still runs
      // as the durable consistency net.
      if (data) {
        queryClient.setQueryData(billingProviderKeys.all, data);
      }
      await handlers.onSuccess();
    },
  });
}
