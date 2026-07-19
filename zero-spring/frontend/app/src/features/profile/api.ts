import { apiFetch } from '@/api/client';
import type {
  ChangePasswordRequest,
  ProfileDto,
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
