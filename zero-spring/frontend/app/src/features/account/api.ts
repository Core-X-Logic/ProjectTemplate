import { apiFetch } from '@/api/client';
import type {
  AcceptInvitationRequest,
  ConfirmEmailRequest,
  ForgotPasswordRequest,
  InvitationInfoDto,
  ResetPasswordRequest,
} from './types';

/**
 * Anonymous account endpoint wrappers (`AccountController`, `permitAll`).
 *
 * All three answer `204 No Content` on success and an RFC 9457 ProblemDetail on
 * failure, which `apiFetch` turns into an `ApiError`.
 */

const ACCOUNT_URL = '/api/account';

/**
 * `POST /api/account/forgot-password` — mails a reset code to the account.
 *
 * Deliberately enumeration-safe on the backend: an unknown username/email logs
 * server-side and still returns 204. The UI must therefore never phrase its
 * confirmation as "we sent a mail to that account" — doing so would leak the
 * existence check the backend just went out of its way to hide.
 */
export function forgotPassword(body: ForgotPasswordRequest): Promise<void> {
  return apiFetch<void>(`${ACCOUNT_URL}/forgot-password`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/**
 * `POST /api/account/reset-password` — consumes the code and sets the password.
 *
 * A wrong/consumed code and a policy violation are both 400s distinguished only
 * by their ProblemDetail `detail`, which is surfaced verbatim.
 */
export function resetPassword(body: ResetPasswordRequest): Promise<void> {
  return apiFetch<void>(`${ACCOUNT_URL}/reset-password`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/** `POST /api/account/confirm-email` — consumes an email confirmation code. */
export function confirmEmail(body: ConfirmEmailRequest): Promise<void> {
  return apiFetch<void>(`${ACCOUNT_URL}/confirm-email`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/* -------------------------------------------------------------------------- */
/* Invitation accept                                                          */
/* -------------------------------------------------------------------------- */

/**
 * `GET /api/account/invitation?token=…` — what the accept screen may render
 * before asking for a password (most importantly the admin-fixed username).
 * The token is the only credential; an unusable one is a 400 whose single
 * message never distinguishes unknown/expired/revoked.
 */
export function getInvitationInfo(token: string): Promise<InvitationInfoDto> {
  return apiFetch<InvitationInfoDto>(
    `${ACCOUNT_URL}/invitation?token=${encodeURIComponent(token)}`,
  );
}

/**
 * `POST /api/account/accept-invitation` — consumes the token and creates the
 * account. 204 both on creation and on the deliberate no-op (already
 * accepted): either way the next step is signing in.
 */
export function acceptInvitation(body: AcceptInvitationRequest): Promise<void> {
  return apiFetch<void>(`${ACCOUNT_URL}/accept-invitation`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}
