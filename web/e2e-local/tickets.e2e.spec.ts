// Runs in the nightly e2e-full workflow (.github/workflows/e2e-full.yml) and locally via npm run e2e:full
//
// Real-backend full-flow E2E (nightly_e2e_deferral): runs ONLY under
// --project=e2e-local (`npm run e2e:local`) against a compose stack at
// E2E_BASE_URL. Excluded from CI — this directory is not in the smoke testDir.
// Requires the T-016 project, T-020 list/create, and T-021 ticket-detail +
// T-019 activity endpoints to be live.
import { expect, test } from '@playwright/test';

const PASSWORD = 'TicketDetail123!';

test('change status on the detail page → a STATUS_CHANGED activity row appears', async ({ page }) => {
  const stamp = Date.now().toString().slice(-5);
  const email = `atlas-detail-${Date.now()}@qa.local`;
  const key = `E${stamp}`;

  // Register → auto-login lands on /projects.
  await page.goto('/register');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Display name').fill('Detail QA');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/projects$/, { timeout: 10_000 });

  // Create a project.
  await page.getByRole('button', { name: 'New project' }).click();
  await page.getByLabel('Name').fill(`Detail E2E ${stamp}`);
  await page.getByLabel('Key').fill(key);
  await page.getByRole('button', { name: 'Create project' }).click();
  await expect(page).toHaveURL(new RegExp(`/projects/${key}$`), { timeout: 10_000 });

  // Create a ticket from the list view, then open it.
  await page.getByRole('link', { name: 'List' }).click();
  await page.getByRole('button', { name: 'New ticket' }).click();
  await page.getByLabel('Title').fill('Detail flow ticket');
  await page.getByRole('button', { name: 'Create' }).click();
  await expect(page.getByText(`${key}-1 created`)).toBeVisible({ timeout: 10_000 });

  await page.getByRole('table').getByText(`${key}-1`).click();
  await expect(page).toHaveURL(new RegExp(`/projects/${key}/tickets/${key}-1$`));
  await expect(page.getByTestId('ticket-detail-page')).toBeVisible();

  // Change the status via the header Select.
  await page.getByTestId('status-select').click();
  await page.getByRole('option', { name: 'In Progress' }).click();

  // The activity timeline should pick up a STATUS_CHANGED row after invalidation.
  await expect(page.getByTestId('activity-event-STATUS_CHANGED')).toBeVisible({ timeout: 10_000 });
});
