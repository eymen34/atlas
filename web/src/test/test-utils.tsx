import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement, ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import { OpenAPI } from '@/api/generated/core/OpenAPI';
import { Toaster } from '@/components/ui/sonner';

// Same-origin base so MSW reliably intercepts generated-client requests in jsdom
// (mirrors the production OpenAPI.BASE='' set by api/client.ts) without importing
// client.ts and its fetch/auth side effects into the test graph.
OpenAPI.BASE = '';

export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

/**
 * Renders `ui` inside a fresh QueryClient (retry off), a MemoryRouter, and a
 * mounted sonner Toaster so toast assertions work. Returns the render result
 * plus the QueryClient for spying on invalidations.
 */
export function renderWithProviders(
  ui: ReactElement,
  options?: { initialEntries?: string[]; queryClient?: QueryClient }
) {
  const queryClient = options?.queryClient ?? createTestQueryClient();
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={options?.initialEntries ?? ['/']}>{children}</MemoryRouter>
        <Toaster richColors position="top-right" />
      </QueryClientProvider>
    );
  }
  return { queryClient, ...render(ui, { wrapper: Wrapper }) };
}
