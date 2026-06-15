import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../../store/authStore';
import { server } from '../../test/msw/server';
import * as client from '../client';

beforeEach(() => {
  localStorage.clear();
  useAuthStore.getState().clearTokens();
});

afterEach(() => {
  useAuthStore.getState().clearTokens();
});

describe('api client (T-013)', () => {
  it('preserves the T-010 exports plus fetchWithAuth', () => {
    expect(typeof client.setOnUnauthorized).toBe('function');
    expect(typeof client.handleUnauthorized).toBe('function');
    expect(client._getMeTypeProbe).toBeDefined();
    expect(client.AuthService).toBeDefined();
    expect(typeof client.fetchWithAuth).toBe('function');
  });

  it('installs an idempotent global fetch patch (Symbol guard)', () => {
    const flag = Symbol.for('atlas.fetchPatched');
    expect((window as unknown as Record<symbol, boolean>)[flag]).toBe(true);
  });

  it('does not inject a Bearer header or refresh on auth endpoints', async () => {
    useAuthStore.setState({
      accessToken: 'acc',
      accessTokenExpiresAt: Date.now() + 60_000,
      user: { id: '1', email: 'a@b.com', displayName: 'A' },
      status: 'authenticated',
    });
    let seenAuthHeader: string | null = 'unset';
    server.use(
      http.post('/api/auth/login', ({ request }) => {
        seenAuthHeader = request.headers.get('authorization');
        return HttpResponse.json({ ok: true });
      })
    );

    await client.fetchWithAuth('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    });

    expect(seenAuthHeader).toBeNull();
  });
});
