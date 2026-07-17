import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { toast } from 'sonner';
import { ApiError } from '@/api/client';
import {
  activateUser,
  assignOrganizationUnits,
  assignRoles,
  createUser,
  deactivateUser,
  exportUsersExcel,
  getUserById,
  listOrganizationUnits,
  listRoleOptions,
  listUsers,
  removeUser,
  unlockUser,
  updateUser,
} from '@/features/users/api';
import type {
  AssignOrganizationUnitsRequest,
  AssignRolesRequest,
  CreateUserRequest,
  OrganizationUnitDto,
  RoleDto,
  UpdateUserRequest,
  UserDto,
} from '@/features/users/types';

/**
 * TanStack Query bindings for the users feature (FRONTEND-ARCHITECTURE.md §7).
 *
 * Every mutation invalidates the `['users']` key family and raises a localized
 * sonner toast; queries share the `usersKeys` factory so invalidation stays
 * consistent across list/detail consumers.
 */

export const usersKeys = {
  all: ['users'] as const,
  list: (page: number, size: number, search: string) =>
    [...usersKeys.all, 'list', { page, size, search }] as const,
  detail: (id: number) => [...usersKeys.all, 'detail', id] as const,
};

/** Server-side paginated user list. Previous page is kept while fetching. */
export function useUsers(page: number, size: number, search: string) {
  return useQuery({
    queryKey: usersKeys.list(page, size, search),
    queryFn: () => listUsers({ page, size, search, sort: ['username,asc'] }),
    placeholderData: keepPreviousData,
  });
}

/** Single user detail (enabled only for a valid id). */
export function useUser(id: number | undefined) {
  return useQuery({
    queryKey: usersKeys.detail(id ?? -1),
    queryFn: () => getUserById(id as number),
    enabled: id !== undefined,
  });
}

/* -------------------------------------------------------------------------- */
/* Mutations                                                                    */
/* -------------------------------------------------------------------------- */

function useUsersMutationHandlers(successMessageId: string) {
  const intl = useIntl();
  const queryClient = useQueryClient();

  return {
    onSuccess: async () => {
      toast.success(intl.formatMessage({ id: successMessageId }));
      await queryClient.invalidateQueries({ queryKey: usersKeys.all });
    },
    onError: (error: unknown) => {
      const fallback = intl.formatMessage({ id: 'users.error' });
      toast.error(fallback, {
        description: error instanceof ApiError ? error.detail : undefined,
      });
    },
  };
}

export function useCreateUser() {
  const handlers = useUsersMutationHandlers('users.created');
  return useMutation<UserDto, unknown, CreateUserRequest>({
    mutationFn: (body) => createUser(body),
    ...handlers,
  });
}

export function useUpdateUser() {
  const handlers = useUsersMutationHandlers('users.updated');
  return useMutation<UserDto, unknown, { id: number; body: UpdateUserRequest }>(
    {
      mutationFn: ({ id, body }) => updateUser(id, body),
      ...handlers,
    },
  );
}

export function useDeleteUser() {
  const handlers = useUsersMutationHandlers('users.deleted');
  return useMutation<void, unknown, number>({
    mutationFn: (id) => removeUser(id),
    ...handlers,
  });
}

export function useUnlockUser() {
  const handlers = useUsersMutationHandlers('users.unlocked');
  return useMutation<UserDto, unknown, number>({
    mutationFn: (id) => unlockUser(id),
    ...handlers,
  });
}

export function useActivateUser() {
  const handlers = useUsersMutationHandlers('users.activated');
  return useMutation<UserDto, unknown, number>({
    mutationFn: (id) => activateUser(id),
    ...handlers,
  });
}

export function useDeactivateUser() {
  const handlers = useUsersMutationHandlers('users.deactivated');
  return useMutation<UserDto, unknown, number>({
    mutationFn: (id) => deactivateUser(id),
    ...handlers,
  });
}

export function useAssignRoles() {
  const handlers = useUsersMutationHandlers('users.updated');
  return useMutation<UserDto, unknown, { id: number; body: AssignRolesRequest }>(
    {
      mutationFn: ({ id, body }) => assignRoles(id, body),
      ...handlers,
    },
  );
}

export function useAssignOrganizationUnits() {
  const handlers = useUsersMutationHandlers('users.updated');
  return useMutation<
    UserDto,
    unknown,
    { id: number; body: AssignOrganizationUnitsRequest }
  >({
    mutationFn: ({ id, body }) => assignOrganizationUnits(id, body),
    ...handlers,
  });
}

/** XLSX export. The caller receives the blob and triggers the download. */
export function useExportUsers() {
  const intl = useIntl();
  return useMutation<Blob, unknown, void>({
    mutationFn: () => exportUsersExcel(),
    onSuccess: () => {
      toast.success(intl.formatMessage({ id: 'users.exported' }));
    },
    onError: (error) => {
      toast.error(intl.formatMessage({ id: 'users.error' }), {
        description: error instanceof ApiError ? error.detail : undefined,
      });
    },
  });
}

/* -------------------------------------------------------------------------- */
/* Lookup queries for the form selects                                          */
/* -------------------------------------------------------------------------- */

/** Role names for the multi-select (owned by the roles feature; read-only here). */
export function useRoleOptions() {
  return useQuery({
    queryKey: ['roles', 'options'] as const,
    queryFn: listRoleOptions,
    select: (page): RoleDto[] => page.content ?? [],
    staleTime: 60_000,
  });
}

/** Flat OU nodes for the multi-select (owned by the OU feature; read-only here). */
export function useOrganizationUnitOptions() {
  return useQuery<OrganizationUnitDto[]>({
    queryKey: ['organization-units', 'options'] as const,
    queryFn: listOrganizationUnits,
    staleTime: 60_000,
  });
}
