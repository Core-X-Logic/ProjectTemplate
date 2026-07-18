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
  createEdition,
  getEditionById,
  getFeatureDefinitions,
  listEditions,
  removeEdition,
  setEditionFeatures,
  updateEdition,
} from './api';
import type {
  CreateEditionRequest,
  EditionListParams,
  FeatureValueDto,
  UpdateEditionRequest,
} from './types';

/**
 * React-query bindings for the editions feature (CONTRACT-phase5.md §A.2).
 *
 * Every mutation invalidates the `['editions']` key family and surfaces the
 * outcome via a sonner toast. Failure toasts prefer the ProblemDetail `detail`
 * so backend rules (409 "edition in use", 400 "expiring edition must be free")
 * reach the operator verbatim instead of a generic message.
 */

export const editionKeys = {
  all: ['editions'] as const,
  lists: () => [...editionKeys.all, 'list'] as const,
  list: (params: EditionListParams) =>
    [...editionKeys.lists(), params] as const,
  detail: (id: number) => [...editionKeys.all, 'detail', id] as const,
};

export const featureDefinitionKeys = {
  all: ['feature-definitions'] as const,
};

/**
 * Paged edition list; the previous page stays on screen while loading.
 *
 * `enabled` lets a consumer that only needs the list conditionally (e.g. the
 * assign-edition dialog, which is mounted but closed most of the time) avoid
 * firing the request until it is actually shown.
 */
export function useEditions(
  params: EditionListParams = {},
  options: { enabled?: boolean } = {},
) {
  return useQuery({
    queryKey: editionKeys.list(params),
    queryFn: () => listEditions(params),
    placeholderData: keepPreviousData,
    enabled: options.enabled ?? true,
  });
}

/** Edition detail (with feature values). Disabled until an id is provided. */
export function useEdition(id?: number) {
  return useQuery({
    queryKey: editionKeys.detail(id ?? -1),
    queryFn: () => getEditionById(id as number),
    enabled: id !== undefined,
  });
}

/** Feature registry — changes only on deploy, so cache it long. */
export function useFeatureDefinitions() {
  return useQuery({
    queryKey: featureDefinitionKeys.all,
    queryFn: getFeatureDefinitions,
    staleTime: 5 * 60 * 1000,
  });
}

/** Shared success/error handlers: toast + `['editions']` invalidation. */
function useEditionMutationHandlers(successMessageId: string) {
  const intl = useIntl();
  const queryClient = useQueryClient();

  return {
    onSuccess: async () => {
      toast.success(intl.formatMessage({ id: successMessageId }));
      await queryClient.invalidateQueries({ queryKey: editionKeys.all });
    },
    onError: (error: unknown) => {
      const fallback = intl.formatMessage({ id: 'editions.toast.error' });
      toast.error(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    },
  };
}

export function useCreateEdition() {
  const handlers = useEditionMutationHandlers('editions.toast.created');
  return useMutation({
    mutationFn: (body: CreateEditionRequest) => createEdition(body),
    ...handlers,
  });
}

export function useUpdateEdition() {
  const handlers = useEditionMutationHandlers('editions.toast.updated');
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: UpdateEditionRequest }) =>
      updateEdition(id, body),
    ...handlers,
  });
}

/** Delete — a 409 (edition in use) is surfaced through the error handler. */
export function useDeleteEdition() {
  const handlers = useEditionMutationHandlers('editions.toast.deleted');
  return useMutation({
    mutationFn: (id: number) => removeEdition(id),
    ...handlers,
  });
}

/** Batch feature-value assignment for one edition. */
export function useSetEditionFeatures() {
  const handlers = useEditionMutationHandlers('editions.toast.featuresSaved');
  return useMutation({
    mutationFn: ({ id, values }: { id: number; values: FeatureValueDto[] }) =>
      setEditionFeatures(id, values),
    ...handlers,
  });
}
