/** Shape of GET /api/auth/me and the embedded user in auth responses. */
export interface UserProfile {
  id: string;
  email: string;
  displayName: string;
}

/** Raw token pair as returned by login/refresh (before expiry derivation). */
export interface TokenBundle {
  accessToken: string;
  refreshToken: string;
}

/**
 * Boot/runtime auth status.
 * - authenticating: boot in progress (token present but user/validity unresolved)
 * - authenticated: usable session
 * - unauthenticated: no session
 */
export type AuthStatus = 'authenticating' | 'authenticated' | 'unauthenticated';
