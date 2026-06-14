// Runs in the nightly e2e-full workflow (.github/workflows/e2e-full.yml) and locally via npm run e2e:full
//
// Real-backend full-flow E2E (nightly_e2e_deferral): runs ONLY under
// --project=e2e-local (`npm run e2e:local`) against a compose stack at E2E_BASE_URL
// that includes MinIO + the mc-init bucket bootstrap. Excluded from CI. Exercises the
// T-025 attachment flow end to end: init → presigned PUT (direct to MinIO) → finalize
// → list, plus the JPEG thumbnail worker for an image upload.
import { expect, test } from '@playwright/test';

const PASSWORD = 'Attachments123!';

// 1x1 transparent PNG — small but a real, decodable image so the thumbnail worker runs.
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64'
);

test('upload a file and an image to a ticket → list + thumbnail appear', async ({ page }) => {
  const stamp = Date.now().toString().slice(-5);
  const key = `A${stamp}`;

  await page.goto('/register');
  await page.getByLabel('Email').fill(`atlas-att-${Date.now()}@qa.local`);
  await page.getByLabel('Display name').fill('Attach QA');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/projects$/, { timeout: 10_000 });

  await page.getByRole('button', { name: 'New project' }).click();
  await page.getByLabel('Name').fill(`Attach E2E ${stamp}`);
  await page.getByLabel('Key').fill(key);
  await page.getByRole('button', { name: 'Create project' }).click();
  await expect(page).toHaveURL(new RegExp(`/projects/${key}$`), { timeout: 10_000 });

  await page.getByRole('link', { name: 'List' }).click();
  await page.getByRole('button', { name: 'New ticket' }).click();
  await page.getByLabel('Title').fill('Has attachments');
  await page.getByRole('button', { name: 'Create' }).click();
  await expect(page.getByText(`${key}-1 created`)).toBeVisible({ timeout: 10_000 });
  await page.getByRole('table').getByText(`${key}-1`).click();
  await expect(page).toHaveURL(new RegExp(`/projects/${key}/tickets/${key}-1$`));

  const section = page.getByTestId
    ? page.getByTestId('attachments-section')
    : page.locator('[data-testid=attachments-section]');
  await expect(section).toBeVisible();

  // Upload a plain text file → appears in the file list.
  await page
    .locator('[data-testid=attachment-input]')
    .setInputFiles({ name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('hello world') });
  await expect(page.getByText('notes.txt')).toBeVisible({ timeout: 15_000 });

  // Upload a real (tiny) PNG → the thumbnail worker runs; an <img> shows up in the grid.
  await page
    .locator('[data-testid=attachment-input]')
    .setInputFiles({ name: 'pixel.png', mimeType: 'image/png', buffer: PNG_1X1 });
  await expect(page.locator('[data-testid=attachments-section] img').first()).toBeVisible({
    timeout: 20_000,
  });
});
