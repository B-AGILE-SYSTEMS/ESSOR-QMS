import { ActionType, RecordSummary, Severity } from './api.models';

export const SEVERITY_LABELS: Record<Severity, string> = { mineure: 'Mineure', majeure: 'Majeure', critique: 'Critique' };
export const ACTION_TYPE_LABELS: Record<ActionType, string> = {
  curative: 'Curative',
  corrective: 'Corrective',
  preventive: 'Préventive',
};

/** Libellés des champs (pour « ce qui manque » renvoyé par le serveur). */
const FIELD_LABELS: Record<string, string> = {
  title: 'Titre',
  description: 'Description',
  severity: 'Gravité',
  ownerId: 'Responsable',
  dueDate: 'Échéance',
  siteId: 'Site',
  causeRacine: 'Cause racine',
  analyse5p: 'Analyse 5 pourquoi',
  ishikawa: 'Diagramme 6M',
  actions: 'Actions',
  efficacite: "Vérification d'efficacité",
};

export function fieldLabel(code: string): string {
  const key = code.startsWith('data.') ? code.slice(5) : code;
  return FIELD_LABELS[key] ?? code;
}

export function actionTypeOf(r: RecordSummary): ActionType | null {
  const t = r.actionType ?? (r.data?.['actionType'] as ActionType | undefined);
  return t && t in ACTION_TYPE_LABELS ? t : null;
}

/** Classe visuelle (`.st`) d'un statut de fiche. */
export function statusTone(status: string, overdue = false): 'crit' | 'warn' | 'info' | 'ok' {
  if (overdue) return 'crit';
  if (['CLOTUREE', 'FAITE', 'VERIFIEE'].includes(status)) return 'ok';
  if (['DECLAREE', 'REJETEE'].includes(status)) return 'crit';
  if (['VERIFICATION', 'A_FAIRE'].includes(status)) return 'info';
  return 'warn';
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short' }).format(d);
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? iso
    : new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short', timeStyle: 'short' }).format(d);
}

export function formatMad(n: number | null | undefined): string {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(n ?? 0);
}
