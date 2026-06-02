import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchWithAuth, setOnUnauthorized } from '../../api/client';
import { __resetRefreshSingleton } from '../../api/refreshSingleton';
import { useAuthStore } from '../../store/authStore';
import { server } from '../../test/msw/server';

function seedAuthenticated() {
  useAuthStore.setState({
    accessToken: 'old-access',
    refreshToken: 'refresh-1',
    accessTokenExpiresAt: Date.now() - 1000,
    user: { id: 'u1', email: 'a@b.com', displayName: 'Alice' },
    status: 'authenticated',
  });
}

beforeEach(() => {
  localStorage.clear();
  __resetRefreshSingleton();
  seedAuthenticated();
  setOnUnauthorized(() => useAuthStore.getState().clearTokens());
});

afterEach(() => {
  useAuthStore.getState().clearTokens();
  __resetRefreshSingleton();
});

describe('refresh singleton', () => {
  it('dedupes 3 concurrent 401s into exactly ONE /api/auth/refresh', async () => {
    let refreshCount = 0;
    server.use(
      http.get('/api/projects', () => new HttpResponse(null, { status: 401 })),
      http.post('/api/auth/refresh', async () => {
        refreshCount += 1;
        await new Promise((resolve) => setTimeout(resolve, 50));
        return HttpResponse.json({
          accessToken: 'new-access',
          refreshToken: 'new-refresh',
          expiresIn: 900,
        });
      })
    );

    const results = await Promise.allSettled([
      fetchWithAuth('/api/projects'),
      fetchWithAuth('/api/projects'),
      fetchWithAuth('/api/projects'),
    ]);

    expect(refreshCount).toBe(1);
    expect(results.every((r) => r.status === 'fulfilled')).toBe(true);
    expect(useAuthStore.getState().accessToken).toBe('new-access');
    expect(useAuthStore.getState().accessTokenExpiresAt).toBeGreaterThan(Date.now());
  });

  it('malformed 200 from refresh → onUnauthorized once, no infinite loop', async () => {
    let refreshCount = 0;
    const onUnauthorized = vi.fn();
    setOnUnauthorized(onUnauthorized);
    server.use(
      http.get('/api/protected', () => new HttpResponse(null, { status: 401 })),
      http.post('/api/auth/refresh', () => {
        refreshCount += 1;
        return HttpResponse.json({ garbage: true });
      })
    );

    const res = await fetchWithAuth('/api/protected');

    expect(refreshCount).toBe(1);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(res.status).toBe(401);
  });

  it('401 with no refresh token → onUnauthorized, no refresh attempt', async () => {
    useAuthStore.setState({ refreshToken: null });
    let refreshCount = 0;
    const onUnauthorized = vi.fn();
    setOnUnauthorized(onUnauthorized);
    server.use(
      http.get('/api/protected', () => new HttpResponse(null, { status: 401 })),
      http.post('/api/auth/refresh', () => {
        refreshCount += 1;
        return HttpResponse.json({ accessToken: 'x', refreshToken: 'y', expiresIn: 900 });
      })
    );

    await fetchWithAuth('/api/protected');

    expect(refreshCount).toBe(0);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });
});
