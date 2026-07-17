import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { useIntl, type IntlShape } from 'react-intl';
import { toast } from 'sonner';
import { ApiError } from '@/api/client';
import {
  createOrganizationUnit,
  listOrganizationUnits,
  moveOrganizationUnit,
  removeOrganizationUnit,
  updateOrganizationUnit,
} from './api';
import type {
  CreateOuRequest,
  MoveOuRequest,
  OrganizationUnit,
  UpdateOuRequest,
} from './types';

/**
 * Query/mutation hooks for the Organization Units feature.
 *
 * Every mutation invalidates the `['organization-units']` cache and reports the
 * outcome through a sonner toast (FRONTEND-ARCHITECTURE.md §7).
 */

export const OU_QUERY_KEY = ['organization-units'] as const;

function toastApiError(intl: IntlShape, error: unknown): void {
  const fallback = intl.formatMessage({ id: 'organizationUnits.toast.error' });
  toast.error(fallback, {
    description:
      error instanceof ApiError && error.detail ? error.detail : undefined,
  });
}

/** Flat unit list; the page derives the tree via `buildOuTree`. */
export function useOrganizationUnits(): UseQueryResult<OrganizationUnit[]> {
  return useQuery({
    queryKey: OU_QUERY_KEY,
    queryFn: listOrganizationUnits,
  });
}

export function useCreateOu(): UseMutationResult<
  OrganizationUnit,
  Error,
  CreateOuRequest
> {
  const intl = useIntl();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateOuRequest) => createOrganizationUnit(body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: OU_QUERY_KEY });
      toast.success(
        intl.formatMessage({ id: 'organizationUnits.toast.created' }),
      );
    },
    onError: (error) => toastApiError(intl, error),
  });
}

export function useUpdateOu(): UseMutationResult<
  OrganizationUnit,
  Error,
  { id: number; body: UpdateOuRequest }
> {
  const intl = useIntl();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: UpdateOuRequest }) =>
      updateOrganizationUnit(id, body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: OU_QUERY_KEY });
      toast.success(
        intl.formatMessage({ id: 'organizationUnits.toast.updated' }),
      );
    },
    onError: (error) => toastApiError(intl, error),
  });
}

export function useMoveOu(): UseMutationResult<
  OrganizationUnit,
  Error,
  { id: number; body: MoveOuRequest }
> {
  const intl = useIntl();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: MoveOuRequest }) =>
      moveOrganizationUnit(id, body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: OU_QUERY_KEY });
      toast.success(
        intl.formatMessage({ id: 'organizationUnits.toast.moved' }),
      );
    },
    onError: (error) => toastApiError(intl, error),
  });
}

export function useDeleteOu(): UseMutationResult<void, Error, number> {
  const intl = useIntl();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => removeOrganizationUnit(id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: OU_QUERY_KEY });
      toast.success(
        intl.formatMessage({ id: 'organizationUnits.toast.deleted' }),
      );
    },
    onError: (error) => toastApiError(intl, error),
  });
}
