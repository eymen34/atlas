import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AUTH_PERSIST_VERSION,
  AUTH_STORAGE_KEY,
  handleAuthStorageEvent,
  useAuthStore,
} from '../../store/authStore';
import type { UserProfile } from '../types';

const USER: UserProfile = { id: 'u1', email: 'a@b.com', displayName: 'Alice' };

beforeEach(() => {
  localStorage.clear();
  useAuthStore.getState().clearTokens();
});

afterEach(() => {
  window.removeEventListener('storage', handleAuthStorageEvent);
});

describe('authStore (T-048 cookie cutover)', () => {
  it('setTokens sets accessToken + expiry + user + status, never a refreshToken', () => {
    useAuthStore.getState().setTokens({ accessToken: 'a', accessTokenExpiresAt: 999, user: USER });
    const s = useAuthStore.getState();
    expect(s.accessToken).toBe('a');
    expect(s.accessTokenExpiresAt).toBe(999);
    expect(s.user).toEqual(USER);
    expect(s.status).toBe('authenticated');
    expect((s as unknown as Record<string, unknown>).refreshToken).toBeUndefined();
  });

  it('clearTokens nulls accessToken + expiry + user, status=unauthenticated', () => {
    useAuthStore.getState().setTokens({ accessToken: 'a', accessTokenExpiresAt: 999, user: USER });
    useAuthStore.getState().clearTokens();
    const s = useAuthStore.getState();
    expect(s.accessToken).toBeNull();
    expect(s.accessTokenExpiresAt).toBeNull();
    expect(s.user).toBeNull();
    expect(s.status).toBe('unauthenticated');
  });

  it('QG-8: the persisted blob has NO refreshToken key', () => {
    useAuthStore.getState().setTokens({ accessToken: 'a', accessTokenExpiresAt: 999, user: USER });
    const raw = localStorage.getItem(AUTH_STORAGE_KEY);
    expect(raw).not.toBeNull();
    const parsed = JSON.parse(raw as string) as { state: Record<string, unknown> };
    expect(parsed.state).toMatchObject({ accessToken: 'a', accessTokenExpiresAt: 999, user: USER });
    expect(parsed.state).not.toHaveProperty('refreshToken');
  });

  it('QG-9: a cross-tab storage event with accessToken/user null clears the session', () => {
    window.addEventListener('storage', handleAuthStorageEvent);
    useAuthStore.getState().setTokens({ accessToken: 'a', accessTokenExpiresAt: 999, user: USER });
    handleAuthStorageEvent(
      new StorageEvent('storage', {
        key: AUTH_STORAGE_KEY,
        newValue: JSON.stringify({ state: { accessToken: null, user: null } }),
      })
    );
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('QG-9: a null newValue (key removed) also clears the session', () => {
    window.addEventListener('storage', handleAuthStorageEvent);
    useAuthStore.getState().setTokens({ accessToken: 'a', accessTokenExpiresAt: 999, user: USER });
    handleAuthStorageEvent(new StorageEvent('storage', { key: AUTH_STORAGE_KEY, newValue: null }));
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('EC-5/QG-8: migrate() scrubs a legacy refreshToken from a pre-version-2 blob', async () => {
    // Seed a legacy v1 blob carrying a refreshToken, then re-import the store fresh so its
    // persist middleware rehydrates + migrates.
    localStorage.setItem(
      AUTH_STORAGE_KEY,
      JSON.stringify({
        state: {
          accessToken: 'a',
          refreshToken: 'legacy-rt',
          accessTokenExpiresAt: 999,
          user: USER,
        },
        version: 1,
      })
    );
    vi.resetModules();
    const fresh = await import('../../store/authStore');

    const state = fresh.useAuthStore.getState();
    expect(state.accessToken).toBe('a'); // preserved
    expect(state.user).toEqual(USER);
    expect((state as unknown as Record<string, unknown>).refreshToken).toBeUndefined();

    // Force a re-persist, then confirm the on-disk blob is scrubbed + version-bumped.
    fresh.useAuthStore.getState().setStatus('authenticated');
    const parsed = JSON.parse(localStorage.getItem(AUTH_STORAGE_KEY) as string) as {
      state: Record<string, unknown>;
      version: number;
    };
    expect(parsed.state).not.toHaveProperty('refreshToken');
    expect(parsed.version).toBe(AUTH_PERSIST_VERSION);
  });
});
