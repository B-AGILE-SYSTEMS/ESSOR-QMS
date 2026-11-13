/**
 * Types et fonctions pures du contrat M4 (docs/pilotage/cartes/M4-contrat.md) : risques, veille réglementaire,
 * métrologie et évaluation des partenaires. Les lecteurs `to*` tolèrent les variantes de noms (français / anglais)
 * tant que les exemples réels (docs/api-exemples) ne sont pas publiés par le back.
 */
import { Ref } from './m3b.models';

type Raw = Record<string, unknown>;
const str = (v: unknown): string | null => (v === undefined || v === null || v === '' ? null : String(v));
const num = (v: unknown): number | null => (v === undefined || v === null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v));
const arr = (v: unknown): Raw[] => (Array.isArray(v) ? (v as Raw[]) : []);
const pick = (r: Raw, ...keys: string[]): unknown => keys.map((k) => r[k]).find((v) => v !== undefined && v !== null);
/** Liste brute ou page `{items}` / `{content}`. */
export function listOf(v: unknown): Raw[] {
  if (Array.isArray(v)) return v as Raw[];
  const o = (v ?? {}) as Raw;
  return arr(o['items'] ?? o['content'] ?? o['data']);
}
const ref = (v: unknown): Ref | null => {
  if (!v) return null;
  if (typeof v === 'string') return { id: v };
  return v as Ref;
};

/** Fiche ACTION M1 rattachée (forme courte). */
export interface ActionLink { id: string; reference: string | null; title: string | null; status: string | null }
export function toActionLink(raw: Raw | string): ActionLink {
  if (typeof raw === 'string') return { id: raw, reference: null, title: null, status: null };
  return { id: String(raw['id']), reference: str(pick(raw, 'reference', 'ref', 'number')), title: str(pick(raw, 'title', 'titre', 'label')),
    status: str(raw['status']) };
}
const actionsOf = (r: Raw): ActionLink[] => {
  const list = arr(pick(r, 'actions', 'actionRecords'));
  const single = pick(r, 'action', 'actionId', 'actionRecordId', 'improvementActionId', 'planActionId');
  const out = list.map(toActionLink);
  if (single && !out.length) out.push(toActionLink(single as Raw | string));
  return out;
};

/* ===== Risques ===== */

export type RiskType = 'sst' | 'environnement' | 'qualite' | 'opportunite';
export const RISK_TYPES: { code: RiskType; label: string }[] = [
  { code: 'sst', label: 'SST (DUER)' }, { code: 'environnement', label: 'Environnement' },
  { code: 'qualite', label: 'Qualité' }, { code: 'opportunite', label: 'Opportunité' },
];
export const riskTypeLabel = (t: string | null | undefined): string => RISK_TYPES.find((x) => x.code === t)?.label ?? t ?? '—';

export interface ScaleLevel { value: number; label: string }
export interface ScaleDimension { code: string; label: string; levels: ScaleLevel[] }
export interface Threshold { level: string; label: string; min: number; max: number | null; color: string }
export interface RiskScale {
  id: string | null; code: string; label: string; dimensions: ScaleDimension[];
  /** Codes des dimensions multipliées pour la cotation. */
  formula: string[]; thresholds: Threshold[];
}
export const CRITICITY_LABELS: Record<string, string> = { faible: 'Faible', moyen: 'Moyen', eleve: 'Élevé', critique: 'Critique' };

export function toRiskScale(raw: Raw): RiskScale {
  const dims = arr(raw['dimensions']).map((d) => ({
    code: String(d['code'] ?? ''), label: String(pick(d, 'label', 'libelle') ?? d['code'] ?? ''),
    levels: arr(pick(d, 'levels', 'niveaux')).map((l) => ({ value: Number(pick(l, 'value', 'valeur', 'level')), label: String(pick(l, 'label', 'libelle') ?? '') })),
  }));
  const f = raw['formula'] ?? raw['formule'];
  const formula = Array.isArray(f) ? f.map(String)
    : f && typeof f === 'object' ? (arr((f as Raw)['dimensions']).length ? ((f as Raw)['dimensions'] as unknown[]).map(String) : [])
      : typeof f === 'string' ? f.split(/[^A-Za-z0-9_]+/).filter((c) => dims.some((d) => d.code === c)) : [];
  return {
    id: str(raw['id']), code: String(raw['code'] ?? ''), label: String(pick(raw, 'label', 'libelle', 'name') ?? ''), dimensions: dims,
    formula: formula.length ? formula : dims.map((d) => d.code),
    thresholds: arr(pick(raw, 'thresholds', 'seuils')).map((t) => {
      const level = String(pick(t, 'level', 'code', 'niveau') ?? '');
      return { level, label: String(pick(t, 'label', 'libelle') ?? CRITICITY_LABELS[level] ?? level), min: Number(t['min'] ?? 0),
        max: num(t['max']), color: String(pick(t, 'color', 'couleur') ?? '#888888') };
    }).sort((a, b) => a.min - b.min),
  };
}
export function toRiskScaleBody(s: RiskScale): Raw {
  return { code: s.code, label: s.label, dimensions: s.dimensions, formula: s.formula, thresholds: s.thresholds };
}

/** Score = produit des dimensions retenues par la formule ; null si une cote manque. */
export function riskScore(scale: RiskScale, values: Record<string, number | null | undefined>): number | null {
  if (!scale.formula.length) return null;
  let p = 1;
  for (const c of scale.formula) {
    const v = values[c];
    if (v === null || v === undefined || !Number.isFinite(Number(v))) return null;
    p *= Number(v);
  }
  return p;
}
export function criticityOf(scale: RiskScale, score: number | null): Threshold | null {
  if (score === null) return null;
  return scale.thresholds.find((t) => score >= t.min && (t.max === null || score <= t.max))
    ?? [...scale.thresholds].reverse().find((t) => score >= t.min) ?? null;
}
/** Erreurs de saisie d'une échelle (le serveur reste juge). */
export function validateScale(s: RiskScale): string[] {
  const e: string[] = [];
  if (!s.dimensions.length) e.push('Ajoutez au moins une dimension.');
  s.dimensions.forEach((d, i) => {
    if (!d.code.trim()) e.push(`Dimension ${i + 1} : code obligatoire.`);
    if (d.levels.length < 2) e.push(`Dimension ${d.code || i + 1} : au moins deux niveaux.`);
  });
  if (!s.formula.length) e.push('La formule doit retenir au moins une dimension.');
  if (s.formula.some((c) => !s.dimensions.some((d) => d.code === c))) e.push('La formule cite une dimension inconnue.');
  const t = [...s.thresholds].sort((a, b) => a.min - b.min);
  if (!t.length) e.push('Ajoutez au moins un seuil de criticité.');
  for (let i = 1; i < t.length; i++) if (t[i - 1].max !== null && t[i - 1].max! >= t[i].min) e.push(`Seuils ${t[i - 1].label} et ${t[i].label} se chevauchent.`);
  return e;
}

export interface Rating { values: Record<string, number>; score: number | null; level: string | null }
export interface RiskHistory { at: string; by: string | null; gross: Rating | null; residual: Rating | null; comment: string | null }
export interface Risk {
  id: string; reference: string | null; type: string; site: Ref | null; unit: string | null; hazard: string; situation: string | null;
  gross: Rating; residual: Rating | null; measures: string | null; actions: ActionLink[]; requirements: Ref[];
  reviewDate: string | null; history: RiskHistory[];
}

function toRating(v: unknown): Rating | null {
  if (!v || typeof v !== 'object') return null;
  const r = v as Raw;
  const vals = (r['values'] ?? r['scores'] ?? r['cotes'] ?? Object.fromEntries(Object.entries(r).filter(([k, x]) => typeof x === 'number' && !['score', 'total'].includes(k)))) as Raw;
  return { values: Object.fromEntries(Object.entries(vals).map(([k, x]) => [k, Number(x)])), score: num(pick(r, 'score', 'total')),
    level: str(pick(r, 'level', 'criticity', 'criticite')) };
}

export function toRisk(input: Raw): Risk {
  const raw: Raw = input['risk'] && typeof input['risk'] === 'object' ? { ...(input['risk'] as Raw), history: input['history'] ?? (input['risk'] as Raw)['history'] } : input;
  return {
    id: String(raw['id']), reference: str(pick(raw, 'reference', 'code', 'ref')), type: String(raw['type'] ?? 'qualite'),
    site: ref(raw['site'] ?? raw['siteId']), unit: str(pick(raw, 'workUnit', 'unit', 'process', 'unite', 'uniteTravail', 'processus')),
    hazard: String(pick(raw, 'hazard', 'danger', 'aspect', 'title', 'label') ?? ''), situation: str(pick(raw, 'situation', 'description')),
    gross: toRating(pick(raw, 'gross', 'raw', 'rawRating', 'grossRating', 'brute', 'cotationBrute')) ?? { values: {}, score: null, level: null },
    residual: toRating(pick(raw, 'residual', 'residualRating', 'residuelle', 'cotationResiduelle')),
    measures: str(pick(raw, 'existingMeasures', 'measures', 'mesures', 'controls')),
    actions: actionsOf(raw), requirements: arr(pick(raw, 'requirements', 'exigences')).map((x) => x as unknown as Ref),
    reviewDate: str(pick(raw, 'reviewDate', 'reassessmentDate', 'nextReview', 'dateReevaluation')),
    history: arr(pick(raw, 'history', 'historique', 'ratings')).map((h) => ({
      at: String(pick(h, 'at', 'date', 'createdAt') ?? ''), by: str(pick(h, 'byName', 'author', 'by')),
      gross: toRating(pick(h, 'gross', 'raw', 'brute')), residual: toRating(pick(h, 'residual', 'residuelle')), comment: str(h['comment']),
    })),
  };
}

export interface NewRisk {
  type: RiskType; siteId: string | null; unit: string; hazard: string; situation: string; measures: string;
  gross: Record<string, number | null>; residual: Record<string, number | null>; reviewDate: string | null;
}
export function toRiskBody(r: NewRisk): Raw {
  const clean = (o: Record<string, number | null>) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== null).map(([k, v]) => [k, Number(v)]));
  return { type: r.type, siteId: r.siteId, workUnit: r.unit || null, hazard: r.hazard, situation: r.situation || null,
    existingMeasures: r.measures || null, gross: { values: clean(r.gross) }, residual: Object.keys(clean(r.residual)).length ? { values: clean(r.residual) } : null,
    reviewDate: r.reviewDate || null };
}

export interface MatrixCell { x: number; y: number; count: number; level: string | null; color: string | null; risks: Risk[] }
export interface RiskMatrix { xDim: string | null; yDim: string | null; xLevels: ScaleLevel[]; yLevels: ScaleLevel[]; cells: MatrixCell[] }

export function toMatrix(raw: Raw): RiskMatrix {
  const lv = (v: unknown): ScaleLevel[] => arr(v).map((l) => (typeof l === 'number' ? { value: l, label: String(l) }
    : { value: Number(pick(l, 'value', 'valeur')), label: String(pick(l, 'label', 'libelle') ?? '') }));
  const x = (raw['x'] ?? raw['columns'] ?? {}) as Raw;
  const y = (raw['y'] ?? raw['rows'] ?? {}) as Raw;
  return {
    xDim: str(pick(raw, 'xDimension', 'xDim') ?? x['dimension'] ?? x['code']), yDim: str(pick(raw, 'yDimension', 'yDim') ?? y['dimension'] ?? y['code']),
    xLevels: lv(pick(raw, 'xLevels') ?? x['levels']), yLevels: lv(pick(raw, 'yLevels') ?? y['levels']),
    cells: listOf(raw['cells'] ?? raw).map((c) => {
      const risks = arr(c['risks'] ?? c['items']).map(toRisk);
      return { x: Number(pick(c, 'x', 'col')), y: Number(pick(c, 'y', 'row')), count: Number(c['count'] ?? risks.length),
        level: str(pick(c, 'level', 'criticity')), color: str(pick(c, 'color', 'couleur')), risks };
    }),
  };
}

/** Grille complète (lignes = y décroissant, colonnes = x croissant), cases vides comprises ; couleur par l'échelle si absente. */
export function matrixGrid(m: RiskMatrix, scale: RiskScale | null): MatrixCell[][] {
  const dimLevels = (code: string | null) => scale?.dimensions.find((d) => d.code === code)?.levels ?? [];
  const xs = (m.xLevels.length ? m.xLevels : dimLevels(m.xDim)).map((l) => l.value);
  const ys = (m.yLevels.length ? m.yLevels : dimLevels(m.yDim)).map((l) => l.value);
  const xv = xs.length ? xs : [...new Set(m.cells.map((c) => c.x))].sort((a, b) => a - b);
  const yv = ys.length ? ys : [...new Set(m.cells.map((c) => c.y))].sort((a, b) => a - b);
  return [...yv].sort((a, b) => b - a).map((y) => [...xv].sort((a, b) => a - b).map((x) => {
    const c = m.cells.find((k) => k.x === x && k.y === y);
    const t = scale ? criticityOf(scale, x * y) : null;
    return { x, y, count: c?.count ?? 0, risks: c?.risks ?? [], level: c?.level ?? t?.level ?? null, color: c?.color ?? t?.color ?? null };
  }));
}

/* ===== Veille réglementaire ===== */

export type EvalResult = 'conforme' | 'non_conforme' | 'partiel' | 'na';
export const EVAL_RESULTS: { code: EvalResult; label: string }[] = [
  { code: 'conforme', label: 'Conforme' }, { code: 'partiel', label: 'Partiellement conforme' },
  { code: 'non_conforme', label: 'Non conforme' }, { code: 'na', label: 'Non applicable' },
];
export const evalLabel = (r: string | null | undefined): string => EVAL_RESULTS.find((x) => x.code === r)?.label ?? r ?? '—';
export const evalTone = (r: string | null | undefined): 'ok' | 'warn' | 'crit' | 'info' =>
  r === 'conforme' ? 'ok' : r === 'partiel' ? 'warn' : r === 'non_conforme' ? 'crit' : 'info';
export const REG_STATUS: Record<string, string> = { en_vigueur: 'En vigueur', abroge: 'Abrogé', a_venir: 'À venir' };
export const REG_DOMAINS = ['SST', 'environnement', 'qualité', 'produit'];

export interface Applicability { siteId: string; applicable: boolean; justification: string | null }
export interface RegRequirement { id: string; text: string; applicability: Applicability[] }
export interface RegEvaluation {
  id: string; siteId: string | null; siteName: string | null; result: string; evidence: string | null; comment: string | null;
  at: string | null; by: string | null; nextReview: string | null; actions: ActionLink[];
}
export interface Regulation {
  id: string; reference: string; title: string; domain: string | null; jurisdiction: string | null; publishedAt: string | null;
  effectiveAt: string | null; summary: string | null; sourceUrl: string | null; status: string;
  requirements: RegRequirement[]; evaluations: RegEvaluation[];
}

export function toRegEvaluation(raw: Raw): RegEvaluation {
  const site = raw['site'] as Raw | undefined;
  return { id: String(raw['id'] ?? ''), siteId: str(raw['siteId'] ?? site?.['id']), siteName: str(site?.['name'] ?? site?.['code']),
    result: String(raw['result'] ?? raw['resultat'] ?? ''), evidence: str(pick(raw, 'evidence', 'preuve')), comment: str(raw['comment']),
    at: str(pick(raw, 'evaluatedAt', 'at', 'createdAt', 'date')), by: str(pick(raw, 'evaluatorName', 'byName', 'author', 'createdBy')),
    nextReview: str(pick(raw, 'nextReviewDate', 'nextEvaluation', 'reviewDate', 'dueDate', 'echeance')), actions: actionsOf(raw) };
}

export function toRegulation(input: Raw): Regulation {
  const raw: Raw = input['regulation'] && typeof input['regulation'] === 'object'
    ? { ...(input['regulation'] as Raw), requirements: input['requirements'] ?? (input['regulation'] as Raw)['requirements'], evaluations: input['evaluations'] ?? (input['regulation'] as Raw)['evaluations'] }
    : input;
  return {
    id: String(raw['id']), reference: String(pick(raw, 'reference', 'ref') ?? ''), title: String(pick(raw, 'title', 'titre', 'label') ?? ''),
    domain: str(pick(raw, 'domain', 'domaine')), jurisdiction: str(pick(raw, 'jurisdiction', 'juridiction')),
    publishedAt: str(pick(raw, 'publishedAt', 'publicationDate', 'datePublication')), effectiveAt: str(pick(raw, 'effectiveAt', 'applicationDate', 'effectiveDate', 'dateApplication')),
    summary: str(pick(raw, 'summary', 'resume')), sourceUrl: str(pick(raw, 'sourceUrl', 'source', 'lien')), status: String(raw['status'] ?? 'en_vigueur'),
    requirements: arr(pick(raw, 'requirements', 'exigences')).map((q) => ({
      id: String(q['id'] ?? ''), text: String(pick(q, 'text', 'texte', 'label') ?? ''),
      applicability: arr(pick(q, 'applicability', 'sites', 'applicabilite')).map((a) => ({
        siteId: String(a['siteId'] ?? (a['site'] as Raw | undefined)?.['id'] ?? ''), applicable: Boolean(pick(a, 'applicable')),
        justification: str(a['justification']) })),
    })),
    evaluations: arr(raw['evaluations']).map(toRegEvaluation),
  };
}

export interface SiteCompliance { siteId: string; siteName: string; rate: number | null; counts: Record<string, number>; total: number }
/** Taux par site (`[{site, rate, ...}]` ou `{sites:[...]}`) ; le taux est un pourcentage 0..100. */
export function toCompliance(raw: unknown): SiteCompliance[] {
  const list = Array.isArray(raw) ? (raw as Raw[]) : arr((raw as Raw)?.['sites'] ?? (raw as Raw)?.['items']);
  return list.map((c) => {
    const site = (c['site'] ?? {}) as Raw;
    const counts = (c['counts'] ?? {}) as Raw;
    const cnt: Record<string, number> = {};
    for (const k of ['conforme', 'non_conforme', 'partiel', 'na']) cnt[k] = Number(counts[k] ?? c[k] ?? 0);
    let rate = num(pick(c, 'rate', 'taux', 'complianceRate'));
    if (rate !== null && rate <= 1 && !Number.isInteger(rate)) rate *= 100;
    return { siteId: String(c['siteId'] ?? site['id'] ?? ''), siteName: String(site['name'] ?? site['code'] ?? c['siteName'] ?? ''),
      rate, counts: cnt, total: Number(c['total'] ?? Object.values(cnt).reduce((s, v) => s + v, 0)) };
  });
}

/** Dernière évaluation par site (la plus récente). */
export function latestBySite(evals: RegEvaluation[]): Map<string, RegEvaluation> {
  const m = new Map<string, RegEvaluation>();
  [...evals].sort((a, b) => String(a.at).localeCompare(String(b.at))).forEach((e) => e.siteId && m.set(e.siteId, e));
  return m;
}

/* ===== Métrologie ===== */

export type InstrumentStatus = 'en_service' | 'bloque' | 'en_etalonnage' | 'reforme';
export const INSTRUMENT_STATUS: Record<string, string> = { en_service: 'En service', bloque: 'Bloqué', en_etalonnage: 'En étalonnage', reforme: 'Réformé' };
export const instrumentTone = (s: string | null | undefined): 'ok' | 'warn' | 'crit' | 'info' =>
  s === 'en_service' ? 'ok' : s === 'bloque' ? 'crit' : s === 'en_etalonnage' ? 'warn' : 'info';

export interface CalPoint { nominal: number; mesure: number; ecart: number | null }
export interface Calibration {
  id: string; date: string; provider: string | null; internal: boolean; certificate: string | null; points: CalPoint[];
  verdict: 'conforme' | 'non_conforme' | string; nextDue: string | null; by: string | null;
  certificateNumber: string | null; certificateUrl: string | null; ncId: string | null; ncReference: string | null; comment: string | null;
}
export interface Instrument {
  id: string; code: string; designation: string; type: string | null; site: Ref | null; location: string | null; serial: string | null;
  range: string | null; resolution: string | null; emt: number | null; periodicityMonths: number | null; provider: string | null;
  status: string; nextDue: string | null; lastCalibration: string | null; qr: string; calibrations: Calibration[];
  /** Fourni par le serveur (`selectable`, `dueState`) : prime sur le calcul local. */
  selectable: boolean | null; dueState: string | null; unit: string | null;
}

export function toCalibration(raw: Raw): Calibration {
  const cert = raw['certificate'] ?? raw['certificat'] ?? raw['certificateAttachmentId'];
  return {
    id: String(raw['id'] ?? ''), date: String(pick(raw, 'calibratedOn', 'date', 'calibratedAt', 'performedAt') ?? ''),
    provider: str(pick(raw, 'provider', 'prestataire')), internal: Boolean(pick(raw, 'internal', 'interne')),
    certificate: cert && typeof cert === 'object' ? str((cert as Raw)['fileName'] ?? (cert as Raw)['id']) : str(cert),
    certificateUrl: cert && typeof cert === 'object' ? str((cert as Raw)['url']) : null, certificateNumber: str(raw['certificateNumber']),
    ncId: str(raw['ncId']), ncReference: str(raw['ncReference']), comment: str(raw['comment']),
    points: arr(pick(raw, 'points', 'measurePoints')).map((p) => {
      const nominal = Number(p['nominal']);
      const mesure = Number(pick(p, 'mesure', 'measured', 'value'));
      return { nominal, mesure, ecart: num(pick(p, 'ecart', 'deviation', 'error')) ?? mesure - nominal };
    }),
    verdict: String(pick(raw, 'verdict', 'result') ?? ''), nextDue: str(pick(raw, 'nextDue', 'nextDueDate', 'prochaineEcheance')),
    by: str(pick(raw, 'byName', 'author', 'createdBy')),
  };
}

const rangeOf = (r: Raw): string | null => {
  const a = num(r['rangeMin']);
  const b = num(r['rangeMax']);
  if (a === null && b === null) return null;
  return `${a ?? '?'}–${b ?? '?'}${r['unit'] ? ` ${String(r['unit'])}` : ''}`;
};

export function toInstrument(input: Raw): Instrument {
  const raw: Raw = input['instrument'] && typeof input['instrument'] === 'object'
    ? { ...(input['instrument'] as Raw), calibrations: input['calibrations'] ?? (input['instrument'] as Raw)['calibrations'] } : input;
  const id = String(raw['id']);
  return {
    id, code: String(raw['code'] ?? ''), designation: String(pick(raw, 'designation', 'label', 'name') ?? ''), type: str(raw['type']),
    site: ref(raw['site'] ?? raw['siteId']), location: str(pick(raw, 'location', 'emplacement')), serial: str(pick(raw, 'serialNumber', 'serial', 'numeroSerie')),
    range: str(pick(raw, 'range', 'plage')) ?? rangeOf(raw), resolution: str(raw['resolution']), unit: str(pick(raw, 'unit', 'unite')),
    selectable: typeof raw['selectable'] === 'boolean' ? (raw['selectable'] as boolean) : null, dueState: str(raw['dueState']), emt: num(pick(raw, 'emt', 'mpe', 'maxPermissibleError')),
    periodicityMonths: num(pick(raw, 'periodMonths', 'periodicityMonths', 'calibrationPeriodMonths', 'periodicite', 'periodicity')),
    provider: str(pick(raw, 'provider', 'prestataire')), status: String(raw['status'] ?? 'en_service'),
    nextDue: str(pick(raw, 'nextDue', 'nextDueDate', 'nextCalibration', 'prochaineEcheance')),
    lastCalibration: str(pick(raw, 'lastCalibration', 'lastCalibrationDate', 'lastCalibratedAt')),
    qr: str(pick(raw, 'qr', 'qrCode', 'qrPayload')) ?? `essor://instrument/${id}`,
    calibrations: arr(raw['calibrations']).map(toCalibration).sort((a, b) => b.date.localeCompare(a.date)),
  };
}

/** Écart et verdict prévisionnels (|mesure − nominal| ≤ EMT) ; le verdict officiel est calculé par le serveur. */
export function previewVerdict(points: { nominal: number | null; mesure: number | null }[], emt: number | null): 'conforme' | 'non_conforme' | null {
  const ok = points.filter((p) => p.nominal !== null && p.mesure !== null);
  if (!ok.length || emt === null) return null;
  return ok.every((p) => Math.abs(p.mesure! - p.nominal!) <= emt + 1e-9) ? 'conforme' : 'non_conforme';
}

/** Jours avant échéance (négatif = échu). */
export function daysUntil(date: string | null, today: Date = new Date()): number | null {
  if (!date) return null;
  const d = new Date(date.length === 10 ? `${date}T00:00:00` : date);
  const t = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  return Math.round((new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() - t.getTime()) / 86_400_000);
}
export function dueLabel(date: string | null, today?: Date): { text: string; tone: 'ok' | 'warn' | 'crit' | 'info' } {
  const d = daysUntil(date, today);
  if (d === null) return { text: '—', tone: 'info' };
  if (d < 0) return { text: `Échu depuis ${-d} j`, tone: 'crit' };
  if (d <= 15) return { text: `Sous ${d} j`, tone: 'warn' };
  return { text: `Dans ${d} j`, tone: 'ok' };
}
/** Instrument utilisable dans un contrôle : en service et non échu. */
export function isUsable(i: Instrument, today?: Date): boolean {
  if (i.selectable !== null) return i.selectable;
  const d = daysUntil(i.nextDue, today);
  return i.status === 'en_service' && (d === null || d >= 0);
}

export interface ImpactInspection { id: string; lotId: string | null; lotNumero: string | null; point: string | null; at: string | null; characteristic: string | null }
export interface ImpactLot { id: string; numero: string; article: string | null; qualityStatus: string; quantity: number | null; unit: string | null }
export interface Impact { since: string | null; inspections: ImpactInspection[]; lots: ImpactLot[] }

export function toImpact(raw: Raw): Impact {
  const lotOf = (l: Raw): ImpactLot => {
    const a = l['article'] as Raw | string | undefined;
    return { id: String(l['id']), numero: String(l['numero'] ?? l['number'] ?? ''),
      article: typeof a === 'string' ? a : a ? [a['code'], a['label'] ?? a['designation']].filter(Boolean).join(' — ') : null,
      qualityStatus: String(l['qualityStatus'] ?? l['status'] ?? ''), quantity: num(l['quantity'] ?? l['quantite']), unit: str(l['unit'] ?? l['unite']) };
  };
  const insp = arr(pick(raw, 'inspections', 'controles'));
  const lots = arr(raw['lots']).map(lotOf);
  insp.forEach((i) => {
    const l = i['lot'] as Raw | undefined;
    if (l && typeof l === 'object' && !lots.some((x) => x.id === String(l['id']))) lots.push(lotOf(l));
  });
  return {
    since: str(pick(raw, 'since', 'from') ?? ((raw['lastConformingCalibration'] ?? {}) as Raw)['calibratedOn']),
    inspections: insp.map((i) => {
      const l = i['lot'] as Raw | undefined;
      return { id: String(i['id']), lotId: str(i['lotId'] ?? l?.['id']), lotNumero: str(i['lotNumero'] ?? l?.['numero']), point: str(i['point']),
        at: str(pick(i, 'measuredAt', 'createdAt', 'at', 'date')),
        characteristic: Array.isArray(i['characteristics']) ? (i['characteristics'] as unknown[]).join(', ') : str(i['characteristic']) };
    }),
    lots,
  };
}
const ALREADY_BLOCKED = ['QUARANTAINE', 'REFUSE', 'DETRUIT'];
/** Lots à mettre en quarantaine pour réévaluation (ni déjà bloqués, ni refusés, ni détruits). */
/** Réponse de `POST /api/instruments/{id}/impact/quarantine` : `{quarantined[{id, numero, previousStatus}], skipped, ncId}`. */
export interface QuarantineResult { quarantined: { id: string; numero: string; previousStatus: string | null }[]; skipped: Raw[]; ncId: string | null }
export function toQuarantine(raw: Raw): QuarantineResult {
  return { quarantined: arr(raw['quarantined']).map((q) => ({ id: String(q['id']), numero: String(q['numero'] ?? ''), previousStatus: str(q['previousStatus']) })),
    skipped: arr(raw['skipped']), ncId: str(raw['ncId']) };
}
export function lotsToQuarantine(i: Impact): ImpactLot[] {
  return i.lots.filter((l) => !ALREADY_BLOCKED.includes(l.qualityStatus));
}

export interface NewCalibration {
  date: string; provider: string; internal: boolean; points: { nominal: number | null; mesure: number | null }[]; certificateNumber: string | null; comment: string | null;
}
/** Corps réel `CalibrationInput{calibratedOn, provider, internal, certificateNumber, points[{nominal, mesure}], comment}`. */
export function toCalibrationBody(c: NewCalibration): Raw {
  return { calibratedOn: c.date, provider: c.internal ? null : c.provider || null, internal: c.internal, certificateNumber: c.certificateNumber || null,
    comment: c.comment || null,
    points: c.points.filter((p) => p.nominal !== null && p.mesure !== null).map((p) => ({ nominal: Number(p.nominal), mesure: Number(p.mesure) })) };
}

/* ===== Évaluation des partenaires ===== */

export interface Criterion { code: string; label: string; weight: number }
export interface GridThreshold { cls: string; label: string; min: number }
export interface EvaluationGrid {
  id: string | null; code: string; label: string; partnerType: string; scaleMax: number; criteria: Criterion[];
  thresholds: GridThreshold[]; improvementClasses: string[];
}
export function toGrid(raw: Raw): EvaluationGrid {
  const scale = (raw['scale'] ?? {}) as Raw;
  const thresholds = arr(pick(raw, 'thresholds', 'seuils', 'classes')).map((t) => ({
    cls: String(pick(t, 'class', 'cls', 'code') ?? ''), label: String(pick(t, 'label', 'libelle') ?? pick(t, 'class', 'code') ?? ''), min: Number(t['min'] ?? 0),
  })).sort((a, b) => b.min - a.min);
  const imp = pick(raw, 'improvementClasses', 'actionClasses');
  return {
    id: str(raw['id']), code: String(raw['code'] ?? ''), label: String(pick(raw, 'label', 'libelle', 'name') ?? ''),
    partnerType: String(pick(raw, 'partnerType', 'type') ?? 'fournisseur'), scaleMax: Number(pick(scale, 'max') ?? raw['scaleMax'] ?? 5),
    criteria: arr(pick(raw, 'criteria', 'criteres')).map((c) => ({ code: String(c['code'] ?? ''), label: String(pick(c, 'label', 'libelle') ?? ''),
      weight: Number(pick(c, 'weight', 'poids') ?? 1) })),
    thresholds, improvementClasses: Array.isArray(imp) ? imp.map(String) : thresholds.length ? [thresholds.at(-1)!.cls] : [],
  };
}
export function toGridBody(g: EvaluationGrid): Raw {
  return { code: g.code, label: g.label, partnerType: g.partnerType, scale: { min: 0, max: g.scaleMax }, criteria: g.criteria,
    thresholds: g.thresholds.map((t) => ({ class: t.cls, label: t.label, min: t.min })), improvementClasses: g.improvementClasses };
}
export function validateGrid(g: EvaluationGrid): string[] {
  const e: string[] = [];
  if (!g.code.trim()) e.push('Le code de la grille est obligatoire.');
  if (!g.criteria.length) e.push('Ajoutez au moins un critère.');
  if (g.criteria.some((c) => !c.code.trim() || !(c.weight > 0))) e.push('Chaque critère a un code et un poids positif.');
  if (!g.thresholds.length) e.push('Ajoutez au moins un seuil de classe.');
  if (!(g.scaleMax > 0)) e.push("L'échelle doit avoir un maximum positif.");
  return e;
}

/** Note globale prévisionnelle sur 100 : moyenne pondérée des notes / maximum de l'échelle. */
export function weightedScore(g: EvaluationGrid, scores: Record<string, number | null | undefined>): number | null {
  const rated = g.criteria.filter((c) => scores[c.code] !== null && scores[c.code] !== undefined);
  if (!rated.length || rated.length < g.criteria.length) return null;
  const w = rated.reduce((s, c) => s + c.weight, 0);
  return Math.round((rated.reduce((s, c) => s + c.weight * Number(scores[c.code]), 0) / w / g.scaleMax) * 1000) / 10;
}
export function classOf(g: EvaluationGrid, score: number | null): GridThreshold | null {
  if (score === null) return null;
  return g.thresholds.find((t) => score >= t.min) ?? g.thresholds.at(-1) ?? null;
}

export interface Indicators { ncCount: number | null; refusedRate: number | null; derogations: number | null; complaints: number | null; lateDeliveries: number | null }
export interface PartnerEvaluation {
  id: string; partnerId: string | null; gridId: string | null; period: string; status: string; scores: Record<string, number>;
  indicators: Indicators; score: number | null; cls: string | null; comment: string | null; actions: ActionLink[]; at: string | null;
}
function toIndicators(v: unknown): Indicators {
  const r = (v ?? {}) as Raw;
  let refused = num(pick(r, 'refusedLotsRate', 'refusedRate', 'lotsRefusedPct', 'pctLotsRefuses', 'refusedLotsPct'));
  if (refused !== null && refused > 0 && refused < 1) refused *= 100;
  return { ncCount: num(pick(r, 'ncCount', 'nc', 'nbNc', 'linkedNc')), refusedRate: refused,
    derogations: num(pick(r, 'derogationLots', 'derogations', 'lotsDerogation')), complaints: num(pick(r, 'complaints', 'reclamations')),
    lateDeliveries: num(pick(r, 'lateDeliveries', 'delays', 'retards')) };
}
export function toEvaluation(raw: Raw): PartnerEvaluation {
  const sc = pick(raw, 'scores', 'notes');
  const scores: Record<string, number> = Array.isArray(sc)
    ? Object.fromEntries((sc as Raw[]).map((s) => [String(pick(s, 'criterion', 'code', 'critere')), Number(pick(s, 'score', 'note', 'value'))]))
    : Object.fromEntries(Object.entries((sc ?? {}) as Raw).map(([k, v]) => [k, Number(v)]));
  const partner = raw['partner'] as Raw | undefined;
  const grid = raw['grid'] as Raw | undefined;
  return {
    id: String(raw['id'] ?? ''), partnerId: str(raw['partnerId'] ?? partner?.['id']), gridId: str(raw['gridId'] ?? grid?.['id']),
    period: String(raw['period'] ?? raw['periode'] ?? ''), status: String(raw['status'] ?? (pick(raw, 'globalScore', 'score', 'noteGlobale') !== undefined ? 'terminee' : 'a_faire')),
    scores, indicators: toIndicators(pick(raw, 'indicators', 'indicateurs', 'kpis')),
    score: num(pick(raw, 'globalScore', 'score', 'noteGlobale')), cls: str(pick(raw, 'class', 'grade', 'classe', 'cls')),
    comment: str(raw['comment']), actions: actionsOf(raw), at: str(pick(raw, 'evaluatedAt', 'updatedAt', 'createdAt')),
  };
}
export interface Scorecard { partner: Ref | null; evaluations: PartnerEvaluation[] }
export function toScorecard(raw: Raw): Scorecard {
  const list = Array.isArray(raw) ? (raw as unknown as Raw[]) : arr(pick(raw, 'evaluations', 'history', 'historique', 'items'));
  return { partner: ref((raw as Raw)['partner']), evaluations: list.map(toEvaluation).sort((a, b) => a.period.localeCompare(b.period)) };
}
export const classTone = (c: string | null | undefined, g?: EvaluationGrid | null): 'ok' | 'warn' | 'crit' | 'info' => {
  if (!c) return 'info';
  if (g?.improvementClasses.includes(c) || /^(c|refuse)/i.test(c)) return 'crit';
  if (g && g.thresholds[0]?.cls === c) return 'ok';
  if (/^(a|agree)/i.test(c)) return 'ok';
  return 'warn';
};
/** Période trimestrielle courante « 2026-T4 ». */
export function currentQuarter(d: Date = new Date()): string {
  return `${d.getFullYear()}-T${Math.floor(d.getMonth() / 3) + 1}`;
}

/* ===== Tableau de bord ===== */
export interface M4Kpis { regulatoryCompliance: number | null; calibrationsDue15: number | null; calibrationsOverdue: number | null }
export function toM4Kpis(raw: Raw | null | undefined): M4Kpis {
  const r = (raw ?? {}) as Raw;
  let rate = num(pick(r, 'regulatoryCompliance', 'regulatoryComplianceRate', 'conformiteReglementaire'));
  if (rate !== null && rate > 0 && rate <= 1 && !Number.isInteger(rate)) rate *= 100;
  return { regulatoryCompliance: rate, calibrationsDue15: num(pick(r, 'calibrationsDue15', 'calibrationsDueSoon', 'etalonnagesSous15j')),
    calibrationsOverdue: num(pick(r, 'calibrationsOverdue', 'etalonnagesEchus')) };
}
