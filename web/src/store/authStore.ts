import { create } from 'zustand';

/**
 * T-010 minimal auth store. In-memory only — no persistence (localStorage /
 * sessionStorage is deferred to a later ticket per
 * architecture_decisions.frontend_state). The API client (src/api/client.ts)
 * reads accessToken from here per request for Bearer injection.
 */
export interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  setTokens: (accessToken: string, refreshToken: string) => void;
  clearTokens: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  refreshToken: null,
  setTokens: (accessToken, refreshToken) => set({ accessToken, refreshToken }),
  clearTokens: () => set({ accessToken: null, refreshToken: null }),
}));
