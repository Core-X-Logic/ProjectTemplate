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
  cloneRole,
  createRole,
  getPermissionTree,
  getRoleById,
  listRoles,
  removeRole,
  updateRole,
} from './api';
import type {
  CreateRoleRequest,
  RoleListParams,
  UpdateRoleRequest,
} from './types';

/**
 * React-query bindings for the roles feature (FRONTEND-ARCHITECTURE.md §7).
 *
 * Every mutation invalidates the `['roles']` key family and surfaces the
 * outcome via a sonner toast (success → localized message, failure → the
 * ProblemDetail `detail` when available).
 */

export const roleKeys = {
  all: ['roles'] as const,
  lists: () => [...roleKeys.all, 'list'] as const,
  list: (params: RoleListParams) => [...roleKeys.lists(), params] as const,
  detail: (id: number) => [...roleKeys.all, 'detail', id] as const,
};

export const permissionKeys = {
  tree: ['permissions', 'tree'] as const,
};

/** Paged role list; previous page stays on screen while the next one loads. */
export function useRoles(params: RoleListParams = {}) {
  return useQuery({
    queryKey: roleKeys.list(params),
    queryFn: () => listRoles(params),
    placeholderData: keepPreviousData,
  });
}

/** Role detail (with permission names). Disabled until an id is provided. */
export function useRole(id?: number) {
  return useQuery({
    queryKey: roleKeys.detail(id ?? -1),
    queryFn: () => getRoleById(id as number),
    enabled: id !== undefined,
  });
}

/** Permission catalogue tree — changes only on deploy, so cache it long. */
export function usePermissionTree() {
  return useQuery({
    queryKey: permissionKeys.tree,
    queryFn: getPermissionTree,
    staleTime: 5 * 60 * 1000,
  });
}

/** Shared success/error handlers: toast + `['roles']` invalidation. */
function useRoleMutationHandlers(successMessageId: string) {
  const intl = useIntl();
  const queryClient = useQueryClient();

  return {
    onSuccess: async () => {
      toast.success(intl.formatMessage({ id: successMessageId }));
      await queryClient.invalidateQueries({ queryKey: roleKeys.all });
    },
    onError: (error: unknown) => {
      const fallback = intl.formatMessage({ id: 'roles.toast.error' });
      toast.error(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    },
  };
}

export function useCreateRole() {
  const handlers = useRoleMutationHandlers('roles.toast.created');
  return useMutation({
    mutationFn: (body: CreateRoleRequest) => createRole(body),
    ...handlers,
  });
}

export function useUpdateRole() {
  const handlers = useRoleMutationHandlers('roles.toast.updated');
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: UpdateRoleRequest }) =>
      updateRole(id, body),
    ...handlers,
  });
}

export function useDeleteRole() {
  const handlers = useRoleMutationHandlers('roles.toast.deleted');
  return useMutation({
    mutationFn: (id: number) => removeRole(id),
    ...handlers,
  });
}

export function useCloneRole() {
  const handlers = useRoleMutationHandlers('roles.toast.cloned');
  return useMutation({
    mutationFn: (id: number) => cloneRole(id),
    ...handlers,
  });
}
