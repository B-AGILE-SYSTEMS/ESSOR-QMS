/**
 * Types du contrat M3 (docs/pilotage/cartes/M3-contrat.md) : référentiels, audits, gestion documentaire.
 * Le contrat fixe les routes et les champs métier ; les formes de réponse non précisées sont lues de façon tolérante
 * (voir web/README.md, « Hypothèses de format M3 »).
 */
import { AvailableTransition, RecordSummary } from './api.models';

export interface Standard {
  id: string;
  code: string;
  title: string;
  version?: string | null;
}

export interface Requirement {
  id: string;
  code: string;
  title: string;
  text?: string | null;
  parentId?: string | null;
  order?: number | null;
  auditable: boolean;
  children?: Requirement[];
}

export interface ImportReport {
  standardId?: string | null;
  /** Faux en simulation ou dès qu'une erreur annule l'import (tout ou rien). */
  applied?: boolean;
  dryRun?: boolean;
  created: number;
  updated: number;
  errors: { line: number; message: string }[];
}

/** Lien vers une fiche (document, NC). */
export interface RecordLink {
  id: string;
  reference: string;
  title: string;
  type?: string | null;
  statusLabel?: string | null;
}

/** Constat d'audit rattaché à une exigence. */
export interface FindingLink {
  auditId: string;
  auditReference?: string | null;
  result: AnswerResult;
  comment?: string | null;
}

export interface RequirementDetail extends Requirement {
  standardId?: string | null;
  standardCode?: string | null;
  documents?: RecordLink[];
  /** Fiches liées (NC, incidents…). */
  records?: RecordLink[];
  findings?: FindingLink[];
}

export const ANSWER_RESULTS = ['conforme', 'ecart_mineur', 'ecart_majeur', 'observation', 'na'] as const;
export type AnswerResult = (typeof ANSWER_RESULTS)[number];
export const ANSWER_LABELS: Record<AnswerResult, string> = {
  conforme: 'Conforme',
  ecart_mineur: 'Écart mineur',
  ecart_majeur: 'Écart majeur',
  observation: 'Observation',
  na: 'N/A',
};

export interface AuditQuestion {
  id: string;
  requirementId?: string | null;
  requirementCode?: string | null;
  requirementTitle?: string | null;
  question: string;
  order?: number | null;
  result?: AnswerResult | null;
  comment?: string | null;
  evidence?: string | null;
  /** NC créée pour un écart. */
  ncId?: string | null;
  ncReference?: string | null;
  closed?: boolean;
}

/** `GET /api/audits/{id}` : la fiche audit et sa grille (`questions`). */
export interface AuditDetail {
  id: string;
  questions?: AuditQuestion[];
  checklist?: AuditQuestion[];
}

export interface Answer {
  result: AnswerResult;
  comment: string;
  evidence: string;
  /** Question close : un écart crée aussitôt sa NC (sinon à `rediger_rapport`). */
  closed?: boolean;
}

/** Audit du programme annuel (fiche AUDIT ; date prévue en tête ou dans `data.plannedDate`). */
export interface AuditSummary extends RecordSummary {
  plannedDate?: string | null;
}

export const DOC_TYPES = ['procedure', 'instruction', 'formulaire', 'enregistrement'] as const;
export type DocType = (typeof DOC_TYPES)[number];
export const DOC_TYPE_LABELS: Record<DocType, string> = {
  procedure: 'Procédure',
  instruction: 'Instruction',
  formulaire: 'Formulaire',
  enregistrement: 'Enregistrement',
};

/** Version en vigueur portée par chaque élément de `GET /api/documents` (précision du 13/11). */
export interface CurrentVersion {
  id: string;
  number: string | number;
  fileName: string;
}

export interface DocumentSummary extends RecordSummary {
  currentVersion?: CurrentVersion | null;
}

export interface DocVersion {
  id?: string;
  /** Numéro de version, utilisé comme `{v}` dans les routes `/versions/{v}/…`. */
  number: string;
  status: string;
  statusLabel?: string | null;
  note?: string | null;
  fileName: string;
  contentType: string;
  size: number;
  createdAt: string;
  createdByName?: string | null;
  current?: boolean;
  availableTransitions?: AvailableTransition[];
}

/** Transitions de version (`POST /api/documents/{id}/versions/{n}/transitions/{code}`) si le serveur ne les liste pas. */
export const VERSION_TRANSITIONS: Record<string, { code: string; label: string }[]> = {
  REDACTION: [{ code: 'soumettre', label: 'Soumettre à vérification' }],
  VERIFICATION: [{ code: 'verifier', label: 'Vérifier' }, { code: 'refuser_verification', label: 'Refuser (vérification)' }],
  APPROBATION: [{ code: 'approuver', label: 'Approuver et diffuser' }, { code: 'refuser_approbation', label: 'Refuser (approbation)' }],
  DIFFUSE: [{ code: 'archiver', label: 'Archiver' }],
};

export function versionTransitions(v: DocVersion): AvailableTransition[] {
  return v.availableTransitions ?? (VERSION_TRANSITIONS[v.status] ?? []).map((t) => ({ ...t, missing: [] }));
}

export interface AckEntry {
  userId: string;
  displayName: string;
  at?: string | null;
}

export interface AckStatus {
  read: AckEntry[];
  pending: AckEntry[];
}

export const DOC_FILE_TYPES = [
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
] as const;
export const DOC_FILE_EXT = /\.(pdf|docx|xlsx)$/i;
export const DOC_MAX_BYTES = 20 * 1024 * 1024;

/** Liste ou page : le contrat ne précise pas l'enveloppe des listes M3. */
export function itemsOf<T>(res: T[] | { items: T[] } | null | undefined): T[] {
  if (!res) return [];
  return Array.isArray(res) ? res : (res.items ?? []);
}

/** Arbre des exigences : accepte un arbre (`children`) ou une liste plate (`parentId`), trié par `order` puis code. */
export function buildTree(nodes: Requirement[]): Requirement[] {
  const nested = nodes.some((n) => n.children?.length);
  if (nested) return sortTree(nodes);
  const byId = new Map(nodes.map((n) => [n.id, { ...n, children: [] as Requirement[] }]));
  const roots: Requirement[] = [];
  for (const n of byId.values()) {
    const parent = n.parentId ? byId.get(n.parentId) : undefined;
    if (parent) parent.children!.push(n);
    else roots.push(n);
  }
  return sortTree(roots);
}

function sortTree(nodes: Requirement[]): Requirement[] {
  return [...nodes]
    .sort((a, b) => (a.order ?? 0) - (b.order ?? 0) || a.code.localeCompare(b.code, 'fr', { numeric: true }))
    .map((n) => ({ ...n, children: sortTree(n.children ?? []) }));
}

/** Chapitre d'une exigence (« 8.1.2 » → « 8 »). */
export function chapterOf(code: string | null | undefined): string {
  return (code ?? '').split(/[.§ ]/).filter(Boolean)[0] ?? '—';
}

/** Taux de conformité par chapitre (questions répondues hors N/A). */
export function conformityByChapter(qs: AuditQuestion[]): { chapter: string; rate: number | null; answered: number; total: number }[] {
  const map = new Map<string, AuditQuestion[]>();
  for (const q of qs) {
    const c = chapterOf(q.requirementCode);
    map.set(c, [...(map.get(c) ?? []), q]);
  }
  return [...map.entries()]
    .sort(([a], [b]) => a.localeCompare(b, 'fr', { numeric: true }))
    .map(([chapter, list]) => {
      const scored = list.filter((q) => q.result && q.result !== 'na' && q.result !== 'observation');
      const ok = scored.filter((q) => q.result === 'conforme').length;
      return {
        chapter,
        rate: scored.length ? Math.round((ok / scored.length) * 100) : null,
        answered: list.filter((q) => !!q.result).length,
        total: list.length,
      };
    });
}

export function isGap(r: AnswerResult | null | undefined): boolean {
  return r === 'ecart_mineur' || r === 'ecart_majeur';
}

export function plannedDateOf(a: AuditSummary): string | null {
  return a.plannedDate ?? (a.data?.['plannedDate'] as string | undefined) ?? a.dueDate ?? null;
}

/** Libellé d'un référentiel (« ISO 45001:2018 »). */
export function standardLabel(s: Standard): string {
  return s.version ? `${s.title}:${s.version}` : s.title;
}
