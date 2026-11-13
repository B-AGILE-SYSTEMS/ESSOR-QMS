import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { recordPath } from '../../core/api.models';
import { errorMessage } from '../../core/api-error';
import { ANSWER_LABELS, ImportReport, Requirement, RequirementDetail, Standard, buildTree, standardLabel } from '../../core/m3.models';
import { IMPORT_TEMPLATE_URL, StandardsService } from '../../core/m3.service';

const IMPORT_EXT = /\.(xlsx|csv)$/i;

/** Paramétrage → Référentiels : import (simulation puis import réel), arbre des exigences, vue d'une exigence et ses liens. */
@Component({
  selector: 'eq-referentiels',
  imports: [FormsModule, RouterLink, NgTemplateOutlet],
  template: `
    <div class="ph"><div><span class="ref">Paramétrage</span><h2>Référentiels</h2></div>
      <a class="btn" [href]="templateUrl" download>Télécharger le modèle d'import</a></div>

    <form class="box pad imp" (ngSubmit)="run(true)">
      <h3 class="t">Importer un référentiel (xlsx ou csv — colonnes code;titre;texte;parent;auditable)</h3>
      <div class="row">
        <label class="field"><span>Code</span><input name="code" [(ngModel)]="std.code" placeholder="ISO45001" required /></label>
        <label class="field"><span>Titre</span><input name="title" [(ngModel)]="std.title" placeholder="ISO 45001" required /></label>
        <label class="field"><span>Version</span><input name="version" [(ngModel)]="std.version" placeholder="2018" /></label>
        <label class="field"><span>Fichier</span><input type="file" accept=".xlsx,.csv" aria-label="Fichier du référentiel" (change)="pick($event)" /></label>
      </div>
      <div class="acts">
        <button type="submit" class="btn" [disabled]="busy() || !file()">Simuler l'import</button>
        <button type="button" class="btn pri" [disabled]="busy() || !simulated()" (click)="run(false)">Importer</button>
      </div>
      @if (report(); as r) {
        <div class="rep" role="status">
          <p><b>{{ dry() ? 'Simulation' : 'Import' }}</b> : {{ r.created }} exigence(s) créée(s), {{ r.updated }} mise(s) à jour,
            {{ r.errors.length }} erreur(s).</p>
          @if (r.errors.length) {
            <table><thead><tr><th>Ligne</th><th>Erreur</th></tr></thead><tbody>
              @for (e of r.errors; track $index) { <tr><td class="mono">{{ e.line }}</td><td>{{ e.message }}</td></tr> }
            </tbody></table>
          }
        </div>
      }
    </form>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }

    <div class="grid">
      <div class="box">
        <div class="stds" role="tablist" aria-label="Référentiels">
          @for (s of standards(); track s.id) {
            <button type="button" role="tab" [attr.aria-selected]="current()?.id === s.id" (click)="open(s)">
              {{ stdLabel(s) }}</button>
          } @empty { <p class="note pad">Aucun référentiel importé.</p> }
        </div>
        <ul class="tree" role="tree" aria-label="Exigences">
          @for (n of tree(); track n.id) {
            <ng-container *ngTemplateOutlet="node; context: { $implicit: n, depth: 0 }" />
          }
        </ul>
      </div>
      <aside class="box pad det" aria-live="polite">
        @if (detail(); as d) {
          <span class="ref mono">{{ d.standardCode ?? current()?.code }} · §{{ d.code }}</span>
          <h3>{{ d.title }}</h3>
          <p>{{ d.text || '—' }}</p>
          <p class="note">{{ d.auditable ? 'Exigence auditable' : 'Non auditable (titre de chapitre)' }}</p>
          <h4>Documents liés</h4>
          <ul>@for (l of d.documents ?? []; track l.id) { <li><a class="lnk" [routerLink]="'/documents/' + l.id">{{ l.reference }}</a> {{ l.title }}</li> }
            @empty { <li class="note">Aucun.</li> }</ul>
          <h4>Fiches liées (NC, incidents)</h4>
          <ul>@for (l of d.records ?? []; track l.id) { <li><a class="lnk" [routerLink]="path(l.type, l.id)">{{ l.reference }}</a> {{ l.title }}</li> }
            @empty { <li class="note">Aucune.</li> }</ul>
          <h4>Constats d'audit</h4>
          <ul>@for (f of d.findings ?? []; track $index) {
              <li><a class="lnk" [routerLink]="'/audits/' + f.auditId">{{ f.auditReference ?? 'Audit' }}</a> {{ answer[f.result] }}@if (f.comment) { — {{ f.comment }} }</li>
            } @empty { <li class="note">Aucun.</li> }</ul>
        } @else { <p class="note">Sélectionnez une exigence dans l'arbre.</p> }
      </aside>
    </div>

    <ng-template #node let-n let-depth="depth">
      <li role="treeitem" [attr.aria-expanded]="n.children?.length ? !collapsed().has(n.id) : null" [attr.aria-selected]="detail()?.id === n.id">
        <div class="ln" [style.padding-left.px]="8 + depth * 18">
          @if (n.children?.length) {
            <button type="button" class="tog" [attr.aria-label]="(collapsed().has(n.id) ? 'Déplier ' : 'Replier ') + n.code" (click)="toggle(n.id)">{{ collapsed().has(n.id) ? '▸' : '▾' }}</button>
          } @else { <span class="tog"></span> }
          <button type="button" class="lbl" (click)="select(n)"><span class="mono">{{ n.code }}</span> {{ n.title }}</button>
          @if (n.auditable) { <span class="badge">auditable</span> }
        </div>
        @if (n.children?.length && !collapsed().has(n.id)) {
          <ul role="group">@for (c of n.children; track c.id) {
            <ng-container *ngTemplateOutlet="node; context: { $implicit: c, depth: depth + 1 }" /> }</ul>
        }
      </li>
    </ng-template>
  `,
  styles: `
    .t { font-size: 14px; margin: 0 0 10px; }
    .row { display: grid; grid-template-columns: 1fr 1.4fr .7fr 1.6fr; gap: 12px; }
    .acts { display: flex; gap: 10px; }
    .rep { margin-top: 12px; p { margin: 0 0 8px; } }
    .imp { margin-bottom: 18px; }
    .grid { display: grid; grid-template-columns: 1.6fr 1fr; gap: 18px; align-items: start; }
    .stds { display: flex; flex-wrap: wrap; border-bottom: var(--bw) solid var(--line);
      button { background: none; border: 0; border-bottom: 3px solid transparent; padding: 10px 14px; font-weight: 600; cursor: pointer; color: var(--ink2); }
      button[aria-selected='true'] { color: var(--ink); border-color: var(--acc); } }
    .tree, .tree ul { list-style: none; margin: 0; padding: 0; }
    .tree { padding: 6px 0; max-height: 640px; overflow: auto; }
    .ln { display: flex; align-items: center; gap: 6px; padding: 3px 10px; border-bottom: var(--bw) solid var(--surf2); }
    li[aria-selected='true'] > .ln { background: var(--surf2); box-shadow: inset 3px 0 0 var(--acc); }
    .tog { width: 22px; flex: none; background: none; border: 0; cursor: pointer; color: var(--ink2); }
    .lbl { background: none; border: 0; text-align: left; cursor: pointer; padding: 4px 0; flex: 1; .mono { font-weight: 600; margin-right: 6px; } }
    .det h3 { margin: 4px 0 8px; font-size: 20px; } .det h4 { margin: 16px 0 4px; font-size: 13px; text-transform: uppercase; letter-spacing: .08em; color: var(--ink2); }
    .det ul { margin: 0; padding-left: 18px; }
    @media (max-width: 860px) { .grid, .row { grid-template-columns: 1fr; } }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReferentielsComponent {
  protected readonly stdLabel = standardLabel;
  private readonly api = inject(StandardsService);
  protected readonly templateUrl = IMPORT_TEMPLATE_URL;
  protected readonly answer = ANSWER_LABELS;
  protected readonly path = (t: string | null | undefined, id: string) => recordPath(t, id);

  protected std = { code: '', title: '', version: '' };
  protected readonly file = signal<File | null>(null);
  protected readonly report = signal<ImportReport | null>(null);
  protected readonly dry = signal(true);
  protected readonly simulated = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly standards = signal<Standard[]>([]);
  protected readonly current = signal<Standard | null>(null);
  protected readonly tree = signal<Requirement[]>([]);
  protected readonly collapsed = signal<Set<string>>(new Set());
  protected readonly detail = signal<RequirementDetail | null>(null);

  constructor() {
    void this.loadStandards();
  }

  private async loadStandards(): Promise<void> {
    try {
      const list = await this.api.list();
      this.standards.set(list);
      const keep = list.find((s) => s.id === this.current()?.id) ?? list[0];
      if (keep) await this.open(keep);
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger les référentiels.'));
    }
  }

  protected pick(ev: Event): void {
    const f = (ev.target as HTMLInputElement).files?.[0] ?? null;
    this.report.set(null);
    this.simulated.set(false);
    if (f && !IMPORT_EXT.test(f.name)) {
      this.error.set('Format refusé : seuls les fichiers xlsx et csv sont acceptés.');
      this.file.set(null);
      return;
    }
    this.error.set(null);
    this.file.set(f);
  }

  /** Simulation (`dryRun=true`) puis import réel, autorisé seulement après une simulation sans erreur. */
  async run(dryRun: boolean): Promise<void> {
    const f = this.file();
    if (!f) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const r = await this.api.import(f, { code: this.std.code.trim(), title: this.std.title.trim(), version: this.std.version.trim() }, dryRun);
      this.report.set(r);
      this.dry.set(dryRun);
      this.simulated.set(dryRun && r.errors.length === 0);
      if (!dryRun && r.errors.length) this.error.set("Import annulé : aucune ligne n'a été appliquée (tout ou rien).");
      if (!dryRun) await this.loadStandards();
    } catch (e) {
      this.error.set(errorMessage(e, "L'import a échoué."));
    } finally {
      this.busy.set(false);
    }
  }

  async open(s: Standard): Promise<void> {
    this.current.set(s);
    this.detail.set(null);
    try {
      this.tree.set(buildTree(await this.api.requirements(s.id)));
    } catch (e) {
      this.error.set(errorMessage(e, 'Impossible de charger les exigences.'));
    }
  }

  protected toggle(id: string): void {
    const next = new Set(this.collapsed());
    if (next.has(id)) next.delete(id);
    else next.add(id);
    this.collapsed.set(next);
  }

  async select(n: Requirement): Promise<void> {
    this.detail.set({ ...n });
    try {
      this.detail.set(await this.api.requirement(n.id));
    } catch (e) {
      this.error.set(errorMessage(e, "Impossible de charger l'exigence."));
    }
  }
}
