import { ApiError } from '@/api/client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { toast } from 'sonner';
import { changePassword, getProfile, updateProfile } from './api';
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
