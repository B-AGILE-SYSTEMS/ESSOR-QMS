import { Route } from '@playwright/test';
import { SITES } from './mock-api';
import { MockApiM3b, PARTNERS } from './mock-api-m3b';

/**
 * API simulée M4 (docs/pilotage/cartes/M4-contrat.md) : risques, veille réglementaire, métrologie, évaluation des partenaires.
 * Étend l'API M1→M3b. Les formes suivent le contrat ; à remplacer par celles de docs/api-exemples dès leur publication.
 */
/** Instrument : forme réelle (docs/api-exemples/instrument.json du back M4). */
type Inst = { id: string; code: string; label: string; type: string; siteId: string; location: string; serialNumber: string; rangeMin: number; rangeMax: number;
  unit: string; resolution: number; emt: number; periodMonths: number; provider: string; status: string; nextDue: string; calibrations: Record<string, unknown>[] };

const PDF = Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n'
  + '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 120]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n');

export const SCALE = {
  id: 'rs1', code: 'STD-4x4', label: 'Échelle standard 4 × 4',
  dimensions: [
    { code: 'G', label: 'Gravité', levels: [{ value: 1, label: 'Faible' }, { value: 2, label: 'Sérieuse' }, { value: 3, label: 'Grave' }, { value: 4, label: 'Très grave' }] },
    { code: 'P', label: 'Probabilité', levels: [{ value: 1, label: 'Rare' }, { value: 2, label: 'Possible' }, { value: 3, label: 'Probable' }, { value: 4, label: 'Fréquente' }] },
  ],
  formula: ['G', 'P'],
  thresholds: [{ level: 'faible', label: 'Faible', min: 1, max: 3, color: '#7cc68d' }, { level: 'moyen', label: 'Moyen', min: 4, max: 6, color: '#f2d65c' },
    { level: 'eleve', label: 'Élevé', min: 8, max: 9, color: '#f2a65c' }, { level: 'critique', label: 'Critique', min: 12, max: 16, color: '#e8665c' }],
};
const level = (s: number) => SCALE.thresholds.find((t) => s >= t.min && s <= t.max)?.level ?? 'faible';

export const GRID = {
  id: 'g1', code: 'EVAL-FOUR', label: 'Évaluation trimestrielle fournisseur', partnerType: 'fournisseur', scale: { min: 0, max: 5 },
  criteria: [{ code: 'QUAL', label: 'Qualité produit', weight: 3 }, { code: 'DELAI', label: 'Respect des délais', weight: 2 }, { code: 'REACT', label: 'Réactivité', weight: 1 }],
  thresholds: [{ class: 'A', label: 'Agréé', min: 80 }, { class: 'B', label: 'Sous surveillance', min: 60 }, { class: 'C', label: 'Refusé', min: 0 }],
  improvementClasses: ['C'],
};

export class MockApiM4 extends MockApiM3b {
  risks: Record<string, unknown>[] = [];
  regulations: Record<string, unknown>[] = [];
  instruments: Inst[] = [];
  evaluations: Record<string, unknown>[] = [];
  calibrationCalls: Record<string, unknown>[] = [];
  quarantineCalls: { id: string; reason: string }[] = [];

  constructor() {
    super();
    const chute = this.create({ type: 'ACTION', title: 'Installer garde-corps et ligne de vie quai 4', siteId: 's1' }, 'EN_COURS', 'u1');
    this.addRisk('sst', 'Chute de hauteur depuis le quai 4', 'Quai de chargement', 4, 3, 2, 2, [chute]);
    this.addRisk('sst', 'Coupure lors de l’ébavurage', 'Atelier usinage', 2, 3, 2, 1, []);
    this.addRisk('environnement', 'Déversement d’huile de coupe', 'Atelier usinage', 3, 2, 3, 1, []);
    this.addRisk('qualite', 'Erreur de cote sur axe Ø 12', 'Usinage ligne 2', 3, 3, 3, 2, []);
    this.addRisk('opportunite', 'Récupération des copeaux acier', 'Atelier usinage', 1, 2, 1, 2, []);
    const reg = (id: string, reference: string, title: string, domain: string, jurisdiction: string, effectiveAt: string, summary: string) =>
      this.regulations.push({ id, reference, title, domain, jurisdiction, publicationDate: effectiveAt, applicationDate: effectiveAt, summary,
        sourceUrl: 'https://www.sgg.gov.ma', status: 'en_vigueur', requirements: [{ id: `${id}-q1`, text: summary,
          applicability: SITES.map((s) => ({ siteId: s.id, applicable: true, justification: null })) }], evaluations: [] });
    reg('rg1', 'Loi 65-99 Code du travail, art. 281', 'Hygiène et sécurité des salariés — obligations de l’employeur', 'SST', 'Maroc', '2004-06-08',
      'L’employeur tient les locaux propres et prend les mesures de prévention des risques (résumé maison).');
    reg('rg2', 'Loi 11-03', 'Protection et mise en valeur de l’environnement', 'environnement', 'Maroc', '2003-06-12', 'Prévenir les pollutions et nuisances (résumé maison).');
    reg('rg3', 'Loi 28-00', 'Gestion des déchets et leur élimination', 'environnement', 'Maroc', '2006-12-07', 'Tri, stockage et élimination des déchets industriels (résumé maison).');
    reg('rg4', 'Code du travail (FR), art. R4224-3', 'Lieux de travail — prévention des chutes', 'SST', 'France', '2008-05-01', 'Prévenir les chutes de hauteur (résumé maison).');
    (this.regulations[1]['evaluations'] as unknown[]).push({ id: 'ev0', siteId: 's1', result: 'conforme', evidence: 'Registre déchets 2026', comment: null,
      evaluatedAt: '2026-09-15T09:00:00Z', nextReviewDate: '2027-09-15', actionId: null });
    this.instruments.push(
      { id: 'i1', code: 'PAC-0142', label: 'Pied à coulisse 150 mm', type: 'pied_a_coulisse', siteId: 's1', location: 'Atelier usinage, ligne 2',
        serialNumber: 'MT-55821', rangeMin: 0, rangeMax: 150, unit: 'mm', resolution: 0.01, emt: 0.02, periodMonths: 12, provider: 'Labo Métrologie Maroc',
        status: 'en_service', nextDue: '2027-03-10', calibrations: [{ id: 'c0', instrumentId: 'i1', calibratedOn: '2026-03-10', provider: 'Labo Métrologie Maroc',
          internal: false, certificateNumber: 'LMM-2026-118', points: [{ nominal: 10, mesure: 10.01, ecart: 0.01, conforme: true }, { nominal: 100, mesure: 100.01, ecart: 0.01, conforme: true }],
          emt: 0.02, maxAbsError: 0.01, verdict: 'conforme', nextDue: '2027-03-10', ncId: null, ncReference: null, certificate: null, comment: null,
          createdBy: 'Salma Bennani', createdAt: '2026-03-10T10:00:00Z' }] },
      { id: 'i2', code: 'MIC-0007', label: 'Micromètre 0–25 mm', type: 'micrometre', siteId: 's1', location: 'Labo qualité', serialNumber: 'MI-221',
        rangeMin: 0, rangeMax: 25, unit: 'mm', resolution: 0.001, emt: 0.004, periodMonths: 12, provider: 'Interne', status: 'en_service', nextDue: '2026-11-20', calibrations: [] },
    );
    // Deux contrôles passés mesurés au PAC-0142 sur le lot fournisseur et le lot fini.
    for (const [k, lot] of this.lots.entries()) {
      this.inspections.push({ id: `ins-p${k + 1}`, lotId: lot.id, controlPlanId: 'cp1', planVersion: 2, point: k ? 'PRODUCTION_ENCOURS' : 'RECEPTION', status: 'TERMINEE',
        sampling: { mode: 'iso2859', lotSize: 500, nqa: 1.0, codeLetter: 'H', planLetter: 'F', sampleSize: 13, ac: 0, re: 1 },
        results: [{ ok: true, characteristic: 'DIAM', values: [12], instrument: 'PAC-0142', instrumentId: 'i1' } as { ok: boolean }],
        proposedDecision: 'LIBERE', decision: 'LIBERE', createdAt: `2026-11-0${k + 3}T09:00:00Z`, authorName: 'Salma Bennani' });
    }
    this.evaluations.push(
      { id: 'pe1', partnerId: 'p1', gridId: 'g1', period: '2026-T2', status: 'terminee', scores: { QUAL: 4, DELAI: 4, REACT: 4 },
        indicators: { ncCount: 0, refusedLotsRate: 0, derogationLots: 0, lateDeliveries: 0 }, globalScore: 82, class: 'A', actions: [] },
      { id: 'pe2', partnerId: 'p1', gridId: 'g1', period: '2026-T3', status: 'terminee', scores: { QUAL: 3, DELAI: 4, REACT: 3 },
        indicators: { ncCount: 1, refusedLotsRate: 5, derogationLots: 1, lateDeliveries: 1 }, globalScore: 66, class: 'B', actions: [] },
    );
  }

  private addRisk(type: string, hazard: string, unit: string, g: number, p: number, rg: number, rp: number, actions: { id: string; reference: string; title: string; status: string }[]): void {
    const n = this.risks.length + 1;
    this.risks.push({ id: `rk${n}`, reference: `RSK-${String(n).padStart(3, '0')}`, type, site: { id: 's1', code: 'KEN', name: 'Kénitra' }, workUnit: unit,
      hazard, situation: null, gross: { values: { G: g, P: p }, score: g * p, level: level(g * p) },
      residual: { values: { G: rg, P: rp }, score: rg * rp, level: level(rg * rp) }, existingMeasures: 'Consignes affichées', reviewDate: '2027-01-15',
      actions: actions.map((a) => ({ id: a.id, reference: a.reference, title: a.title, status: a.status })), requirements: [],
      history: [{ at: '2026-01-15T09:00:00Z', author: 'Salma Bennani', gross: { values: { G: g, P: p }, score: g * p, level: level(g * p) }, residual: null, comment: 'Évaluation initiale' }] });
  }

  /** Instrument à plat (liste, `instrument` de la fiche et de l'effet). */
  private instView(i: Inst) {
    const s = SITES.find((x) => x.id === i.siteId)!;
    const { siteId, calibrations, ...rest } = i;
    void siteId;
    void calibrations;
    const days = (Date.parse(i.nextDue) - Date.now()) / 86_400_000;
    const dueState = days < 0 ? 'echu' : days <= 15 ? 'bientot' : 'ok';
    return { ...rest, site: { name: s.name, id: s.id, code: s.code }, dueState, selectable: i.status === 'en_service' && days >= 0,
      qr: `essor://instrument/${i.id}`, createdAt: '2026-01-01T00:00:00Z', updatedAt: this.now() };
  }

  private matrix(type: string | null) {
    const rs = this.risks.filter((r) => !type || r['type'] === type);
    const cells: Record<string, unknown>[] = [];
    for (let g = 1; g <= 4; g++) for (let p = 1; p <= 4; p++) {
      const here = rs.filter((r) => { const v = (r['gross'] as { values: Record<string, number> }).values; return v['G'] === g && v['P'] === p; });
      cells.push({ x: p, y: g, count: here.length, level: level(g * p), color: SCALE.thresholds.find((t) => t.level === level(g * p))!.color, risks: here });
    }
    return { xDimension: 'P', yDimension: 'G', cells };
  }

  private impact(i: Inst) {
    const lastOk = i.calibrations.filter((c) => c['verdict'] === 'conforme').sort((a, b) => String(a['calibratedOn']).localeCompare(String(b['calibratedOn']))).at(-1);
    const since = String(lastOk?.['calibratedOn'] ?? '2000-01-01');
    const ins = this.inspections.filter((x) => (x.results as { instrumentId?: string; instrument?: string }[]).some((r) => r.instrumentId === i.id || r.instrument === i.code)
      && x.createdAt >= since);
    const lots = [...new Set(ins.map((x) => x.lotId))].map((id) => this.lots.find((l) => l.id === id)!);
    const art = (id: string) => (id === 'a1' ? { label: 'Tube acier Ø 12', code: 'MP-TUBE-12' } : { label: 'Axe usiné Ø 12,00', code: 'PF-AXE-12' });
    return { instrument: this.instView(i), since, lastConformingCalibration: lastOk ? { id: lastOk['id'], calibratedOn: lastOk['calibratedOn'] } : null,
      inspections: ins.map((x) => { const l = this.lots.find((y) => y.id === x.lotId)!; return { id: x.id, point: x.point, decision: x.decision, lotId: l.id,
        lotNumero: l.numero, articleCode: art(l.articleId).code, characteristics: ['DIAM'], measuredAt: x.createdAt }; }),
      lots: lots.map((l) => ({ id: l.id, numero: l.numero, article: art(l.articleId), supplier: null, qualityStatus: l.qualityStatus, quantity: l.quantite, unit: l.unite })) };
  }

  private scoreOf(scores: Record<string, number>, ind: { ncCount: number; refusedLotsRate: number; lateDeliveries: number }): number {
    const w = GRID.criteria.reduce((s, c) => s + c.weight, 0);
    const notes = GRID.criteria.reduce((s, c) => s + c.weight * Number(scores[c.code] ?? 0), 0) / w / 5 * 100;
    // Indicateurs automatiques : −5 par NC liée, −1 par % de lots refusés, −3 par retard.
    return Math.max(0, Math.round((notes - 5 * ind.ncCount - ind.refusedLotsRate - 3 * ind.lateDeliveries) * 10) / 10);
  }

  protected override async handle(route: Route): Promise<void> {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname;
    const m = req.method();
    const json = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    const body = () => JSON.parse(req.postData() ?? '{}');
    let mm: RegExpMatchArray | null;

    if (p === '/api/dashboard/summary') {
      const open = this.records.filter((r) => r.type === 'NC' && !['CLOTUREE', 'REJETEE'].includes(r.status));
      const evals = this.regulations.flatMap((r) => r['evaluations'] as { result: string }[]).filter((e) => e.result !== 'na');
      return json({ ncOpen: open.length, ncToQualify: open.filter((r) => r.status === 'DECLAREE').length, actionsOverdue: 1, ncCostMonth: 48300,
        regulatoryCompliance: evals.length ? Math.round((evals.filter((e) => e.result === 'conforme').length / evals.length) * 1000) / 10 : null,
        calibrationsDue15: 1, calibrationsOverdue: 0 });
    }
    if (p === '/api/risk-scales') { if (m === 'PUT') return json({ ...SCALE, ...body() }); return json([SCALE]); }
    if ((mm = p.match(/^\/api\/risk-scales\/([^/]+)$/))) return json({ ...SCALE, ...body(), id: mm[1] });
    if (p === '/api/risks/matrix') return json(this.matrix(url.searchParams.get('type')));
    if (p === '/api/risks/duer.pdf') {
      return route.fulfill({ status: 200, body: PDF, headers: { 'content-type': 'application/pdf', 'content-disposition': 'attachment; filename="duer.pdf"' } });
    }
    if (p === '/api/risks' && m === 'POST') {
      const b = body();
      const v = b.gross.values;
      this.addRisk(b.type, b.hazard, b.workUnit, v.G, v.P, v.G, v.P, []);
      return json(this.risks.at(-1), 201);
    }
    if (p === '/api/risks') return json(this.risks.filter((r) => !url.searchParams.get('type') || r['type'] === url.searchParams.get('type')));
    if ((mm = p.match(/^\/api\/risks\/([^/]+)$/))) return json(this.risks.find((r) => r['id'] === mm![1]) ?? {});

    if (p === '/api/regulations/compliance') {
      return json(SITES.map((s) => {
        const ev = this.regulations.map((r) => (r['evaluations'] as { siteId: string; result: string }[]).filter((e) => e.siteId === s.id).at(-1)).filter(Boolean) as { result: string }[];
        const c = (k: string) => ev.filter((e) => e.result === k).length;
        const base = ev.length - c('na');
        return { site: { id: s.id, code: s.code, name: s.name }, rate: base ? Math.round((c('conforme') / base) * 1000) / 10 : null,
          counts: { conforme: c('conforme'), partiel: c('partiel'), non_conforme: c('non_conforme'), na: c('na') }, total: this.regulations.length };
      }));
    }
    if (p === '/api/regulations') return json(this.regulations.filter((r) => !url.searchParams.get('domain') || r['domain'] === url.searchParams.get('domain')));
    if ((mm = p.match(/^\/api\/regulations\/([^/]+)(\/evaluations)?$/))) {
      const r = this.regulations.find((x) => x['id'] === mm![1]);
      if (!r) return json({ error: 'introuvable', message: 'Texte introuvable.' }, 404);
      if (mm[2] && m === 'POST') {
        const b = body();
        let actionId: string | null = null;
        let action: Record<string, unknown> | null = null;
        if (b.result === 'non_conforme') {
          const a = this.create({ type: 'ACTION', title: `Mise en conformité — ${r['reference']}`, siteId: b.siteId }, 'A_FAIRE', 'u1');
          actionId = a.id;
          action = { id: a.id, reference: a.reference, title: a.title, status: a.status };
          this.notify(`Plan d'action créé : ${r['reference']}`, a.id);
        }
        const e = { id: `ev${Date.now()}`, siteId: b.siteId, result: b.result, evidence: b.evidence || null, comment: b.comment || null,
          evaluatedAt: this.now(), nextReviewDate: '2027-11-12', actionId, action };
        (r['evaluations'] as unknown[]).push(e);
        return json(e, 201);
      }
      return json(r);
    }

    if (p === '/api/instruments') return json(this.instruments.map((i) => this.instView(i)));
    if ((mm = p.match(/^\/api\/instruments\/([^/]+)(\/calibrations|\/impact|\/impact\/quarantine)?$/))) {
      const i = this.instruments.find((x) => x.id === mm![1]);
      if (!i) return json({ error: 'introuvable', message: 'Instrument introuvable.' }, 404);
      if (mm[2] === '/impact') return json(this.impact(i));
      if (mm[2] === '/impact/quarantine' && m === 'POST') {
        const reason = body().reason;
        this.quarantineCalls.push({ id: i.id, reason });
        const out = this.impact(i).lots.filter((l) => !['QUARANTAINE', 'REFUSE', 'DETRUIT'].includes(l.qualityStatus)).map((l) => {
          const lot = this.lots.find((x) => x.id === l.id)!;
          const previousStatus = lot.qualityStatus;
          this.setLotStatus(lot, 'QUARANTAINE', reason);
          return { numero: lot.numero, previousStatus, id: lot.id };
        });
        const nc = this.records.find((r) => r.type === 'NC' && r.title.includes(i.code));
        return json({ quarantined: out, skipped: [], ncId: nc?.id ?? null });
      }
      if (mm[2] === '/calibrations' && m === 'POST') {
        const b = body();
        this.calibrationCalls.push(b);
        const points = (b.points as { nominal: number; mesure: number }[]).map((x) => ({ ...x, ecart: Math.round((x.mesure - x.nominal) * 10000) / 10000 }));
        const verdict = points.every((x) => Math.abs(x.ecart) <= i.emt) ? 'conforme' : 'non_conforme';
        let nc: { id: string; reference: string } | null = null;
        if (verdict === 'non_conforme') {
          i.status = 'bloque';
          nc = this.create({ type: 'NC', title: `Instrument ${i.code} hors tolérance à l'étalonnage`, siteId: i.siteId, severity: 'majeure' });
          this.notify(`Instrument ${i.code} bloqué : étalonnage non conforme`, nc.id);
        }
        const c = { id: `c${i.calibrations.length + 1}`, instrumentId: i.id, calibratedOn: b.calibratedOn, provider: b.provider, internal: !!b.internal,
          certificateNumber: b.certificateNumber, points: points.map((x) => ({ ...x, conforme: Math.abs(x.ecart) <= i.emt })), emt: i.emt,
          maxAbsError: Math.max(...points.map((x) => Math.abs(x.ecart))), verdict, nextDue: verdict === 'conforme' ? '2027-11-12' : i.nextDue,
          ncId: nc?.id ?? null, ncReference: nc?.reference ?? null, certificate: null, comment: b.comment, createdBy: 'Salma Bennani', createdAt: this.now() };
        i.calibrations.push(c);
        return json(c, 201);
      }
      return json({ instrument: this.instView(i), calibrations: [...i.calibrations].reverse() });
    }

    if (p === '/api/evaluation-grids') { if (m === 'POST') return json({ ...body(), id: 'g2' }, 201); return json([GRID]); }
    if ((mm = p.match(/^\/api\/evaluation-grids\/([^/]+)$/))) return json({ ...body(), id: mm[1] });
    if (p === '/api/evaluation-campaigns' && m === 'POST') {
      const b = body();
      const created = (b.partnerIds as string[]).map((pid, k) => ({ id: `pe-c${this.evaluations.length + k + 1}`, partnerId: pid, gridId: b.gridId, period: b.period,
        status: 'a_faire', scores: {}, indicators: {}, globalScore: null, class: null, actions: [] }));
      this.evaluations.push(...created);
      return json({ evaluations: created }, 201);
    }
    if ((mm = p.match(/^\/api\/partners\/([^/]+)\/(evaluations|scorecard)$/))) {
      const pid = mm[1];
      if (mm[2] === 'evaluations' && m === 'POST') {
        const b = body();
        const ind = { ncCount: 2, refusedLotsRate: 12.5, derogationLots: 1, lateDeliveries: 3 };
        const score = this.scoreOf(b.scores, ind);
        const cls = GRID.thresholds.find((t) => score >= t.min)!.class;
        const actions = cls === 'C' ? [this.create({ type: 'ACTION', title: `Plan de progrès ${PARTNERS.find((x) => x.id === pid)!.name} ${b.period}`, siteId: 's1' }, 'A_FAIRE', 'u1')]
          .map((a) => ({ id: a.id, reference: a.reference, title: a.title, status: a.status })) : [];
        const existing = this.evaluations.find((e) => e['id'] === b.evaluationId);
        const e = { ...(existing ?? { id: `pe${this.evaluations.length + 1}` }), partnerId: pid, gridId: b.gridId, period: b.period, status: 'terminee',
          scores: b.scores, indicators: ind, globalScore: score, class: cls, comment: b.comment, actions };
        if (existing) Object.assign(existing, e); else this.evaluations.push(e);
        return json(e, 201);
      }
      const list = this.evaluations.filter((e) => e['partnerId'] === pid);
      if (mm[2] === 'scorecard') return json({ partner: PARTNERS.find((x) => x.id === pid), evaluations: list });
      return json(list);
    }
    return super.handle(route);
  }
}
