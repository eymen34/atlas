/** Shape of GET /api/auth/me and the embedded user in auth responses. */
export interface UserProfile {
  id: string;
  email: string;
  displayName: string;
}

/**
 * Access token returned by login/refresh (before expiry derivation). T-048: there is no longer a
 * refresh token in JS — it lives in the HttpOnly atlas_refresh cookie.
 */
export interface TokenBundle {
  accessToken: string;
}

/**
 * Boot/runtime auth status.
 * - authenticating: boot in progress (token present but user/validity unresolved)
 * - authenticated: usable session
 * - unauthenticated: no session
 */
export type AuthStatus = 'authenticating' | 'authenticated' | 'unauthenticated';
