import { ApiError } from '@/api/client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { toast } from 'sonner';
import {
  activateTenant,
  createTenant,
  deactivateTenant,
  listTenants,
} from './api';
import type { CreateTenantRequest } from './types';

/**
 * React-query bindings for the tenants feature (U-01, flow 3).
 *
 * Every mutation invalidates the `['tenants']` key family and surfaces the
 * outcome via a sonner toast. Failure toasts prefer the ProblemDetail `detail`
 * so backend rules (409 "tenant already exists") reach the operator verbatim.
 */

export const tenantKeys = {
  all: ['tenants'] as const,
  lists: () => [...tenantKeys.all, 'list'] as const,
};

/** `GET /api/tenants` — unpaged; the grid slices the array client-side. */
export function useTenants() {
  return useQuery({
    queryKey: tenantKeys.lists(),
    queryFn: listTenants,
  });
}

/** Shared success/error handlers: toast + `['tenants']` invalidation. */
function useTenantMutationHandlers(successMessageId: string) {
  const intl = useIntl();
  const queryClient = useQueryClient();

  return {
    onSuccess: async () => {
      toast.success(intl.formatMessage({ id: successMessageId }));
      await queryClient.invalidateQueries({ queryKey: tenantKeys.all });
    },
    onError: (error: unknown) => {
      const fallback = intl.formatMessage({ id: 'tenants.toast.error' });
      toast.error(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    },
  };
}

export function useCreateTenant() {
  const handlers = useTenantMutationHandlers('tenants.toast.created');
  return useMutation({
    mutationFn: (body: CreateTenantRequest) => createTenant(body),
    ...handlers,
  });
}

export function useActivateTenant() {
  const handlers = useTenantMutationHandlers('tenants.toast.activated');
  return useMutation({
    mutationFn: (id: number) => activateTenant(id),
    ...handlers,
  });
}

export function useDeactivateTenant() {
  const handlers = useTenantMutationHandlers('tenants.toast.deactivated');
  return useMutation({
    mutationFn: (id: number) => deactivateTenant(id),
    ...handlers,
  });
}
