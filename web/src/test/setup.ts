import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach } from 'vitest';
import { server } from './msw/server';

// Start MSW at module scope (NOT in beforeAll) so it patches global fetch before
// any test module — and crucially before src/api/nativeFetch.ts captures its
// reference. onUnhandledRequest='bypass' keeps non-fetching tests unaffected.
server.listen({ onUnhandledRequest: 'bypass' });

afterEach(() => server.resetHandlers());
afterAll(() => server.close());
