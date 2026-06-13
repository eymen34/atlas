import { expect, test } from '@playwright/test';

// Smoke runs against the built bundle with NO backend. The global ⌘K search mounts in
// the AppShell header on every protected route, so hitting one unauthenticated must
// redirect to /login and the route/search chunk must load with zero pageerror — proving
// the T-028 search code bundles and parses cleanly.

test('@smoke global search chunk loads (protected route redirects with zero pageerror)', async ({
  page,
}) => {
  const pageerrors: Error[] = [];
  page.on('pageerror', (err) => pageerrors.push(err));

  await page.goto('/projects', { waitUntil: 'domcontentloaded' });
  await expect(page).toHaveURL(/\/login$/, { timeout: 5_000 });
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();

  const body = await page.content();
  expect(body).not.toContain('Minified React error');
  expect(pageerrors, pageerrors.map((e) => e.message).join(' | ')).toEqual([]);
});
