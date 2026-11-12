export interface NavItem {
  label: string;
  path: string;
  role?: string;
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
    ],
  },
  {
    label: 'Pilotage',
    items: [
      { label: 'Indicateurs', path: '/indicateurs' },
      { label: 'Risques & conformité', path: '/risques' },
    ],
  },
  {
    label: 'Ressources',
    items: [
      { label: 'Équipements', path: '/equipements' },
      { label: 'Documents', path: '/documents' },
    ],
  },
  {
    label: 'Administration',
    items: [
      { label: 'Workflows', path: '/parametrage/workflows', role: 'ADMIN' },
      { label: 'Règles de notification', path: '/parametrage/notifications', role: 'ADMIN' },
      { label: 'Apparence', path: '/parametrage/apparence', role: 'ADMIN' },
    ],
  },
];
