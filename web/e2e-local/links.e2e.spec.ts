// Runs in the nightly e2e-full workflow (.github/workflows/e2e-full.yml) and locally via npm run e2e:full
//
// Real-backend full-flow E2E (nightly_e2e_deferral): runs ONLY under
// --project=e2e-local (`npm run e2e:local`) against a compose stack at E2E_BASE_URL.
// Excluded from CI. Exercises the T-026 link flow: create two tickets, link one to the
// other (BLOCKS), and assert the reciprocal relation surfaces in the links panel.
import { expect, test } from '@playwright/test';

const PASSWORD = 'Linking123!';

test('link two tickets (BLOCKS) → the link appears in the panel', async ({ page }) => {
  const stamp = Date.now().toString().slice(-5);
  const key = `L${stamp}`;

  await page.goto('/register');
  await page.getByLabel('Email').fill(`atlas-link-${Date.now()}@qa.local`);
  await page.getByLabel('Display name').fill('Link QA');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/projects$/, { timeout: 10_000 });

  await page.getByRole('button', { name: 'New project' }).click();
  await page.getByLabel('Name').fill(`Link E2E ${stamp}`);
  await page.getByLabel('Key').fill(key);
  await page.getByRole('button', { name: 'Create project' }).click();
  await expect(page).toHaveURL(new RegExp(`/projects/${key}$`), { timeout: 10_000 });

  // Two tickets: KEY-1 and KEY-2. Scope "List" to the sidebar nav (ProjectViewToggle, T-027,
  // added a second "List" link → unscoped getByRole is ambiguous).
  await page
    .getByRole('navigation', { name: 'Project sections' })
    .getByRole('link', { name: 'List', exact: true })
    .click();
  for (const title of ['First ticket', 'Second ticket']) {
    await page.getByRole('button', { name: 'New ticket' }).click();
    await page.getByLabel('Title').fill(title);
    await page.getByRole('button', { name: 'Create' }).click();
    await expect(page.getByText('created')).toBeVisible({ timeout: 10_000 });
  }

  // Open KEY-1 and link it to KEY-2.
  await page.getByRole('table').getByText(`${key}-1`).click();
  await expect(page).toHaveURL(new RegExp(`/projects/${key}/tickets/${key}-1$`));
  await expect(page.getByTestId('links-section')).toBeVisible();

  await page.getByRole('button', { name: 'Add link' }).click();
  await page.getByPlaceholder('Search tickets in this project…').fill('Second');
  await page.getByText('Second ticket').click();
  await page.getByTestId('add-link-submit').click();

  // The reciprocal BLOCKS link to KEY-2 now shows in the panel.
  await expect(page.getByTestId('link-row').filter({ hasText: `${key}-2` })).toBeVisible({
    timeout: 10_000,
  });
});
