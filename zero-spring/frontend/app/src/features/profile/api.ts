import { apiFetch } from '@/api/client';
import type {
  ChangePasswordRequest,
  ProfileDto,
  RecoveryCodesDto,
  TwoFactorSetupDto,
  UpdateProfileRequest,
} from './types';

/**
 * Profile endpoint wrappers (`ProfileController`).
 *
 * Every method is `@PreAuthorize("isAuthenticated()")` — no named permission.
 * These are the user's OWN details, so the only gate is having a session; the
 * screen sits behind the plain `<RequireAuth>` shell for the same reason.
 */

const PROFILE_URL = '/api/profile';

/** `GET /api/profile` — the caller's own profile. */
export function getProfile(): Promise<ProfileDto> {
  return apiFetch<ProfileDto>(PROFILE_URL);
}

/** `PUT /api/profile` — updates name/surname/phone/email, returns the new state. */
export function updateProfile(
  body: UpdateProfileRequest,
): Promise<ProfileDto> {
  return apiFetch<ProfileDto>(PROFILE_URL, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

/**
 * `POST /api/profile/change-password` — 204 on success.
 *
 * A wrong `currentPassword`, a policy violation and a password-history hit all
 * arrive as ProblemDetail; the mutation surfaces `detail` verbatim so the user
 * learns which one it was.
 */
export function changePassword(body: ChangePasswordRequest): Promise<void> {
  return apiFetch<void>(`${PROFILE_URL}/change-password`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/* -------------------------------------------------------------------------- */
/* Two-factor (self-service, `isAuthenticated()`, always the JWT subject)       */
/* -------------------------------------------------------------------------- */

const TWO_FACTOR_URL = `${PROFILE_URL}/two-factor`;

/**
 * `POST /api/profile/two-factor/setup` — provisions a pending TOTP secret and
 * returns it + the otpauth URI ONCE. Does NOT enable 2FA (that needs `enable`).
 */
export function setupTwoFactor(): Promise<TwoFactorSetupDto> {
  return apiFetch<TwoFactorSetupDto>(`${TWO_FACTOR_URL}/setup`, {
    method: 'POST',
  });
}

/**
 * `POST /api/profile/two-factor/enable` — confirms the pending secret with a
 * live TOTP code, switches 2FA on, and returns the recovery codes ONCE.
 */
export function enableTwoFactor(code: string): Promise<RecoveryCodesDto> {
  return apiFetch<RecoveryCodesDto>(`${TWO_FACTOR_URL}/enable`, {
    method: 'POST',
    body: JSON.stringify({ code }),
  });
}

/**
 * `POST /api/profile/two-factor/disable` — turns 2FA off after re-verifying the
 * current password. 204 on success.
 */
export function disableTwoFactor(password: string): Promise<void> {
  return apiFetch<void>(`${TWO_FACTOR_URL}/disable`, {
    method: 'POST',
    body: JSON.stringify({ password }),
  });
}

/**
 * `POST /api/profile/two-factor/recovery-codes/regenerate` — replaces the
 * recovery-code set after re-verifying the current password; returns them ONCE.
 */
export function regenerateRecoveryCodes(
  password: string,
): Promise<RecoveryCodesDto> {
  return apiFetch<RecoveryCodesDto>(
    `${TWO_FACTOR_URL}/recovery-codes/regenerate`,
    {
      method: 'POST',
      body: JSON.stringify({ password }),
    },
  );
}
