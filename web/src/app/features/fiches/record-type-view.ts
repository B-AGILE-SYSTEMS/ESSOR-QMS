/**
 * Libellés d'affichage d'un type de fiche. Aucune logique métier par type : la fiche, la liste et le workflow
 * viennent du moteur générique ; seuls les mots changent. Passé par `data.view` de la route.
 */
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

/** Valeur lisible d'un champ `data` (booléens, catégories d'incident, listes). */
export function dataValue(key: string, v: unknown): string {
  if (v === undefined || v === null || v === '') return '—';
  if (typeof v === 'boolean') return v ? 'Oui' : 'Non';
  if (key === 'categorie' && typeof v === 'string') return INCIDENT_CATEGORIES[v] ?? v;
  if (Array.isArray(v)) return v.map(String).join(', ') || '—';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

/** Vrai si la fiche a été créée depuis le mobile (le mobile envoie toujours `clientId`). */
export function fromMobile(r: { clientId?: string | null }): boolean {
  return !!r.clientId;
}
