import { defineConfig, devices } from '@playwright/test';

// Two projects (architecture: playwright_architecture):
//   * smoke              — built bundle on the Vite preview :4173, NO backend.
//                          Run by `npm run e2e` (= --project=smoke) and CI.
//   * auth-real-backend  — full register→login→reload→logout (auth.spec.ts) plus
//                          the ticket-list create/filter flow (tickets.e2e.spec.ts)
//                          against a real compose stack at E2E_BASE_URL. LOCAL ONLY
//                          via `npm run e2e:auth`; NOT wired to CI (deferred T-038).
//
// `npm run e2e` scopes to --project=smoke so auth.spec.ts never runs in CI.
// The preview webServer is skipped when E2E_BASE_URL is set (auth mode targets
// an external stack, not the preview).
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [['github'], ['list']] : 'list',
  use: {
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'smoke',
      testMatch: /smoke\.spec\.ts$/,
      use: { ...devices['Desktop Chrome'], baseURL: 'http://localhost:4173' },
    },
    {
      name: 'auth-real-backend',
      testMatch: /(auth\.spec|tickets\.e2e\.spec)\.ts$/,
      use: {
        ...devices['Desktop Chrome'],
        baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
      },
    },
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'npm run preview',
        url: 'http://localhost:4173',
        reuseExistingServer: !process.env.CI,
        timeout: 60_000,
      },
});
