import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, vi } from 'vitest';
import { server } from './msw/server';

// jsdom has no matchMedia; sonner's <Toaster /> calls it on mount. Polyfill it
// so components that mount the Toaster (project pages, dialogs) render in tests.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  configurable: true,
  value: vi.fn((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
});

// Start MSW at module scope (NOT in beforeAll) so it patches global fetch before
// any test module — and crucially before src/api/nativeFetch.ts captures its
// reference. onUnhandledRequest='bypass' keeps non-fetching tests unaffected.
server.listen({ onUnhandledRequest: 'bypass' });

afterEach(() => server.resetHandlers());
afterAll(() => server.close());
