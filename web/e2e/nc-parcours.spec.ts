import { expect, test } from '@playwright/test';
import { MockApi } from './mock-api';

const SHOTS = process.env['E2E_SCREENSHOTS'];

test('parcours NC complet : déclarer → qualifier → analyser → actions → vérifier → clôturer, avec notifications', async ({ page }) => {
  const api = new MockApi();
  await api.install(page);

  // Mes actions : données réelles de l'API.
  await page.goto('/mes-actions');
  await expect(page.getByRole('heading', { name: 'Mes actions' })).toBeVisible();
  await expect(page.locator('.kpi').filter({ hasText: 'NC ouvertes' }).locator('.v')).toHaveText('2');
  await expect(page.getByRole('link', { name: 'Fuite hydraulique chariot CH-07' })).toBeVisible();
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/1-mes-actions.png`, fullPage: true });

  // Déclarer depuis le bouton global.
  await page.getByRole('link', { name: '＋ Déclarer une NC' }).click();
  await page.getByLabel("Que s'est-il passé ? *").fill('Écart de cote Ø 12,05 mm sur lot 2611-B');
  await page.getByLabel('Site *').selectOption({ label: 'Kénitra' });
  await page.getByRole('button', { name: 'Majeure' }).click();
  await page.getByRole('button', { name: /Enregistrer la déclaration/ }).click();
  await expect(page.locator('.ph .ref')).toContainText('NC-2026-0003');
  await expect(page.locator('.flow li.cur')).toContainText('Déclarée');

  // Qualifier : désactivé tant que le responsable manque.
  const qualifier = page.locator('.nextbar').getByRole('button', { name: 'Qualifier' });
  await expect(qualifier).toBeDisabled();
  await expect(page.locator('.miss').first()).toContainText('Responsable');
  await page.getByRole('combobox', { name: 'Responsable' }).fill('Karim');
  await page.getByRole('option', { name: 'Karim Tazi' }).click();
  await page.getByRole('button', { name: 'Enregistrer', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('Fiche enregistrée');
  await qualifier.click();
  await expect(page.locator('.flow li.cur')).toContainText('Qualifiée');
  await page.locator('.nextbar').getByRole('button', { name: "Lancer l'analyse" }).click();

  // Analyse : 5 pourquoi + 6M dans data.
  await expect(page.getByRole('tab', { name: 'Analyse' })).toHaveAttribute('aria-selected', 'true');
  const valider = page.locator('.nextbar').getByRole('button', { name: "Valider l'analyse" });
  await expect(valider).toBeDisabled();
  await expect(page.locator('.miss')).toContainText('Cause racine');
  const whys = [
    'La cote Ø est hors tolérance de +0,05 mm.',
    "L'outil de finition était usé au-delà de sa durée de vie.",
    "Le compteur de pièces de l'outil n'a pas déclenché l'alerte.",
    'Le seuil a été remis à zéro lors du changement de série.',
    'La procédure PR-USI-07 ne prévoit pas la vérification du compteur outil.',
  ];
  for (const [i, w] of whys.entries()) await page.getByLabel(`Pourquoi ${i + 1}`).fill(w);
  await page.getByLabel('Cause racine').fill('Procédure de changement de série PR-USI-07 incomplète');
  await page.getByLabel('Méthode').fill('PR-USI-07 incomplète');
  await page.getByLabel('Machine').fill('Tour CN T-03');
  await page.getByLabel("Main-d'œuvre").fill('Nouvel opérateur poste B');
  await page.getByLabel('Mesure').fill('Contrôle 1/50 pièces');
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/2-fiche-nc-analyse.png`, fullPage: true });
  await page.getByRole('button', { name: "Enregistrer l'analyse" }).click();
  await expect(valider).toBeEnabled();
  expect(api.records.find((r) => r.reference === 'NC-2026-0003')!.data['causeRacine']).toBe('Procédure de changement de série PR-USI-07 incomplète');
  await valider.click();

  // Actions : ajout et suivi ; vérification bloquée tant qu'elles ne sont pas FAITE.
  await expect(page.locator('.flow li.cur')).toContainText('Actions');
  await expect(page.getByRole('tab', { name: /Actions/ })).toHaveAttribute('aria-selected', 'true');
  const verif = page.locator('.nextbar').getByRole('button', { name: 'Passer à la vérification' });
  await expect(verif).toBeDisabled();
  await page.getByLabel('Type').selectOption('curative');
  await page.getByLabel('Intitulé *').fill('Tri 100 % du lot 2611-B');
  await page.getByRole('combobox', { name: 'Responsable' }).fill('Salma');
  await page.getByRole('option', { name: 'Salma Bennani' }).click();
  await page.getByLabel('Échéance *').fill('2026-11-20');
  await page.getByRole('button', { name: "＋ Ajouter l'action" }).click();
  const row = page.locator('tbody tr').filter({ hasText: 'Tri 100 %' });
  await expect(row).toContainText('À faire');
  await row.getByRole('button', { name: 'Démarrer' }).click();
  await expect(row).toContainText('En cours');
  await row.getByRole('button', { name: 'Terminer' }).click();
  await expect(row).toContainText('Faite');
  await expect(verif).toBeEnabled();
  await verif.click();

  // Vérification puis clôture avec commentaire.
  await expect(page.locator('.flow li.cur')).toContainText('Vérification');
  await page.getByLabel('Commentaire (joint à la transition)').fill('0 écart sur 372 pièces contrôlées.');
  await page.locator('.nextbar').getByRole('button', { name: 'Clôturer la NC' }).click();
  await expect(page.locator('.flow li.cur')).toContainText('Clôturée');
  await expect(page.locator('.nextbar')).toHaveCount(0);

  // Notifications reçues par SSE : compteur, panneau, marquer lu.
  const bell = page.getByRole('button', { name: /^Notifications/ });
  await expect(bell.locator('.badge')).toHaveText(/^[1-9]\d*$/, { timeout: 10_000 });
  await bell.click();
  const panel = page.getByRole('region', { name: 'Notifications' });
  await expect(panel).toContainText('NC-2026-0003 : Clôturer la NC');
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/3-notifications.png` });
  await panel.getByRole('button', { name: /^Marquer lu/ }).first().click();
  await panel.getByRole('button', { name: 'Tout marquer lu' }).click();
  await expect(bell.locator('.badge')).toHaveCount(0);
  expect(api.notifications.every((n) => n.read)).toBeTruthy();

  // Liste : la NC clôturée y figure.
  await page.getByRole('link', { name: 'Non-conformités' }).first().click();
  await expect(page.locator('tbody tr').filter({ hasText: 'NC-2026-0003' })).toContainText('Clôturée');
});

test('paramétrage : workflow en diagramme, JSON refusé par le serveur, règles de notification', async ({ page }) => {
  await new MockApi().install(page);
  await page.goto('/parametrage/workflows');
  await expect(page.locator('.state').first()).toContainText('Déclarée');
  await page.getByLabel('Workflow JSON').fill('{"initial":"A","states":[],"transitions":[]}');
  await page.getByRole('button', { name: 'Enregistrer le workflow' }).click();
  await expect(page.getByRole('alert')).toContainText('au moins un état');
  await page.goto('/parametrage/notifications');
  const box = page.getByLabel('Fiche assignée — E-mail');
  await expect(box).not.toBeChecked();
  await box.check();
  await page.getByRole('button', { name: 'Enregistrer' }).click();
  await expect(page.getByRole('status')).toContainText('Règles enregistrées');
});
