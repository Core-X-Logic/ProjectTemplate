import { ApiError } from '@/api/client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { toast } from 'sonner';
import {
  changePassword,
  disableTwoFactor,
  enableTwoFactor,
  getProfile,
  regenerateRecoveryCodes,
  setupTwoFactor,
  updateProfile,
} from './api';
import type { ChangePasswordRequest, UpdateProfileRequest } from './types';

/**
 * React-query bindings for the profile feature (U-01, flow 2).
 *
 * Failure toasts prefer the ProblemDetail `detail` so backend rules ("current
 * password is incorrect", password policy, password history) reach the user
 * verbatim instead of a generic message.
 */

export const profileKeys = {
  all: ['profile'] as const,
  detail: () => [...profileKeys.all, 'detail'] as const,
};

export function useProfile() {
  return useQuery({
    queryKey: profileKeys.detail(),
    queryFn: getProfile,
  });
}

export function useUpdateProfile() {
  const intl = useIntl();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (body: UpdateProfileRequest) => updateProfile(body),
    onSuccess: async () => {
      toast.success(intl.formatMessage({ id: 'profile.toast.updated' }));
      await queryClient.invalidateQueries({ queryKey: profileKeys.all });
    },
    onError: (error: unknown) => {
      const fallback = intl.formatMessage({ id: 'profile.toast.error' });
      toast.error(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    },
  });
}

/**
 * Change password. No cache invalidation: the endpoint returns 204 and mutates
 * nothing this app reads back — the access token stays valid.
 */
export function useChangePassword() {
  const intl = useIntl();

  return useMutation({
    mutationFn: (body: ChangePasswordRequest) => changePassword(body),
    onSuccess: () => {
      toast.success(
        intl.formatMessage({ id: 'profile.toast.passwordChanged' }),
      );
    },
    onError: (error: unknown) => {
      const fallback = intl.formatMessage({
        id: 'profile.toast.passwordError',
      });
      toast.error(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    },
  });
}

/* -------------------------------------------------------------------------- */
/* Two-factor mutations                                                         */
/* -------------------------------------------------------------------------- */

/**
 * Provision a pending TOTP secret. No toast on success — the secret/QR is the
 * result and is rendered inline; only failure is surfaced.
 */
export function useSetupTwoFactor() {
  const intl = useIntl();

  return useMutation({
    mutationFn: () => setupTwoFactor(),
    onError: (error: unknown) => {
      const fallback = intl.formatMessage({ id: 'profile.twoFactor.setupError' });
      toast.error(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    },
  });
}

/**
 * Confirm the pending secret and switch 2FA on. Invalidates the profile query so
 * anything derived from the identity refreshes; the returned recovery codes are
 * shown ONCE by the caller and never cached.
 */
export function useEnableTwoFactor() {
  const intl = useIntl();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (code: string) => enableTwoFactor(code),
    onSuccess: async () => {
      toast.success(intl.formatMessage({ id: 'profile.twoFactor.enabledToast' }));
      await queryClient.invalidateQueries({ queryKey: profileKeys.all });
    },
    onError: (error: unknown) => {
      const fallback = intl.formatMessage({
        id: 'profile.twoFactor.enableError',
      });
      toast.error(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    },
  });
}

/** Turn 2FA off after re-verifying the current password. Invalidates profile. */
export function useDisableTwoFactor() {
  const intl = useIntl();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (password: string) => disableTwoFactor(password),
    onSuccess: async () => {
      toast.success(
        intl.formatMessage({ id: 'profile.twoFactor.disabledToast' }),
      );
      await queryClient.invalidateQueries({ queryKey: profileKeys.all });
    },
    onError: (error: unknown) => {
      const fallback = intl.formatMessage({
        id: 'profile.twoFactor.disableError',
      });
      toast.error(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    },
  });
}

/**
 * Replace the recovery-code set after re-verifying the current password. The new
 * codes are shown ONCE by the caller; nothing is cached.
 */
export function useRegenerateRecoveryCodes() {
  const intl = useIntl();

  return useMutation({
    mutationFn: (password: string) => regenerateRecoveryCodes(password),
    onSuccess: () => {
      toast.success(
        intl.formatMessage({ id: 'profile.twoFactor.regeneratedToast' }),
      );
    },
    onError: (error: unknown) => {
      const fallback = intl.formatMessage({
        id: 'profile.twoFactor.regenerateError',
      });
      toast.error(
        error instanceof ApiError ? error.detail || fallback : fallback,
      );
    },
  });
}
