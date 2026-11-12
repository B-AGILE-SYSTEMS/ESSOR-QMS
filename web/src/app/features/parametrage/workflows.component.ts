import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RecordType, Workflow, WorkflowState, WorkflowTransition } from '../../core/api.models';
import { errorMessage } from '../../core/api-error';
import { RecordsService } from '../../core/records.service';

/** Contrôle de forme minimal côté client ; la validation qui fait foi est celle du serveur (PUT). */
export function parseWorkflow(text: string): { wf: Workflow | null; error: string | null } {
  let v: unknown;
  try {
    v = JSON.parse(text);
  } catch (e) {
    return { wf: null, error: `JSON invalide : ${(e as Error).message}` };
  }
  const o = v as Partial<Workflow> | null;
  if (!o || typeof o !== 'object' || typeof o.initial !== 'string' || !Array.isArray(o.states) || !Array.isArray(o.transitions)) {
    return { wf: null, error: 'Structure attendue : { "initial", "states": [...], "transitions": [...] }.' };
  }
  return { wf: o as Workflow, error: null };
}

/** Paramétrage → Workflows : diagramme états/transitions et édition JSON (ADMIN). */
@Component({
  selector: 'eq-workflows',
  standalone: true,
  template: `
    <div class="ph">
      <div><span class="ref">Paramétrage · Moteur de fiches</span><h2>Workflows</h2></div>
      <label class="field sel"><span>Type de fiche</span>
        <select #t (change)="select(t.value)">
          @for (rt of types(); track rt.code) { <option [value]="rt.code" [selected]="rt.code === code()">{{ rt.label }} ({{ rt.code }})</option> }
        </select>
      </label>
    </div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (info(); as msg) { <p class="ok-msg" role="status">{{ msg }}</p> }
    @if (workflow(); as wf) {
      <section class="sec" aria-labelledby="h-diag">
        <h3 id="h-diag">Diagramme</h3>
        <div class="box pad diag">
          @for (s of wf.states; track s.code) {
            <div class="state" [class.initial]="s.code === wf.initial" [class.final]="s.final">
              <b>{{ s.label }}</b><span class="mono">{{ s.code }}</span>
              @if (s.code === wf.initial) { <small>initial</small> }
              @if (s.final) { <small>final</small> }
              <ul>
                @for (t of outgoing(wf, s); track t.code) {
                  <li><span aria-hidden="true">→</span> <b>{{ label(wf, t.to) }}</b> <span class="note">par « {{ t.label }} »</span></li>
                }
              </ul>
            </div>
          }
        </div>
      </section>
      <section class="sec" aria-labelledby="h-tr">
        <h3 id="h-tr">Transitions</h3>
        <div class="box tw">
          <table>
            <thead><tr><th>Code</th><th>Libellé</th><th>De</th><th>Vers</th><th>Rôles</th><th>Champs requis</th></tr></thead>
            <tbody>
              @for (t of wf.transitions; track t.code + t.from) {
                <tr>
                  <td class="mono">{{ t.code }}</td><td>{{ t.label }}</td><td>{{ label(wf, t.from) }}</td><td>{{ label(wf, t.to) }}</td>
                  <td class="mono">{{ t.roles.join(', ') || '—' }}</td><td class="mono">{{ (t.requires ?? []).join(', ') || '—' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    }
    <section class="sec" aria-labelledby="h-json">
      <h3 id="h-json">Édition JSON <span class="note">validée par le serveur à l'enregistrement</span></h3>
      <textarea #j class="json mono" rows="18" aria-label="Workflow JSON" spellcheck="false" [value]="text()" (input)="text.set(j.value)"></textarea>
      @if (localError(); as msg) { <p class="miss">{{ msg }}</p> }
      <div class="bar">
        <button type="button" class="btn" (click)="reset()">Annuler les modifications</button>
        <button type="button" class="btn pri" [disabled]="saving() || !!localError()" (click)="save()">Enregistrer le workflow</button>
      </div>
    </section>
  `,
  styles: `
    .sel { margin: 0; min-width: 260px; }
    .diag { display: flex; gap: 12px; flex-wrap: wrap; }
    .state { border: var(--bw) solid var(--line2); padding: 10px 12px; min-width: 180px; flex: 1 1 180px; background: var(--surf); }
    .state > b { display: block; }
    .state > .mono { font-size: 12px; color: var(--ink2); }
    .state small { margin-left: 6px; font-family: var(--mono); font-size: 11px; text-transform: uppercase; border: var(--bw) solid var(--line2); padding: 0 4px; }
    .state.initial { border-top: 4px solid var(--acc); }
    .state.final { background: var(--surf2); }
    ul { list-style: none; margin: 8px 0 0; padding: 0; font-size: 13px; }
    .json { width: 100%; border: var(--bw) solid var(--line2); background: var(--surf); padding: 10px; font-size: 13px; }
    .bar { display: flex; gap: 8px; justify-content: flex-end; margin-top: 10px; }
    .miss { color: var(--crit); font-size: 13px; font-weight: 600; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WorkflowsComponent {
  private readonly records = inject(RecordsService);
  protected readonly types = signal<RecordType[]>([]);
  protected readonly code = signal('NC');
  protected readonly workflow = signal<Workflow | null>(null);
  protected readonly text = signal('');
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);
  protected readonly localError = computed(() => (this.text() ? parseWorkflow(this.text()).error : null));

  constructor() {
    this.records.recordTypes().then((t) => this.types.set(t), (e) => this.error.set(errorMessage(e)));
    void this.select('NC');
  }

  async select(code: string): Promise<void> {
    this.code.set(code);
    this.error.set(null);
    this.info.set(null);
    try {
      this.show(await this.records.workflow(code));
    } catch (e) {
      this.error.set(errorMessage(e, 'Workflow introuvable.'));
    }
  }

  private show(wf: Workflow): void {
    this.workflow.set(wf);
    this.text.set(JSON.stringify(wf, null, 2));
  }

  protected reset(): void {
    const wf = this.workflow();
    if (wf) this.text.set(JSON.stringify(wf, null, 2));
  }

  async save(): Promise<void> {
    const { wf } = parseWorkflow(this.text());
    if (!wf) return;
    this.saving.set(true);
    this.error.set(null);
    this.info.set(null);
    try {
      this.show(await this.records.saveWorkflow(this.code(), wf));
      this.info.set('Workflow enregistré.');
    } catch (e) {
      this.error.set(errorMessage(e, 'Workflow refusé par le serveur.'));
    } finally {
      this.saving.set(false);
    }
  }

  protected outgoing(wf: Workflow, s: WorkflowState): WorkflowTransition[] {
    return wf.transitions.filter((t) => t.from === s.code);
  }

  protected label(wf: Workflow, code: string): string {
    return wf.states.find((s) => s.code === code)?.label ?? code;
  }
}
