// Runs in the nightly e2e-full workflow (.github/workflows/e2e-full.yml) and locally via npm run e2e:full
//
// T-048 real-backend auth-cookie E2E (AC-4.3 / QG-1,2,5): the refresh token is an HttpOnly cookie,
// not JS-readable. Register → assert cookie attributes + JS-invisibility → force the access token
// stale → a protected navigation silently refreshes via the cookie (no /login bounce) → logout
// clears the cookie. Runs ONLY under --project=e2e-local against a compose stack at E2E_BASE_URL;
// excluded from PR CI (this directory is not in the smoke testDir).
import { expect, test } from '@playwright/test';

const PASSWORD = 'CookieAuth123!';
const STORE_KEY = 'atlas.auth.v1';

test('refresh token is an HttpOnly cookie: silent refresh works, JS never sees it', async ({
  page,
}) => {
  const email = `atlas-cookie-${Date.now()}@qa.local`;

  // Register → auto-login lands on /projects.
  await page.goto('/register');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Display name').fill('Cookie QA');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/projects$/, { timeout: 10_000 });

  // QG-1: atlas_refresh is set HttpOnly, Path=/api/auth, SameSite=Lax.
  const refresh = (await page.context().cookies()).find((c) => c.name === 'atlas_refresh');
  expect(refresh, 'atlas_refresh cookie set on login').toBeTruthy();
  expect(refresh?.httpOnly).toBe(true);
  expect(refresh?.path).toBe('/api/auth');
  expect(String(refresh?.sameSite).toLowerCase()).toBe('lax');

  // QG-2: JS cannot read it — absent from document.cookie AND from the persisted store blob.
  expect(await page.evaluate(() => document.cookie)).not.toContain('atlas_refresh');
  expect(await page.evaluate((k) => localStorage.getItem(k), STORE_KEY)).not.toContain(
    'refreshToken'
  );

  // QG-5: force the access token stale; a protected navigation must silently refresh via the
  // cookie (fetchWithAuth → body-less POST /api/auth/refresh) and stay authenticated.
  await page.evaluate((k) => {
    const raw = localStorage.getItem(k);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    parsed.state.accessToken = 'expired.invalid.token';
    parsed.state.accessTokenExpiresAt = Date.now() - 1000;
    localStorage.setItem(k, JSON.stringify(parsed));
  }, STORE_KEY);

  await page.goto('/projects');
  await expect(page).toHaveURL(/\/projects$/, { timeout: 10_000 });
  await expect(page.getByTestId('logout-button')).toBeVisible();

  // Logout clears the cookie.
  await page.getByTestId('logout-button').click();
  await expect(page).toHaveURL(/\/login$/, { timeout: 10_000 });
  const afterLogout = (await page.context().cookies()).find((c) => c.name === 'atlas_refresh');
  expect(afterLogout?.value ?? '').toBe('');
});
