import { AuthService } from './generated';
import { OpenAPI } from './generated/core/OpenAPI';
import { useAuthStore } from '../store/authStore';
import { nativeFetch } from './nativeFetch';
import { getRefreshPromise } from './refreshSingleton';

/**
 * T-013 API client. Extends the T-010 wrapper: configures the generated
 * openapi-typescript-codegen client, exposes the 401 seam, AND adds
 * fetchWithAuth (Bearer injection + silent single-flight refresh on 401).
 *
 * Step 0b record: the generated OpenAPI.BASE default is 'http://localhost:8080';
 * we force '' (same-origin) so the dev proxy / prod static serving work and so
 * codegen regeneration cannot silently change the interceptor's base.
 */
OpenAPI.BASE = '';
OpenAPI.TOKEN = async () => useAuthStore.getState().accessToken ?? '';

let onUnauthorizedHandler: () => void = () => {
  useAuthStore.getState().clearTokens();
};

export function setOnUnauthorized(handler: () => void): void {
  onUnauthorizedHandler = handler;
}

export function handleUnauthorized(): void {
  onUnauthorizedHandler();
}

// Auth endpoints never carry a Bearer and must never trigger the refresh loop.
const AUTH_ENDPOINTS = [
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/refresh',
  '/api/auth/logout',
];

function urlOf(input: RequestInfo | URL): string {
  if (typeof input === 'string') {
    return input;
  }
  if (input instanceof URL) {
    return input.toString();
  }
  return input.url;
}

function withBearer(init: RequestInit | undefined, token: string | null): RequestInit {
  const headers = new Headers(init?.headers);
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  return { ...init, headers };
}

/**
 * Wraps a fetch call with Bearer injection and silent refresh. On 401 with a
 * refresh token present, awaits the singleton refresh and retries once; on no
 * token or refresh failure, invokes the onUnauthorized handler and returns the
 * original 401.
 */
export async function fetchWithAuth(
  input: RequestInfo | URL,
  init?: RequestInit
): Promise<Response> {
  if (AUTH_ENDPOINTS.some((path) => urlOf(input).includes(path))) {
    return nativeFetch(input, init);
  }

  const response = await nativeFetch(
    input,
    withBearer(init, useAuthStore.getState().accessToken)
  );
  if (response.status !== 401) {
    return response;
  }

  if (!useAuthStore.getState().refreshToken) {
    onUnauthorizedHandler();
    return response;
  }

  try {
    const bundle = await getRefreshPromise();
    return await nativeFetch(input, withBearer(init, bundle.accessToken));
  } catch {
    onUnauthorizedHandler();
    return response;
  }
}

// Integration: openapi-typescript-codegen 0.29.0 has no response-interceptor
// hook (OpenAPIConfig exposes no FETCH). Feature-detect FETCH; otherwise
// monkeypatch the global fetch ONCE (idempotent via a well-known Symbol) so
// generated-client calls flow through fetchWithAuth.
const PATCH_FLAG = Symbol.for('atlas.fetchPatched');
type PatchedGlobal = Record<symbol, boolean>;

if ('FETCH' in OpenAPI) {
  (OpenAPI as unknown as { FETCH: typeof fetch }).FETCH = fetchWithAuth;
} else if (
  typeof window !== 'undefined' &&
  !(window as unknown as PatchedGlobal)[PATCH_FLAG]
) {
  window.fetch = (input: RequestInfo | URL, init?: RequestInit) =>
    fetchWithAuth(input, init);
  (window as unknown as PatchedGlobal)[PATCH_FLAG] = true;
}

export { AuthService };

// Compile-time probe: forces tsc to validate the generated get-current-user
// operation (operationId getMe) still resolves after codegen.
export const _getMeTypeProbe: typeof AuthService.getMe = AuthService.getMe;
