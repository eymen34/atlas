import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { AuthStatus, UserProfile } from '../auth/types';

/**
 * Auth store (T-013; T-048 cookie cutover). The REFRESH token is no longer kept here — it lives in
 * the HttpOnly `atlas_refresh` cookie, out of JS reach (XSS-exfiltration hardening, see
 * docs/security.md). The store persists ONLY the short-lived access token (+ derived expiry) and
 * the resolved user under "atlas.auth.v1". A persist version bump + {@link migrate} scrubs any
 * legacy refreshToken left behind in a pre-T-048 blob.
 */

/** Token bundle including derived expiry + resolved user (login/refresh). */
export interface TokenBundleInput {
  accessToken: string;
  accessTokenExpiresAt: number;
  user: UserProfile;
}

export interface AuthState {
  accessToken: string | null;
  accessTokenExpiresAt: number | null;
  user: UserProfile | null;
  status: AuthStatus;
  setTokens: (tokens: TokenBundleInput) => void;
  clearTokens: () => void;
  setUser: (user: UserProfile) => void;
  setStatus: (status: AuthStatus) => void;
}

export const AUTH_STORAGE_KEY = 'atlas.auth.v1';

/** Persist schema version — bumped to 2 by T-048 so {@link migrate} scrubs the legacy refreshToken. */
export const AUTH_PERSIST_VERSION = 2;

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      accessTokenExpiresAt: null,
      user: null,
      // Start in 'authenticating' so the first render does not flash to /login
      // before AuthProvider / rehydration resolves the real status.
      status: 'authenticating',
      setTokens: (tokens) =>
        set({
          accessToken: tokens.accessToken,
          accessTokenExpiresAt: tokens.accessTokenExpiresAt,
          user: tokens.user,
          status: 'authenticated',
        }),
      clearTokens: () =>
        set({
          accessToken: null,
          accessTokenExpiresAt: null,
          user: null,
          status: 'unauthenticated',
        }),
      setUser: (user) => set({ user }),
      setStatus: (status) => set({ status }),
    }),
    {
      name: AUTH_STORAGE_KEY,
      version: AUTH_PERSIST_VERSION,
      storage: createJSONStorage(() => localStorage),
      // T-048: scrub a legacy refreshToken from any pre-version-2 persisted blob so the long-lived
      // credential never lingers in localStorage after the cookie cutover.
      migrate: (persistedState) => {
        const state = (persistedState ?? {}) as Record<string, unknown>;
        delete state.refreshToken;
        return state as unknown as AuthState;
      },
      partialize: (state) => ({
        accessToken: state.accessToken,
        accessTokenExpiresAt: state.accessTokenExpiresAt,
        user: state.user,
      }),
      onRehydrateStorage: () => (state) => {
        if (!state) return;
        if (state.accessToken && state.user) {
          state.status = 'authenticated';
        } else if (state.accessToken) {
          state.status = 'authenticating';
        } else {
          state.status = 'unauthenticated';
        }
      },
    }
  )
);

/**
 * Cross-tab logout: when another tab clears or rewrites the persisted blob to a logged-out state,
 * mirror the logout here. T-048: keyed on accessToken/user becoming absent (the refresh token is no
 * longer in the blob — the old refreshToken key would never fire again). Exported so tests can
 * add/remove it deterministically (avoids cross-test listener leakage).
 */
export function handleAuthStorageEvent(event: StorageEvent): void {
  if (event.key !== AUTH_STORAGE_KEY) {
    return;
  }
  if (event.newValue === null) {
    useAuthStore.getState().clearTokens();
    return;
  }
  try {
    const parsed = JSON.parse(event.newValue) as {
      state?: { accessToken?: string | null; user?: unknown };
    };
    if (!parsed?.state?.accessToken || !parsed?.state?.user) {
      useAuthStore.getState().clearTokens();
    }
  } catch {
    useAuthStore.getState().clearTokens();
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('storage', handleAuthStorageEvent);
}
