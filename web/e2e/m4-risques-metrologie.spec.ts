import { expect, test } from '@playwright/test';
import { MockApiM4 } from './mock-api-m4';

const SHOTS = process.env['E2E_SCREENSHOTS'];

test('Étalonnage non conforme → instrument bloqué → effet instrument → quarantaine des lots', async ({ page }) => {
  const api = new MockApiM4();
  await api.install(page);
  await page.goto('/mes-actions');
  await page.getByRole('link', { name: 'Équipements' }).click();
  await page.getByRole('link', { name: 'PAC-0142' }).click();
  await expect(page.getByRole('heading', { name: 'Pied à coulisse 150 mm' })).toBeVisible();
  await expect(page.getByTestId('instrument-status')).toHaveText('En service');

  const form = page.getByRole('form', { name: "Saisie d'étalonnage" });
  await form.getByLabel('Nominal 1').fill('10');
  await form.getByLabel('Mesuré 1').fill('10.01');
  await form.getByLabel('Nominal 2').fill('100');
  await form.getByLabel('Mesuré 2').fill('100.05');
  await form.getByLabel('N° de certificat').fill('LMM-2026-311');
  await expect(page.getByTestId('verdict-preview')).toContainText('Non conforme');
  await form.getByRole('button', { name: "Enregistrer l'étalonnage" }).click();

  await expect(page.getByTestId('instrument-status')).toHaveText('Bloqué');
  expect(api.calibrationCalls[0]).toMatchObject({ certificateNumber: 'LMM-2026-311', points: [{ nominal: 10, mesure: 10.01 }, { nominal: 100, mesure: 100.05 }] });
  await expect(page.getByRole('table', { name: "Historique d'étalonnage" })).toContainText('Non conforme');
  await expect(page.getByRole('table', { name: "Historique d'étalonnage" }).getByRole('link', { name: /^NC-2026-/ })).toBeVisible();
  await page.getByRole('link', { name: "Voir l'effet instrument →" }).click();

  await expect(page.getByTestId('impact-summary')).toContainText('2 contrôle(s) et 2 lot(s)');
  await expect(page.getByRole('table', { name: 'Lots concernés' })).toContainText('R-2611');
  await expect(page.getByRole('table', { name: 'Lots concernés' })).toContainText('PF-2611-B');
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/effet-instrument.png`, fullPage: true });
  await page.getByRole('button', { name: /Mettre ces lots en quarantaine/ }).click();
  await expect(page.getByRole('status')).toContainText('2 lot(s) mis en quarantaine');
  expect(api.quarantineCalls).toEqual([{ id: 'i1', reason: "Instrument PAC-0142 hors tolérance à l'étalonnage — réévaluation" }]);
  expect(api.webhookEvents.filter((e) => e['status'] === 'QUARANTAINE')).toHaveLength(2);
  await expect(page.getByRole('button', { name: /Mettre ces lots en quarantaine/ })).toBeDisabled();
  expect(api.records.some((r) => r.type === 'NC' && r.title.includes('PAC-0142'))).toBeTruthy();
});

test('Matrice des risques : case critique → risque dont l’action de maîtrise est une action M1', async ({ page }) => {
  const api = new MockApiM4();
  await api.install(page);
  await page.goto('/risques');
  const grid = page.getByRole('grid', { name: 'Matrice des risques' });
  await expect(grid.getByRole('gridcell')).toHaveCount(16);
  const critical = grid.getByRole('gridcell', { name: 'Gravité 4, Probabilité 3 : 1 risque(s), Critique' });
  await expect(critical).toHaveText('1');
  await critical.click();
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/matrice-risques.png`, fullPage: true });
  await page.getByRole('list', { name: 'Risques de la case' }).getByRole('link', { name: /Chute de hauteur/ }).click();
  await expect(page.getByRole('heading', { name: 'Chute de hauteur depuis le quai 4' })).toBeVisible();
  await expect(page.locator('dl')).toContainText('Gravité 4 × Probabilité 3 = 12 (Critique)');
  const action = page.getByRole('link', { name: 'ACT-2026-0001' });
  await expect(action).toBeVisible();
  await action.click();
  await expect(page).toHaveURL(/\/non-conformites\/action-1$/);
  expect(api.records.find((r) => r.id === 'action-1')?.type).toBe('ACTION');
  await page.goto('/risques');
  await expect(page.getByRole('link', { name: 'Document unique (DUER) PDF' })).toHaveAttribute('href', '/api/risks/duer.pdf');
});

test('Texte réglementaire évalué non conforme → plan d’action et taux de conformité', async ({ page }) => {
  const api = new MockApiM4();
  await api.install(page);
  await page.goto('/veille');
  await page.getByRole('link', { name: 'Loi 65-99 Code du travail, art. 281' }).click();
  const form = page.getByRole('form', { name: 'Évaluation de conformité' });
  await form.getByLabel('Résultat').selectOption('non_conforme');
  await expect(form).toContainText("Un plan d'action (fiche ACTION) sera créé automatiquement.");
  await form.getByLabel('Preuve').fill('Visite du 12/11 : garde-corps absent quai 4');
  await form.getByRole('button', { name: "Enregistrer l'évaluation" }).click();
  const table = page.getByRole('table', { name: 'Évaluations par site' });
  await expect(table).toContainText('Non conforme');
  await expect(table.getByRole('link', { name: /^ACT-2026-/ })).toBeVisible();
  expect(api.records.some((r) => r.type === 'ACTION' && r.title.includes('Loi 65-99'))).toBeTruthy();
  await page.getByRole('link', { name: '← Registre' }).click();
  await expect(page.getByLabel('Conformité réglementaire par site')).toContainText('50 %');
  await page.goto('/mes-actions');
  await expect(page.locator('.kpis')).toContainText('Conformité réglementaire');
  await expect(page.locator('.kpis')).toContainText('Étalonnages sous 15 jours');
});

test('Campagne fournisseur → évaluation trimestrielle (notes + indicateurs) → classe C et plan de progrès', async ({ page }) => {
  const api = new MockApiM4();
  await api.install(page);
  await page.goto('/partenaires');
  await page.getByRole('button', { name: "+ Campagne d'évaluation" }).click();
  const camp = page.getByRole('form', { name: "Campagne d'évaluation" });
  await camp.getByLabel('Période').fill('2026-T4');
  await camp.getByLabel('F-ACIERNORD — Aciers du Nord').check();
  await camp.getByRole('button', { name: 'Créer les évaluations' }).click();
  await expect(page.getByRole('status')).toContainText('1 évaluation(s) à faire créée(s) pour 2026-T4');
  await page.getByRole('link', { name: 'Aciers du Nord' }).click();

  await expect(page.getByRole('heading', { name: 'Scorecard Aciers du Nord' })).toBeVisible();
  await expect(page.getByRole('table', { name: 'Évaluations' })).toContainText('à faire');
  const f = page.getByRole('form', { name: 'Évaluation du partenaire' });
  await expect(f.getByLabel('Période évaluée')).toHaveValue('2026-T4');
  await f.getByLabel('Note Qualité produit').fill('3');
  await f.getByLabel('Note Respect des délais').fill('2');
  await f.getByLabel('Note Réactivité').fill('3');
  await expect(f).toContainText('53,3');
  await f.getByRole('button', { name: 'Calculer et enregistrer' }).click();
  await expect(page.getByTestId('partner-class')).toContainText('Classe C');
  const row = page.getByRole('table', { name: 'Évaluations' }).getByRole('row', { name: /2026-T4/ });
  await expect(row).toContainText('12,5 %');
  await expect(row.getByRole('link', { name: /^ACT-2026-/ })).toBeVisible();
  expect(api.evaluations.find((e) => e['period'] === '2026-T4')).toMatchObject({ status: 'terminee', class: 'C', scores: { QUAL: 3, DELAI: 2, REACT: 3 } });
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/scorecard-fournisseur.png`, fullPage: true });
});
