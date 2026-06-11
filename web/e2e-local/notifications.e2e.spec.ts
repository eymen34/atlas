// RUN LOCALLY ONLY — CI wiring deferred (see CI-wiring ticket)
//
// Real-backend full-flow E2E (nightly_e2e_deferral): runs ONLY under
// --project=e2e-local (`npm run e2e:local`) against a compose stack at
// E2E_BASE_URL. Excluded from CI — this directory is not in the smoke testDir.
//
// Exercises the T-024 fan-out end to end across TWO users: alice assigns a ticket
// to bob; the AFTER_COMMIT listener writes an ASSIGNED notification; bob's bell
// shows it and clicking the row deep-links to the ticket. A single session cannot
// produce a notification for itself (every kind skips the actor), hence two
// browser contexts.
import { expect, test } from '@playwright/test';

const PASSWORD = 'NotifyFlow123!';

test('alice assigns a ticket to bob → bob sees an ASSIGNED notification and it deep-links', async ({
  browser,
}) => {
  const stamp = Date.now().toString().slice(-6);
  const key = `N${stamp.slice(-5)}`;
  const bobEmail = `atlas-bob-${stamp}@qa.local`;

  // ── Bob registers first (so alice can add him by email) and stays signed in. ──
  const bobCtx = await browser.newContext();
  const bob = await bobCtx.newPage();
  await bob.goto('/register');
  await bob.getByLabel('Email').fill(bobEmail);
  await bob.getByLabel('Display name').fill('Bob Assignee');
  await bob.getByLabel('Password').fill(PASSWORD);
  await bob.getByRole('button', { name: 'Create account' }).click();
  await expect(bob).toHaveURL(/\/projects$/, { timeout: 10_000 });

  // ── Alice registers, builds a project, adds bob, creates + assigns a ticket. ──
  const aliceCtx = await browser.newContext();
  const alice = await aliceCtx.newPage();
  await alice.goto('/register');
  await alice.getByLabel('Email').fill(`atlas-alice-${stamp}@qa.local`);
  await alice.getByLabel('Display name').fill('Alice Actor');
  await alice.getByLabel('Password').fill(PASSWORD);
  await alice.getByRole('button', { name: 'Create account' }).click();
  await expect(alice).toHaveURL(/\/projects$/, { timeout: 10_000 });

  await alice.getByRole('button', { name: 'New project' }).click();
  await alice.getByLabel('Name').fill(`Notify E2E ${stamp}`);
  await alice.getByLabel('Key').fill(key);
  await alice.getByRole('button', { name: 'Create project' }).click();
  await expect(alice).toHaveURL(new RegExp(`/projects/${key}$`), { timeout: 10_000 });

  // Add bob as a member.
  await alice.getByRole('link', { name: 'Members' }).click();
  await alice.getByLabel('Email').fill(bobEmail);
  await alice.getByRole('button', { name: 'Add member' }).click();
  await expect(alice.getByText(bobEmail)).toBeVisible({ timeout: 10_000 });

  // Create a ticket and open it.
  await alice.getByRole('link', { name: 'List' }).click();
  await alice.getByRole('button', { name: 'New ticket' }).click();
  await alice.getByLabel('Title').fill('Please take this');
  await alice.getByRole('button', { name: 'Create' }).click();
  await expect(alice.getByText(`${key}-1 created`)).toBeVisible({ timeout: 10_000 });
  await alice.getByRole('table').getByText(`${key}-1`).click();
  await expect(alice).toHaveURL(new RegExp(`/projects/${key}/tickets/${key}-1$`));

  // Assign bob via the sidebar picker.
  await alice.getByTestId('assignee-picker').click();
  await alice.getByRole('option', { name: /Bob Assignee/ }).click();

  // ── Bob: the bell badge + row appear, and clicking deep-links to the ticket. ──
  await bob.goto(`/projects/${key}`);
  // Poll-driven; open the bell and wait for the row to land (≤ poll interval).
  await expect(async () => {
    await bob.getByRole('button', { name: 'Notifications' }).click();
    await expect(bob.getByTestId('notification-row').first()).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 40_000 });

  await bob.getByTestId('notification-row').first().click();
  await expect(bob).toHaveURL(new RegExp(`/projects/${key}/tickets/${key}-1$`));

  await aliceCtx.close();
  await bobCtx.close();
});
