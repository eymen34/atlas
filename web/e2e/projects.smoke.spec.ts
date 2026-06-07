import { expect, test } from '@playwright/test';

// CI smoke (playwright_architecture): built bundle on the Vite preview :4173,
// NO backend. We seed an authenticated session into localStorage (so
// AuthProvider resolves authenticated without a /me round-trip) and mock
// GET /api/projects via page.route(), then assert the list + dialog render.

const AUTH_BLOB = JSON.stringify({
  state: {
    accessToken: 'smoke-access-token',
    refreshToken: 'smoke-refresh-token',
    accessTokenExpiresAt: 4102444800000, // far future
    user: { id: 'u1', email: 'alice@example.com', displayName: 'Alice' },
  },
  version: 0,
});

const PROJECTS = [
  {
    id: 'p1',
    key: 'SMOKE1',
    name: 'Smoke Project',
    description: 'A mocked project for the smoke test.',
    createdBy: 'u1',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    callerRole: 'ADMIN',
    memberCount: 2,
  },
];

test.beforeEach(async ({ page }) => {
  await page.addInitScript(
    ([key, blob]) => {
      window.localStorage.setItem(key, blob);
    },
    ['atlas.auth.v1', AUTH_BLOB]
  );
  await page.route('**/api/projects', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(PROJECTS),
    });
  });
});

test('projects list renders mocked cards and opens the New Project dialog', async ({ page }) => {
  const pageerrors: Error[] = [];
  page.on('pageerror', (err) => pageerrors.push(err));

  await page.goto('/projects', { waitUntil: 'domcontentloaded' });

  await expect(page.getByTestId('project-card')).toBeVisible({ timeout: 5_000 });
  await expect(page.getByText('SMOKE1')).toBeVisible();

  await page.getByRole('button', { name: 'New project' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByLabel('Key')).toBeVisible();

  expect(pageerrors, pageerrors.map((e) => e.message).join(' | ')).toEqual([]);
});
