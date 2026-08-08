import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { toast } from 'sonner';
import { ApiError } from '@/api/client';
import {
  inviteUser,
  listInvitations,
  resendInvitation,
  revokeInvitation,
} from '@/features/users/invitation-api';
import type {
  InvitationDto,
  InviteUserRequest,
} from '@/features/users/invitation-types';

/**
 * TanStack Query bindings for the invitation flow. Separate module
 * from `hooks.ts` on purpose: sibling tests mock that module with an explicit
 * factory, and growing it would silently undefine hooks under those mocks.
 */

export const invitationsKeys = {
  all: ['invitations'] as const,
  list: (page: number, size: number) =>
    [...invitationsKeys.all, 'list', { page, size }] as const,
};

export function useInvitations(page = 0, size = 50, enabled = true) {
  return useQuery({
    queryKey: invitationsKeys.list(page, size),
    queryFn: () => listInvitations(page, size),
    enabled,
  });
}

function useInvitationMutationHandlers(successMessageId: string) {
  const intl = useIntl();
  const queryClient = useQueryClient();
  return {
    onSuccess: async () => {
      toast.success(intl.formatMessage({ id: successMessageId }));
      await queryClient.invalidateQueries({ queryKey: invitationsKeys.all });
    },
    onError: (error: unknown) => {
      toast.error(intl.formatMessage({ id: 'users.error' }), {
        description: error instanceof ApiError ? error.detail : undefined,
      });
    },
  };
}

export function useInviteUser() {
  const handlers = useInvitationMutationHandlers('users.invite.sent');
  return useMutation<InvitationDto, unknown, InviteUserRequest>({
    mutationFn: (body) => inviteUser(body),
    ...handlers,
  });
}

export function useResendInvitation() {
  const handlers = useInvitationMutationHandlers('users.invitations.resent');
  return useMutation<InvitationDto, unknown, number>({
    mutationFn: (id) => resendInvitation(id),
    ...handlers,
  });
}

export function useRevokeInvitation() {
  const handlers = useInvitationMutationHandlers('users.invitations.revoked');
  return useMutation<InvitationDto, unknown, number>({
    mutationFn: (id) => revokeInvitation(id),
    ...handlers,
  });
}
