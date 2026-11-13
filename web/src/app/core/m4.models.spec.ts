import {
  classOf, classTone, criticityOf, currentQuarter, daysUntil, dueLabel, isUsable, latestBySite, listOf, lotsToQuarantine, matrixGrid,
  previewVerdict, riskScore, toCalibrationBody, toCompliance, toEvaluation, toGrid, toGridBody, toImpact, toInstrument, toM4Kpis, toMatrix,
  toRegulation, toRisk, toRiskBody, toRiskScale, toRiskScaleBody, toScorecard, validateGrid, validateScale, weightedScore,
} from './m4.models';

const SCALE_RAW = {
  id: 's', code: 'STD', label: 'Std',
  dimensions: [{ code: 'G', label: 'Gravité', levels: [1, 2, 3, 4].map((v) => ({ value: v, label: `g${v}` })) },
    { code: 'P', libelle: 'Probabilité', niveaux: [1, 2, 3, 4].map((v) => ({ valeur: v, libelle: `p${v}` })) }],
  formula: 'G * P',
  seuils: [{ code: 'critique', min: 12, max: 16, couleur: '#f00' }, { level: 'faible', label: 'Faible', min: 1, max: 3, color: '#0f0' },
    { level: 'moyen', min: 4, max: 6, color: '#ff0' }, { level: 'eleve', min: 8, max: 9, color: '#f80' }],
};

describe('Modèles M4 — risques', () => {
  const scale = toRiskScale(SCALE_RAW);

  it('lit une échelle paramétrable (variantes FR/EN, formule texte, seuils triés)', () => {
    expect(scale.dimensions.map((d) => d.label)).toEqual(['Gravité', 'Probabilité']);
    expect(scale.dimensions[1].levels[3]).toEqual({ value: 4, label: 'p4' });
    expect(scale.formula).toEqual(['G', 'P']);
    expect(scale.thresholds.map((t) => t.level)).toEqual(['faible', 'moyen', 'eleve', 'critique']);
    expect(scale.thresholds[3].label).toBe('Critique');
    expect(toRiskScale({ dimensions: [{ code: 'A' }], formula: { dimensions: ['A'] } }).formula).toEqual(['A']);
    expect(toRiskScale({ dimensions: [{ code: 'A' }, { code: 'B' }] }).formula).toEqual(['A', 'B']);
    expect(toRiskScaleBody(scale)['formula']).toEqual(['G', 'P']);
  });

  it('cote par produit et classe par seuils', () => {
    expect(riskScore(scale, { G: 4, P: 3 })).toBe(12);
    expect(riskScore(scale, { G: 4 })).toBeNull();
    expect(criticityOf(scale, 12)?.level).toBe('critique');
    expect(criticityOf(scale, 7)?.level).toBe('moyen');
    expect(criticityOf(scale, null)).toBeNull();
  });

  it('valide une échelle', () => {
    expect(validateScale(scale)).toEqual([]);
    const bad = { ...scale, formula: ['X'], thresholds: [{ level: 'a', label: 'A', min: 1, max: 5, color: '' }, { level: 'b', label: 'B', min: 4, max: null, color: '' }],
      dimensions: [{ code: '', label: '', levels: [] }] };
    expect(validateScale(bad).length).toBe(4);
    expect(validateScale({ ...scale, dimensions: [], formula: [], thresholds: [] }).length).toBe(3);
  });

  it('lit un risque (fiche enveloppée ou à plat) et produit le corps POST', () => {
    const r = toRisk({ risk: { id: 1, code: 'R1', type: 'sst', siteId: 's1', danger: 'Chute', uniteTravail: 'Quai', cotationBrute: { G: 4, P: 3, score: 12, criticite: 'critique' },
      residual: { values: { G: 2, P: 2 }, total: 4 }, mesures: 'Consignes', actionId: 'a1' }, history: [{ date: 'd', by: 'X', brute: { scores: { G: 1 } } }] });
    expect(r.reference).toBe('R1');
    expect(r.site).toEqual({ id: 's1' });
    expect(r.gross).toEqual({ values: { G: 4, P: 3 }, score: 12, level: 'critique' });
    expect(r.residual?.score).toBe(4);
    expect(r.actions).toEqual([{ id: 'a1', reference: null, title: null, status: null }]);
    expect(r.history[0].gross?.values).toEqual({ G: 1 });
    expect(toRisk({ id: 2, actions: [{ id: 'x', ref: 'ACT-1', titre: 't' }] }).actions[0].reference).toBe('ACT-1');
    const body = toRiskBody({ type: 'sst', siteId: 's1', unit: '', hazard: 'H', situation: '', measures: '', gross: { G: 2, P: null }, residual: {}, reviewDate: null });
    expect(body).toEqual({ type: 'sst', siteId: 's1', workUnit: null, hazard: 'H', situation: null, existingMeasures: null, gross: { values: { G: 2 } }, residual: null, reviewDate: null });
  });

  it('construit la grille complète de la matrice, cases vides colorées par l’échelle', () => {
    const m = toMatrix({ xDimension: 'P', yDimension: 'G', cells: [{ x: 3, y: 4, count: 1, risks: [{ id: 'r', hazard: 'Chute' }] }] });
    const g = matrixGrid(m, scale);
    expect(g.length).toBe(4);
    expect(g[0][0].y).toBe(4);
    expect(g[0][2]).toEqual(jasmine.objectContaining({ x: 3, y: 4, count: 1, level: 'critique', color: '#f00' }));
    expect(g[3][0]).toEqual(jasmine.objectContaining({ x: 1, y: 1, count: 0, level: 'faible' }));
    const bare = matrixGrid(toMatrix({ x: { dimension: 'P', levels: [1, 2] }, y: { code: 'G', levels: [{ value: 1 }] }, cells: [] }), null);
    expect(bare.length).toBe(1);
    expect(bare[0].length).toBe(2);
    expect(matrixGrid(toMatrix([{ x: 1, y: 2, count: 3 }] as unknown as Record<string, unknown>), null)[0][0].count).toBe(3);
  });
});

describe('Modèles M4 — veille, métrologie, évaluations', () => {
  it('lit un texte réglementaire, ses exigences et évaluations', () => {
    const r = toRegulation({ regulation: { id: 'r', reference: 'Loi 65-99', titre: 'Code', domaine: 'SST', juridiction: 'Maroc', status: 'en_vigueur' },
      requirements: [{ id: 'q', texte: 'Faire', sites: [{ site: { id: 's1' }, applicable: true }] }],
      evaluations: [{ id: 'e1', site: { id: 's1', name: 'K' }, result: 'conforme', at: '2026-01-01' }, { id: 'e2', siteId: 's1', resultat: 'non_conforme', createdAt: '2026-06-01', action: { id: 'a', reference: 'ACT-1' } }] });
    expect(r.title).toBe('Code');
    expect(r.requirements[0].applicability[0]).toEqual({ siteId: 's1', applicable: true, justification: null });
    expect(r.evaluations[1].actions[0].reference).toBe('ACT-1');
    expect(latestBySite(r.evaluations).get('s1')?.result).toBe('non_conforme');
  });

  it('lit le taux de conformité par site (fraction ou pourcentage)', () => {
    const c = toCompliance({ sites: [{ site: { id: 's1', name: 'K' }, taux: 0.5, conforme: 1, non_conforme: 1 }] });
    expect(c[0]).toEqual({ siteId: 's1', siteName: 'K', rate: 50, counts: { conforme: 1, non_conforme: 1, partiel: 0, na: 0 }, total: 2 });
    expect(toCompliance([{ siteId: 's2', rate: 75, counts: { conforme: 3 }, total: 4 }])[0].rate).toBe(75);
  });

  it('lit un instrument et ses étalonnages, calcule le verdict prévisionnel', () => {
    const i = toInstrument({ instrument: { id: 'i1', code: 'PAC', label: 'Pied', siteId: 's1', mpe: '0.02', status: 'bloque', nextDueDate: '2027-01-01' },
      calibrations: [{ id: 'c1', date: '2026-01-01', points: [{ nominal: 10, measured: 10.01 }], result: 'conforme', certificate: { id: 'att' } },
        { id: 'c2', date: '2026-06-01', points: [{ nominal: 10, mesure: 10.05, ecart: 0.05 }], verdict: 'non_conforme' }] });
    expect(i.designation).toBe('Pied');
    expect(i.emt).toBe(0.02);
    expect(i.qr).toBe('essor://instrument/i1');
    expect(i.calibrations.map((c) => c.id)).toEqual(['c2', 'c1']);
    expect(i.calibrations[1].points[0].ecart).toBeCloseTo(0.01, 6);
    expect(i.calibrations[1].certificate).toBe('att');
    expect(previewVerdict([{ nominal: 10, mesure: 10.02 }], 0.02)).toBe('conforme');
    expect(previewVerdict([{ nominal: 10, mesure: 10.03 }, { nominal: null, mesure: 1 }], 0.02)).toBe('non_conforme');
    expect(previewVerdict([], 0.02)).toBeNull();
    expect(toCalibrationBody({ date: 'd', provider: 'L', internal: true, certificateNumber: '', comment: null, points: [{ nominal: 1, mesure: 1.1 }, { nominal: null, mesure: null }] }))
      .toEqual({ calibratedOn: 'd', provider: null, internal: true, certificateNumber: null, comment: null, points: [{ nominal: 1, mesure: 1.1 }] });
  });

  it('échéances : J-15, échu, utilisable dans un contrôle', () => {
    const today = new Date(2026, 10, 13);
    expect(daysUntil('2026-11-20', today)).toBe(7);
    expect(daysUntil(null, today)).toBeNull();
    expect(dueLabel('2026-11-20', today)).toEqual({ text: 'Sous 7 j', tone: 'warn' });
    expect(dueLabel('2026-11-10', today).tone).toBe('crit');
    expect(dueLabel('2027-11-10', today).tone).toBe('ok');
    expect(dueLabel(null).text).toBe('—');
    const base = toInstrument({ id: 'i', status: 'en_service', nextDue: '2026-12-31' });
    expect(isUsable(base, today)).toBeTrue();
    expect(isUsable({ ...base, nextDue: '2026-11-01' }, today)).toBeFalse();
    expect(isUsable({ ...base, status: 'bloque' }, today)).toBeFalse();
  });

  it('effet instrument : contrôles, lots (y compris ceux des contrôles) et lots à mettre en quarantaine', () => {
    const im = toImpact({ since: '2026-03-10', inspections: [{ id: 'x', lot: { id: 'l2', numero: 'L2', qualityStatus: 'LIBERE' }, point: 'RECEPTION', createdAt: 't' }],
      lots: [{ id: 'l1', number: 'L1', article: { code: 'A', label: 'Axe' }, status: 'QUARANTAINE', quantite: 3, unite: 'pcs' }] });
    expect(im.inspections[0]).toEqual({ id: 'x', lotId: 'l2', lotNumero: 'L2', point: 'RECEPTION', at: 't', characteristic: null });
    expect(im.lots.map((l) => l.numero)).toEqual(['L1', 'L2']);
    expect(im.lots[0].article).toBe('A — Axe');
    expect(lotsToQuarantine(im).map((l) => l.id)).toEqual(['l2']);
  });

  it('grille, note pondérée, classe et scorecard', () => {
    const g = toGrid({ id: 'g', code: 'G', label: 'L', type: 'fournisseur', scale: { max: 5 }, criteres: [{ code: 'Q', libelle: 'Qualité', poids: 3 }, { code: 'D', label: 'Délai', weight: 1 }],
      classes: [{ code: 'C', min: 0 }, { class: 'A', label: 'Agréé', min: 80 }, { class: 'B', min: 60 }] });
    expect(g.thresholds.map((t) => t.cls)).toEqual(['A', 'B', 'C']);
    expect(g.improvementClasses).toEqual(['C']);
    expect(weightedScore(g, { Q: 5, D: 1 })).toBe(80);
    expect(weightedScore(g, { Q: 5 })).toBeNull();
    expect(classOf(g, 80)?.cls).toBe('A');
    expect(classOf(g, 10)?.cls).toBe('C');
    expect(classOf(g, null)).toBeNull();
    expect(classTone('C', g)).toBe('crit');
    expect(classTone('A', g)).toBe('ok');
    expect(classTone('B', g)).toBe('warn');
    expect(classTone(null)).toBe('info');
    expect(toGridBody(g)['thresholds']).toEqual([{ class: 'A', label: 'Agréé', min: 80 }, { class: 'B', label: 'B', min: 60 }, { class: 'C', label: 'C', min: 0 }]);
    expect(validateGrid(g)).toEqual([]);
    expect(validateGrid({ ...g, code: '', criteria: [], thresholds: [], scaleMax: 0 }).length).toBe(4);
    const e = toEvaluation({ id: 'e', partner: { id: 'p' }, period: '2026-T4', notes: [{ critere: 'Q', note: 4 }], indicateurs: { nbNc: 2, pctLotsRefuses: 0.125, retards: 3 },
      noteGlobale: 21.8, classe: 'C', planActionId: 'a' });
    expect(e.partnerId).toBe('p');
    expect(e.scores).toEqual({ Q: 4 });
    expect(e.indicators).toEqual({ ncCount: 2, refusedRate: 12.5, derogations: null, complaints: null, lateDeliveries: 3 });
    expect(e.status).toBe('terminee');
    expect(e.actions[0].id).toBe('a');
    expect(toEvaluation({ id: 'x', period: 'T' }).status).toBe('a_faire');
    const sc = toScorecard({ evaluations: [{ id: '2', period: '2026-T3' }, { id: '1', period: '2026-T2' }] });
    expect(sc.evaluations.map((x) => x.id)).toEqual(['1', '2']);
    expect(toScorecard([{ id: 'z', period: 'p' }] as unknown as Record<string, unknown>).evaluations.length).toBe(1);
  });

  it('divers : listes paginées, trimestre courant, KPI du tableau de bord', () => {
    expect(listOf({ items: [1] })).toEqual([1] as unknown as Record<string, unknown>[]);
    expect(listOf({ content: [2] }).length).toBe(1);
    expect(listOf(null)).toEqual([]);
    expect(currentQuarter(new Date(2026, 10, 13))).toBe('2026-T4');
    expect(toM4Kpis({ regulatoryCompliance: 0.875, calibrationsDue15: 2 })).toEqual({ regulatoryCompliance: 87.5, calibrationsDue15: 2, calibrationsOverdue: null });
    expect(toM4Kpis(null).regulatoryCompliance).toBeNull();
  });
});
