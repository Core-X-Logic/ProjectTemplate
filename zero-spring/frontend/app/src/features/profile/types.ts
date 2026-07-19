import type { components } from '@/api/schema';

/**
 * Profile feature types (U-01, flow 2).
 *
 * DTO shapes are aliased from the generated OpenAPI schema (`npm run gen:api`).
 */

export type ProfileDto = components['schemas']['ProfileDto'];
export type UpdateProfileRequest =
  components['schemas']['UpdateProfileRequest'];
export type ChangePasswordRequest =
  components['schemas']['ChangePasswordRequest'];

/**
 * Field bounds mirrored from `UpdateProfileRequest`'s bean-validation
 * annotations so the form can reject over-long input before the round trip.
 */
export const PROFILE_LIMITS = {
  name: 64,
  surname: 64,
  phoneNumber: 32,
  email: 256,
} as const;

/**
 * Minimum length accepted by `POST /api/profile/change-password`
 * (`ChangePasswordRequest.newPassword` is `@Size(min = 8)`).
 *
 * Note this is STRICTER than the anonymous reset flow's floor of 6 — the two
 * DTOs genuinely disagree on the backend. The tenant `PasswordPolicy` is
 * applied on top of both and can raise the bar further; those rejections come
 * back as ProblemDetail and are shown verbatim.
 */
export const CHANGE_PASSWORD_MIN_LENGTH = 8;
