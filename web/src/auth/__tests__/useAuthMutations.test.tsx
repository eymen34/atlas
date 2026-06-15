import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../../store/authStore';
import { server } from '../../test/msw/server';
import { useLogout } from '../useAuthMutations';

function makeWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/projects']}>{children}</MemoryRouter>
      </QueryClientProvider>
    );
  };
}

function seededClient(): QueryClient {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  // Stand in for "user A" server state cached during their session.
  queryClient.setQueryData(['projects'], [{ id: 'p1', key: 'AAA' }]);
  queryClient.setQueryData(['me'], { id: 'user-A', isAdmin: true });
  return queryClient;
}

beforeEach(() => {
  localStorage.clear();
  // Authenticated "user A" so logout has a session to tear down.
  useAuthStore.setState({
    accessToken: 'acc-A',
    accessTokenExpiresAt: Date.now() + 60_000,
    user: { id: 'user-A', email: 'a@b.com', displayName: 'A' },
    status: 'authenticated',
  });
});

afterEach(() => {
  useAuthStore.getState().clearTokens();
});

describe('useLogout — T-061 clear client cache on logout (F-8)', () => {
  it('clears the entire TanStack Query cache on a successful logout (MSW default 204)', async () => {
    const queryClient = seededClient();
    expect(queryClient.getQueryCache().getAll()).toHaveLength(2);

    const { result } = renderHook(() => useLogout(), { wrapper: makeWrapper(queryClient) });
    result.current.mutate();

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // No flash of A's data/menus for the next user: cache is empty post-logout.
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
    expect(queryClient.getQueryData(['projects'])).toBeUndefined();
    expect(queryClient.getQueryData(['me'])).toBeUndefined();
    // The local session is torn down too (existing onSettled behavior preserved).
    expect(useAuthStore.getState().status).toBe('unauthenticated');
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('clears the cache even when the logout request fails — clear() lives in onSettled, not onSuccess', async () => {
    // mutationFn retries once then swallows the network error, so the mutation still resolves;
    // the point under test is that onSettled fires regardless and the cache is dropped.
    server.use(http.post('/api/auth/logout', () => HttpResponse.error()));
    const queryClient = seededClient();
    expect(queryClient.getQueryCache().getAll()).toHaveLength(2);

    const { result } = renderHook(() => useLogout(), { wrapper: makeWrapper(queryClient) });
    result.current.mutate();

    await waitFor(() => expect(result.current.isPending).toBe(false));
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
    expect(useAuthStore.getState().status).toBe('unauthenticated');
  });
});
