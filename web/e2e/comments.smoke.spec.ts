import { expect, test } from '@playwright/test';

// Smoke runs against the built bundle with NO backend, so the ticket-detail route
// (which now mounts the comments section) is always unauthenticated → it must
// redirect to /login and the route chunk must load with zero pageerror.

test('@smoke ticket detail with comments section loads with zero pageerror', async ({ page }) => {
  const pageerrors: Error[] = [];
  page.on('pageerror', (err) => pageerrors.push(err));

  await page.goto('/projects/ENG/tickets/ENG-1', { waitUntil: 'domcontentloaded' });
  await expect(page).toHaveURL(/\/login$/, { timeout: 5_000 });
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();

  const body = await page.content();
  expect(body).not.toContain('Minified React error');
  expect(pageerrors, pageerrors.map((e) => e.message).join(' | ')).toEqual([]);
});
