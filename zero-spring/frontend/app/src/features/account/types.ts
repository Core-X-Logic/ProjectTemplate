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
