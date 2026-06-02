import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AUTH_STORAGE_KEY, handleAuthStorageEvent, useAuthStore } from '../../store/authStore';
import type { UserProfile } from '../types';

const USER: UserProfile = { id: 'u1', email: 'a@b.com', displayName: 'Alice' };

beforeEach(() => {
  localStorage.clear();
  useAuthStore.getState().clearTokens();
});

afterEach(() => {
  window.removeEventListener('storage', handleAuthStorageEvent);
});

describe('authStore', () => {
  it('two-arg setTokens sets tokens but leaves accessTokenExpiresAt + user untouched', () => {
    useAuthStore.getState().setTokens({
      accessToken: 'a',
      refreshToken: 'r',
      accessTokenExpiresAt: 123,
      user: USER,
    });
    useAuthStore.getState().setTokens('a2', 'r2');

    const s = useAuthStore.getState();
    expect(s.accessToken).toBe('a2');
    expect(s.refreshToken).toBe('r2');
    expect(s.accessTokenExpiresAt).toBe(123);
    expect(s.user).toEqual(USER);
  });

  it('object setTokens sets all four fields + status=authenticated', () => {
    useAuthStore.getState().setTokens({
      accessToken: 'a',
      refreshToken: 'r',
      accessTokenExpiresAt: 999,
      user: USER,
    });
    const s = useAuthStore.getState();
    expect(s.accessToken).toBe('a');
    expect(s.refreshToken).toBe('r');
    expect(s.accessTokenExpiresAt).toBe(999);
    expect(s.user).toEqual(USER);
    expect(s.status).toBe('authenticated');
  });

  it('clearTokens nulls all four + status=unauthenticated', () => {
    useAuthStore.getState().setTokens({
      accessToken: 'a',
      refreshToken: 'r',
      accessTokenExpiresAt: 999,
      user: USER,
    });
    useAuthStore.getState().clearTokens();
    const s = useAuthStore.getState();
    expect(s.accessToken).toBeNull();
    expect(s.refreshToken).toBeNull();
    expect(s.accessTokenExpiresAt).toBeNull();
    expect(s.user).toBeNull();
    expect(s.status).toBe('unauthenticated');
  });

  it('persists the bundle to localStorage under atlas.auth.v1', () => {
    useAuthStore.getState().setTokens({
      accessToken: 'a',
      refreshToken: 'r',
      accessTokenExpiresAt: 999,
      user: USER,
    });
    const raw = localStorage.getItem(AUTH_STORAGE_KEY);
    expect(raw).not.toBeNull();
    const parsed = JSON.parse(raw as string) as { state: Record<string, unknown> };
    expect(parsed.state).toMatchObject({
      accessToken: 'a',
      refreshToken: 'r',
      accessTokenExpiresAt: 999,
      user: USER,
    });
  });

  it('cross-tab storage event without a refreshToken clears the session', () => {
    window.addEventListener('storage', handleAuthStorageEvent);
    useAuthStore.getState().setTokens({
      accessToken: 'a',
      refreshToken: 'r',
      accessTokenExpiresAt: 999,
      user: USER,
    });
    handleAuthStorageEvent(
      new StorageEvent('storage', {
        key: AUTH_STORAGE_KEY,
        newValue: JSON.stringify({ state: { accessToken: 'a', user: USER } }),
      })
    );
    expect(useAuthStore.getState().accessToken).toBeNull();
  });
});
