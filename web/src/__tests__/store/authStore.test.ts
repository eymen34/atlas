import { describe, it, expect, beforeEach } from 'vitest';
import { useAuthStore } from '../../store/authStore';

describe('useAuthStore', () => {
  beforeEach(() => {
    useAuthStore.getState().clearTokens();
  });

  it('starts with both tokens null', () => {
    const state = useAuthStore.getState();
    expect(state.accessToken).toBeNull();
    expect(state.refreshToken).toBeNull();
  });

  it('setTokens stores both the access and refresh tokens', () => {
    useAuthStore.getState().setTokens('access-1', 'refresh-1');
    const state = useAuthStore.getState();
    expect(state.accessToken).toBe('access-1');
    expect(state.refreshToken).toBe('refresh-1');
  });

  it('clearTokens resets both tokens to null', () => {
    useAuthStore.getState().setTokens('access-1', 'refresh-1');
    useAuthStore.getState().clearTokens();
    const state = useAuthStore.getState();
    expect(state.accessToken).toBeNull();
    expect(state.refreshToken).toBeNull();
  });
});
