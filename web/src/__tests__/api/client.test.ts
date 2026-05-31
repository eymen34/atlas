import { describe, it, expect, beforeEach } from 'vitest';
import { OpenAPI } from '../../api/generated/core/OpenAPI';
import type { ApiRequestOptions } from '../../api/generated/core/ApiRequestOptions';
import { useAuthStore } from '../../store/authStore';
import { setOnUnauthorized, handleUnauthorized } from '../../api/client';

const DUMMY_REQ: ApiRequestOptions = { method: 'GET', url: '/api/auth/me' };

describe('api client wiring', () => {
  beforeEach(() => {
    useAuthStore.getState().clearTokens();
    // Restore the default 401 handler so cross-test ordering is irrelevant.
    setOnUnauthorized(() => useAuthStore.getState().clearTokens());
  });

  it('sets OpenAPI.BASE to an empty string (same-origin)', () => {
    expect(OpenAPI.BASE).toBe('');
  });

  it('resolves the Bearer token from live Zustand state per request', async () => {
    const resolver = OpenAPI.TOKEN;
    expect(typeof resolver).toBe('function');
    useAuthStore.getState().setTokens('access-1', 'refresh-1');
    if (typeof resolver === 'function') {
      await expect(resolver(DUMMY_REQ)).resolves.toBe('access-1');
    }
  });

  it('resolves the token to empty string when none is set', async () => {
    const resolver = OpenAPI.TOKEN;
    if (typeof resolver === 'function') {
      await expect(resolver(DUMMY_REQ)).resolves.toBe('');
    }
  });

  it('handleUnauthorized invokes the registered handler', () => {
    let calls = 0;
    setOnUnauthorized(() => {
      calls += 1;
    });
    handleUnauthorized();
    expect(calls).toBe(1);
  });

  it('default 401 handler clears the tokens', () => {
    useAuthStore.getState().setTokens('access-2', 'refresh-2');
    handleUnauthorized();
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().refreshToken).toBeNull();
  });
});
