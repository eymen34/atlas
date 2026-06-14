// Backend-dependent (auth-real-backend project). Runs in the nightly e2e-full workflow
// (.github/workflows/e2e-full.yml) and locally via `npm run e2e:full` or `npm run e2e:auth`.
// Requires a compose stack reachable at E2E_BASE_URL (defaults to the Vite dev server
// http://localhost:5173, which proxies /api to the backend on :8080). Set AUTH_E2E_TEARDOWN=1
// for best-effort cleanup hooks.
//
// Backend note: this requires the T-012 auth endpoints (login/refresh/logout/me)
// to be live; against a T-011 backend (501 stubs) it will fail by design.
import { expect, test } from '@playwright/test';

const PASSWORD = 'AlicePass123!';

test('register → /projects → reload restores session → logout → /login', async ({ page }) => {
  const email = `atlas-test-${Date.now()}@qa.local`;

  await page.goto('/register');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Display name').fill('QA Tester');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();

  // Auto-login lands on /projects inside AppShell.
  await expect(page).toHaveURL(/\/projects$/, { timeout: 10_000 });
  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByTestId('topbar-user')).toContainText('QA Tester');

  // Reload restores the session from localStorage (no bounce to /login).
  await page.reload();
  await expect(page).toHaveURL(/\/projects$/);
  await expect(page.getByTestId('app-shell')).toBeVisible();

  // Logout → /login, and /projects becomes inaccessible.
  await page.getByTestId('logout-button').click();
  await expect(page).toHaveURL(/\/login$/, { timeout: 10_000 });
  await page.goto('/projects');
  await expect(page).toHaveURL(/\/login$/);
});
