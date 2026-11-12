import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  AvailableTransition, HistoryEntry, QmsRecord, RecordData, RecordPatch, SEVERITIES, Severity, UserRef, Workflow,
} from '../../core/api.models';
import { errorMessage } from '../../core/api-error';
import { SEVERITY_LABELS, fieldLabel, formatDate, formatDateTime, statusTone } from '../../core/labels';
import { RecordsService } from '../../core/records.service';
import { AttachmentsComponent } from '../fiches/attachments.component';
import { NC_VIEW, RecordTypeView, dataValue, fromMobile } from '../fiches/record-type-view';
import { NcActionsComponent } from './nc-actions.component';
import { NcAnalyseComponent } from './nc-analyse.component';
import { buildSteps } from './stepper';
import { UserPickerComponent } from './user-picker.component';

export type Tab = 'description' | 'analyse' | 'actions' | 'pieces' | 'historique';

/** Onglet ouvert par défaut selon l'étape de la fiche. */
export function defaultTab(status: string): Tab {
  if (status === 'ANALYSE' || status === 'EN_ANALYSE') return 'analyse';
  if (status === 'ACTIONS' || status === 'VERIFICATION') return 'actions';
  return 'description';
}

const LOCKED = new Set(['CLOTUREE', 'REJETEE', 'CLOS', 'CLASSE_SANS_SUITE']);

/** Fiche générique (NC, incident…) : barre d'étapes, onglets, transitions issues de `availableTransitions`. */
@Component({
    selector: 'eq-nc-fiche',
    imports: [FormsModule, NcAnalyseComponent, NcActionsComponent, UserPickerComponent, AttachmentsComponent],
    templateUrl: './nc-fiche.component.html',
    styleUrl: './nc-fiche.component.scss',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NcFicheComponent {
  private readonly records = inject(RecordsService);
  readonly id = input.required<string>();
  /** Libellés du type (route `data.view`). */
  readonly view = input<RecordTypeView>(NC_VIEW);

  protected readonly record = signal<QmsRecord | null>(null);
  protected readonly workflow = signal<Workflow | null>(null);
  protected readonly history = signal<HistoryEntry[]>([]);
  protected readonly tab = signal<Tab>('description');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected comment = '';

  protected readonly steps = computed(() => buildSteps(this.workflow(), this.record()?.status ?? ''));
  protected readonly locked = computed(() => {
    const st = this.record()?.status ?? '';
    return LOCKED.has(st) || !!this.workflow()?.states.find((s) => s.code === st)?.final;
  });
  protected readonly mobile = computed(() => !!this.record() && fromMobile(this.record()!));
  protected readonly participle = computed(() => (this.view().feminine ? 'Déclarée' : 'Déclaré'));
  protected readonly value = dataValue;
  protected readonly tabs: { id: Tab; label: string }[] = [
    { id: 'description', label: 'Description' },
    { id: 'analyse', label: 'Analyse' },
    { id: 'actions', label: 'Actions' },
    { id: 'pieces', label: 'Pièces jointes' },
    { id: 'historique', label: 'Historique' },
  ];
  protected readonly severities = SEVERITIES;
  protected readonly sevLabel = SEVERITY_LABELS;
  protected readonly date = formatDate;
  protected readonly dateTime = formatDateTime;
  protected readonly tone = statusTone;

  // Brouillon de l'onglet Description.
  protected draft: { title: string; description: string; severity: Severity | ''; dueDate: string } = {
    title: '', description: '', severity: '', dueDate: '',
  };
  protected readonly newOwner = signal<UserRef | null>(null);

  constructor() {
    effect(() => {
      const code = this.view().code;
      untracked(() => this.records.workflow(code).then((wf) => this.workflow.set(wf), () => undefined));
    });
    effect(() => {
      const id = this.id();
      untracked(() => void this.load(id, true));
    });
  }

  async load(id = this.id(), first = false): Promise<void> {
    try {
      const rec = await this.records.get(id);
      this.setRecord(rec);
      if (first) this.tab.set(defaultTab(rec.status));
      this.loadError.set(null);
      if (this.tab() === 'historique') await this.loadHistory();
    } catch (e) {
      this.loadError.set(errorMessage(e, 'Fiche introuvable.'));
    }
  }

  private setRecord(rec: QmsRecord): void {
    this.record.set(rec);
    this.draft = {
      title: rec.title, description: rec.description ?? '', severity: rec.severity ?? '', dueDate: (rec.dueDate ?? '').slice(0, 10),
    };
    this.newOwner.set(null);
  }

  protected async selectTab(t: Tab): Promise<void> {
    this.tab.set(t);
    if (t === 'historique') await this.loadHistory();
  }

  private async loadHistory(): Promise<void> {
    try {
      this.history.set(await this.records.history(this.id()));
    } catch (e) {
      this.error.set(errorMessage(e, "Impossible de charger l'historique."));
    }
  }

  protected missing(t: AvailableTransition): string {
    return t.missing.map(fieldLabel).join(', ');
  }

  async transition(t: AvailableTransition): Promise<void> {
    const rec = this.record();
    if (!rec || t.missing.length) return;
    await this.run(async () => {
      this.setRecord(await this.records.transition(rec.id, t.code, this.comment.trim()));
      this.comment = '';
      this.info.set(`Transition « ${t.label} » effectuée.`);
      this.tab.set(defaultTab(this.record()!.status));
    });
  }

  async saveDescription(): Promise<void> {
    const patch: RecordPatch = {
      title: this.draft.title.trim(), description: this.draft.description.trim(), dueDate: this.draft.dueDate || null,
    };
    if (this.draft.severity) patch.severity = this.draft.severity;
    const owner = this.newOwner();
    if (owner) patch.ownerId = owner.id;
    await this.patch(patch, 'Fiche enregistrée.');
  }

  async saveData(data: RecordData): Promise<void> {
    await this.patch({ data }, 'Analyse enregistrée.');
  }

  private async patch(body: RecordPatch, okMsg: string): Promise<void> {
    const rec = this.record();
    if (!rec) return;
    await this.run(async () => {
      this.setRecord(await this.records.patch(rec.id, body));
      this.info.set(okMsg);
    });
  }

  private async run(op: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.info.set(null);
    try {
      await op();
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.busy.set(false);
    }
  }

  protected historyLabel(h: HistoryEntry): string {
    return h.transition ?? h.action ?? '—';
  }

  protected diffKeys(h: HistoryEntry): string[] {
    return h.diff ? Object.keys(h.diff).map(fieldLabel) : [];
  }
}
