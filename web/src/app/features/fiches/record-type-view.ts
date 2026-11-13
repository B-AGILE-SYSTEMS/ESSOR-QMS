/**
 * Libellés d'affichage d'un type de fiche. Aucune logique métier par type : la fiche, la liste et le workflow
 * viennent du moteur générique ; seuls les mots changent. Passé par `data.view` de la route.
 */
/** Onglets de la fiche générique. */
export type Tab = 'description' | 'analyse' | 'actions' | 'pieces' | 'exigences' | 'grille' | 'versions' | 'lecture' | 'historique';

export const TAB_LABELS: Record<Tab, string> = {
  description: 'Description', analyse: 'Analyse', actions: 'Actions', pieces: 'Pièces jointes', exigences: 'Exigences',
  grille: "Grille d'audit", versions: 'Versions', lecture: 'Diffusion & lecture', historique: 'Historique',
};

export const DEFAULT_TABS: Tab[] = ['description', 'analyse', 'actions', 'pieces', 'exigences', 'historique'];

export interface RecordTypeView {
  code: string;
  /** Chemin de base des routes (`/non-conformites`). */
  path: string;
  plural: string;
  /** Nom au singulier, minuscule (« non-conformité »). */
  singular: string;
  /** Accord des participes (« Déclarée » / « Déclaré »). */
  feminine: boolean;
  /** Chemin de déclaration web, s'il existe. */
  declarePath?: string;
  /** Champs `data` affichés dans la description, avec leur libellé. */
  dataFields: { key: string; label: string }[];
  /** Onglets affichés (défaut : ceux des NC). */
  tabs?: Tab[];
  /** Onglet ouvert selon l'étape (prioritaire sur la règle NC). */
  startTab?: Record<string, Tab>;
  /** Le type porte une gravité (défaut : oui). */
  severity?: boolean;
}

export const NC_VIEW: RecordTypeView = {
  code: 'NC', path: '/non-conformites', plural: 'Non-conformités', singular: 'non-conformité', feminine: true,
  declarePath: '/non-conformites/declarer', dataFields: [],
};

export const INCIDENT_CATEGORIES: Record<string, string> = {
  accident_travail: 'Accident du travail',
  presqu_accident: "Presqu'accident",
  situation_dangereuse: 'Situation dangereuse',
  environnement: 'Environnement',
  materiel: 'Matériel',
};

export const INCIDENT_VIEW: RecordTypeView = {
  code: 'INCIDENT', path: '/incidents', plural: 'Incidents & sécurité', singular: 'incident', feminine: false,
  dataFields: [
    { key: 'categorie', label: 'Catégorie' },
    { key: 'lieu', label: 'Lieu' },
    { key: 'blesses', label: 'Blessés' },
    { key: 'temoins', label: 'Témoins' },
  ],
};

export const AUDIT_VIEW: RecordTypeView = {
  code: 'AUDIT', path: '/audits', plural: 'Audits & inspections', singular: 'audit', feminine: false, severity: false,
  tabs: ['description', 'grille', 'pieces', 'historique'],
  startTab: { EN_COURS: 'grille', RAPPORT: 'grille', CLOTURE: 'grille' },
  dataFields: [
    { key: 'plannedDate', label: 'Date prévue' },
    { key: 'perimetre', label: 'Périmètre' },
    { key: 'auditeurs', label: 'Auditeur(s)' },
    { key: 'audites', label: 'Audités' },
  ],
};

export const DOCUMENT_VIEW: RecordTypeView = {
  code: 'DOCUMENT', path: '/documents', plural: 'Documents', singular: 'document', feminine: false, severity: false,
  tabs: ['description', 'versions', 'lecture', 'exigences', 'historique'],
  startTab: { REDACTION: 'versions', VERIFICATION: 'versions', APPROBATION: 'versions', DIFFUSE: 'lecture' },
  dataFields: [
    { key: 'code', label: 'Code' },
    { key: 'docType', label: 'Type' },
    { key: 'processus', label: 'Processus' },
    { key: 'reviewDate', label: 'Date de revue' },
  ],
};

const DOC_TYPES_FR: Record<string, string> = {
  procedure: 'Procédure', instruction: 'Instruction', formulaire: 'Formulaire', enregistrement: 'Enregistrement',
};

/** Valeur lisible d'un champ `data` (booléens, catégories d'incident, listes). */
export function dataValue(key: string, v: unknown): string {
  if (v === undefined || v === null || v === '') return '—';
  if (typeof v === 'boolean') return v ? 'Oui' : 'Non';
  if (key === 'categorie' && typeof v === 'string') return INCIDENT_CATEGORIES[v] ?? v;
  if (key === 'docType' && typeof v === 'string') return DOC_TYPES_FR[v] ?? v;
  if ((key === 'plannedDate' || key === 'reviewDate') && typeof v === 'string' && /^\d{4}-\d{2}-\d{2}/.test(v)) {
    return `${v.slice(8, 10)}/${v.slice(5, 7)}/${v.slice(0, 4)}`;
  }
  if (Array.isArray(v)) return v.map(String).join(', ') || '—';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

/** Vrai si la fiche a été créée depuis le mobile (le mobile envoie toujours `clientId`). */
export function fromMobile(r: { clientId?: string | null }): boolean {
  return !!r.clientId;
}
