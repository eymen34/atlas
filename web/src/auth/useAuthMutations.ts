import { useMutation } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router';
import { nativeFetch } from '../api/nativeFetch';
import { useAuthStore } from '../store/authStore';
import { authResponseSchema, userProfileSchema, type LoginInput, type RegisterInput } from './schemas';
import type { UserProfile } from './types';

const ACCESS_TTL_SECONDS = Number(import.meta.env.VITE_ACCESS_TTL_SECONDS ?? '900');

/** Thrown by the auth mutations carrying the HTTP status + parsed body. */
export class ApiHttpError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: unknown
  ) {
    super(`HTTP ${status}`);
    this.name = 'ApiHttpError';
  }
}

async function postJson(path: string, body: unknown): Promise<Response> {
  // Auth endpoints are excluded from the silent-refresh wrapper; hit the network
  // directly so no Bearer/refresh machinery is involved.
  return nativeFetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

/**
 * AuthResponse carries no user (contract: {accessToken, refreshToken,
 * expiresIn}), so we stash the tokens and resolve the user with a single GET
 * /me before marking the session authenticated.
 */
async function establishSession(
  accessToken: string,
  refreshToken: string,
  expiresIn: number | undefined
): Promise<void> {
  const accessTokenExpiresAt = Date.now() + (expiresIn ?? ACCESS_TTL_SECONDS) * 1000;
  useAuthStore.setState({ accessToken, refreshToken, accessTokenExpiresAt, status: 'authenticating' });
  const meRes = await nativeFetch('/api/auth/me', {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!meRes.ok) {
    throw new ApiHttpError(meRes.status, await meRes.json().catch(() => null));
  }
  const user: UserProfile = userProfileSchema.parse(await meRes.json());
  useAuthStore.getState().setTokens({ accessToken, refreshToken, accessTokenExpiresAt, user });
}

export function useLogin() {
  const navigate = useNavigate();
  const location = useLocation();
  return useMutation<void, ApiHttpError, LoginInput>({
    mutationFn: async (input) => {
      const res = await postJson('/api/auth/login', input);
      if (!res.ok) {
        throw new ApiHttpError(res.status, await res.json().catch(() => null));
      }
      const data = authResponseSchema.parse(await res.json());
      await establishSession(data.accessToken, data.refreshToken, data.expiresIn);
    },
    onSuccess: () => {
      const from =
        (location.state as { from?: { pathname?: string } } | null)?.from?.pathname ?? '/projects';
      navigate(from, { replace: true });
    },
  });
}

export function useRegister() {
  const navigate = useNavigate();
  const login = useLogin();
  return useMutation<void, ApiHttpError, RegisterInput>({
    mutationFn: async (input) => {
      const res = await postJson('/api/auth/register', input);
      if (!res.ok) {
        throw new ApiHttpError(res.status, await res.json().catch(() => null));
      }
      // 201: best-effort auto-login (one retry). On failure, fall back to /login
      // with a one-shot "account created" hint.
      try {
        await login.mutateAsync({ email: input.email, password: input.password });
      } catch {
        await new Promise((resolve) => setTimeout(resolve, 250));
        try {
          await login.mutateAsync({ email: input.email, password: input.password });
        } catch {
          sessionStorage.setItem('atlas.justRegistered', '1');
          navigate('/login', { replace: true });
        }
      }
    },
  });
}

export function useLogout() {
  const navigate = useNavigate();
  return useMutation<void, Error, void>({
    mutationFn: async () => {
      const { refreshToken, accessToken } = useAuthStore.getState();
      if (!refreshToken) {
        return;
      }
      const attempt = async (): Promise<void> => {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 3000);
        try {
          await nativeFetch('/api/auth/logout', {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
            },
            body: JSON.stringify({ refreshToken }),
            signal: controller.signal,
          });
        } finally {
          clearTimeout(timer);
        }
      };
      try {
        await attempt();
      } catch {
        try {
          await attempt();
        } catch {
          // Network failure: log out locally regardless (onSettled).
        }
      }
    },
    // ALWAYS clear + redirect, whether the server call succeeded or not.
    onSettled: () => {
      useAuthStore.getState().clearTokens();
      navigate('/login', { replace: true });
    },
  });
}
