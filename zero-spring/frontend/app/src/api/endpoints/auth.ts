import { apiFetch } from '@/api/client';

/**
 * Auth endpoint wrappers (FRONTEND-ARCHITECTURE.md §4).
 *
 * NOTE: The typed OpenAPI client (`src/api/schema.d.ts`) is generated after the
 * backend is available (`npm run gen:api`). Until then these request/response
 * shapes are declared by hand as minimal interfaces. In slice B they will be
 * replaced by `components['schemas'][...]` from the generated schema — keep the
 * exported names stable so call sites do not change.
 */

export interface LoginRequest {
  usernameOrEmail: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
}

export interface MeResponse {
  id: string;
  username: string;
  email: string;
  tenantId: string;
  roles: string[];
  permissions: string[];
}

/** `POST /api/auth/login` — the selected tenant travels in the `X-Tenant` header. */
export function login(body: LoginRequest): Promise<LoginResponse> {
  return apiFetch<LoginResponse>('/api/auth/login', {
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
