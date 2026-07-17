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

interface AuthContextValue {
  /** Current authenticated user, or `null` when signed out. */
  user: MeResponse | null;
  /** Flattened permission keys (RBAC source). */
  permissions: string[];
  /** Role names granted to the user. */
  roles: string[];
  /** `true` while the initial session bootstrap is running. */
  loading: boolean;
  login: (
    usernameOrEmail: string,
    password: string,
    tenant?: string,
  ) => Promise<void>;
  logout: () => Promise<void>;
  refreshMe: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<MeResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(true);

  const refreshMe = useCallback(async () => {
    try {
      const me = await getMe();
      setUser(me);
    } catch {
      // Refresh/access chain failed — treat as signed out.
      setUser(null);
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
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      permissions: user?.permissions ?? [],
      roles: user?.roles ?? [],
      loading,
      login,
      logout,
      refreshMe,
    }),
    [user, loading, login, logout, refreshMe],
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
