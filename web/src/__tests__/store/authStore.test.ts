import { describe, it, expect, beforeEach } from 'vitest';
import { useAuthStore } from '../../store/authStore';

const USER = { id: 'u', email: 'e@x.com', displayName: 'n' };

describe('useAuthStore', () => {
  beforeEach(() => {
    useAuthStore.getState().clearTokens();
  });

  it('starts with the access token null', () => {
    // T-048: there is no refreshToken in the store anymore — it is the HttpOnly cookie.
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('setTokens stores the access token (+ expiry + user)', () => {
    useAuthStore
      .getState()
      .setTokens({ accessToken: 'access-1', accessTokenExpiresAt: 1, user: USER });
    expect(useAuthStore.getState().accessToken).toBe('access-1');
  });

  it('clearTokens resets the access token to null', () => {
    useAuthStore
      .getState()
      .setTokens({ accessToken: 'access-1', accessTokenExpiresAt: 1, user: USER });
    useAuthStore.getState().clearTokens();
    expect(useAuthStore.getState().accessToken).toBeNull();
  });
});
