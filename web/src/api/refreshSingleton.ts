import { authResponseSchema } from '../auth/schemas';
import type { TokenBundle } from '../auth/types';
import { useAuthStore } from '../store/authStore';
import { nativeFetch } from './nativeFetch';

const DEFAULT_ACCESS_TTL_SECONDS = Number(
  import.meta.env.VITE_ACCESS_TTL_SECONDS ?? '900'
);

export class AuthError extends Error {
  constructor(public readonly code: string) {
    super(code);
    this.name = 'AuthError';
  }
}

let inflightRefresh: Promise<TokenBundle> | null = null;

/**
 * Returns a single shared in-flight refresh promise: concurrent 401s all await
 * the SAME POST /api/auth/refresh (singleton dedup). Resets to null on settle so
 * a later 401 can refresh again. A malformed/failed refresh rejects (and resets),
 * so callers fall through to onUnauthorized — no infinite loop.
 */
export function getRefreshPromise(): Promise<TokenBundle> {
  if (inflightRefresh) {
    return inflightRefresh;
  }
  inflightRefresh = (async (): Promise<TokenBundle> => {
    const refreshToken = useAuthStore.getState().refreshToken;
    if (!refreshToken) {
      throw new AuthError('no_refresh_token');
    }
    const res = await nativeFetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });
    if (!res.ok) {
      throw new AuthError('refresh_failed');
    }
    const parsed = authResponseSchema.safeParse(await res.json());
    if (!parsed.success) {
      throw new AuthError('malformed_refresh');
    }
    const ttlMs =
      parsed.data.expiresIn != null
        ? parsed.data.expiresIn * 1000
        : DEFAULT_ACCESS_TTL_SECONDS * 1000;
    const accessTokenExpiresAt = parsed.data.accessTokenExpiresAt ?? Date.now() + ttlMs;
    const user = parsed.data.user ?? useAuthStore.getState().user;
    if (!user) {
      throw new AuthError('no_user');
    }
    useAuthStore.getState().setTokens({
      accessToken: parsed.data.accessToken,
      refreshToken: parsed.data.refreshToken,
      accessTokenExpiresAt,
      user,
    });
    return { accessToken: parsed.data.accessToken, refreshToken: parsed.data.refreshToken };
  })().finally(() => {
    inflightRefresh = null;
  });
  return inflightRefresh;
}

/** Test-only: clear any in-flight refresh between tests. */
export function __resetRefreshSingleton(): void {
  inflightRefresh = null;
}
