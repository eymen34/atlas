// RUN LOCALLY ONLY — CI wiring deferred (see CI-wiring ticket)
//
// Mirrors auth.spec.ts: runs ONLY under --project=auth-real-backend
// (`npm run e2e:auth`) against a real compose stack at E2E_BASE_URL. NOT wired to
// CI (deferred T-038). Requires the T-016 project endpoints and the T-018/T-019
// ticket list+create endpoints to be live.
import { expect, test } from '@playwright/test';

const PASSWORD = 'TicketPass123!';

test('create project → add ticket → see it in the list → filter by status', async ({ page }) => {
  const stamp = Date.now().toString().slice(-5);
  const email = `atlas-tix-${Date.now()}@qa.local`;
  const key = `E${stamp}`; // valid project key: leading uppercase letter + digits

  // Register → auto-login lands on /projects.
  await page.goto('/register');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Display name').fill('Ticket QA');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/projects$/, { timeout: 10_000 });

  // Create a project (Key set explicitly so it's unique per run).
  await page.getByRole('button', { name: 'New project' }).click();
  await page.getByLabel('Name').fill(`E2E Tickets ${stamp}`);
  await page.getByLabel('Key').fill(key);
  await page.getByRole('button', { name: 'Create project' }).click();
  await expect(page).toHaveURL(new RegExp(`/projects/${key}$`), { timeout: 10_000 });

  // Open the list view via the project sidebar; it starts empty.
  await page.getByRole('link', { name: 'List' }).click();
  await expect(page).toHaveURL(new RegExp(`/projects/${key}/list$`));
  await expect(page.getByText('No tickets match the current filters.')).toBeVisible();

  // Create a ticket.
  const title = `First ticket ${stamp}`;
  await page.getByRole('button', { name: 'New ticket' }).click();
  await page.getByLabel('Title').fill(title);
  await page.getByRole('button', { name: 'Create' }).click();

  // Toast confirms the assigned key; the row appears in the table.
  await expect(page.getByText(`${key}-1 created`)).toBeVisible({ timeout: 10_000 });
  const table = page.getByRole('table');
  await expect(table.getByText(title)).toBeVisible();
  await expect(table.getByText(`${key}-1`)).toBeVisible();

  // Filter by status=TODO → URL reflects the backend param; the new ticket matches.
  await page.getByRole('button', { name: /^Status/ }).click();
  await page.getByRole('menuitemcheckbox', { name: 'TODO' }).click();
  await page.keyboard.press('Escape'); // checkbox dropdowns stay open by design
  await expect(page).toHaveURL(/[?&]status=TODO\b/);
  await expect(table.getByText(title)).toBeVisible();

  // Switch to a non-matching status → the list empties.
  await page.getByRole('button', { name: /^Status/ }).click();
  await page.getByRole('menuitemcheckbox', { name: 'TODO' }).click(); // uncheck
  await page.getByRole('menuitemcheckbox', { name: 'DONE' }).click();
  await page.keyboard.press('Escape');
  await expect(page.getByText('No tickets match the current filters.')).toBeVisible();
});
