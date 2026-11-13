import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/api-error';
import { EvaluationGrid, validateGrid } from '../../core/m4.models';
import { EvaluationsService } from '../../core/m4.service';

/** Paramétrage → Grilles d'évaluation : critères pondérés, échelle, seuils de classe, classes déclenchant un plan de progrès. */
@Component({
  selector: 'eq-grilles-evaluation',
  imports: [FormsModule],
  template: `
    <div class="ph"><div><span class="ref">Paramétrage</span><h2>Grilles d'évaluation</h2></div>
      <button type="button" class="btn" (click)="add()">+ Nouvelle grille</button></div>
    @if (error(); as msg) { <p class="err" role="alert">{{ msg }}</p> }
    @if (saved()) { <p class="note" role="status">Grille enregistrée.</p> }
    <div class="chips">@for (g of grids(); track $index; let k = $index) {
      <button type="button" class="chip" [attr.aria-pressed]="sel() === k" (click)="sel.set(k)">{{ g.code || 'Nouvelle' }}</button> }</div>
    @if (grids()[sel()]; as g) {
      <form class="box pad sec" (ngSubmit)="save(g)" aria-label="Grille d'évaluation">
        <div class="row"><label class="field"><span>Code</span><input name="c" [(ngModel)]="g.code" /></label>
          <label class="field"><span>Libellé</span><input name="l" [(ngModel)]="g.label" /></label>
          <label class="field"><span>Pour</span><select name="t" [(ngModel)]="g.partnerType"><option value="fournisseur">Fournisseurs</option><option value="client">Clients</option></select></label>
          <label class="field"><span>Note maximale</span><input type="number" name="m" [(ngModel)]="g.scaleMax" /></label></div>
        <table aria-label="Critères"><thead><tr><th>Code</th><th>Critère</th><th>Poids</th></tr></thead><tbody>
          @for (c of g.criteria; track $index; let k = $index) {
            <tr><td><input [name]="'cc' + k" [(ngModel)]="c.code" /></td><td><input [name]="'cl' + k" [(ngModel)]="c.label" /></td>
              <td><input type="number" [name]="'cw' + k" [(ngModel)]="c.weight" /></td></tr>
          }</tbody></table>
        <button type="button" class="btn" (click)="g.criteria.push({ code: '', label: '', weight: 1 })">+ Critère</button>
        <table class="sec" aria-label="Classes"><thead><tr><th>Classe</th><th>Libellé</th><th>Note min (/100)</th><th>Plan de progrès</th></tr></thead><tbody>
          @for (t of g.thresholds; track $index; let k = $index) {
            <tr><td><input [name]="'tc' + k" [(ngModel)]="t.cls" /></td><td><input [name]="'tl' + k" [(ngModel)]="t.label" /></td>
              <td><input type="number" [name]="'tm' + k" [(ngModel)]="t.min" /></td>
              <td><input type="checkbox" [name]="'ti' + k" [ngModel]="g.improvementClasses.includes(t.cls)" (ngModelChange)="toggle(g, t.cls)" /></td></tr>
          }</tbody></table>
        <button type="button" class="btn" (click)="g.thresholds.push({ cls: '', label: '', min: 0 })">+ Classe</button>
        @for (e of errors(); track e) { <p class="err">{{ e }}</p> }
        <p><button type="submit" class="btn pri" [disabled]="busy()">Enregistrer la grille</button></p>
      </form>
    }
  `,
  styles: `.row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 0 12px; }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GrillesEvaluationComponent {
  private readonly api = inject(EvaluationsService);
  protected readonly grids = signal<EvaluationGrid[]>([]);
  protected readonly sel = signal(0);
  protected readonly errors = signal<string[]>([]);
  protected readonly busy = signal(false);
  protected readonly saved = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.api.grids().then((g) => this.grids.set(g), (e) => this.error.set(errorMessage(e, 'Grilles indisponibles.')));
  }
  protected add(): void {
    this.grids.update((l) => [...l, { id: null, code: '', label: '', partnerType: 'fournisseur', scaleMax: 5, criteria: [], improvementClasses: ['C'],
      thresholds: [{ cls: 'A', label: 'Agréé', min: 80 }, { cls: 'B', label: 'Sous surveillance', min: 60 }, { cls: 'C', label: 'Refusé', min: 0 }] }]);
    this.sel.set(this.grids().length - 1);
  }
  protected toggle(g: EvaluationGrid, cls: string): void {
    g.improvementClasses = g.improvementClasses.includes(cls) ? g.improvementClasses.filter((c) => c !== cls) : [...g.improvementClasses, cls];
  }
  async save(g: EvaluationGrid): Promise<void> {
    const errs = validateGrid(g);
    this.errors.set(errs);
    if (errs.length) return;
    this.busy.set(true);
    try {
      const out = await this.api.saveGrid(g);
      this.grids.update((l) => l.map((x, i) => (i === this.sel() ? out : x)));
      this.saved.set(true);
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, "La grille n'a pas pu être enregistrée."));
    } finally {
      this.busy.set(false);
    }
  }
}
