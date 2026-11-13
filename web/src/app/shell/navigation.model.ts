export interface NavItem {
  label: string;
  path: string;
  /** Rôle(s) autorisé(s) : un seul suffit. */
  role?: string | string[];
}

export function canSee(i: NavItem, hasRole: (r: string) => boolean): boolean {
  return !i.role || [i.role].flat().some(hasRole);
}

export interface NavFamily {
  label: string;
  items: NavItem[];
}

/** Navigation en 5 familles (maquette v2). */
export const NAV_FAMILIES: NavFamily[] = [
  { label: 'Mon travail', items: [{ label: 'Mes actions', path: '/mes-actions' }] },
  {
    label: 'Terrain',
    items: [
      { label: 'Non-conformités', path: '/non-conformites' },
      { label: 'Incidents & sécurité', path: '/incidents' },
      { label: 'Audits & inspections', path: '/audits' },
      { label: 'Contrôles', path: '/controles' },
    ],
  },
  {
    label: 'Pilotage',
    items: [
      { label: 'Indicateurs', path: '/indicateurs' },
      { label: 'Risques', path: '/risques' },
      { label: 'Veille réglementaire', path: '/veille' },
    ],
  },
  {
    label: 'Ressources',
    items: [
      { label: 'Équipements', path: '/equipements' },
      { label: 'Documents', path: '/documents' },
      { label: 'Lots & traçabilité', path: '/lots' },
      { label: 'Clients & fournisseurs', path: '/partenaires' },
    ],
  },
  {
    label: 'Administration',
    items: [
      { label: 'Référentiels', path: '/parametrage/referentiels', role: ['ADMIN', 'QUALITY_MANAGER'] },
      { label: 'Échelles de risque', path: '/parametrage/echelles-risque', role: ['ADMIN', 'QUALITY_MANAGER'] },
      { label: "Grilles d'évaluation", path: '/parametrage/grilles-evaluation', role: ['ADMIN', 'QUALITY_MANAGER'] },
      { label: 'Plans de contrôle', path: '/parametrage/plans-controle', role: ['ADMIN', 'QUALITY_MANAGER'] },
      { label: 'Données de base & connecteurs', path: '/parametrage/donnees-base', role: 'ADMIN' },
      { label: 'Workflows', path: '/parametrage/workflows', role: 'ADMIN' },
      { label: 'Règles de notification', path: '/parametrage/notifications', role: 'ADMIN' },
      { label: 'Apparence', path: '/parametrage/apparence', role: 'ADMIN' },
    ],
  },
];
