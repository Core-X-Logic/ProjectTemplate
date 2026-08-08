import type { components } from '@/api/schema';

/**
 * Anonymous account self-service types (U-01).
 *
 * DTO shapes are aliased from the generated OpenAPI schema (`npm run gen:api`)
 * so the feature stays in lock-step with the backend contract — nothing here is
 * hand-typed.
 *
 * These three endpoints are `permitAll` in `SecurityConfig` (they run before a
 * session exists), so there is no permission guard on the screens — the guard is
 * the secret code itself.
 */

export type ForgotPasswordRequest =
  components['schemas']['ForgotPasswordRequest'];
export type ResetPasswordRequest =
  components['schemas']['ResetPasswordRequest'];
export type ConfirmEmailRequest = components['schemas']['ConfirmEmailRequest'];

/**
 * Minimum password length accepted by `POST /api/account/reset-password`
 * (`ResetPasswordRequest.newPassword` is `@Size(min = 6, max = 128)`).
 *
 * This is only the DTO floor. The service additionally resolves the target
 * user's tenant `PasswordPolicy` and validates against it, so a password that
 * clears this check can still be rejected server-side — that rejection arrives
 * as a ProblemDetail and is surfaced verbatim rather than guessed at here.
 */
export const RESET_PASSWORD_MIN_LENGTH = 6;

/** Upper bound from the same DTO annotation. */
export const RESET_PASSWORD_MAX_LENGTH = 128;

/**
 * Query-string parameter carrying the secret code.
 *
 * NOT arbitrary: `EmailTemplateService` builds the emailed links as
 * `{baseUrl}/account/reset-password?code=…` and
 * `{baseUrl}/account/confirm-email?code=…`. The route paths and this parameter
 * name are a contract with the backend's mail templates — changing either side
 * alone breaks every link already sitting in a user's inbox.
 */
export const CODE_QUERY_PARAM = 'code';

/* -------------------------------------------------------------------------- */
/* Invitation accept                                                          */
/* -------------------------------------------------------------------------- */

/**
 * Same contract as {@link CODE_QUERY_PARAM}, for the invitation mail:
 * `EmailTemplateService.invitation` builds
 * `{baseUrl}/account/accept-invitation?token=…`.
 */
export const INVITATION_TOKEN_QUERY_PARAM = 'token';

/**
 * TODO(gen:api): HAND-DECLARED until the generated schema is regenerated with
 * the invitation endpoints — then replace both with
 * `components['schemas'][…]` aliases like everything above (a hand copy lets a
 * backend field rename slip through the compiler).
 *
 * `InvitationInfoDto.status`: `PENDING` → show the password form; `ACCEPTED` →
 * point at sign-in.
 */
export interface InvitationInfoDto {
  username?: string | null;
  email?: string | null;
  status?: string;
}

/** TODO(gen:api): see above — mirrors backend `AcceptInvitationRequest`. */
export interface AcceptInvitationRequest {
  token: string;
  password: string;
}

/**
 * DTO floor of `AcceptInvitationRequest.password` (`@Size(min = 6, max = 128)`)
 * — deliberately the same floor as the reset flow; the tenant `PasswordPolicy`
 * is enforced on top server-side and surfaces as a ProblemDetail.
 */
export const INVITATION_PASSWORD_MIN_LENGTH = 6;
export const INVITATION_PASSWORD_MAX_LENGTH = 128;
