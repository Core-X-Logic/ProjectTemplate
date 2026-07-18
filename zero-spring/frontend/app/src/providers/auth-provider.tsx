import {
  createContext,
  ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { tenantStore, tokenStore } from '@/api/client';
import {
  getMe,
  login as loginRequest,
  logout as logoutRequest,
  type MeResponse,
} from '@/api/endpoints/auth';
import {
  authenticateImpersonation,
  backToImpersonator as backToImpersonatorRequest,
  startImpersonation,
} from '@/features/impersonation/api';

interface AuthContextValue {
  /** Current authenticated user, or `null` when signed out. */
  user: MeResponse | null;
  /** Flattened permission keys (RBAC source). */
  permissions: string[];
  /** Role names granted to the user. */
  roles: string[];
  /** `true` while the initial session bootstrap is running. */
  loading: boolean;
  /**
   * `true` when the active access token carries an `act` (actor) claim — i.e.
   * the current session is an impersonation of another user. Drives the
   * impersonation banner and the cascade block on the row action.
   */
  isImpersonating: boolean;
  login: (
    usernameOrEmail: string,
    password: string,
    tenant?: string,
  ) => Promise<void>;
  logout: () => Promise<void>;
  refreshMe: () => Promise<void>;
  /**
   * Start impersonating `targetUserId` (optionally in `targetTenantId`):
   * mints an impersonation token, exchanges it for a token pair and reloads the
   * identity so `user`/`permissions`/`isImpersonating` reflect the target.
   */
  impersonate: (targetUserId: number, targetTenantId?: number) => Promise<void>;
  /** Return to the original (impersonator) session and reload the identity. */
  backToImpersonator: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

/**
 * Decodes the JWT payload and reports whether it carries an `act` (actor)
 * claim — the RFC 8693 marker the backend stamps onto impersonation tokens.
 *
 * Deliberately hand-rolled (no new dependency): split off the payload segment,
 * base64url-decode it and read `act`. Any malformed token is treated as "not
 * impersonating" — this is UX only, the backend enforces the real rules.
 */
function hasActorClaim(token: string | null): boolean {
  if (!token) {
    return false;
  }
  const segment = token.split('.')[1];
  if (!segment) {
    return false;
  }
  try {
    const base64 = segment.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(
      base64.length + ((4 - (base64.length % 4)) % 4),
      '=',
    );
    const payload = JSON.parse(atob(padded)) as { act?: unknown };
    return payload.act !== undefined && payload.act !== null;
  } catch {
    return false;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<MeResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  // Mirrors `tokenStore.getAccess()` in React state so `isImpersonating` (derived
  // from the token's `act` claim) stays reactive across login/impersonate/back.
  const [accessToken, setAccessToken] = useState<string | null>(() =>
    tokenStore.getAccess(),
  );

  const refreshMe = useCallback(async () => {
    try {
      const me = await getMe();
      setUser(me);
      // A silent 401 refresh inside `apiFetch` may have rotated the token; read
      // the freshest value back so the `act` claim is evaluated post-refresh.
      setAccessToken(tokenStore.getAccess());
    } catch {
      // Refresh/access chain failed — treat as signed out.
      setUser(null);
      setAccessToken(null);
      tokenStore.clear();
    } finally {
      setLoading(false);
    }
  }, []);

  // Bootstrap: a persisted refresh token means we may still have a live session.
  // `apiFetch` transparently exchanges it for a fresh access token on the 401.
  useEffect(() => {
    if (tokenStore.getRefresh()) {
      void refreshMe();
    } else {
      setLoading(false);
    }
  }, [refreshMe]);

  const login = useCallback(
    async (usernameOrEmail: string, password: string, tenant?: string) => {
      if (tenant) {
        tenantStore.set(tenant);
      }
      const tokens = await loginRequest({ usernameOrEmail, password });
      tokenStore.setTokens(tokens.accessToken, tokens.refreshToken);
      setAccessToken(tokens.accessToken);
      const me = await getMe();
      setUser(me);
    },
    [],
  );

  const logout = useCallback(async () => {
    try {
      await logoutRequest();
    } catch {
      // Ignore server-side failures; local cleanup below is authoritative.
    }
    tokenStore.clear();
    setUser(null);
    setAccessToken(null);
  }, []);

  const impersonate = useCallback(
    async (targetUserId: number, targetTenantId?: number) => {
      const { impersonationToken } = await startImpersonation(
        targetUserId,
        targetTenantId,
      );
      if (!impersonationToken) {
        throw new Error('Impersonation token missing from response.');
      }
      const tokens = await authenticateImpersonation(impersonationToken);
      if (!tokens.accessToken || !tokens.refreshToken) {
        throw new Error('Impersonation authentication returned no token pair.');
      }
      tokenStore.setTokens(tokens.accessToken, tokens.refreshToken);
      await refreshMe();
    },
    [refreshMe],
  );

  const backToImpersonator = useCallback(async () => {
    const tokens = await backToImpersonatorRequest();
    if (!tokens.accessToken || !tokens.refreshToken) {
      throw new Error('back-to-impersonator returned no token pair.');
    }
    tokenStore.setTokens(tokens.accessToken, tokens.refreshToken);
    await refreshMe();
  }, [refreshMe]);

  const isImpersonating = useMemo(
    () => hasActorClaim(accessToken),
    [accessToken],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      permissions: user?.permissions ?? [],
      roles: user?.roles ?? [],
      loading,
      isImpersonating,
      login,
      logout,
      refreshMe,
      impersonate,
      backToImpersonator,
    }),
    [
      user,
      loading,
      isImpersonating,
      login,
      logout,
      refreshMe,
      impersonate,
      backToImpersonator,
    ],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
