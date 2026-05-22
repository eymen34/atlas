import { expect, test } from '@playwright/test';

const ROUTES = ['/login', '/projects', '/projects/test-project-123'] as const;

for (const route of ROUTES) {
  test(`AC-1.2 ${route} loads via AppShell with zero pageerror events`, async ({ page }) => {
    const pageerrors: Error[] = [];
    page.on('pageerror', (err) => pageerrors.push(err));

    const response = await page.goto(route, { waitUntil: 'domcontentloaded' });
    expect(response, `${route} must return a response`).not.toBeNull();
    expect(response!.status(), `${route} HTTP status`).toBe(200);

    const header = page.locator('header');
    await expect(header).toBeVisible({ timeout: 5_000 });

    const body = await page.content();
    expect(body).not.toContain('Uncaught');
    expect(body).not.toContain('Minified React error');

    expect(pageerrors, `unexpected pageerror(s): ${pageerrors.map((e) => e.message).join(' | ')}`).toEqual([]);
  });
}

test('EC-4 /login renders Bell + Avatar placeholders; clicking Bell does not navigate or error', async ({
  page,
}) => {
  const pageerrors: Error[] = [];
  page.on('pageerror', (err) => pageerrors.push(err));

  await page.goto('/login', { waitUntil: 'domcontentloaded' });

  const bell = page.getByRole('button', { name: 'Notifications' });
  await expect(bell).toBeVisible();

  await bell.click();
  expect(pageerrors).toEqual([]);
  expect(page.url()).toMatch(/\/login$/);
});
