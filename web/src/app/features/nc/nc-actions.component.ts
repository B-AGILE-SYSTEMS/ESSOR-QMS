import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ACTION_TYPES, ActionType, AvailableTransition, QmsRecord, RecordSummary, UserRef } from '../../core/api.models';
import { errorMessage } from '../../core/api-error';
import { ACTION_TYPE_LABELS, actionTypeOf, fieldLabel, formatDate, statusTone } from '../../core/labels';
import { RecordsService } from '../../core/records.service';
import { UserPickerComponent } from './user-picker.component';

interface ActionRow {
  summary: RecordSummary;
  transitions: AvailableTransition[];
}

/** Onglet Actions : plan d'actions (fiches ACTION enfants), ajout et suivi du statut. */
@Component({
  selector: 'eq-nc-actions',
  standalone: true,
  imports: [FormsModule, UserPickerComponent],
  template: `
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    <div class="box tw">
      <table>
        <thead><tr><th>Type</th><th>Action</th><th>Responsable</th><th>Échéance</th><th>Statut</th><th>Suivi</th></tr></thead>
        <tbody>
          @for (a of rows(); track a.summary.id) {
            <tr>
              <td>{{ typeLabel(a.summary) }}</td>
              <td><span class="mono ref">{{ a.summary.reference }}</span> {{ a.summary.title }}</td>
              <td>{{ a.summary.ownerName ?? '—' }}</td>
              <td class="mono">{{ date(a.summary.dueDate) }}</td>
              <td><span [class]="'st ' + tone(a.summary.status)"><i></i>{{ a.summary.statusLabel }}</span></td>
              <td class="acts">
                @for (t of a.transitions; track t.code) {
                  <button type="button" class="btn" [disabled]="busy() || t.missing.length > 0"
                    [attr.title]="t.missing.length ? 'Manque : ' + missing(t) : null" (click)="move(a, t)">{{ t.label }}</button>
                }
              </td>
            </tr>
          } @empty {
            <tr><td colspan="6" class="note">Aucune action pour l'instant.</td></tr>
          }
        </tbody>
      </table>
    </div>
    @if (canAdd()) {
      <form class="box pad add" (ngSubmit)="add()">
        <h3 class="t">Ajouter une action</h3>
        <div class="grid">
          <label class="field"><span>Type</span>
            <select name="actionType" [(ngModel)]="actionType">
              @for (t of types; track t) { <option [value]="t">{{ typeLabels[t] }}</option> }
            </select>
          </label>
          <label class="field wide"><span>Intitulé *</span><input name="title" [(ngModel)]="title" required maxlength="200" /></label>
          <div class="field"><span>Responsable *</span><eq-user-picker (picked)="owner.set($event)" /></div>
          <label class="field"><span>Échéance *</span><input type="date" name="dueDate" [(ngModel)]="dueDate" required /></label>
        </div>
        <button type="submit" class="btn pri" [disabled]="busy()">＋ Ajouter l'action</button>
      </form>
    }
  `,
  styles: `
    .ref { color: var(--ink2); font-size: 12px; }
    .acts { display: flex; gap: 6px; flex-wrap: wrap; }
    .acts .btn { min-height: 32px; padding: 4px 10px; font-size: 13px; }
    .add { margin-top: 14px; }
    .t { margin: 0 0 10px; font-size: 13px; text-transform: uppercase; letter-spacing: .08em; color: var(--ink2); }
    .grid { display: grid; grid-template-columns: 1fr 2fr 1.4fr 1fr; gap: 12px; }
    @media (max-width: 860px) { .grid { grid-template-columns: 1fr; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NcActionsComponent {
  private readonly records = inject(RecordsService);
  readonly record = input.required<QmsRecord>();
  readonly canAdd = input(true);
  readonly changed = output<void>();

  protected readonly types = ACTION_TYPES;
  protected readonly typeLabels = ACTION_TYPE_LABELS;
  protected readonly date = formatDate;
  protected readonly tone = statusTone;
  protected readonly rows = signal<ActionRow[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly owner = signal<UserRef | null>(null);
  protected actionType: ActionType = 'corrective';
  protected title = '';
  protected dueDate = '';

  constructor() {
    effect(() => {
      const children = this.record().children ?? [];
      untracked(() => void this.loadRows(children));
    });
  }

  /** Détail de chaque action pour connaître ses transitions possibles. */
  private async loadRows(children: RecordSummary[]): Promise<void> {
    const actions = children.filter((c) => c.type === 'ACTION');
    this.rows.set(actions.map((summary) => ({ summary, transitions: [] })));
    const details = await Promise.all(actions.map((c) => this.records.get(c.id).catch(() => null)));
    this.rows.set(actions.map((summary, i) => ({ summary: details[i] ?? summary, transitions: details[i]?.availableTransitions ?? [] })));
  }

  protected typeLabel(r: RecordSummary): string {
    const t = actionTypeOf(r);
    return t ? ACTION_TYPE_LABELS[t] : '—';
  }

  protected missing(t: AvailableTransition): string {
    return t.missing.map(fieldLabel).join(', ');
  }

  async move(a: ActionRow, t: AvailableTransition): Promise<void> {
    await this.run(() => this.records.transition(a.summary.id, t.code, ''));
  }

  async add(): Promise<void> {
    const owner = this.owner();
    if (!this.title.trim() || !owner || !this.dueDate) {
      this.error.set("Renseignez l'intitulé, le responsable et l'échéance.");
      return;
    }
    const ok = await this.run(() =>
      this.records.addAction(this.record().id, { actionType: this.actionType, title: this.title.trim(), ownerId: owner.id, dueDate: this.dueDate }),
    );
    if (ok) {
      this.title = '';
      this.dueDate = '';
    }
  }

  private async run(op: () => Promise<unknown>): Promise<boolean> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await op();
      this.changed.emit();
      return true;
    } catch (e) {
      this.error.set(errorMessage(e));
      return false;
    } finally {
      this.busy.set(false);
    }
  }
}
