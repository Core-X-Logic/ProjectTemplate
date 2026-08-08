import { useMutation, useQuery } from '@tanstack/react-query';
import {
  acceptInvitation,
  confirmEmail,
  forgotPassword,
  getInvitationInfo,
  resetPassword,
} from './api';
import type {
  AcceptInvitationRequest,
  ConfirmEmailRequest,
  ForgotPasswordRequest,
  ResetPasswordRequest,
} from './types';

/**
 * React-query bindings for the anonymous account flows (U-01).
 *
 * No cache invalidation and no toasts here: these screens run before a session
 * exists, so there is nothing cached to refresh, and each page renders its own
 * inline result panel (a toast on a page the user is about to navigate away
 * from is easy to miss). Errors stay on the mutation and are read off
 * `ApiError` at the call site.
 */

export function useForgotPassword() {
  return useMutation({
    mutationFn: (body: ForgotPasswordRequest) => forgotPassword(body),
  });
}

export function useResetPassword() {
  return useMutation({
    mutationFn: (body: ResetPasswordRequest) => resetPassword(body),
  });
}

export function useConfirmEmail() {
  return useMutation({
    mutationFn: (body: ConfirmEmailRequest) => confirmEmail(body),
  });
}

/* -------------------------------------------------------------------------- */
/* Invitation accept                                                          */
/* -------------------------------------------------------------------------- */

/**
 * Invitation lookup for the accept screen. `retry: false` on purpose: the only
 * failure mode is "this token is unusable" (400), which retrying cannot fix
 * and would only delay telling the invitee.
 */
export function useInvitationInfo(token: string) {
  return useQuery({
    queryKey: ['account', 'invitation', token] as const,
    queryFn: () => getInvitationInfo(token),
    enabled: token.length > 0,
    retry: false,
  });
}

export function useAcceptInvitation() {
  return useMutation({
    mutationFn: (body: AcceptInvitationRequest) => acceptInvitation(body),
  });
}
