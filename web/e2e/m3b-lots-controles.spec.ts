import { expect, test } from '@playwright/test';
import { MockApiM3b } from './mock-api-m3b';

const SHOTS = process.env['E2E_SCREENSHOTS'];
const IN_TOL = '12,00 12,01 11,99 12,02 12,00 11,98 12,01 12,00 12,02 11,99 12,00 12,01 12,00';

test('Contrôle réception → libération du lot fournisseur', async ({ page }) => {
  const api = new MockApiM3b();
  await api.install(page);
  await page.goto('/mes-actions');
  await page.getByRole('link', { name: 'Lots & traçabilité' }).click();
  await page.getByRole('button', { name: '+ Réceptionner un lot' }).click();
  const form = page.getByRole('form', { name: "Réception d'un lot" });
  await form.getByLabel('Article').selectOption({ label: 'MP-TUBE-12 — Tube acier Ø 12' });
  await form.getByLabel('N° de lot', { exact: true }).fill('R-2650');
  await form.locator('select[name=four]').selectOption({ label: 'F-ACIERNORD — Aciers du Nord' });
  await form.getByLabel('N° lot fournisseur').fill('AN-78001');
  await form.getByLabel('Quantité').fill('500');
  await form.getByRole('button', { name: 'Enregistrer la réception' }).click();

  await expect(page.getByRole('heading', { name: 'Lot R-2650' })).toBeVisible();
  await expect(page.getByTestId('lot-status')).toHaveText('En attente');
  await page.getByLabel('Plan de contrôle').selectOption({ label: 'CP-REC-TUBE — Réception' });
  await page.getByRole('button', { name: 'Lancer un contrôle' }).click();

  await expect(page.getByRole('heading', { name: /Contrôle du lot R-2650/ })).toBeVisible();
  await expect(page.locator('.smp')).toContainText('n = 13');
  await page.getByLabel('Mesures Diamètre extérieur').fill('12,00 12,05');
  await page.getByLabel('Instrument Diamètre extérieur').fill('PAC-0142');
  await expect(page.locator('tbody tr').first()).toContainText('Non conforme');
  await page.getByLabel('Mesures Diamètre extérieur').fill(IN_TOL);
  await page.getByRole('checkbox').check();
  await expect(page.locator('tbody tr').first()).toContainText('Conforme');
  await page.getByRole('button', { name: 'Enregistrer les résultats' }).click();
  await expect(page.getByRole('form', { name: 'Décision' })).toContainText('Décision proposée : Libérer');
  expect(api.inspections[0].results).toEqual([
    { characteristic: 'DIAM', values: IN_TOL.split(' ').map((v) => Number(v.replace(',', '.'))), ok: true, instrument: 'PAC-0142' },
    { characteristic: 'CERT', values: ['present'], ok: true, instrument: null },
  ]);
  await page.getByRole('button', { name: 'Valider la décision' }).click();

  await expect(page.getByRole('heading', { name: 'Lot R-2650' })).toBeVisible();
  await expect(page.getByTestId('lot-status')).toHaveText('Libéré');
  await expect(page.locator('.hist')).toContainText('Libéré');
  expect(api.webhookEvents.at(-1)).toMatchObject({ event: 'lot.status_changed', numero: 'R-2650', status: 'LIBERE' });
  await page.getByRole('link', { name: 'Contrôles' }).first().click();
  await page.getByRole('tab', { name: 'Historique' }).click();
  await expect(page.locator('tbody')).toContainText('R-2650');
});

test('Rappel depuis le lot fournisseur : généalogie, clients, PDF et blocage du stock restant', async ({ page }) => {
  const api = new MockApiM3b();
  await api.install(page);
  await page.goto('/lots');
  await page.getByLabel('Recherche').fill('AN-77812');
  await page.getByRole('button', { name: 'Rechercher', exact: true }).click();
  await page.getByRole('link', { name: 'R-2611' }).click();

  const tree = page.getByRole('tree', { name: 'Généalogie du lot' });
  await expect(tree.getByRole('treeitem')).toHaveCount(4);
  await expect(tree).toContainText('PF-2611-B');
  await expect(tree).toContainText('Renault Tanger');
  await expect(tree).toContainText('Stellantis Kénitra');
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/m3b-1-fiche-lot-genealogie.png`, fullPage: true });

  const t0 = Date.now();
  await page.getByRole('button', { name: 'Simuler un rappel' }).click();
  const rap = page.getByRole('region', { name: /Simulation de rappel/ });
  await expect(rap.locator('.kpis')).toContainText('Clients touchés2');
  await expect(rap).toContainText('BL-8841');
  expect(Date.now() - t0).toBeLessThan(60_000);
  const download = page.waitForEvent('download');
  await rap.getByRole('link', { name: /dossier de rappel/ }).click();
  expect((await download).suggestedFilename()).toBe('rappel-R-2611.pdf');
  await expect(rap.getByRole('link', { name: /dossier de rappel/ })).toHaveAttribute('href', `/api/lots/${api.lots[0].id}/recall.pdf`);

  await rap.getByRole('button', { name: 'Bloquer le stock restant' }).click();
  await expect(rap.getByRole('alertdialog')).toContainText('Bloquer 2 lot(s)');
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/m3b-2-simulation-rappel.png`, fullPage: true });
  await rap.getByRole('button', { name: 'Confirmer le blocage' }).click();
  await expect(rap.getByRole('status')).toHaveText('2 lot(s) bloqué(s) en quarantaine.');
  expect(api.blockCalls).toEqual([{ id: api.lots[0].id, reason: 'Rappel fournisseur — lot R-2611' }]);
  expect(api.webhookEvents.map((e) => e['numero'])).toEqual(['R-2611', 'PF-2611-B']);
  await expect(page.getByTestId('lot-status')).toHaveText('Quarantaine');

  // Ascendant depuis le lot fini : on remonte au lot fournisseur.
  await tree.getByRole('link', { name: 'PF-2611-B' }).click();
  await page.getByRole('button', { name: /Ascendante/ }).click();
  await expect(tree).toContainText('R-2611');
  await expect(tree).toContainText('OF-2611');
});

test('Carte de contrôle en-cours : limites, Cp/Cpk et points hors limites', async ({ page }) => {
  const api = new MockApiM3b();
  await api.install(page);
  await page.goto('/controles');
  await page.getByRole('link', { name: 'Carte de contrôle' }).click();
  await expect(page.getByRole('img', { name: 'Carte des moyennes' })).toBeVisible();
  await expect(page.locator('.kpis')).toContainText('1,05');
  await expect(page.getByRole('img', { name: 'Carte des moyennes' }).locator('circle.bad')).toHaveCount(2);
  await expect(page.getByRole('img', { name: 'Carte des étendues' }).locator('circle.bad')).toHaveCount(1);
  await expect(page.getByRole('list', { name: 'Alertes' })).toContainText('7 points consécutifs');
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/m3b-3-carte-controle.png`, fullPage: true });
});

test('Paramétrage : éditeur de plan, import de données de base, connecteur webhook', async ({ page }) => {
  const api = new MockApiM3b();
  await api.install(page);
  await page.goto('/parametrage/plans-controle');
  await page.getByRole('button', { name: '+ Nouveau plan' }).click();
  const f = page.getByRole('form', { name: 'Plan de contrôle' });
  await f.getByRole('button', { name: 'Créer le plan' }).click();
  await expect(f.locator('ul.err')).toContainText('Le code du plan est obligatoire.');
  await f.getByLabel('Code', { exact: true }).fill('CP-EXP-AXE');
  await f.getByLabel('Point de flux').selectOption('EXPEDITION');
  await f.getByLabel('Code caractéristique 1').fill('ETIQ');
  await f.getByLabel('Libellé caractéristique 1').fill('Étiquette GS1 lisible');
  await f.getByRole('button', { name: 'Créer le plan' }).click();
  await expect(page.getByRole('status')).toContainText('Plan CP-EXP-AXE enregistré (version 1)');
  expect(api.plans.at(-1)).toMatchObject({ code: 'CP-EXP-AXE', point: 'EXPEDITION', sampling: { mode: 'iso2859', nqa: 1 } });

  await page.goto('/parametrage/donnees-base');
  await page.getByLabel('Fichier de données de base').setInputFiles({ name: 'articles.csv', mimeType: 'text/csv', buffer: Buffer.from('code;designation\nA;B\n') });
  await page.getByRole('button', { name: "Simuler l'import" }).click();
  await expect(page.locator('.rep')).toContainText('Simulation : 2 créé(s)');
  await page.getByRole('button', { name: 'Importer', exact: true }).click();
  await expect(page.locator('.rep')).toContainText('Import : 2 créé(s)');
  const hook = page.getByRole('form', { name: 'Connecteur webhook' });
  await expect(hook.getByLabel('URL')).toHaveValue('http://wms.local/hooks/qualite');
  await hook.getByLabel('Secret HMAC').fill('valeur-de-test');
  await hook.getByRole('button', { name: 'Enregistrer le connecteur' }).click();
  await expect(hook.getByRole('status')).toHaveText('Connecteur enregistré.');
  await expect(hook.getByRole('table', { name: 'Journal des envois' })).toBeVisible();
  expect(api.lastWebhookPut).toEqual({ url: 'http://wms.local/hooks/qualite', enabled: true, secret: 'valeur-de-test' });
});
