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
 * Two-factor DTOs (self-service enrollment/management under
 * `/api/profile/two-factor`). `TwoFactorSetupDto` and `RecoveryCodesDto` are
 * each shown to the user exactly ONCE and are never persisted by the client.
 *
 * The current on/off state is authoritative on the backend: `MeDto` (from
 * `/api/auth/me`, stored on the auth context user) carries `twoFactorEnabled`.
 * The card reads that flag to decide between the enable flow and the manage
 * flow, and calls `refreshMe()` after a change so it flips; see `TwoFactorCard`.
 */
export type TwoFactorSetupDto = components['schemas']['TwoFactorSetupDto'];
export type RecoveryCodesDto = components['schemas']['RecoveryCodesDto'];
export type TwoFactorEnableRequest =
  components['schemas']['TwoFactorEnableRequest'];
export type TwoFactorPasswordRequest =
  components['schemas']['TwoFactorPasswordRequest'];

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
