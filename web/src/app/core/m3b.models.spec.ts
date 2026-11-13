import {
  Characteristic, chartGeometry, formatUnitQty, toLotBody, emptyPlan, flaggedPoints, flattenTrace, fmt, lotsToBlock, measureDefects, measureOk, parseValues,
  proposeDecision, qualityTone, refLabel, samplingLabel, statusLabel, toInspection, toLot, toRecall, toSpc, toTrace, validatePlan,
} from './m3b.models';

const DIAM: Characteristic = { code: 'D', libelle: 'Diamètre', type: 'mesure', nominal: 12, tolMin: 11.97, tolMax: 12.03, unite: 'mm',
  instrumentRequis: 'pied à coulisse', criticite: 'majeure' };

describe('Modèles M3b', () => {
  it('normalise un lot et ses variantes de champs', () => {
    const l = toLot({ id: 1, number: 'L1', article: { id: 'a', code: 'A', designation: 'Axe' }, supplierLot: 'F9', status: 'LIBERE',
      history: [{ to: 'LIBERE', motif: 'ok', by: 'Salma', date: '2026-11-12' }] });
    expect(l.numero).toBe('L1');
    expect(l.lotFournisseur).toBe('F9');
    expect(l.historique[0]).toEqual({ from: null, status: 'LIBERE', reason: 'ok', by: 'Salma', at: '2026-11-12' });
    expect(refLabel(l.article)).toBe('A — Axe');
    expect(refLabel('X')).toBe('X');
    expect(refLabel(null)).toBe('—');
    expect(statusLabel('QUARANTAINE')).toBe('Quarantaine');
    expect(['LIBERE', 'DEROGATION', 'REFUSE', 'X'].map(qualityTone)).toEqual(['ok', 'warn', 'crit', 'info']);
  });

  it('aplatit l’arbre de généalogie, y compris les expéditions sans lot', () => {
    const t = toTrace({ lot: { id: 'r', numero: 'R' }, children: [
      { link: { kind: 'consumed_by', reference: 'OF-1' }, node: { lot: { id: 'f', numero: 'F' },
        children: [{ link: { kind: 'shipped_to', partner: { id: 'c', nom: 'Client' } } }] } }] });
    const rows = flattenTrace(t);
    expect(rows.map((r) => [r.depth, r.lot?.numero ?? r.link?.partner?.nom])).toEqual([[0, 'R'], [1, 'F'], [2, 'Client']]);
  });

  it('calcule les lots à bloquer lors d’un rappel', () => {
    const src = toLot({ id: 's', numero: 'S', qualityStatus: 'LIBERE' });
    const r = toRecall({ lotsImpactes: [{ id: 'a', qualityStatus: 'LIBERE' }, { id: 'b', qualityStatus: 'QUARANTAINE' }, { id: 's', qualityStatus: 'LIBERE' }],
      clients: [{ partner: { id: 'c' }, quantite: 3, references: ['BL'] }], quantites: { enStock: [{ unit: 'kg', qty: 5 }, { unit: 'pcs', qty: 2 }] } });
    expect(lotsToBlock(src, r).map((l) => l.id)).toEqual(['s', 'a']);
    expect(r.quantites).toEqual({ enStock: [{ unit: 'kg', qty: 5 }, { unit: 'pcs', qty: 2 }], enCours: [], expedie: [] });
    expect(formatUnitQty(r.quantites.enStock)).toBe('5 kg ; 2 pcs');
    expect(formatUnitQty([])).toBe('0');
  });

  it('juge les mesures et propose une décision', () => {
    expect(parseValues('12,01 ; 12.02\n12,5 x')).toEqual([12.01, 12.02, 12.5]);
    expect(measureOk(DIAM, [12, 12.03])).toBeTrue();
    expect(measureOk(DIAM, [])).toBeFalse();
    expect(measureDefects(DIAM, [11.9, 12, 12.1])).toBe(2);
    const ok = [{ characteristic: 'D', values: [12], ok: true }];
    const ko = [{ characteristic: 'D', values: [13], ok: false }];
    expect(proposeDecision([DIAM], ok, 0, 0, 1)).toBe('LIBERE');
    expect(proposeDecision([DIAM], ko, 1, 0, 1)).toBe('REFUSE');
    expect(proposeDecision([DIAM], ko, 2, 1, 3)).toBe('QUARANTAINE');
    expect(proposeDecision([DIAM], ko, 1, null, null)).toBe('QUARANTAINE');
    expect(proposeDecision([DIAM], ok, 0, null, null)).toBe('LIBERE');
    expect(proposeDecision([{ ...DIAM, criticite: 'critique' }], ko, 1, 5, 6)).toBe('REFUSE');
  });

  it('valide un plan de contrôle', () => {
    const p = emptyPlan();
    expect(validatePlan(p)).toContain('Le code du plan est obligatoire.');
    const bad = { ...p, code: 'X', point: 'PRODUCTION_ENCOURS' as const, sampling: { mode: 'fixe' as const, taille: 0 },
      characteristics: [{ ...DIAM, tolMin: 13 }] };
    expect(validatePlan(bad)).toEqual(['Caractéristique D : tolérance min supérieure à la max.', "Taille d'échantillon fixe obligatoire.",
      'En-cours : fréquence en pièces ou en minutes obligatoire.']);
    expect(validatePlan({ ...p, characteristics: [] })).toContain('Ajoutez au moins une caractéristique.');
    expect(samplingLabel({ mode: 'fixe', taille: 5 })).toBe('Fixe : 5 pièce(s)');
    expect(samplingLabel({ mode: 'iso2859', nqa: 2.5, niveau: 'II' })).toBe('ISO 2859-1 · niveau II · NQA 2,5');
    expect(samplingLabel(null)).toBe('—');
  });

  it('normalise un contrôle et une carte de contrôle', () => {
    const i = toInspection({ id: 'i', lot: { id: 'l', numero: 'L' }, sampling: { sampleSize: 13, planLetter: 'F', ac: 0, re: 1 } });
    expect([i.lotId, i.lotNumero, i.sampleSize, i.codeLetter, i.status]).toEqual(['l', 'L', 13, 'F', 'A_FAIRE']);
    const s = toSpc({ points: [{ moyenne: 1, etendue: 0.1 }, { mean: 5, range: 0.2 }], limits: { xbar: { lcl: 0, cl: 2, ucl: 4 } },
      alerts: [{ type: 'x', index: 1 }] });
    expect(s.points[0]).toEqual({ index: 1, mean: 1, range: 0.1, at: null });
    expect([...flaggedPoints(s.points, s.xbar, s.alerts, (p) => p.mean)]).toEqual([0, 1]);
    const g = chartGeometry([1, 5], [0, 4], 200, 100);
    expect(g.y(g.max)).toBe(g.pad.t);
    expect(g.x(1)).toBe(200 - g.pad.r);
    expect(chartGeometry([], []).max).toBeGreaterThan(chartGeometry([], []).min);
    expect(chartGeometry([3, 3], []).max).toBeGreaterThan(3);
    expect(fmt(null)).toBe('—');
    expect(fmt(1.5)).toBe('1,5');
  });
});

describe('Corps de réception d’un lot', () => {
  it('envoie les noms du serveur et le site', () => {
    expect(toLotBody({ articleId: 'a', numero: 'L', fournisseurId: 'f', lotFournisseur: 'FL', quantite: 3, unite: 'kg', dlc: null, siteId: 's' }))
      .toEqual({ articleId: 'a', numero: 'L', supplierId: 'f', supplierLot: 'FL', quantity: 3, unit: 'kg', dlc: null, siteId: 's' });
  });
});
