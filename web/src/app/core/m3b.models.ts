/**
 * Types et fonctions pures du contrat M3b (docs/pilotage/cartes/M3B-contrat.md) : lots, traçabilité, rappel,
 * plans de contrôle, contrôles et carte de contrôle. Les normaliseurs tolèrent quelques variantes de noms de champs.
 */

export type QualityStatus = 'EN_ATTENTE' | 'LIBERE' | 'QUARANTAINE' | 'DEROGATION' | 'REFUSE' | 'DETRUIT';
export type Decision = 'LIBERE' | 'QUARANTAINE' | 'DEROGATION' | 'REFUSE' | 'DETRUIT';
export type FlowPoint =
  | 'RECEPTION' | 'STOCKAGE' | 'PREPARATION' | 'PRODUCTION_ENTREE' | 'PRODUCTION_ENCOURS' | 'PRODUCTION_SORTIE' | 'EXPEDITION';
export type LinkKind = 'consumed_by' | 'shipped_to' | 'split_into';

export const QUALITY_STATUS_LABELS: Record<QualityStatus, string> = {
  EN_ATTENTE: 'En attente', LIBERE: 'Libéré', QUARANTAINE: 'Quarantaine', DEROGATION: 'Dérogation', REFUSE: 'Refusé', DETRUIT: 'Détruit',
};
export const FLOW_POINTS: { code: FlowPoint; label: string }[] = [
  { code: 'RECEPTION', label: 'Réception' }, { code: 'STOCKAGE', label: 'Stockage' }, { code: 'PREPARATION', label: 'Préparation' },
  { code: 'PRODUCTION_ENTREE', label: 'Production — entrée' }, { code: 'PRODUCTION_ENCOURS', label: 'Production — en-cours' },
  { code: 'PRODUCTION_SORTIE', label: 'Production — sortie' }, { code: 'EXPEDITION', label: 'Expédition' },
];
export const LINK_LABELS: Record<LinkKind, string> = { consumed_by: 'Consommé par', shipped_to: 'Expédié à', split_into: 'Divisé en' };
export const DECISION_LABELS: Record<Decision, string> = {
  LIBERE: 'Libérer', QUARANTAINE: 'Mettre en quarantaine', DEROGATION: 'Accepter par dérogation', REFUSE: 'Refuser', DETRUIT: 'Détruire',
};
/** Statuts qui n'ont plus besoin d'être bloqués lors d'un rappel. */
export const BLOCKED_STATUSES: QualityStatus[] = ['QUARANTAINE', 'REFUSE', 'DETRUIT'];

export function pointLabel(p: string | null | undefined): string {
  return FLOW_POINTS.find((f) => f.code === p)?.label ?? p ?? '—';
}
export function statusLabel(s: string | null | undefined): string {
  return QUALITY_STATUS_LABELS[s as QualityStatus] ?? s ?? '—';
}
export function qualityTone(s: string | null | undefined): 'ok' | 'warn' | 'crit' | 'info' {
  if (s === 'LIBERE') return 'ok';
  if (s === 'DEROGATION' || s === 'EN_ATTENTE') return 'warn';
  if (s === 'QUARANTAINE' || s === 'REFUSE' || s === 'DETRUIT') return 'crit';
  return 'info';
}

export interface Ref { id: string; code?: string; designation?: string; label?: string; nom?: string; name?: string }
export interface Article { id: string; code: string; designation: string; famille?: string; unite?: string; suivi?: string; dlc?: boolean }
export interface Partner { id: string; code: string; nom: string; type: 'fournisseur' | 'client' | string }

export interface StatusChange { from?: string | null; status: string; reason?: string | null; by?: string | null; at: string }
export interface Lot {
  id: string; numero: string; article: Ref | string | null; fournisseur?: Ref | string | null; lotFournisseur?: string | null;
  quantite?: number | null; unite?: string | null; dlc?: string | null; site?: Ref | string | null; qualityStatus: QualityStatus | string;
  historique: StatusChange[]; ncId?: string | null;
  /** Quantité restante (rappel). */
  remaining?: number | null;
}
export interface NewLot {
  articleId: string; numero: string; fournisseurId: string | null; lotFournisseur: string | null; quantite: number; unite: string;
  dlc: string | null; siteId: string | null;
}

/** Corps de `POST /api/lots` (noms du serveur ; le site est obligatoire). */
export function toLotBody(l: NewLot): Record<string, unknown> {
  return { articleId: l.articleId, numero: l.numero, supplierId: l.fournisseurId, supplierLot: l.lotFournisseur, quantity: l.quantite,
    unit: l.unite, dlc: l.dlc, siteId: l.siteId };
}

/** Libellé lisible d'une référence (article, partenaire, site) quelle que soit sa forme. */
export function refLabel(r: Ref | string | null | undefined): string {
  if (!r) return '—';
  if (typeof r === 'string') return r;
  const name = r.designation ?? r.label ?? r.nom ?? r.name ?? '';
  return [r.code, name].filter(Boolean).join(' — ') || r.id;
}

type Raw = Record<string, unknown>;
const str = (v: unknown): string | null => (v === undefined || v === null || v === '' ? null : String(v));

/** Article du serveur ({code, label, family, unit, tracking, dlc}) → modèle web. */
export function toArticle(raw: Raw): Article {
  return {
    id: String(raw['id']), code: String(raw['code'] ?? ''), designation: String(raw['designation'] ?? raw['label'] ?? ''),
    famille: str(raw['famille'] ?? raw['family']) ?? undefined, unite: str(raw['unite'] ?? raw['unit']) ?? undefined,
    suivi: str(raw['suivi'] ?? raw['tracking']) ?? undefined, dlc: Boolean(raw['dlc']),
  };
}

/** Partenaire du serveur ({code, name, type}) → modèle web. */
export function toPartner(raw: Raw): Partner {
  return { id: String(raw['id']), code: String(raw['code'] ?? ''), nom: String(raw['nom'] ?? raw['name'] ?? ''), type: String(raw['type'] ?? '') };
}

/** Lot seul, ou fiche `GET /api/lots/{id}` = `{lot, history, inspections}` (historique : `{at, from, to, reason, author}`). */
export function toLot(input: Raw): Lot {
  const raw: Raw = input['lot'] && typeof input['lot'] === 'object' ? { ...(input['lot'] as Raw), history: input['history'] } : input;
  const hist = (raw['historique'] ?? raw['history'] ?? []) as Raw[];
  return {
    id: String(raw['id']),
    numero: String(raw['numero'] ?? raw['number'] ?? ''),
    article: (raw['article'] ?? null) as Lot['article'],
    fournisseur: (raw['fournisseur'] ?? raw['supplier'] ?? null) as Lot['fournisseur'],
    lotFournisseur: str(raw['lotFournisseur'] ?? raw['supplierLot']),
    quantite: (raw['quantite'] ?? raw['quantity'] ?? null) as number | null,
    unite: str(raw['unite'] ?? raw['unit']),
    dlc: str(raw['dlc']),
    site: (raw['site'] ?? null) as Lot['site'],
    qualityStatus: String(raw['qualityStatus'] ?? raw['status'] ?? 'EN_ATTENTE'),
    ncId: str(raw['ncId']),
    remaining: (raw['remaining'] ?? null) as number | null,
    historique: hist.map((h) => ({
      from: str(h['from']), status: String(h['status'] ?? h['to'] ?? ''), reason: str(h['reason'] ?? h['motif']),
      by: str(h['byName'] ?? h['by'] ?? h['auteur'] ?? h['author']), at: String(h['at'] ?? h['date'] ?? ''),
    })),
  };
}

export interface GenealogyLink { kind: LinkKind | string; quantity?: number | null; reference?: string | null; partner?: Ref | null }
export interface TraceNode { lot: Lot; children: { link: GenealogyLink; node: TraceNode | null }[] }

export function toTrace(raw: Raw): TraceNode {
  const kids = (raw['children'] ?? []) as Raw[];
  return {
    lot: toLot((raw['lot'] ?? {}) as Raw),
    children: kids.map((c) => ({
      link: (c['link'] ?? {}) as GenealogyLink,
      node: c['node'] ? toTrace(c['node'] as Raw) : null,
    })),
  };
}

/** Ligne à plat de l'arbre de généalogie, pour l'affichage indenté (role="tree"). */
export interface TraceRow { key: string; depth: number; lot: Lot | null; link: GenealogyLink | null }

export function flattenTrace(root: TraceNode): TraceRow[] {
  const rows: TraceRow[] = [];
  const walk = (n: TraceNode, depth: number, key: string, link: GenealogyLink | null) => {
    rows.push({ key, depth, lot: n.lot, link });
    n.children.forEach((c, i) => {
      const k = `${key}.${i}`;
      if (c.node) walk(c.node, depth + 1, k, c.link);
      else rows.push({ key: k, depth: depth + 1, lot: null, link: c.link });
    });
  };
  walk(root, 0, '0', null);
  return rows;
}

export interface RecallClient { partner: Ref; quantite: number; references: string[] }
export interface UnitQty { unit: string; qty: number }
/** Quantités regroupées par unité : jamais de somme entre unités différentes. */
export interface RecallQuantities { enStock: UnitQty[]; enCours: UnitQty[]; expedie: UnitQty[] }
export interface Recall {
  source: Lot | null; lotsImpactes: Lot[]; clients: RecallClient[]; quantites: RecallQuantities;
}

const toUnitQty = (v: unknown): UnitQty[] =>
  Array.isArray(v) ? (v as Raw[]).map((q) => ({ unit: String(q['unit'] ?? ''), qty: Number(q['qty'] ?? 0) })) : [];

/** « 10 kg ; 40 pcs », ou « 0 ». */
export function formatUnitQty(q: UnitQty[]): string {
  return q.length ? q.map((x) => `${String(x.qty).replace('.', ',')} ${x.unit}`.trim()).join(' ; ') : '0';
}

export function toRecall(raw: Raw): Recall {
  const q = (raw['quantites'] ?? {}) as Raw;
  return {
    source: raw['source'] ? toLot(raw['source'] as Raw) : null,
    lotsImpactes: ((raw['lotsImpactes'] ?? []) as Raw[]).map(toLot),
    clients: ((raw['clients'] ?? []) as Raw[]).map((c) => ({
      partner: (c['partner'] ?? {}) as Ref, quantite: Number(c['quantite'] ?? 0), references: (c['references'] ?? []) as string[],
    })),
    quantites: { enStock: toUnitQty(q['enStock']), enCours: toUnitQty(q['enCours']), expedie: toUnitQty(q['expedie']) },
  };
}

/**
 * Lots que le blocage touchera : le lot source et les lots impactés ni en quarantaine, ni refusés, ni détruits,
 * et dont il reste du stock (`remaining` > 0 quand le serveur le fournit). Le blocage lui-même est fait par le serveur.
 */
export function lotsToBlock(source: Lot, recall: Recall): Lot[] {
  const src = recall.source ?? source;
  const all = [src, ...recall.lotsImpactes.filter((l) => l.id !== src.id)];
  return all.filter((l) => !BLOCKED_STATUSES.includes(l.qualityStatus as QualityStatus) && (l.remaining === null || l.remaining === undefined || l.remaining > 0));
}

/** Statuts de lot pour lesquels le serveur exige un motif. */
export const REASON_REQUIRED: string[] = ['QUARANTAINE', 'DEROGATION', 'REFUSE', 'DETRUIT'];

/* ===== Plans de contrôle ===== */

export type CharType = 'mesure' | 'attribut' | 'document';
export type Criticity = 'critique' | 'majeure' | 'mineure';
export interface Characteristic {
  code: string; libelle: string; type: CharType; nominal: number | null; tolMin: number | null; tolMax: number | null;
  /** Nom de l'instrument requis (texte libre), ou null. */
  unite: string | null; instrumentRequis: string | null; criticite: Criticity;
}
export interface Sampling { mode: 'fixe' | 'iso2859'; taille?: number | null; nqa?: number | null; niveau?: string | null }
export interface Frequency { pieces?: number | null; minutes?: number | null }
export interface PlanTarget { type: 'article' | 'famille' | 'fournisseur' | 'client'; id?: string | null; famille?: string | null }
export interface ControlPlan {
  id?: string; code: string; libelle: string; version?: number; point: FlowPoint; target: PlanTarget;
  characteristics: Characteristic[]; sampling: Sampling; frequency?: Frequency | null;
}
export const NQA_VALUES = [0.65, 1.0, 1.5, 2.5, 4.0, 6.5];

export function emptyCharacteristic(): Characteristic {
  return { code: '', libelle: '', type: 'mesure', nominal: null, tolMin: null, tolMax: null, unite: null, instrumentRequis: null, criticite: 'majeure' };
}
export function emptyPlan(): ControlPlan {
  return { code: '', libelle: '', point: 'RECEPTION', target: { type: 'article', id: null, famille: null }, characteristics: [emptyCharacteristic()],
    sampling: { mode: 'iso2859', nqa: 1.0, niveau: 'II' }, frequency: null };
}

/** Plan du serveur (`label`, `nqa` en texte) → modèle web (`libelle`, `nqa` nombre). */
export function toPlan(raw: Raw): ControlPlan {
  const sampling = (raw['sampling'] ?? {}) as Raw;
  const chars = (raw['characteristics'] ?? []) as Raw[];
  const num = (v: unknown): number | null => (v === null || v === undefined || v === '' ? null : Number(v));
  return {
    id: str(raw['id']) ?? undefined, code: String(raw['code'] ?? ''), libelle: String(raw['libelle'] ?? raw['label'] ?? ''),
    version: (raw['version'] ?? undefined) as number | undefined, point: String(raw['point'] ?? 'RECEPTION') as FlowPoint,
    target: (raw['target'] ?? { type: 'article', id: null, famille: null }) as PlanTarget,
    characteristics: chars.map((c) => ({
      code: String(c['code'] ?? ''), libelle: String(c['libelle'] ?? ''), type: String(c['type'] ?? 'mesure') as CharType,
      nominal: num(c['nominal']), tolMin: num(c['tolMin']), tolMax: num(c['tolMax']), unite: str(c['unite']),
      instrumentRequis: typeof c['instrumentRequis'] === 'string' ? str(c['instrumentRequis']) : null,
      criticite: String(c['criticite'] ?? 'majeure') as Criticity,
    })),
    sampling: { mode: (sampling['mode'] ?? 'fixe') as Sampling['mode'], taille: num(sampling['taille']), nqa: num(sampling['nqa']),
      niveau: str(sampling['niveau']) },
    frequency: (raw['frequency'] ?? null) as Frequency | null,
  };
}

/** Corps attendu par `POST/PUT /api/control-plans` (`label`). */
export function toPlanBody(p: ControlPlan): Raw {
  const { libelle, ...rest } = p;
  return { ...rest, label: libelle,
    characteristics: p.characteristics.map((c) => ({ ...c, instrumentRequis: c.instrumentRequis?.trim() || null })) };
}

/** Erreurs de saisie d'un plan avant envoi (le serveur reste juge). */
export function validatePlan(p: ControlPlan): string[] {
  const errs: string[] = [];
  if (!p.code.trim()) errs.push('Le code du plan est obligatoire.');
  if (!p.characteristics.length) errs.push('Ajoutez au moins une caractéristique.');
  p.characteristics.forEach((c, i) => {
    if (!c.code.trim() || !c.libelle.trim()) errs.push(`Caractéristique ${i + 1} : code et libellé obligatoires.`);
    if (c.type === 'mesure' && c.tolMin !== null && c.tolMax !== null && c.tolMin > c.tolMax) {
      errs.push(`Caractéristique ${c.code || i + 1} : tolérance min supérieure à la max.`);
    }
  });
  if (p.sampling.mode === 'fixe' && !(Number(p.sampling.taille) > 0)) errs.push("Taille d'échantillon fixe obligatoire.");
  if (p.point === 'PRODUCTION_ENCOURS' && !p.frequency?.pieces && !p.frequency?.minutes) {
    errs.push('En-cours : fréquence en pièces ou en minutes obligatoire.');
  }
  return errs;
}

export function samplingLabel(s: Sampling | null | undefined): string {
  if (!s) return '—';
  if (s.mode === 'fixe') return `Fixe : ${s.taille ?? '?'} pièce(s)`;
  return `ISO 2859-1 · niveau ${s.niveau ?? 'II'} · NQA ${String(s.nqa ?? '?').replace('.', ',')}`;
}

/* ===== Contrôles ===== */

export interface InspectionResult { characteristic: string; values: (number | string)[]; ok: boolean; instrument?: string | null }
export interface Inspection {
  plan?: ControlPlan | null;
  id: string; lotId: string; lotNumero?: string | null; controlPlanId: string; planVersion?: number | null; point: FlowPoint | string;
  status: string; sampleSize: number; codeLetter?: string | null; ac?: number | null; re?: number | null; lotSize?: number | null;
  results: InspectionResult[]; decision?: string | null; proposedDecision?: Decision | null; createdAt?: string | null; author?: string | null;
}

export function toInspection(raw: Raw): Inspection {
  const lot = raw['lot'] as Raw | undefined;
  const s = (raw['sampling'] ?? {}) as Raw;
  return {
    id: String(raw['id']),
    lotId: String(raw['lotId'] ?? lot?.['id'] ?? ''),
    lotNumero: str(raw['lotNumero'] ?? lot?.['numero']),
    controlPlanId: String(raw['controlPlanId'] ?? (raw['controlPlan'] as Raw | undefined)?.['id'] ?? ''),
    plan: raw['controlPlan'] ? toPlan(raw['controlPlan'] as Raw) : null,
    planVersion: (raw['planVersion'] ?? (raw['controlPlan'] as Raw | undefined)?.['version'] ?? null) as number | null,
    point: String(raw['point'] ?? ''),
    status: String(raw['status'] ?? 'A_FAIRE'),
    sampleSize: Number(s['sampleSize'] ?? raw['sampleSize'] ?? 1),
    codeLetter: str(s['planLetter'] ?? s['codeLetter'] ?? raw['codeLetter']),
    ac: (s['ac'] ?? raw['ac'] ?? null) as number | null,
    re: (s['re'] ?? raw['re'] ?? null) as number | null,
    lotSize: (s['lotSize'] ?? null) as number | null,
    proposedDecision: (raw['proposedDecision'] ?? null) as Decision | null,
    results: (raw['results'] ?? []) as InspectionResult[],
    decision: str(raw['decision']),
    createdAt: str(raw['createdAt']),
    author: str(raw['authorName'] ?? raw['author']),
  };
}

export const INSPECTION_STATUS_LABELS: Record<string, string> = { A_FAIRE: 'À faire', EN_COURS: 'En cours', TERMINEE: 'Terminée' };

/** Verdict d'une mesure : chaque valeur dans [tolMin, tolMax] (bornes absolues). */
export function measureOk(c: Characteristic, values: number[]): boolean {
  if (!values.length) return false;
  return values.every((v) => (c.tolMin === null || v >= c.tolMin) && (c.tolMax === null || v <= c.tolMax));
}

/** Nombre de pièces non conformes d'une mesure. */
export function measureDefects(c: Characteristic, values: number[]): number {
  return values.filter((v) => (c.tolMin !== null && v < c.tolMin) || (c.tolMax !== null && v > c.tolMax)).length;
}

/** Valeurs saisies « 12,01 ; 12.02 12,03 » → nombres. */
export function parseValues(text: string): number[] {
  return text.split(/[\s;]+/).map((t) => t.replace(',', '.')).filter((t) => t !== '').map(Number).filter((n) => Number.isFinite(n));
}

/**
 * Décision proposée : un défaut sur une caractéristique critique → REFUSE ; sinon nombre de non-conformes ≤ Ac → LIBERE,
 * ≥ Re → REFUSE, entre les deux → QUARANTAINE. Sans Ac/Re : tout conforme → LIBERE, sinon QUARANTAINE.
 */
export function proposeDecision(
  chars: Characteristic[], results: InspectionResult[], defects: number, ac: number | null | undefined, re: number | null | undefined,
): Decision {
  const ko = results.filter((r) => !r.ok);
  if (ko.some((r) => chars.find((c) => c.code === r.characteristic)?.criticite === 'critique')) return 'REFUSE';
  if (ac === null || ac === undefined) return ko.length ? 'QUARANTAINE' : 'LIBERE';
  if (defects <= ac) return 'LIBERE';
  if (re !== null && re !== undefined && defects >= re) return 'REFUSE';
  return 'QUARANTAINE';
}

/* ===== Carte de contrôle (X̄/R) ===== */

export interface Limits { lcl: number; cl: number; ucl: number }
export interface SpcPoint { index: number; mean: number; range: number; at?: string | null }
export interface SpcAlert { type: string; index: number; message?: string | null }
export interface Spc {
  points: SpcPoint[]; xbar: Limits | null; r: Limits | null; subgroupSize?: number | null; sigma?: number | null; cp: number | null; cpk: number | null; alerts: SpcAlert[];
  tolMin?: number | null; tolMax?: number | null;
}

export function toSpc(raw: Raw): Spc {
  const lim = (raw['limits'] ?? {}) as Raw;
  const pts = (raw['points'] ?? []) as Raw[];
  return {
    points: pts.map((p, i) => ({
      index: Number(p['index'] ?? p['subgroup'] ?? i + 1), mean: Number(p['mean'] ?? p['moyenne']), range: Number(p['range'] ?? p['etendue']),
      at: str(p['at']),
    })),
    xbar: (lim['xbar'] ?? raw['xbar'] ?? null) as Limits | null,
    r: (lim['range'] ?? lim['r'] ?? null) as Limits | null,
    subgroupSize: (raw['subgroupSize'] ?? null) as number | null,
    sigma: (raw['sigma'] ?? null) as number | null,
    cp: (raw['cp'] ?? null) as number | null,
    cpk: (raw['cpk'] ?? null) as number | null,
    alerts: (raw['alerts'] ?? []) as SpcAlert[],
    tolMin: (raw['tolMin'] ?? null) as number | null,
    tolMax: (raw['tolMax'] ?? null) as number | null,
  };
}

export interface ChartGeometry {
  width: number; height: number; pad: { l: number; r: number; t: number; b: number };
  x: (i: number) => number; y: (v: number) => number; min: number; max: number;
}

/** Échelle verticale englobant points, limites et tolérances, avec 8 % de marge. */
export function chartGeometry(values: number[], lines: number[], width = 900, height = 260): ChartGeometry {
  const all = [...values, ...lines].filter((v) => Number.isFinite(v));
  let min = Math.min(...all);
  let max = Math.max(...all);
  if (!all.length) { min = 0; max = 1; }
  if (min === max) { min -= 1; max += 1; }
  const m = (max - min) * 0.08;
  min -= m;
  max += m;
  const pad = { l: 64, r: 96, t: 14, b: 28 };
  const n = Math.max(values.length - 1, 1);
  return {
    width, height, pad, min, max,
    x: (i) => pad.l + (i * (width - pad.l - pad.r)) / n,
    y: (v) => pad.t + ((max - v) * (height - pad.t - pad.b)) / (max - min),
  };
}

/** Indices (0-based) des points hors limites ou signalés par une alerte du serveur. */
export function flaggedPoints(points: SpcPoint[], lim: Limits | null, alerts: SpcAlert[], value: (p: SpcPoint) => number): Set<number> {
  const out = new Set<number>();
  points.forEach((p, i) => {
    const v = value(p);
    if (lim && (v > lim.ucl || v < lim.lcl)) out.add(i);
    if (alerts.some((a) => a.index === p.index)) out.add(i);
  });
  return out;
}

export function fmt(n: number | null | undefined, digits = 3): string {
  if (n === null || n === undefined || !Number.isFinite(n)) return '—';
  return n.toLocaleString('fr-FR', { maximumFractionDigits: digits });
}

/* ===== Imports de données de base et connecteur ===== */

export interface ImportReport { created: number; updated: number; errors: { line: number; message: string }[] }
export interface WebhookConfig { url: string; secret?: string | null; enabled: boolean; secretSet?: boolean }
export interface WebhookDelivery { id?: string; event?: string; at?: string; status?: number | string | null; ok?: boolean; error?: string | null; [k: string]: unknown }
