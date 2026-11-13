import { expect, test } from '@playwright/test';
import { MockApiM3 } from './mock-api-m3';

const SHOTS = process.env['E2E_SCREENSHOTS'];
// Résumés rédigés par nous (aucun texte de la norme recopié).
const ISO45001 = [
  'code;titre;texte;parent;auditable',
  '8;Fonctionnement;Planifier et maîtriser les opérations SST.;;non',
  '8.1;Planification et maîtrise opérationnelles;Établir les dispositions de maîtrise des opérations.;8;non',
  '8.1.2;Élimination des dangers et réduction des risques;Appliquer la hiérarchie des mesures de prévention.;8.1;oui',
  '8.1.3;Management du changement;Maîtriser les changements temporaires et permanents.;8.1;oui',
  "8.2;Préparation et réponse aux situations d'urgence;Préparer et tester la réponse aux urgences.;8;oui",
  '10;Amélioration;Améliorer en continu le système SST.;;non',
  '10.2;Événement indésirable, non-conformité et action corrective;Traiter les incidents et NC et en éliminer les causes.;10;oui',
];
const PDF = Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n'
  + '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 120]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n');

async function importIso45001(page: import('@playwright/test').Page, api: MockApiM3): Promise<void> {
  await page.goto('/parametrage/referentiels');
  await page.getByLabel('Code').fill('ISO45001');
  await page.getByLabel('Titre').fill('ISO 45001');
  await page.getByLabel('Version').fill('2018');
  // Fichier avec une erreur : la simulation la signale et l'import réel reste bloqué.
  const bad = [...ISO45001, '9.9.9;Ligne orpheline;Parent absent.;9.9;oui'].join('\n');
  await page.getByLabel('Fichier du référentiel').setInputFiles({ name: 'iso45001.csv', mimeType: 'text/csv', buffer: Buffer.from(bad) });
  await page.getByRole('button', { name: "Simuler l'import" }).click();
  await expect(page.locator('.rep')).toContainText('1 erreur(s)');
  await expect(page.locator('.rep td').first()).toHaveText('9');
  await expect(page.locator('.rep')).toContainText('Parent « 9.9 » inconnu.');
  await expect(page.getByRole('button', { name: 'Importer', exact: true })).toBeDisabled();
  await page.getByLabel('Fichier du référentiel').setInputFiles({ name: 'iso45001.csv', mimeType: 'text/csv', buffer: Buffer.from(ISO45001.join('\n')) });
  await page.getByRole('button', { name: "Simuler l'import" }).click();
  await expect(page.locator('.rep')).toContainText('Simulation : 7 exigence(s) créée(s), 0 mise(s) à jour, 0 erreur(s)');
  expect(api.standards.map((s) => s.code)).toEqual(['ISO9001']);
  await page.getByRole('button', { name: 'Importer', exact: true }).click();
  await expect(page.locator('.rep')).toContainText('Import : 7 exigence(s) créée(s)');
  expect(api.imports.map((i) => i.dryRun)).toEqual([true, true, false]);
  expect(api.lastImportFields).toEqual(['standardCode', 'standardTitle', 'standardVersion']);
}

test('Référentiels : import avec simulation et rapport d’erreurs, arbre, exigence et ses liens', async ({ page }) => {
  const api = new MockApiM3();
  await api.install(page);
  await importIso45001(page, api);

  await page.getByRole('tab', { name: 'ISO 45001:2018' }).click();
  const tree = page.getByRole('tree', { name: 'Exigences' });
  await expect(tree.getByRole('treeitem')).toHaveCount(7);
  await page.getByRole('button', { name: 'Replier 10' }).click();
  await expect(tree.getByRole('treeitem')).toHaveCount(6);
  await page.getByRole('button', { name: 'Déplier 10' }).click();
  await tree.getByRole('button', { name: /8\.1\.2/ }).click();
  await expect(page.locator('.det h3')).toHaveText('Élimination des dangers et réduction des risques');
  await expect(page.locator('.det')).toContainText('ISO45001 · §8.1.2');
  await expect(page.locator('.det')).toContainText('Exigence auditable');
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/m3-1-arbre-referentiel.png`, fullPage: true });
  await expect(page.getByRole('link', { name: "Télécharger le modèle d'import" })).toHaveAttribute('href', '/api/standards/import/template');
});

test('Audit : programme annuel, grille générée, exécution, NC créée, rapport PDF', async ({ page }) => {
  const api = new MockApiM3();
  await api.install(page);
  await importIso45001(page, api);

  await page.getByRole('link', { name: 'Audits & inspections' }).click();
  await expect(page.getByRole('heading', { name: "Programme d'audits 2026" })).toBeVisible();
  const nov = page.getByRole('region', { name: 'Novembre' });
  await expect(page.getByRole('region', { name: 'Mars' })).toContainText('Audit fournisseur transport');
  const mine = page.waitForRequest((r) => r.url().includes('/api/audits?') && r.url().includes('auditor=me'));
  await page.getByRole('button', { name: 'Mes audits' }).click();
  await mine;
  await expect(page.getByRole('region', { name: 'Mars' })).not.toContainText('Audit fournisseur transport');
  await nov.getByRole('link', { name: /Entrepôt Nord/ }).click();
  await expect(page).toHaveURL(/\/audits\/audit-1$/);
  await expect(page.locator('.flow li.cur')).toContainText('Planifié');

  await page.getByRole('tab', { name: "Grille d'audit" }).click();
  await page.getByLabel('ISO 45001:2018').check();
  await page.getByRole('button', { name: 'Générer la grille' }).click();
  await expect(page.getByRole('status')).toContainText('Grille générée : 4 question(s).');
  await page.getByLabel('Question 1', { exact: true }).fill('Les EPI de quai sont-ils portés et contrôlés ?');
  await page.getByLabel('Question 1', { exact: true }).blur();
  await expect.poll(() => api.questions[0].question).toBe('Les EPI de quai sont-ils portés et contrôlés ?');
  // Une question en trop est retirée de la grille (DELETE /questions/{qid}).
  await page.getByRole('button', { name: 'Supprimer la question 4' }).click();
  await expect(page.locator('.q')).toHaveCount(3);

  await page.locator('.nextbar').getByRole('button', { name: "Démarrer l'audit" }).click();
  await expect(page.locator('.flow li.cur')).toContainText('En cours');
  const answer = async (n: number, result: string, comment = '', close = false) => {
    const q = page.locator('.q').nth(n - 1);
    await q.getByRole('group', { name: `Résultat question ${n}` }).getByRole('button', { name: result }).click();
    if (comment) await q.getByLabel(`Commentaire question ${n}`).fill(comment);
    if (close) await q.getByLabel(`Clore la question ${n}`).check();
    const put = page.waitForRequest((r) => r.method() === 'PUT' && r.url().includes('/answers/'));
    await q.getByRole('button', { name: 'Enregistrer la réponse' }).click();
    expect((await put).headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
  };
  await answer(1, 'Conforme');
  await answer(2, 'Écart majeur', 'Pas de procédure de changement pour le nouveau convoyeur.', true);
  await expect(page.locator('eq-audit-grille').getByRole('status')).toContainText('NC NC-2026-0003 créée');
  await expect(page.locator('.q').nth(1).locator('.nc')).toContainText('NC-2026-0003');
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/m3-2-execution-audit.png`, fullPage: true });
  await expect(page.locator('.nextbar').getByRole('button', { name: 'Rédiger le rapport' })).toBeDisabled();
  // Écart non clos : la NC sera créée à la rédaction du rapport.
  await answer(3, 'Écart mineur', 'Exercice incendie non réalisé cette année.');
  await expect(page.locator('.q').nth(2).locator('.nc')).toHaveCount(0);
  await expect(page.locator('.sum')).toContainText('3/3');
  await expect(page.locator('.sum')).toContainText('§833 %');

  await page.locator('.nextbar').getByRole('button', { name: 'Rédiger le rapport' }).click();
  await expect(page.locator('.flow li.cur')).toContainText('Rapport');
  await expect(page.locator('table')).toContainText('Écart majeur');
  await expect(page.locator('.q').nth(2).locator('.nc')).toContainText('NC-2026-0004');
  const download = page.waitForEvent('download');
  await page.getByRole('link', { name: 'Télécharger le rapport PDF' }).click();
  expect((await download).suggestedFilename()).toBe('AUD-2026-0001.pdf');

  // L'écart est lié à l'exigence : visible depuis le référentiel.
  await page.goto('/parametrage/referentiels');
  await page.getByRole('tab', { name: 'ISO 45001:2018' }).click();
  await page.getByRole('tree').getByRole('button', { name: /8\.1\.3/ }).click();
  await expect(page.locator('.det')).toContainText('NC-2026-0003');
  await expect(page.locator('.det')).toContainText('AUD-2026-0001 Écart majeur');
});

test('Document : version, rédaction → vérification → approbation → diffusion, accusé de lecture', async ({ page }) => {
  const api = new MockApiM3();
  await api.install(page);
  await page.goto('/mes-actions');
  await page.getByRole('link', { name: 'Documents' }).click();
  const row = page.locator('tr.go').filter({ hasText: 'PR-QHSE-07' });
  await expect(row).toContainText('Procédure');
  await row.getByRole('link', { name: 'Consignation des énergies' }).click();
  await expect(page).toHaveURL(/\/documents\/document-1$/);
  await expect(page.getByRole('tab', { name: 'Versions' })).toHaveAttribute('aria-selected', 'true');

  await page.getByLabel('Fichier de la nouvelle version').setInputFiles({ name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('x') });
  await expect(page.getByRole('alert')).toContainText('seuls PDF, DOCX et XLSX');
  await page.getByLabel('Fichier de la nouvelle version').setInputFiles({ name: 'PR-QHSE-07-v1.pdf', mimeType: 'application/pdf', buffer: PDF });
  await page.getByLabel('Note de version').fill('Création');
  await page.getByRole('button', { name: 'Téléverser' }).click();
  await expect(page.locator('tbody tr').first()).toContainText('PR-QHSE-07-v1.pdf');

  for (const [btn, step] of [['Soumettre à vérification', 'Vérification'], ['Vérifier', 'Approbation'], ['Approuver et diffuser', 'Diffusé']]) {
    const tr = page.waitForRequest((r) => r.method() === 'POST' && r.url().includes('/api/documents/document-1/versions/1/transitions/'));
    await page.locator('tbody tr').first().getByRole('button', { name: btn }).click();
    await tr;
    await expect(page.locator('.flow li.cur')).toContainText(step);
    await expect(page.locator('tbody tr').first()).toContainText(step);
  }
  await page.getByRole('tab', { name: 'Diffusion & lecture' }).click();
  await expect(page.locator('.bar')).toContainText('0/3');
  await page.getByRole('button', { name: "J'ai lu et compris la version 1" }).click();
  await expect(page.locator('.bar')).toContainText('1/3');
  await expect(page.locator('.cols')).toContainText('✓ Salma Bennani');
  await expect(page.locator('.kv')).toContainText('QUALITY_MANAGER');
  await expect(page.getByRole('button', { name: /J'ai lu et compris/ })).toHaveCount(0);
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/m3-3-fiche-document-accuses.png`, fullPage: true });

  await page.goto('/documents');
  await expect(page.locator('tr.go').filter({ hasText: 'PR-QHSE-07' }).locator('td').nth(4)).toHaveText('1');
  await page.locator('tr.go').filter({ hasText: 'PR-QHSE-07' }).getByRole('link').click();
  await page.getByRole('tab', { name: 'Versions' }).click();
  await expect(page.locator('tbody tr').first()).toContainText('en vigueur');
  await expect(page.locator('iframe[title="Visionneuse PR-QHSE-07-v1.pdf"]')).toHaveAttribute('src', '/api/documents/document-1/versions/1/file');
});

test('Document : création par POST /api/documents (CreateDocument)', async ({ page }) => {
  const api = new MockApiM3();
  await api.install(page);
  await page.goto('/documents');
  await page.getByRole('button', { name: '＋ Nouveau document' }).click();
  const form = page.getByRole('form', { name: 'Nouveau document' });
  await form.getByLabel('Code').fill('IT-LOG-02');
  await form.getByLabel('Titre').fill('Chargement des remorques');
  await form.getByLabel('Rôles destinataires').fill('OPERATEUR, CHEF_EQUIPE');
  await form.getByRole('button', { name: 'Créer' }).click();
  await expect(page).toHaveURL(/\/documents\/document-2$/);
  expect(api.lastCreateDocument).toEqual(expect.objectContaining({
    code: 'IT-LOG-02', docType: 'procedure', recipients: { roles: ['OPERATEUR', 'CHEF_EQUIPE'], siteIds: [] },
    verifierRoles: ['QUALITY_MANAGER'], approverRoles: ['ADMIN'],
  }));
  await expect(page.getByRole('heading', { name: 'Chargement des remorques' })).toBeVisible();
});
