import { expect, test } from '@playwright/test';

// Smoke runs against the built bundle with NO backend, so the app is always
// unauthenticated: the board deep link must redirect to /login and the route chunk
// (dnd-kit) must load without throwing. The "four columns visible"
// assertion needs an authenticated, project-resolved board → that lives in the
// real-backend e2e-local spec (board.dnd.local.spec.ts).

test('@smoke board deep link redirects to /login with zero pageerror', async ({ page }) => {
  const pageerrors: Error[] = [];
  page.on('pageerror', (err) => pageerrors.push(err));

  await page.goto('/projects/ENG/board', { waitUntil: 'domcontentloaded' });
  await expect(page).toHaveURL(/\/login$/, { timeout: 5_000 });
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();

  const body = await page.content();
  expect(body).not.toContain('Minified React error');
  expect(pageerrors, pageerrors.map((e) => e.message).join(' | ')).toEqual([]);
});
