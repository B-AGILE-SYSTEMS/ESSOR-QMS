/**
 * Garde-fou contre la dérive des mocks : les fichiers docs/api-exemples/*.json sont écrits par les tests d'intégration
 * du back à partir des réponses RÉELLES de l'API. Les fonctions de lecture du web doivent les comprendre.
 */
import article from '../../../../docs/api-exemples/article.json';
import audit from '../../../../docs/api-exemples/audit.json';
import document from '../../../../docs/api-exemples/document.json';
import inspection from '../../../../docs/api-exemples/inspection.json';
import lot from '../../../../docs/api-exemples/lot.json';
import partenaire from '../../../../docs/api-exemples/partenaire.json';
import plan from '../../../../docs/api-exemples/plan.json';
import rappel from '../../../../docs/api-exemples/rappel.json';
import { toQuestion } from './m3.models';
import { formatUnitQty, refLabel, toArticle, toInspection, toLot, toPartner, toPlan, toPlanBody, toRecall } from './m3b.models';

type Raw = Record<string, unknown>;
const raw = (x: unknown) => x as Raw;

describe('Lecture des exemples réels de l’API (docs/api-exemples)', () => {
  it('fiche lot {lot, history, inspections}', () => {
    const l = toLot(raw(lot));
    expect(l.id).toBe(lot.lot.id);
    expect(l.numero).toBe(lot.lot.numero);
    expect(l.qualityStatus).toBe(lot.lot.qualityStatus);
    expect(l.quantite).toBe(lot.lot.quantity);
    expect(l.unite).toBe(lot.lot.unit);
    expect(l.lotFournisseur).toBe(lot.lot.supplierLot);
    expect(refLabel(l.article)).toBe(`${lot.lot.article.code} — ${lot.lot.article.label}`);
    expect(refLabel(l.fournisseur)).toContain(lot.lot.supplier.name);
    expect(l.historique.length).toBe(lot.history.length);
    expect(l.historique.at(-1)!.status).toBe(lot.history.at(-1)!.to);
    expect(l.historique.every((h) => !!h.by)).toBeTrue();
  });

  it('contrôle : plan, lot, échantillonnage et résultats', () => {
    const i = toInspection(raw(inspection));
    expect(i.controlPlanId).toBe(inspection.controlPlan.id);
    expect(i.lotId).toBe(inspection.lot.id);
    expect(i.lotNumero).toBe(inspection.lot.numero);
    expect(i.sampleSize).toBe(inspection.sampleSize);
    expect(i.planVersion).toBe(inspection.controlPlan.version);
    expect(i.plan!.libelle).toBe(inspection.controlPlan.label);
    expect(i.results.length).toBe(inspection.results.length);
    expect(i.proposedDecision).toBe(inspection.proposedDecision as never);
  });

  it('rappel : lots, clients et quantités par unité', () => {
    const r = toRecall(raw(rappel));
    expect(r.source!.id).toBe(rappel.source.id);
    expect(r.lotsImpactes.map((l) => l.id)).toEqual(rappel.lotsImpactes.map((l) => l.id));
    expect(r.lotsImpactes.every((l) => typeof l.remaining === 'number' && !!l.unite)).toBeTrue();
    expect(r.clients.length).toBe(rappel.clients.length);
    expect(refLabel(r.clients[0].partner)).toContain(rappel.clients[0].partner.name);
    expect(r.quantites.expedie).toEqual(rappel.quantites.expedie);
    expect(r.quantites.enCours).toEqual(rappel.quantites.enCours);
    expect(formatUnitQty(r.quantites.enCours)).toMatch(/kg/);
  });

  it('plan : libellé, caractéristiques (instrument requis = nom) et aller-retour', () => {
    const p = toPlan(raw(plan));
    expect(p.libelle).toBe(plan.label);
    expect(p.characteristics[0].instrumentRequis).toBe(plan.characteristics[0].instrumentRequis);
    expect(p.characteristics[1].instrumentRequis).toBeNull();
    expect(p.sampling.nqa).toBe(Number(plan.sampling.nqa));
    expect(toPlanBody(p)['label']).toBe(plan.label);
  });

  it('article et partenaire', () => {
    const a = toArticle(raw(article));
    expect([a.designation, a.unite, a.famille]).toEqual([article.label, article.unit, article.family]);
    const pa = toPartner(raw(partenaire));
    expect([pa.nom, pa.type]).toEqual([partenaire.name, partenaire.type]);
  });

  it('audit et document (M3)', () => {
    const a = raw(audit);
    expect(a['id'] ?? (a['record'] as Raw)?.['id']).toBeTruthy();
    const questions = (a['questions'] ?? a['checklist'] ?? []) as unknown[];
    questions.forEach((q) => expect(toQuestion(q as never).question).toBeTruthy());
    const d = raw(document);
    expect(d['document'] ?? d['id']).toBeTruthy();
  });
});
