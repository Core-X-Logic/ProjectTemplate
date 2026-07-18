import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ApiError } from '@/api/client';
import {
  getHostSettings,
  getTenantSettings,
  updateHostSettings,
  updateTenantSettings,
} from './api';
import { useSettingsMessages } from './messages';
import type { SettingDto, SettingScope, SettingUpdate } from './types';

/**
 * TanStack Query bindings for the settings feature (FRONTEND-ARCHITECTURE.md §7).
 *
 * Query keys:
 *  - `['settings','tenant']` — tenant-scope overrides
 *  - `['settings','host']`   — host-scope overrides
 *  - `['settings','client']` — read-only bootstrap map (invalidated by writes
 *    because a tenant/host change can alter the client-visible projection)
 *
 * Every mutation invalidates its own scope plus the client key and raises a
 * localized sonner toast.
 */

export const settingsKeys = {
  all: ['settings'] as const,
  tenant: ['settings', 'tenant'] as const,
  host: ['settings', 'host'] as const,
  client: ['settings', 'client'] as const,
};

/** Effective tenant-scope settings. */
export function useTenantSettings() {
  return useQuery({
    queryKey: settingsKeys.tenant,
    queryFn: getTenantSettings,
  });
}

/** Effective host-scope settings (host operators only). */
export function useHostSettings() {
  return useQuery({
    queryKey: settingsKeys.host,
    queryFn: getHostSettings,
  });
}

/* -------------------------------------------------------------------------- */
/* Mutations                                                                    */
/* -------------------------------------------------------------------------- */

/**
 * Shared batch-update mutation. Invalidates the edited scope AND the client
 * bootstrap key, since a tenant/host change may shift the client projection.
 */
function useUpdateSettings(scope: SettingScope) {
  const queryClient = useQueryClient();
  const t = useSettingsMessages();

  const mutationFn = scope === 'tenant' ? updateTenantSettings : updateHostSettings;
  const scopeKey = scope === 'tenant' ? settingsKeys.tenant : settingsKeys.host;

  return useMutation<SettingDto[], unknown, SettingUpdate[]>({
    mutationFn: (items) => mutationFn(items),
    onSuccess: async () => {
      toast.success(t('settings.saved'));
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: scopeKey }),
        queryClient.invalidateQueries({ queryKey: settingsKeys.client }),
      ]);
    },
    onError: (error) => {
      toast.error(t('settings.error'), {
        description: error instanceof ApiError ? error.detail : undefined,
      });
    },
  });
}

/** `PUT /api/settings/tenant` as a mutation. */
export function useUpdateTenantSettings() {
  return useUpdateSettings('tenant');
}

/** `PUT /api/settings/host` as a mutation. */
export function useUpdateHostSettings() {
  return useUpdateSettings('host');
}
