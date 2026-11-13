import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { QmsRecord } from '../../core/api.models';
import { errorMessage } from '../../core/api-error';
import { Requirement, RequirementRef, Standard, buildTree, standardLabel } from '../../core/m3.models';
import { StandardsService } from '../../core/m3.service';

type LinkedRequirement = RequirementRef;

export function flatten(nodes: Requirement[], depth = 0): (Requirement & { depth: number })[] {
  return nodes.flatMap((n) => [{ ...n, depth }, ...flatten(n.children ?? [], depth + 1)]);
}

/** Onglet « Exigences » d'une fiche (NC, incident, document, audit) : `PUT /api/records/{id}/requirements`. */
@Component({
  selector: 'eq-record-requirements',
  template: `
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (info(); as msg) { <p class="ok-msg" role="status">{{ msg }}</p> }
    <div class="two">
      <div class="box pad">
        <div class="stds">@for (s of standards(); track s.id) {
          <button type="button" class="chip" [attr.aria-pressed]="std()?.id === s.id" (click)="open(s)">{{ stdLabel(s) }}</button> }</div>
        <ul class="lst">
          @for (n of rows(); track n.id) {
            <li [style.padding-left.px]="n.depth * 16">
              <label><input type="checkbox" [disabled]="readonly()" [checked]="sel().has(n.id)" (change)="toggle(n)" />
                <span class="mono">{{ n.code }}</span> {{ n.title }}</label>
            </li>
          }
        </ul>
      </div>
      <div class="box pad">
        <h3 class="t">Exigences liées ({{ sel().size }})</h3>
        <ul class="lst">@for (l of linked(); track l.id) { <li><span class="mono">{{ l.standardCode ?? '' }} §{{ l.code }}</span> {{ l.title }}</li> }
          @empty { <li class="note">Aucune exigence liée.</li> }</ul>
        @if (!readonly()) { <button type="button" class="btn pri" [disabled]="busy()" (click)="save()">Enregistrer les liens</button> }
      </div>
    </div>
  `,
  styles: `
    .stds { margin-bottom: 10px; display: flex; gap: 8px; flex-wrap: wrap; }
    .lst { list-style: none; margin: 0 0 12px; padding: 0; max-height: 420px; overflow: auto; li { padding: 3px 0; } .mono { font-weight: 600; margin-right: 4px; } }
    .t { margin: 0 0 10px; font-size: 16px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RecordRequirementsComponent {
  protected readonly stdLabel = standardLabel;
  private readonly api = inject(StandardsService);
  readonly record = input.required<QmsRecord>();
  readonly readonly = input(false);
  readonly changed = output<void>();

  protected readonly standards = signal<Standard[]>([]);
  protected readonly std = signal<Standard | null>(null);
  protected readonly rows = signal<(Requirement & { depth: number })[]>([]);
  protected readonly picked = signal<Map<string, LinkedRequirement>>(new Map());
  protected readonly sel = computed(() => new Set(this.picked().keys()));
  protected readonly linked = computed(() => [...this.picked().values()]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);

  constructor() {
    effect(() => {
      const rec = this.record();
      untracked(() => {
        this.api.linked(rec.id).then(
          (cur) => this.picked.set(new Map(cur.map((r) => [r.id, r]))),
          (e) => this.error.set(errorMessage(e, 'Impossible de charger les exigences liées.')),
        );
      });
    });
    this.api.list().then((l) => {
      this.standards.set(l);
      if (l[0]) void this.open(l[0]);
    }, (e) => this.error.set(errorMessage(e, 'Impossible de charger les référentiels.')));
  }

  async open(s: Standard): Promise<void> {
    this.std.set(s);
    try {
      this.rows.set(flatten(buildTree(await this.api.requirements(s.id))));
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger les exigences.'));
    }
  }

  protected toggle(n: Requirement): void {
    const next = new Map(this.picked());
    if (next.has(n.id)) next.delete(n.id);
    else next.set(n.id, { id: n.id, code: n.code, title: n.title, standardCode: this.std()?.code });
    this.picked.set(next);
  }

  async save(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.linkRequirements(this.record().id, [...this.sel()]);
      this.info.set('Liens enregistrés.');
      this.changed.emit();
    } catch (e) {
      this.error.set(errorMessage(e, "Impossible d'enregistrer les liens."));
    } finally {
      this.busy.set(false);
    }
  }
}
