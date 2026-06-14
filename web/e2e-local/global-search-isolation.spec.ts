// Runs in the nightly e2e-full workflow (.github/workflows/e2e-full.yml) and locally via npm run e2e:full
//
// Real-backend full-flow E2E (nightly_e2e_deferral): runs ONLY under
// --project=e2e-local (`npm run e2e:local`) against a compose stack at
// E2E_BASE_URL. Excluded from CI — this directory is not in the smoke testDir.
//
// Proves the T-028 global-search membership isolation end to end (SEC-2): two users
// each create a project containing a ticket that matches the SAME query. Alice's ⌘K
// global search must surface ONLY her ticket — bob's, in a project she is not a member
// of, is filtered out IN SQL (a project_members subquery), never reaches the client.
import { expect, test } from '@playwright/test';

const PASSWORD = 'SearchFlow123!';

test('global search shows only tickets in projects the caller is a member of', async ({
  browser,
}) => {
  const stamp = Date.now().toString().slice(-6);
  const aliceKey = `SA${stamp.slice(-5)}`;
  const bobKey = `SB${stamp.slice(-5)}`;

  // ── Bob: own project + a ticket matching "authentication". ──
  const bobCtx = await browser.newContext();
  const bob = await bobCtx.newPage();
  await bob.goto('/register');
  await bob.getByLabel('Email').fill(`atlas-bob-${stamp}@qa.local`);
  await bob.getByLabel('Display name').fill('Bob Outsider');
  await bob.getByLabel('Password').fill(PASSWORD);
  await bob.getByRole('button', { name: 'Create account' }).click();
  await expect(bob).toHaveURL(/\/projects$/, { timeout: 10_000 });

  await bob.getByRole('button', { name: 'New project' }).click();
  await bob.getByLabel('Name').fill(`Bob Search ${stamp}`);
  await bob.getByLabel('Key').fill(bobKey);
  await bob.getByRole('button', { name: 'Create project' }).click();
  await expect(bob).toHaveURL(new RegExp(`/projects/${bobKey}$`), { timeout: 10_000 });
  // Scope "List" to the sidebar nav — ProjectViewToggle (T-027) added a second "List" link.
  await bob
    .getByRole('navigation', { name: 'Project sections' })
    .getByRole('link', { name: 'List', exact: true })
    .click();
  await bob.getByRole('button', { name: 'New ticket' }).click();
  await bob.getByLabel('Title').fill('Authentication service for Bob');
  await bob.getByRole('button', { name: 'Create' }).click();
  await expect(bob.getByText(`${bobKey}-1 created`)).toBeVisible({ timeout: 10_000 });

  // ── Alice: own project + a ticket matching the SAME query. ──
  const aliceCtx = await browser.newContext();
  const alice = await aliceCtx.newPage();
  await alice.goto('/register');
  await alice.getByLabel('Email').fill(`atlas-alice-${stamp}@qa.local`);
  await alice.getByLabel('Display name').fill('Alice Member');
  await alice.getByLabel('Password').fill(PASSWORD);
  await alice.getByRole('button', { name: 'Create account' }).click();
  await expect(alice).toHaveURL(/\/projects$/, { timeout: 10_000 });

  await alice.getByRole('button', { name: 'New project' }).click();
  await alice.getByLabel('Name').fill(`Alice Search ${stamp}`);
  await alice.getByLabel('Key').fill(aliceKey);
  await alice.getByRole('button', { name: 'Create project' }).click();
  await expect(alice).toHaveURL(new RegExp(`/projects/${aliceKey}$`), { timeout: 10_000 });
  await alice
    .getByRole('navigation', { name: 'Project sections' })
    .getByRole('link', { name: 'List', exact: true })
    .click();
  await alice.getByRole('button', { name: 'New ticket' }).click();
  await alice.getByLabel('Title').fill('Authentication service for Alice');
  await alice.getByRole('button', { name: 'Create' }).click();
  await expect(alice.getByText(`${aliceKey}-1 created`)).toBeVisible({ timeout: 10_000 });

  // ── Alice's global ⌘K search for the shared term: her ticket only. ──
  await alice.getByTestId('global-search-trigger').click();
  await alice.getByTestId('global-search-input').fill('authentication');

  const results = alice.getByTestId('global-search-result');
  await expect(results.filter({ hasText: `${aliceKey}-1` })).toBeVisible({ timeout: 10_000 });
  // Bob's ticket (in a project Alice cannot see) must be absent — enforced in SQL.
  await expect(results.filter({ hasText: `${bobKey}-1` })).toHaveCount(0);

  // Selecting Alice's hit deep-links to its detail page.
  await results.filter({ hasText: `${aliceKey}-1` }).click();
  await expect(alice).toHaveURL(new RegExp(`/projects/${aliceKey}/tickets/${aliceKey}-1$`));

  await aliceCtx.close();
  await bobCtx.close();
});
