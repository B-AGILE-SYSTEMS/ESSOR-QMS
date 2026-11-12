import { expect, test } from '@playwright/test';
import { MockApi } from './mock-api';

const SHOTS = process.env['E2E_SCREENSHOTS'];
// PNG 1×1 valide (signature réelle contrôlée par l'API simulée, comme par le serveur).
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

test('Incidents & sécurité : liste, fiche générique, badge mobile, date terrain, pièces jointes', async ({ page }) => {
  const api = new MockApi();
  await api.install(page);

  await page.goto('/mes-actions');
  await page.getByRole('link', { name: 'Incidents & sécurité' }).click();
  await expect(page.getByRole('heading', { name: 'Incidents & sécurité' })).toBeVisible();
  const row = page.locator('tr.go').filter({ hasText: 'Chute de palette évitée allée B' });
  await expect(row).toContainText('INC-2026-0001');
  await expect(row.locator('.badge')).toHaveText('mobile');
  await expect(page.getByRole('link', { name: '＋ Déclarer', exact: true })).toHaveCount(0);

  await row.getByRole('link').click();
  await expect(page).toHaveURL(/\/incidents\/incident-1$/);
  await expect(page.locator('.ph .badge')).toHaveText('Déclaré depuis le mobile');
  await expect(page.locator('.flow li.cur')).toContainText('Déclaré');
  const dd = (label: string) => page.locator('dt', { hasText: new RegExp(`^${label}$`) }).locator('xpath=following-sibling::dd[1]');
  await expect(dd('Date terrain')).toHaveText('11/11/2026 23:15');
  await expect(dd('Catégorie')).toHaveText("Presqu'accident");
  await expect(dd('Lieu')).toHaveText('Kénitra — allée B');
  await expect(dd('Blessés')).toHaveText('Non');

  // Pièces jointes : ajout (multipart + Idempotency-Key), vignette, aperçu, téléchargement.
  await page.getByRole('tab', { name: 'Pièces jointes' }).click();
  await expect(page.getByText('Aucune pièce jointe.')).toBeVisible();
  const upload = page.waitForRequest((r) => r.url().endsWith('/attachments') && r.method() === 'POST');
  await page.getByLabel('Ajouter une pièce jointe').setInputFiles({ name: 'palette.png', mimeType: 'image/png', buffer: PNG });
  const req = await upload;
  expect(req.headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
  await expect(page.locator('.att').filter({ hasText: 'palette.png' })).toContainText('Salma Bennani');
  await expect(page.locator('.thumb img')).toHaveJSProperty('naturalWidth', 1);
  expect(api.attachments).toHaveLength(1);

  // Fichier refusé côté client (type non admis) : aucun envoi.
  await page.getByLabel('Ajouter une pièce jointe').setInputFiles({ name: 'note.txt', mimeType: 'text/plain', buffer: Buffer.from('x') });
  await expect(page.getByRole('alert')).toContainText('seuls JPEG, PNG et PDF');
  expect(api.attachments).toHaveLength(1);

  await page.getByRole('button', { name: 'Aperçu de palette.png' }).click();
  await expect(page.getByRole('dialog', { name: 'palette.png' })).toBeVisible();
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/incident-apercu.png` });
  const download = page.waitForEvent('download');
  await page.getByRole('dialog').getByRole('link', { name: 'Télécharger' }).click();
  expect((await download).suggestedFilename()).toBe('palette.png');
  await page.getByRole('button', { name: 'Fermer' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);

  // Workflow du type INCIDENT, sans code dédié.
  await page.locator('.nextbar').getByRole('button', { name: 'Analyser' }).click();
  await expect(page.locator('.flow li.cur')).toContainText('En analyse');
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/incident-fiche.png`, fullPage: true });
});

test("API simulée : l'Idempotency-Key rend la même réponse sans doublon", async ({ page }) => {
  const api = new MockApi();
  await api.install(page);
  await page.goto('/mes-actions');
  const status = await page.evaluate(async () => {
    const init = {
      method: 'POST', headers: { 'content-type': 'application/json', 'Idempotency-Key': '0f8e7d6c-5b4a-4321-8fed-cba987654321' },
      body: JSON.stringify({ type: 'INCIDENT', siteId: 's1', title: 'Flaque huile', description: '', severity: 'mineure',
        clientId: '0f8e7d6c-5b4a-4321-8fed-cba987654321', occurredAt: '2026-11-12T06:00:00Z', data: { categorie: 'environnement' } }),
    };
    const a = await (await fetch('/api/records', init)).json();
    const b = await (await fetch('/api/records', init)).json();
    return [a.id, b.id, a.reference];
  });
  expect(status[0]).toBe(status[1]);
  expect(status[2]).toBe('INC-2026-0002');
  expect(api.records.filter((r) => r.type === 'INCIDENT')).toHaveLength(2);
});
