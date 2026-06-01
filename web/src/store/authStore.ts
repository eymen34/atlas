import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { AuthStatus, UserProfile } from '../auth/types';

/**
 * T-013 auth store. Extends the T-010 in-memory store: preserves the
 * setTokens(access, refresh) / clearTokens signatures, adds
 * accessTokenExpiresAt + user + status, and persists to localStorage under
 * "atlas.auth.v1" (XSS trade-off documented in docs/security.md).
 */

/** Full token bundle including derived expiry + resolved user (login/refresh). */
export interface TokenBundleInput {
  accessToken: string;
  refreshToken: string;
  accessTokenExpiresAt: number;
  user: UserProfile;
}

interface SetTokens {
  /** Back-compat two-arg form (T-010). Leaves accessTokenExpiresAt + user untouched. */
  (accessToken: string, refreshToken: string): void;
  /** Full form: sets all four fields + status=authenticated. */
  (tokens: TokenBundleInput): void;
}

export interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  accessTokenExpiresAt: number | null;
  user: UserProfile | null;
  status: AuthStatus;
  setTokens: SetTokens;
  clearTokens: () => void;
  setUser: (user: UserProfile) => void;
  setStatus: (status: AuthStatus) => void;
}

export const AUTH_STORAGE_KEY = 'atlas.auth.v1';

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      accessTokenExpiresAt: null,
      user: null,
      // Start in 'authenticating' so the first render does not flash to /login
      // before AuthProvider / rehydration resolves the real status.
      status: 'authenticating',
      setTokens: ((
        accessOrTokens: string | TokenBundleInput,
        refreshToken?: string
      ): void => {
        if (typeof accessOrTokens === 'string') {
          // Two-arg back-compat: only the two tokens change.
          set({ accessToken: accessOrTokens, refreshToken: refreshToken ?? null });
        } else {
          set({
            accessToken: accessOrTokens.accessToken,
            refreshToken: accessOrTokens.refreshToken,
            accessTokenExpiresAt: accessOrTokens.accessTokenExpiresAt,
            user: accessOrTokens.user,
            status: 'authenticated',
          });
        }
      }) as SetTokens,
      clearTokens: () =>
        set({
          accessToken: null,
          refreshToken: null,
          accessTokenExpiresAt: null,
          user: null,
          status: 'unauthenticated',
        }),
      setUser: (user) => set({ user }),
      setStatus: (status) => set({ status }),
    }),
    {
      name: AUTH_STORAGE_KEY,
      storage: createJSONStorage(() => localStorage),
      partialize: (state) => ({
        accessToken: state.accessToken,
        refreshToken: state.refreshToken,
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
 * Cross-tab logout: when another tab clears or rewrites the persisted blob
 * without a refreshToken, mirror the logout here. Exported so tests can add /
 * remove it deterministically (avoids cross-test listener leakage).
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
    const parsed = JSON.parse(event.newValue) as { state?: { refreshToken?: string | null } };
    if (!parsed?.state?.refreshToken) {
      useAuthStore.getState().clearTokens();
    }
  } catch {
    useAuthStore.getState().clearTokens();
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('storage', handleAuthStorageEvent);
}
