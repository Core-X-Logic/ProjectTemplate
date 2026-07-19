import { useMutation } from '@tanstack/react-query';
import { confirmEmail, forgotPassword, resetPassword } from './api';
import type {
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
