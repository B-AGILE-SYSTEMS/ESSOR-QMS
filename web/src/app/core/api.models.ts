/** Types du contrat d'interface M1 (docs/pilotage/cartes/M1-contrat.md). */

export const SEVERITIES = ['mineure', 'majeure', 'critique'] as const;
export type Severity = (typeof SEVERITIES)[number];

export const ACTION_TYPES = ['curative', 'corrective', 'preventive'] as const;
export type ActionType = (typeof ACTION_TYPES)[number];

export interface WorkflowState {
  code: string;
  label: string;
  final: boolean;
}

export interface WorkflowTransition {
  code: string;
  label: string;
  from: string;
  to: string;
  roles: string[];
  requires?: string[];
}

export interface Workflow {
  initial: string;
  states: WorkflowState[];
  transitions: WorkflowTransition[];
}

export interface RecordType {
  code: string;
  label: string;
  prefix?: string;
}

export interface AvailableTransition {
  code: string;
  label: string;
  missing: string[];
}

export interface RecordSummary {
  id: string;
  reference: string;
  type: string;
  status: string;
  statusLabel: string;
  title: string;
  severity?: Severity | null;
  siteId?: string | null;
  siteName?: string | null;
  ownerId?: string | null;
  ownerName?: string | null;
  dueDate?: string | null;
  createdAt?: string;
  /** Fiches ACTION : curative | corrective | preventive (dans `actionType` ou `data.actionType`). */
  actionType?: ActionType | null;
  data?: RecordData;
  /** M2 : UUID généré par le mobile à la création ; présent ⇒ fiche déclarée depuis le mobile. */
  clientId?: string | null;
  /** M2 : date terrain de l'événement (distincte de `createdAt`). */
  occurredAt?: string | null;
}

export type RecordData = Record<string, unknown>;

export interface QmsRecord extends RecordSummary {
  description: string | null;
  data: RecordData;
  createdBy: string;
  /** Nom lisible du déclarant (display_name, à défaut username). */
  createdByName?: string | null;
  availableTransitions: AvailableTransition[];
  children: RecordSummary[];
}

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface RecordQuery {
  type: string;
  status?: string;
  severity?: string;
  siteId?: string;
  q?: string;
  page?: number;
  size?: number;
}

export interface NewRecord {
  type: string;
  siteId: string;
  title: string;
  description: string;
  severity?: Severity;
  data: RecordData;
}

export type RecordPatch = Partial<Pick<QmsRecord, 'title' | 'description' | 'severity' | 'ownerId' | 'dueDate' | 'data'>>;

export interface NewAction {
  actionType: ActionType;
  title: string;
  ownerId: string;
  dueDate: string;
}

/** Entrée d'historique (`audit_log`). Le contrat fixe le contenu, pas les noms : lecture tolérante. */
export interface HistoryEntry {
  id?: string;
  at?: string;
  createdAt?: string;
  author?: string;
  authorName?: string;
  action?: string;
  transition?: string | null;
  comment?: string | null;
  diff?: Record<string, unknown> | null;
}

export interface MyTask extends RecordSummary {
  overdue: boolean;
  recordType?: string | null;
}

export interface UserRef {
  id: string;
  displayName: string;
  username?: string;
}

export interface DashboardSummary {
  ncOpen: number;
  ncToQualify: number;
  actionsOverdue: number;
  ncCostMonth: number;
  /** M4 : taux de conformité réglementaire (%), étalonnages sous 15 jours et échus. */
  regulatoryCompliance?: number | null;
  calibrationsDue15?: number | null;
  calibrationsOverdue?: number | null;
}

export interface AppNotification {
  id: string;
  type: string;
  title: string;
  recordId?: string | null;
  recordType?: string | null;
  link?: string | null;
  read: boolean;
  createdAt: string;
}

/** Règle « événement × canal » (`notification_rule`). */
export interface NotificationRule {
  event: string;
  channel: string;
  enabled: boolean;
}

/** Pièce jointe (contrat M2). */
export interface Attachment {
  id: string;
  fileName: string;
  contentType: string;
  size: number;
  createdAt: string;
  createdByName: string | null;
}

export const ATTACHMENT_TYPES = ['image/jpeg', 'image/png', 'application/pdf'] as const;
export const ATTACHMENT_MAX_BYTES = 10 * 1024 * 1024;

export interface ApiError {
  error: string;
  message: string;
  details?: Record<string, unknown>;
}

const RECORD_BASES: Record<string, string> = { INCIDENT: '/incidents', AUDIT: '/audits', DOCUMENT: '/documents' };

/** Chemin de la fiche selon son type (INCIDENT → /incidents, AUDIT → /audits, DOCUMENT → /documents, sinon NC). */
export function recordPath(type: string | null | undefined, id: string): string {
  const base = RECORD_BASES[type ?? ''] ?? '/non-conformites';
  return `${base}/${encodeURIComponent(id)}`;
}
