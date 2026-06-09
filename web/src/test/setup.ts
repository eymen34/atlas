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

// Radix dropdown/select (FilterBar, pagers, dialog selects) lean on a few DOM APIs
// jsdom doesn't implement: Pointer Capture, scrollIntoView, and ResizeObserver
// (Popper positioning). Polyfill them so opening a menu in a test doesn't throw.
if (!Element.prototype.hasPointerCapture) {
  Element.prototype.hasPointerCapture = () => false;
  Element.prototype.setPointerCapture = () => {};
  Element.prototype.releasePointerCapture = () => {};
}
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {};
}
// lib.dom declares ResizeObserver as always-present, so guard via a locally-typed
// optional ref (an `in` check would narrow window to `never` here).
const g = globalThis as { ResizeObserver?: typeof ResizeObserver };
if (!g.ResizeObserver) {
  g.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
}

// Start MSW at module scope (NOT in beforeAll) so it patches global fetch before
// any test module — and crucially before src/api/nativeFetch.ts captures its
// reference. onUnhandledRequest='bypass' keeps non-fetching tests unaffected.
server.listen({ onUnhandledRequest: 'bypass' });

afterEach(() => server.resetHandlers());
afterAll(() => server.close());
