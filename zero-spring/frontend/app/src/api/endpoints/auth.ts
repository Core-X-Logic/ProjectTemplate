import { apiFetch } from '@/api/client';
import type { components } from '@/api/schema';

/**
 * Auth endpoint wrappers (FRONTEND-ARCHITECTURE.md §4).
 *
 * Request/response shapes are aliased from the generated OpenAPI schema
 * (`npm run gen:api`) so the client stays in lock-step with the backend
 * contract. Exported names are kept stable so call sites do not churn.
 */

export type LoginRequest = components['schemas']['LoginRequest'];

/**
 * Discriminated result of `POST /api/auth/login` (2FA slice).
 *
 * `twoFactorRequired` is the discriminator:
 *  - `false`/absent — the token fields (`accessToken`, `refreshToken`,
 *    `expiresInSeconds`) are populated. This is BYTE-FOR-BYTE the pre-2FA
 *    shape, so the non-2FA path behaves exactly as before.
 *  - `true` — no tokens; `twoFactor.challengeToken` must be redeemed at
 *    `POST /api/auth/two-factor/verify`.
 */
export type LoginResultDto = components['schemas']['LoginResultDto'];
export type TokenPairDto = components['schemas']['TokenPairDto'];
export type TwoFactorChallengeDto =
  components['schemas']['TwoFactorChallengeDto'];

export interface MeResponse {
  id: string;
  username: string;
  email: string;
  /**
   * Wire truth (`MeDto.tenantId`: `Long`, schema `tenantId?: number`): a NUMBER
   * in tenant sessions and NULL for host users. The host/tenant split in the UI
   * (`user.tenantId == null` → host) hangs on this nullability — do not "tidy"
   * it to a plain string/number.
   */
  tenantId: number | null;
  roles: string[];
  permissions: string[];
  /**
   * Whether the account has two-factor authentication switched on. Authoritative
   * backend state (`MeDto.twoFactorEnabled`, verified against the regenerated
   * schema); the self-service 2FA card reads this instead of guessing.
   */
  twoFactorEnabled: boolean;
}

/** `POST /api/auth/login` — the selected tenant travels in the `X-Tenant` header. */
export function login(body: LoginRequest): Promise<LoginResultDto> {
  return apiFetch<LoginResultDto>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/**
 * `POST /api/auth/two-factor/verify` — redeems the login challenge with a TOTP
 * or recovery code. Returns a `TokenPairDto` on success; ANY failure (wrong
 * code, expired/consumed challenge) is a generic 401 with no oracle, so the
 * caller must surface a single neutral message. Anonymous, like `/login`.
 */
export function verifyTwoFactor(
  challengeToken: string,
  code: string,
): Promise<TokenPairDto> {
  const body: components['schemas']['TwoFactorVerifyRequest'] = {
    challengeToken,
    code,
  };
  return apiFetch<TokenPairDto>('/api/auth/two-factor/verify', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/** `GET /api/auth/me` — identity + RBAC source for the AuthContext. */
export function getMe(): Promise<MeResponse> {
  return apiFetch<MeResponse>('/api/auth/me');
}

/** `POST /api/auth/logout` — best-effort server-side token revocation. */
export function logout(): Promise<void> {
  return apiFetch<void>('/api/auth/logout', { method: 'POST' });
}
