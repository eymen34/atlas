import { useEffect, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { fetchWithAuth, setOnUnauthorized } from '../api/client';
import { useAuthStore } from '../store/authStore';
import { userProfileSchema } from './schemas';

/**
 * Boots auth state once on mount and wires the global 401 handler.
 *
 * - accessToken + user present → authenticated.
 * - accessToken but no user (rehydrated partial) → resolve via GET /me;
 *   fetchWithAuth silently refreshes if the access token is stale. On failure,
 *   clear the session.
 * - no accessToken → unauthenticated.
 *
 * /me is read through fetchWithAuth + userProfileSchema rather than the generated
 * client: the committed (pre-T-012) UserProfileResponse model lacks displayName,
 * but the real /me response (and MSW mocks) include it.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate();

  useEffect(() => {
    let cancelled = false;

    setOnUnauthorized(() => {
      useAuthStore.getState().clearTokens();
      navigate('/login', { replace: true });
    });

    const { accessToken, user, setUser, setStatus, clearTokens } = useAuthStore.getState();

    // T-048 (lone-cookie-no-bootstrap, see docs/auth-frontend.md): with no access token in the
    // store we treat the session as unauthenticated and do NOT speculatively POST /api/auth/refresh
    // at boot. The refresh cookie is HttpOnly so JS can't detect it; a blind boot-time refresh would
    // race every reload and, on a logged-out user, add a guaranteed 401. The user re-authenticates
    // via /login, which sets a fresh cookie.
    if (!accessToken) {
      setStatus('unauthenticated');
      return;
    }
    if (user) {
      setStatus('authenticated');
      return;
    }

    setStatus('authenticating');
    (async () => {
      const res = await fetchWithAuth('/api/auth/me');
      if (!res.ok) {
        throw new Error('me_failed');
      }
      return userProfileSchema.parse(await res.json());
    })()
      .then((profile) => {
        if (cancelled) {
          return;
        }
        setUser(profile);
        setStatus('authenticated');
      })
      .catch(() => {
        if (!cancelled) {
          clearTokens();
        }
      });

    return () => {
      cancelled = true;
    };
    // Boot once; the store + setOnUnauthorized seam handle later transitions.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return <>{children}</>;
}
