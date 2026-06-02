import { expect, test } from '@playwright/test';

// Smoke runs against the built bundle with NO backend, so the app is always
// unauthenticated: protected routes redirect to /login, and /login + /register
// render outside AppShell (no topbar). We assert those render cleanly with zero
// pageerror events. (Authenticated AppShell flows need a backend → auth.spec.ts.)

async function trackPageErrors(page: import('@playwright/test').Page) {
  const pageerrors: Error[] = [];
  page.on('pageerror', (err) => pageerrors.push(err));
  return pageerrors;
}

test('AC-3 /login renders the sign-in form outside AppShell, zero pageerror', async ({ page }) => {
  const pageerrors = await trackPageErrors(page);

  const response = await page.goto('/login', { waitUntil: 'domcontentloaded' });
  expect(response, '/login must return a response').not.toBeNull();
  expect(response!.status(), '/login HTTP status').toBe(200);

  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible({ timeout: 5_000 });
  // Login is OUTSIDE AppShell: no app-shell container, no Notifications bell.
  await expect(page.getByTestId('app-shell')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Notifications' })).toHaveCount(0);

  const body = await page.content();
  expect(body).not.toContain('Uncaught');
  expect(body).not.toContain('Minified React error');
  expect(pageerrors, pageerrors.map((e) => e.message).join(' | ')).toEqual([]);
});

test('AC-3 /register renders the create-account form, zero pageerror', async ({ page }) => {
  const pageerrors = await trackPageErrors(page);

  const response = await page.goto('/register', { waitUntil: 'domcontentloaded' });
  expect(response!.status(), '/register HTTP status').toBe(200);
  await expect(page.getByRole('button', { name: 'Create account' })).toBeVisible({ timeout: 5_000 });
  expect(pageerrors, pageerrors.map((e) => e.message).join(' | ')).toEqual([]);
});

test('AC-3 unauthenticated /projects redirects to /login with no error', async ({ page }) => {
  const pageerrors = await trackPageErrors(page);

  await page.goto('/projects', { waitUntil: 'domcontentloaded' });
  await expect(page).toHaveURL(/\/login$/, { timeout: 5_000 });
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  expect(pageerrors, pageerrors.map((e) => e.message).join(' | ')).toEqual([]);
});
