// Runs in the nightly e2e-full workflow (.github/workflows/e2e-full.yml) and locally via npm run e2e:full
//
// Real-backend full-flow E2E (nightly_e2e_deferral): runs ONLY under
// --project=e2e-local (`npm run e2e:local`) against a compose stack at E2E_BASE_URL.
// Excluded from CI. Exercises the T-027 board: drag a card TODO → IN_PROGRESS and
// assert the ticket detail activity feed gains a STATUS_CHANGED entry (the optimistic
// transition really hit the backend).
import { expect, test } from '@playwright/test';

const PASSWORD = 'BoardDnd123!';

test('drag a card TODO → IN_PROGRESS writes a STATUS_CHANGED activity', async ({ page }) => {
  const stamp = Date.now().toString().slice(-5);
  const key = `B${stamp}`;

  await page.goto('/register');
  await page.getByLabel('Email').fill(`atlas-board-${Date.now()}@qa.local`);
  await page.getByLabel('Display name').fill('Board QA');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/projects$/, { timeout: 10_000 });

  await page.getByRole('button', { name: 'New project' }).click();
  await page.getByLabel('Name').fill(`Board E2E ${stamp}`);
  await page.getByLabel('Key').fill(key);
  await page.getByRole('button', { name: 'Create project' }).click();
  await expect(page).toHaveURL(new RegExp(`/projects/${key}$`), { timeout: 10_000 });

  // Create a ticket from the list, then open the board. Scope nav links to the sidebar —
  // ProjectViewToggle (T-027) added second "Board"/"List" links → unscoped getByRole is ambiguous.
  await page
    .getByRole('navigation', { name: 'Project sections' })
    .getByRole('link', { name: 'List', exact: true })
    .click();
  await page.getByRole('button', { name: 'New ticket' }).click();
  await page.getByLabel('Title').fill('Drag me');
  await page.getByRole('button', { name: 'Create' }).click();
  await expect(page.getByText(`${key}-1 created`)).toBeVisible({ timeout: 10_000 });

  await page
    .getByRole('navigation', { name: 'Project sections' })
    .getByRole('link', { name: 'Board', exact: true })
    .click();
  // Force a fresh board fetch: the ticket was created in the List view, which does not invalidate
  // the board's query key, and the board's 15s staleTime serves the stale (empty) cache the index
  // route pre-fetched. A reload re-runs the board query against the committed ticket.
  await page.reload();
  await expect(page.getByTestId('board-column-TODO')).toBeVisible();
  const card = page.locator('[data-testid^="board-ticket-card-"]').filter({ hasText: `${key}-1` });
  await expect(card).toBeVisible();

  // Pointer-drag the card's handle into the IN_PROGRESS column (steps > 8px activation).
  const handle = card.getByRole('button', { name: /^Drag / });
  const from = await handle.boundingBox();
  const target = await page.getByTestId('board-column-IN_PROGRESS').boundingBox();
  if (!from || !target) throw new Error('missing drag geometry');
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(target.x + target.width / 2, target.y + 40, { steps: 12 });
  await page.mouse.up();

  // The card now lives under IN_PROGRESS.
  await expect(
    page.getByTestId('board-column-IN_PROGRESS').locator('[data-testid^="board-ticket-card-"]')
  ).toContainText(`${key}-1`, { timeout: 10_000 });

  // Open the ticket and confirm the transition was persisted (STATUS_CHANGED activity).
  await page.getByText(`${key}-1`).first().click();
  await expect(page).toHaveURL(new RegExp(`/projects/${key}/tickets/${key}-1$`));
  await expect(page.getByTestId('activity-event-STATUS_CHANGED')).toBeVisible({ timeout: 10_000 });
});
