/**
 * Thin, typed fetch wrapper for the zero-platform backend.
 *
 * Responsibilities (FRONTEND-ARCHITECTURE.md §7):
 *  - Base URL from `VITE_API_BASE_URL`.
 *  - `Authorization: Bearer <access token>` from the in-memory token store.
 *  - `X-Tenant` from the active tenant store (localStorage backed).
 *  - `Accept-Language` from the active locale.
 *  - RFC 9457 Problem Details error bodies -> `ApiError`.
 *  - On `401` a single-flight refresh is attempted, then the request is retried once.
 *
 * Token storage strategy (§4): access token lives in memory only, refresh token is
 * persisted to localStorage (SPA reality; XSS mitigation via CSP + short access TTL).
 */

const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '');

// Boş bir base URL PROD'da meşrudur: reverse proxy arkasında tek origin kurulumunda API
// göreli yoldan (`/api/...`) servis edilir ve RELEASE-RUNBOOK'un önerdiği kurulum budur.
// DEV'de ise neredeyse her zaman eksik bir `.env` demektir — ve sonucu sessizdir: her istek
// Vite dev sunucusuna gider, 404 döner, konsolda "neden hiçbir şey yüklenmiyor" sorusundan
// başka ipucu kalmaz. Şablonu ilk kez klonlayan birinin karşılaştığı ilk duvar buydu.
if (import.meta.env.DEV && !BASE_URL) {
  throw new Error(
    'VITE_API_BASE_URL tanımlı değil. `frontend/app/.env` dosyası oluşturun ' +
      '(`cp .env.example .env`) ve backend adresini yazın, örn. http://localhost:8080. ' +
      'Not: üretimde boş bırakmak geçerlidir — o durumda API aynı origin üzerinden servis edilir.',
  );
}

const REFRESH_STORAGE_KEY = 'refresh_token';
export const TENANT_STORAGE_KEY = 'tenant';
export const LOCALE_STORAGE_KEY = 'locale';

/* -------------------------------------------------------------------------- */
/* Token store (access in memory, refresh persisted)                           */
/* -------------------------------------------------------------------------- */

let accessToken: string | null = null;

export const tokenStore = {
  getAccess(): string | null {
    return accessToken;
  },
  getRefresh(): string | null {
    try {
      return localStorage.getItem(REFRESH_STORAGE_KEY);
    } catch {
      return null;
    }
  },
  setTokens(access: string, refresh: string): void {
    accessToken = access;
    try {
      localStorage.setItem(REFRESH_STORAGE_KEY, refresh);
    } catch {
      /* storage unavailable — access token still held in memory */
    }
  },
  clear(): void {
    accessToken = null;
    try {
      localStorage.removeItem(REFRESH_STORAGE_KEY);
    } catch {
      /* ignore */
    }
  },
};

/* -------------------------------------------------------------------------- */
/* Active tenant store (localStorage backed, shared with tenant-provider)       */
/* -------------------------------------------------------------------------- */

export const tenantStore = {
  get(): string | null {
    try {
      return localStorage.getItem(TENANT_STORAGE_KEY);
    } catch {
      return null;
    }
  },
  set(tenant: string | null): void {
    try {
      if (tenant) {
        localStorage.setItem(TENANT_STORAGE_KEY, tenant);
      } else {
        localStorage.removeItem(TENANT_STORAGE_KEY);
      }
    } catch {
      /* ignore */
    }
  },
};

function getActiveLocale(): string | null {
  try {
    return localStorage.getItem(LOCALE_STORAGE_KEY);
  } catch {
    return null;
  }
}

/* -------------------------------------------------------------------------- */
/* RFC 9457 Problem Details -> ApiError                                         */
/* -------------------------------------------------------------------------- */

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  /** Application-specific error code (backend extension member). */
  code?: string;
  /** Field validation errors keyed by field name (backend extension member). */
  errors?: Record<string, string[]>;
  [key: string]: unknown;
}

export class ApiError extends Error {
  readonly status: number;
  readonly detail?: string;
  readonly code?: string;
  readonly fields?: Record<string, string[]>;
  readonly problem?: ProblemDetail;

  constructor(status: number, problem?: ProblemDetail) {
    super(
      problem?.detail ||
        problem?.title ||
        `Request failed with status ${status}`,
    );
    this.name = 'ApiError';
    this.status = status;
    this.detail = problem?.detail;
    this.code = problem?.code;
    this.fields = problem?.errors;
    this.problem = problem;
  }
}

/* -------------------------------------------------------------------------- */
/* Header assembly                                                              */
/* -------------------------------------------------------------------------- */

const AUTH_ENDPOINTS = [
  '/api/auth/login',
  '/api/auth/refresh',
  '/api/auth/logout',
];

function isAuthEndpoint(path: string): boolean {
  return AUTH_ENDPOINTS.some((endpoint) => path.startsWith(endpoint));
}

function buildHeaders(init?: RequestInit): Headers {
  const headers = new Headers(init?.headers);

  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json');
  }

  const body = init?.body;
  const isFormData = typeof FormData !== 'undefined' && body instanceof FormData;
  if (
    body !== undefined &&
    body !== null &&
    !isFormData &&
    !headers.has('Content-Type')
  ) {
    headers.set('Content-Type', 'application/json');
  }

  const access = tokenStore.getAccess();
  if (access && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${access}`);
  }

  const tenant = tenantStore.get();
  if (tenant && !headers.has('X-Tenant')) {
    headers.set('X-Tenant', tenant);
  }

  const locale = getActiveLocale();
  if (locale && !headers.has('Accept-Language')) {
    headers.set('Accept-Language', locale);
  }

  return headers;
}

/* -------------------------------------------------------------------------- */
/* Single-flight refresh                                                        */
/* -------------------------------------------------------------------------- */

interface RefreshResponse {
  accessToken?: string;
  refreshToken?: string;
}

let refreshInFlight: Promise<boolean> | null = null;

async function performRefresh(): Promise<boolean> {
  const refresh = tokenStore.getRefresh();
  if (!refresh) {
    return false;
  }

  try {
    const headers = new Headers();
    headers.set('Accept', 'application/json');
    headers.set('Content-Type', 'application/json');
    const tenant = tenantStore.get();
    if (tenant) {
      headers.set('X-Tenant', tenant);
    }

    const res = await fetch(`${BASE_URL}/api/auth/refresh`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ refreshToken: refresh }),
    });

    if (!res.ok) {
      return false;
    }

    const data = (await res.json()) as RefreshResponse;
    if (!data.accessToken || !data.refreshToken) {
      return false;
    }

    tokenStore.setTokens(data.accessToken, data.refreshToken);
    return true;
  } catch {
    return false;
  }
}

/** Ensures at most one refresh request is in flight; concurrent 401s share it. */
function refreshOnce(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = performRefresh().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

/* -------------------------------------------------------------------------- */
/* Response parsing                                                             */
/* -------------------------------------------------------------------------- */

async function toApiError(res: Response): Promise<ApiError> {
  let problem: ProblemDetail | undefined;
  try {
    const text = await res.text();
    if (text) {
      problem = JSON.parse(text) as ProblemDetail;
    }
  } catch {
    /* non-JSON / empty error body */
  }
  return new ApiError(res.status, problem);
}

async function parseBody<T>(res: Response): Promise<T> {
  if (res.status === 204 || res.status === 205) {
    return undefined as T;
  }
  const text = await res.text();
  if (!text) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}

/* -------------------------------------------------------------------------- */
/* Public API                                                                   */
/* -------------------------------------------------------------------------- */

export interface ApiFetchOptions {
  /**
   * How to read a successful response body. `'json'` (default) parses JSON;
   * `'blob'` returns the raw `Blob` untouched — used for binary downloads
   * (e.g. XLSX export) that still need the shared auth/tenant/locale headers
   * and the single-flight 401 refresh.
   */
  responseType?: 'json' | 'blob';
}

/**
 * Perform a typed request against the backend.
 *
 * @throws {ApiError} on network failure or any non-2xx response.
 */
export async function apiFetch<T>(
  path: string,
  init?: RequestInit,
  options?: ApiFetchOptions,
): Promise<T> {
  const url = /^https?:\/\//i.test(path) ? path : `${BASE_URL}${path}`;

  const execute = (): Promise<Response> =>
    fetch(url, { ...init, headers: buildHeaders(init) });

  let res: Response;
  try {
    res = await execute();
  } catch {
    throw new ApiError(0, {
      title: 'Network error',
      detail: 'network',
      code: 'network',
    });
  }

  if (res.status === 401 && !isAuthEndpoint(path) && tokenStore.getRefresh()) {
    const refreshed = await refreshOnce();
    if (refreshed) {
      try {
        res = await execute();
      } catch {
        throw new ApiError(0, {
          title: 'Network error',
          detail: 'network',
          code: 'network',
        });
      }
    } else {
      tokenStore.clear();
    }
  }

  if (!res.ok) {
    throw await toApiError(res);
  }

  if (options?.responseType === 'blob') {
    return (await res.blob()) as T;
  }

  return parseBody<T>(res);
}
