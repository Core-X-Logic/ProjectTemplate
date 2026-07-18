import { apiFetch } from '@/api/client';
import type { components } from '@/api/schema';

/**
 * Impersonation endpoint wrappers (FRONTEND-ARCHITECTURE.md §7).
 *
 * Every request/response shape is derived from the generated OpenAPI schema
 * (`src/api/schema.d.ts` — operations `impersonate`, `authenticate`,
 * `backToImpersonator`) so the feature stays in lock-step with the backend
 * contract. Transport (auth header, tenant header, 401 refresh, ProblemDetail
 * parsing) is handled by `apiFetch`.
 *
 * The two-step start flow mirrors the backend: `POST /api/auth/impersonate`
 * mints a short-lived impersonation token which is then exchanged for a real
 * `TokenPair` via `POST /api/auth/impersonate/authenticate`. Returning to the
 * original account is a single `POST /api/auth/back-to-impersonator`.
 */

type ImpersonateRequest = components['schemas']['ImpersonateRequest'];
type ImpersonateAuthRequest = components['schemas']['ImpersonateAuthRequest'];
export type ImpersonationTokenDto =
  components['schemas']['ImpersonationTokenDto'];
export type TokenPairDto = components['schemas']['TokenPairDto'];

/** `POST /api/auth/impersonate` — mints the impersonation token (`users.impersonate`). */
export function startImpersonation(
  targetUserId: number,
  targetTenantId?: number,
): Promise<ImpersonationTokenDto> {
  const body: ImpersonateRequest = {
    targetUserId,
    ...(targetTenantId !== undefined ? { targetTenantId } : {}),
  };
  return apiFetch<ImpersonationTokenDto>('/api/auth/impersonate', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/** `POST /api/auth/impersonate/authenticate` — exchanges the token for a `TokenPair`. */
export function authenticateImpersonation(
  impersonationToken: string,
): Promise<TokenPairDto> {
  const body: ImpersonateAuthRequest = { impersonationToken };
  return apiFetch<TokenPairDto>('/api/auth/impersonate/authenticate', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/** `POST /api/auth/back-to-impersonator` — restores the original session's `TokenPair`. */
export function backToImpersonator(): Promise<TokenPairDto> {
  return apiFetch<TokenPairDto>('/api/auth/back-to-impersonator', {
    method: 'POST',
  });
}
