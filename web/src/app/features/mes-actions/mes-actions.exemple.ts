/* DONNÉES D'EXEMPLE — affichées comme telles à l'écran. Remplacées par l'API en M1. */
export type Filtre = 'a-faire' | 'en-retard' | 'a-valider';
export type Statut = 'crit' | 'warn' | 'info' | 'ok';

export interface ExempleAction {
  echeance: string;
  titre: string;
  detail?: string;
  origine: string;
  site: string;
  statut: Statut;
  statutLabel: string;
  filtres: Filtre[];
}

export const EXEMPLE_KPIS = [
  { k: 'Actions en retard', v: '2', d: 'dont 1 sécurité — à traiter aujourd’hui', dom: true },
  { k: 'NC ouvertes', v: '14', d: '5 à qualifier', dom: false },
  { k: 'Audits du mois', v: '6/9', d: 'réalisés', dom: false },
  { k: 'Coût NC (mois)', v: '48 300', d: 'MAD', dom: false },
];

export const EXEMPLE_ACTIONS: ExempleAction[] = [
  { echeance: "Aujourd'hui", titre: 'Qualifier NC-2026-0418', detail: 'Écart de cote Ø 12,05 mm, lot 2611-B', origine: 'NC-2026-0418', site: 'Kénitra', statut: 'crit', statutLabel: 'Bloquant', filtres: ['a-faire', 'a-valider'] },
  { echeance: '10/11', titre: 'Remplacer garde-corps quai 4', origine: 'NC-2026-0402', site: 'Entrepôt Nord', statut: 'crit', statutLabel: 'En retard 2 j', filtres: ['a-faire', 'en-retard'] },
  { echeance: '14/11', titre: 'Analyse 5 pourquoi — fuite hydraulique', origine: 'NC-2026-0411', site: 'Casablanca', statut: 'warn', statutLabel: 'En cours', filtres: ['a-faire'] },
  { echeance: '18/11', titre: 'Vérifier efficacité — étiquetage FIFO', origine: 'NC-2026-0377', site: 'Entrepôt Nord', statut: 'info', statutLabel: 'À vérifier', filtres: ['a-faire', 'a-valider'] },
  { echeance: '21/11', titre: 'Audit interne ISO 9001 §8.5 — réception', origine: 'AUD-2026-031', site: 'Kénitra', statut: 'ok', statutLabel: 'Planifié', filtres: ['a-faire'] },
];
